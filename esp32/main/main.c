#include <stdio.h>
#include <stdlib.h>
#include <stdbool.h>
#include <string.h>
#include <stdint.h>
#include <inttypes.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include "config_local.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "esp_wifi.h"
#include "esp_event.h"
#include "esp_netif.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "esp_https_server.h"
#include "driver/ledc.h"
#include "driver/gpio.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "freertos/semphr.h"
#include "mbedtls/base64.h"
#include "mbedtls/pk.h"
#include "mbedtls/sha256.h"
#include "cJSON.h"

#define MAX_JSON 1536
#define MAX_CLIENT_ID 96
#define NONCE_BYTES 24
#define PAIR_SECONDS 180
#define CHALLENGE_SECONDS 30
#define SERVO_COOLDOWN_US 20000000LL
#define TAG "rcp-esp32"

extern const uint8_t server_crt_start[] asm("_binary_server_crt_start");
extern const uint8_t server_crt_end[] asm("_binary_server_crt_end");
extern const uint8_t server_key_start[] asm("_binary_server_key_start");
extern const uint8_t server_key_end[] asm("_binary_server_key_end");

static EventGroupHandle_t wifi_events;
static SemaphoreHandle_t state_lock;
static char client_id[MAX_CLIENT_ID+1] = "";
static char client_spki[384] = "";
static char pending_token[49] = "";
static int64_t token_expires_us = 0;
static char nonce_value[49] = "";
static int64_t nonce_expires_us = 0;
static int64_t last_press_us = 0;

static void random_hex(char *dest, size_t bytes)
{
    uint8_t raw[NONCE_BYTES];
    if (bytes > sizeof raw) bytes = sizeof raw;
    esp_fill_random(raw, bytes);
    static const char digits[] = "0123456789ABCDEF";
    for (size_t i=0;i<bytes;i++) { dest[i*2] = digits[raw[i]>>4]; dest[i*2+1]=digits[raw[i]&15]; }
    dest[bytes*2]='\0';
}

static bool secure_equal(const char *a, const char *b)
{
    if (strlen(a) != strlen(b)) return false;
    unsigned char diff=0;
    for (size_t i=0;i<strlen(a);i++) diff |= (unsigned char)(a[i]^b[i]);
    return diff == 0;
}

static void send_json(httpd_req_t *req, const char *status, const char *json)
{
    httpd_resp_set_status(req,status);
    httpd_resp_set_type(req,"application/json; charset=utf-8");
    httpd_resp_set_hdr(req,"Cache-Control","no-store");
    httpd_resp_sendstr(req,json);
}

static void load_pairing(void)
{
    nvs_handle_t nvs;
    if (nvs_open("rcp_client", NVS_READONLY, &nvs) != ESP_OK) return;
    size_t id_len=sizeof client_id, pk_len=sizeof client_spki;
    if (nvs_get_str(nvs,"id",client_id,&id_len)!=ESP_OK ||
        nvs_get_str(nvs,"spki",client_spki,&pk_len)!=ESP_OK)
    { client_id[0]=0; client_spki[0]=0; }
    nvs_close(nvs);
}

static bool save_pairing(const char *id,const char *spki)
{
    nvs_handle_t nvs;
    if (nvs_open("rcp_client", NVS_READWRITE, &nvs)!=ESP_OK) return false;
    bool ok=nvs_set_str(nvs,"id",id)==ESP_OK && nvs_set_str(nvs,"spki",spki)==ESP_OK && nvs_commit(nvs)==ESP_OK;
    nvs_close(nvs); return ok;
}

static bool lan_peer(httpd_req_t *req)
{
    struct sockaddr_storage peer = {0};
    socklen_t len = sizeof peer;
    int fd = httpd_req_to_sockfd(req);
    if (getpeername(fd,(struct sockaddr *)&peer,&len)!=0) return false;
    if (peer.ss_family != AF_INET) return false;
    const uint8_t *b=(const uint8_t *)&((const struct sockaddr_in *)&peer)->sin_addr.s_addr;
    return b[0]==10 || (b[0]==172 && b[1]>=16 && b[1]<=31) || (b[0]==192 && b[1]==168);
}

static bool verify_spki_and_signature(const char *public_key_b64, const uint8_t digest[32], const char *sig_b64)
{
    uint8_t spki[300], signature[256];
    size_t spki_len=0, sig_len=0;
    if (mbedtls_base64_decode(spki,sizeof spki,&spki_len,
            (const unsigned char *)public_key_b64,strlen(public_key_b64)) != 0) return false;
    if (mbedtls_base64_decode(signature,sizeof signature,&sig_len,
            (const unsigned char *)sig_b64,strlen(sig_b64)) != 0) return false;
    mbedtls_pk_context key;
    mbedtls_pk_init(&key);
    int err = mbedtls_pk_parse_public_key(&key,spki,spki_len);
    bool ok = err==0 && mbedtls_pk_can_do(&key,MBEDTLS_PK_ECKEY) &&
        mbedtls_pk_verify(&key,MBEDTLS_MD_SHA256,digest,32,signature,sig_len)==0;
    mbedtls_pk_free(&key);
    return ok;
}

static bool auth(httpd_req_t *req,const char *method,const char *path,const unsigned char *body,size_t body_len)
{
    char id[128]="",nonce[100]="",sig[380]="",stored_spki[384]="";
    if (httpd_req_get_hdr_value_str(req,"X-Client-Id",id,sizeof id)!=ESP_OK ||
        httpd_req_get_hdr_value_str(req,"X-Nonce",nonce,sizeof nonce)!=ESP_OK ||
        httpd_req_get_hdr_value_str(req,"X-Signature",sig,sizeof sig)!=ESP_OK) return false;
    xSemaphoreTake(state_lock,portMAX_DELAY);
    bool valid = client_id[0] && secure_equal(client_id,id) && nonce_value[0] &&
        secure_equal(nonce_value,nonce) && esp_timer_get_time()<nonce_expires_us;
    if (valid) strncpy(stored_spki,client_spki,sizeof stored_spki-1);
    // The issued nonce is consumed before signature verification: no replay or retry.
    if (valid) nonce_value[0]=0;
    xSemaphoreGive(state_lock);
    if (!valid) return false;
    uint8_t body_hash[32];
    mbedtls_sha256(body,body_len,body_hash,0);
    char body_hex[65];
    for (int i=0;i<32;i++) snprintf(body_hex+i*2,3,"%02X",body_hash[i]);
    char canonical[1024];
    int chars=snprintf(canonical,sizeof canonical,"RCP1\n%s\n%s\n%s\n%s\n%s",
        id,method,path,body_hex,nonce);
    if (chars<=0 || chars >= sizeof canonical) return false;
    uint8_t digest[32];
    mbedtls_sha256((const unsigned char *)canonical,chars,digest,0);
    return verify_spki_and_signature(stored_spki,digest,sig);
}

static esp_err_t challenge_handler(httpd_req_t *req)
{
    char query[200]="",id[128]="";
    if (httpd_req_get_url_query_str(req,query,sizeof query)!=ESP_OK ||
        httpd_query_key_value(query,"clientId",id,sizeof id)!=ESP_OK) { send_json(req,"400 Bad Request","{\"error\":\"query\"}"); return ESP_OK; }
    xSemaphoreTake(state_lock,portMAX_DELAY);
    bool allow=client_id[0] && secure_equal(id,client_id);
    if (allow) { random_hex(nonce_value,NONCE_BYTES); nonce_expires_us=esp_timer_get_time()+CHALLENGE_SECONDS*1000000LL; }
    char issued[49]; strncpy(issued,nonce_value,sizeof issued);
    xSemaphoreGive(state_lock);
    if (!allow) { send_json(req,"403 Forbidden","{\"error\":\"unknown-client\"}"); return ESP_OK; }
    char answer[120]; snprintf(answer,sizeof answer,"{\"nonce\":\"%s\",\"ttlSeconds\":30}",issued);
    send_json(req,"200 OK",answer); return ESP_OK;
}

static esp_err_t pair_handler(httpd_req_t *req)
{
    if (req->content_len<=0 || req->content_len>MAX_JSON || !lan_peer(req))
    { send_json(req,"403 Forbidden","{\"error\":\"pairing-unavailable\"}"); return ESP_OK; }
    char json[MAX_JSON+1]={0};
    int count=0;
    while (count<(int)req->content_len) {
        int part=httpd_req_recv(req,json+count,req->content_len-count);
        if (part<=0) break;
        count+=part;
    }
    if (count!=(int)req->content_len) { send_json(req,"400 Bad Request","{\"error\":\"short-body\"}"); return ESP_OK; }
    cJSON *root=cJSON_Parse(json);
    cJSON *t=root?cJSON_GetObjectItemCaseSensitive(root,"token"):NULL;
    cJSON *id=root?cJSON_GetObjectItemCaseSensitive(root,"clientId"):NULL;
    cJSON *pub=root?cJSON_GetObjectItemCaseSensitive(root,"publicKeySpki"):NULL;
    bool good=false;
    if (cJSON_IsString(t)&&cJSON_IsString(id)&&cJSON_IsString(pub) &&
        strlen(id->valuestring)>=8 && strlen(id->valuestring)<=MAX_CLIENT_ID &&
        strlen(pub->valuestring)>60 && strlen(pub->valuestring)<sizeof client_spki)
    {
        // Verify the supplied DER key really is an EC key (not arbitrary base64).
        uint8_t decoded[300]; size_t len=0;
        if (mbedtls_base64_decode(decoded,sizeof decoded,&len,(const unsigned char *)pub->valuestring,
                strlen(pub->valuestring))==0)
        {
            mbedtls_pk_context pk; mbedtls_pk_init(&pk);
            bool ec = mbedtls_pk_parse_public_key(&pk,decoded,len)==0 && mbedtls_pk_can_do(&pk,MBEDTLS_PK_ECKEY);
            mbedtls_pk_free(&pk);
            xSemaphoreTake(state_lock,portMAX_DELAY);
            if (ec && pending_token[0] && esp_timer_get_time()<token_expires_us &&
                secure_equal(pending_token,t->valuestring))
            {
                good=save_pairing(id->valuestring,pub->valuestring);
                if (good) { strncpy(client_id,id->valuestring,sizeof client_id-1);
                    strncpy(client_spki,pub->valuestring,sizeof client_spki-1); pending_token[0]=0; nonce_value[0]=0; }
            }
            xSemaphoreGive(state_lock);
        }
    }
    cJSON_Delete(root);
    send_json(req,good?"200 OK":"403 Forbidden",good?"{\"ok\":true}":"{\"error\":\"invalid-pair\"}");
    return ESP_OK;
}

static esp_err_t status_handler(httpd_req_t *req)
{
    if (!auth(req,"GET","/api/v1/power/status",(const unsigned char *)"",0))
    { send_json(req,"401 Unauthorized","{\"error\":\"unauthorized\"}"); return ESP_OK; }
    send_json(req,"200 OK","{\"ok\":true,\"powerState\":\"unknown\",\"actuator\":\"servo\"}");
    return ESP_OK;
}

static void set_servo_us(int pulse)
{
    // 50 Hz period = 20000 us; LEDC 16-bit duty 0..65535.
    uint32_t duty=(uint32_t)((uint64_t)pulse * 65535 / 20000);
    ledc_set_duty(LEDC_LOW_SPEED_MODE,LEDC_CHANNEL_0,duty);
    ledc_update_duty(LEDC_LOW_SPEED_MODE,LEDC_CHANNEL_0);
}

static esp_err_t press_handler(httpd_req_t *req)
{
    if (req->content_len!=0 || !auth(req,"POST","/api/v1/power/press",(const unsigned char *)"",0))
    { send_json(req,"401 Unauthorized","{\"error\":\"unauthorized\"}"); return ESP_OK; }
    xSemaphoreTake(state_lock,portMAX_DELAY);
    int64_t now=esp_timer_get_time();
    bool allow=last_press_us==0 || now-last_press_us>=SERVO_COOLDOWN_US;
    if (allow) last_press_us=now;
    xSemaphoreGive(state_lock);
    if (!allow) { send_json(req,"429 Too Many Requests","{\"error\":\"cooldown-20s\"}"); return ESP_OK; }
    set_servo_us(RCP_SERVO_PRESS_US);
    vTaskDelay(pdMS_TO_TICKS(350));
    set_servo_us(RCP_SERVO_RELEASE_US);
    send_json(req,"200 OK","{\"ok\":true,\"pulseMs\":350}");
    return ESP_OK;
}

static void pairing_button_task(void *arg)
{
    gpio_config_t input={ .pin_bit_mask=1ULL<<GPIO_NUM_0, .mode=GPIO_MODE_INPUT,
        .pull_up_en=GPIO_PULLUP_ENABLE,.pull_down_en=GPIO_PULLDOWN_DISABLE, .intr_type=GPIO_INTR_DISABLE };
    gpio_config(&input);
    int held=0;
    while (true)
    {
        if (gpio_get_level(GPIO_NUM_0)==0) held++; else held=0;
        if (held==20)
        {
            xSemaphoreTake(state_lock,portMAX_DELAY);
            random_hex(pending_token,NONCE_BYTES);
            token_expires_us=esp_timer_get_time()+PAIR_SECONDS*1000000LL;
            char token[49]; strncpy(token,pending_token,sizeof token);
            xSemaphoreGive(state_lock);
            esp_netif_ip_info_t ip={0};
            esp_netif_t *iface=esp_netif_get_handle_from_ifkey("WIFI_STA_DEF");
            if (iface) esp_netif_get_ip_info(iface,&ip);
            printf("PAIR_JSON:{\"kind\":\"esp32\",\"deviceId\":\"%s\",\"lanHost\":\"" IPSTR "\",\"lanPort\":443,\"wanPort\":9443,\"spkiSha256\":\"%s\",\"token\":\"%s\"}\n",
                RCP_DEVICE_ID,IP2STR(&ip.ip),RCP_SPKI_SHA256,token);
            fflush(stdout);
        }
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

static void on_wifi(void *arg,esp_event_base_t base,int32_t event,void *data)
{
    if (base==WIFI_EVENT && event==WIFI_EVENT_STA_START) esp_wifi_connect();
    if (base==WIFI_EVENT && event==WIFI_EVENT_STA_DISCONNECTED) esp_wifi_connect();
    if (base==IP_EVENT && event==IP_EVENT_STA_GOT_IP)
    {
        const ip_event_got_ip_t *ip=(const ip_event_got_ip_t *)data;
        ESP_LOGI(TAG,"IP: " IPSTR,IP2STR(&ip->ip_info.ip));
        xEventGroupSetBits(wifi_events,BIT0);
    }
}

void app_main(void)
{
    esp_err_t nv=nvs_flash_init();
    if (nv==ESP_ERR_NVS_NO_FREE_PAGES || nv==ESP_ERR_NVS_NEW_VERSION_FOUND) { ESP_ERROR_CHECK(nvs_flash_erase()); nv=nvs_flash_init(); }
    ESP_ERROR_CHECK(nv);
    state_lock=xSemaphoreCreateMutex();
    wifi_events=xEventGroupCreate();
    load_pairing();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t init=WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&init));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT,ESP_EVENT_ANY_ID,on_wifi,NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT,IP_EVENT_STA_GOT_IP,on_wifi,NULL));
    wifi_config_t config={0};
    strncpy((char *)config.sta.ssid,RCP_WIFI_SSID,sizeof config.sta.ssid);
    strncpy((char *)config.sta.password,RCP_WIFI_PASSWORD,sizeof config.sta.password);
    config.sta.threshold.authmode=WIFI_AUTH_WPA2_PSK;
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA,&config));
    ESP_ERROR_CHECK(esp_wifi_start());

    ledc_timer_config_t timer={ .speed_mode=LEDC_LOW_SPEED_MODE,.duty_resolution=LEDC_TIMER_16_BIT,
        .timer_num=LEDC_TIMER_0,.freq_hz=50,.clk_cfg=LEDC_AUTO_CLK };
    ESP_ERROR_CHECK(ledc_timer_config(&timer));
    ledc_channel_config_t channel={ .gpio_num=RCP_SERVO_SIGNAL_GPIO,.speed_mode=LEDC_LOW_SPEED_MODE,
        .channel=LEDC_CHANNEL_0,.timer_sel=LEDC_TIMER_0,.duty=0,.hpoint=0 };
    ESP_ERROR_CHECK(ledc_channel_config(&channel));
    set_servo_us(RCP_SERVO_RELEASE_US);
    xTaskCreate(pairing_button_task,"rcp_pair_btn",4096,NULL,5,NULL);
    ESP_LOGI(TAG,"Esperando Wi-Fi...");
    xEventGroupWaitBits(wifi_events,BIT0,pdFALSE,pdTRUE,portMAX_DELAY);

    httpd_ssl_config_t cfg=HTTPD_SSL_CONFIG_DEFAULT();
    cfg.port_secure=443;
    cfg.servercert=server_crt_start;
    cfg.servercert_len=server_crt_end-server_crt_start;
    cfg.prvtkey_pem=server_key_start;
    cfg.prvtkey_len=server_key_end-server_key_start;
    httpd_handle_t server=NULL;
    ESP_ERROR_CHECK(httpd_ssl_start(&server,&cfg));
    httpd_uri_t uri={.uri="/api/v1/pair",.method=HTTP_POST,.handler=pair_handler};
    httpd_register_uri_handler(server,&uri);
    uri=(httpd_uri_t){.uri="/api/v1/auth/challenge",.method=HTTP_GET,.handler=challenge_handler};
    httpd_register_uri_handler(server,&uri);
    uri=(httpd_uri_t){.uri="/api/v1/power/status",.method=HTTP_GET,.handler=status_handler};
    httpd_register_uri_handler(server,&uri);
    uri=(httpd_uri_t){.uri="/api/v1/power/press",.method=HTTP_POST,.handler=press_handler};
    httpd_register_uri_handler(server,&uri);
    ESP_LOGI(TAG,"RCP1 HTTPS en TCP 443; BOOT mantenido ~2 s para emparejar. Firmware experimental.");
}
