/* Standalone ESP32 Wi-Fi power controller, ESP-IDF 5.5 target: esp32.
 * Read HARDWARE.md before connecting a servo. Never connect motor power to GPIO.
 * Pairing requires physical hold of BOOT for 2+ seconds AND secret printed via USB serial.
 * HTTP traffic is always TLS; no unsigned command triggers the servo.
 */
#include <stdio.h>
#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#include <stdint.h>
#include <inttypes.h>
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "freertos/event_groups.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_random.h"
#include "esp_wifi.h"
#include "esp_netif.h"
#include "esp_event.h"
#include "esp_timer.h"
#include "esp_https_server.h"
#include "nvs_flash.h"
#include "nvs.h"
#include "driver/gpio.h"
#include "driver/ledc.h"
#include "mbedtls/pk.h"
#include "mbedtls/sha256.h"
#include "mbedtls/base64.h"
#include "cJSON.h"
#include "config_local.h"

#define MAX_BODY 2048
#define MAX_CLIENT_KEY 512
#define CHALLENGE_MS 30000
#define PAIR_WINDOW_MS 90000

static const char *TAG = "RemotePower";
static EventGroupHandle_t wifi_events;
static const EventBits_t WIFI_CONNECTED = BIT0;
static httpd_handle_t server = NULL;
static int64_t pair_deadline_us = 0;
static char pair_token[41] = {0};
static char paired_client[65] = {0};
static char paired_pub_b64[MAX_CLIENT_KEY] = {0};
static char nonce_hex[49] = {0};
static int64_t nonce_expiry_us = 0;
static int64_t last_press_us = -((int64_t)PRESS_COOLDOWN_MS * 1000);
static esp_ip4_addr_t local_ip;

extern const unsigned char server_cert_start[] asm("_binary_server_cert_pem_start");
extern const unsigned char server_cert_end[] asm("_binary_server_cert_pem_end");
extern const unsigned char server_key_start[] asm("_binary_server_key_pem_start");
extern const unsigned char server_key_end[] asm("_binary_server_key_pem_end");

static bool fixed_equal(const char *a, const char *b)
{
    size_t al = strlen(a), bl = strlen(b);
    if (al != bl) return false;
    unsigned char diff = 0;
    for (size_t i = 0; i < al; ++i) diff |= (unsigned char)(a[i] ^ b[i]);
    return diff == 0;
}

static void random_hex(char *dest, size_t bytes)
{
    static const char alpha[] = "0123456789ABCDEF";
    unsigned char buf[32];
    if (bytes > sizeof(buf)) abort();
    esp_fill_random(buf, bytes);
    for (size_t i = 0; i < bytes; ++i) {
        dest[i*2] = alpha[(buf[i] >> 4) & 15];
        dest[i*2+1] = alpha[buf[i] & 15];
    }
    dest[bytes*2] = '\0';
    memset(buf, 0, sizeof(buf));
}

static void load_paired_client(void)
{
    nvs_handle_t nvs;
    if (nvs_open("auth", NVS_READONLY, &nvs) != ESP_OK) return;
    size_t n = sizeof(paired_client);
    nvs_get_str(nvs, "client_id", paired_client, &n);
    n = sizeof(paired_pub_b64);
    nvs_get_str(nvs, "client_key", paired_pub_b64, &n);
    nvs_close(nvs);
}

static bool save_paired_client(const char *client, const char *pubkey)
{
    nvs_handle_t nvs;
    if (nvs_open("auth", NVS_READWRITE, &nvs) != ESP_OK) return false;
    esp_err_t result = nvs_set_str(nvs, "client_id", client);
    if (result == ESP_OK) result = nvs_set_str(nvs, "client_key", pubkey);
    if (result == ESP_OK) result = nvs_commit(nvs);
    nvs_close(nvs);
    if (result != ESP_OK) return false;
    strlcpy(paired_client, client, sizeof(paired_client));
    strlcpy(paired_pub_b64, pubkey, sizeof(paired_pub_b64));
    return true;
}

static void send_json(httpd_req_t *req, int code, const char *json)
{
    char status[40];
    snprintf(status, sizeof(status), "%d %s", code,
        code == 200 ? "OK" : code == 401 ? "Unauthorized" :
        code == 403 ? "Forbidden" : code == 429 ? "Too Many Requests" : "Bad Request");
    httpd_resp_set_status(req, status);
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Cache-Control", "no-store");
    httpd_resp_sendstr(req, json);
}

static bool request_body(httpd_req_t *req, char *buffer, size_t max, size_t *len)
{
    if (req->content_len < 0 || req->content_len > max-1) return false;
    int received = 0;
    while (received < req->content_len) {
        int chunk = httpd_req_recv(req, buffer + received, req->content_len - received);
        if (chunk <= 0) return false;
        received += chunk;
    }
    buffer[received] = '\0';
    *len = received;
    return true;
}

static bool header_value(httpd_req_t *req, const char *name, char *buffer, size_t max)
{
    size_t len = httpd_req_get_hdr_value_len(req, name);
    return len > 0 && len < max &&
        httpd_req_get_hdr_value_str(req, name, buffer, max) == ESP_OK;
}

static bool is_valid_client_id(const char *s)
{
    size_t len = strlen(s);
    if (len < 8 || len > 64) return false;
    for (size_t i = 0; i < len; i++)
        if (!((s[i] >= 'a' && s[i] <= 'z') || (s[i] >= 'A' && s[i] <= 'Z') ||
              (s[i] >= '0' && s[i] <= '9') || s[i] == '-')) return false;
    return true;
}

static bool valid_public_key_b64(const char *s)
{
    uint8_t der[384];
    size_t der_len = 0;
    if (mbedtls_base64_decode(der, sizeof(der), &der_len,
        (const unsigned char *)s, strlen(s)) != 0) return false;
    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    bool ok = mbedtls_pk_parse_public_key(&pk, der, der_len) == 0 &&
        mbedtls_pk_get_type(&pk) == MBEDTLS_PK_ECKEY &&
        mbedtls_pk_get_bitlen(&pk) == 256;
    mbedtls_pk_free(&pk);
    return ok;
}

static esp_err_t pair_handler(httpd_req_t *req)
{
    if (esp_timer_get_time() > pair_deadline_us || pair_token[0] == 0) {
        send_json(req, 403, "{\"error\":\"physical pairing closed\"}"); return ESP_OK;
    }
    char body[MAX_BODY]; size_t body_len = 0;
    if (!request_body(req, body, sizeof(body), &body_len)) {
        send_json(req, 400, "{\"error\":\"invalid body\"}"); return ESP_OK;
    }
    cJSON *root = cJSON_ParseWithLength(body, body_len);
    const cJSON *id = cJSON_GetObjectItemCaseSensitive(root, "clientId");
    const cJSON *key = cJSON_GetObjectItemCaseSensitive(root, "publicKeyBase64");
    const cJSON *token = cJSON_GetObjectItemCaseSensitive(root, "token");
    bool okay = cJSON_IsString(id) && cJSON_IsString(key) && cJSON_IsString(token) &&
        strlen(key->valuestring) < sizeof(paired_pub_b64) &&
        is_valid_client_id(id->valuestring) && fixed_equal(token->valuestring, pair_token) &&
        valid_public_key_b64(key->valuestring);
    if (okay) okay = save_paired_client(id->valuestring, key->valuestring);
    cJSON_Delete(root);
    if (!okay) { send_json(req, 403, "{\"error\":\"invalid pairing\"}"); return ESP_OK; }
    pair_token[0] = '\0'; pair_deadline_us = 0;
    ESP_LOGI(TAG, "A phone was paired using the physical pairing window");
    send_json(req, 200, "{\"paired\":true}");
    return ESP_OK;
}

static esp_err_t challenge_handler(httpd_req_t *req)
{
    char query[128], client[70];
    if (httpd_req_get_url_query_str(req, query, sizeof(query)) != ESP_OK ||
        httpd_query_key_value(query, "clientId", client, sizeof(client)) != ESP_OK ||
        !fixed_equal(client, paired_client) || paired_client[0] == '\0') {
        send_json(req, 401, "{\"error\":\"unauthorized\"}"); return ESP_OK;
    }
    random_hex(nonce_hex, 24);
    nonce_expiry_us = esp_timer_get_time() + ((int64_t)CHALLENGE_MS * 1000);
    char response[110];
    snprintf(response, sizeof(response), "{\"nonce\":\"%s\",\"ttlSeconds\":30}", nonce_hex);
    send_json(req, 200, response);
    return ESP_OK;
}

static bool verify_signed_request(httpd_req_t *req, const char *method, const char *path,
                                  const char *body, size_t len)
{
    char client[80], nonce[80], signature_b64[192];
    if (!header_value(req, "X-Client-Id", client, sizeof(client)) ||
        !header_value(req, "X-Nonce", nonce, sizeof(nonce)) ||
        !header_value(req, "X-Signature", signature_b64, sizeof(signature_b64))) return false;
    if (paired_client[0] == '\0' || !fixed_equal(client, paired_client) ||
        nonce_hex[0] == '\0' || !fixed_equal(nonce, nonce_hex) ||
        esp_timer_get_time() > nonce_expiry_us) return false;
    // ALWAYS consume challenge before signature verification.
    nonce_hex[0] = '\0'; nonce_expiry_us = 0;

    unsigned char body_hash[32], canonical_hash[32];
    char digest_hex[65];
    static const char alpha[] = "0123456789ABCDEF";
    mbedtls_sha256((const unsigned char *)body, len, body_hash, 0);
    for (int i = 0; i < 32; ++i) {
        digest_hex[i*2] = alpha[body_hash[i] >> 4];
        digest_hex[i*2+1] = alpha[body_hash[i] & 0xF];
    }
    digest_hex[64] = '\0';
    char canonical[256];
    int used = snprintf(canonical, sizeof(canonical), "RCP1\n%s\n%s\n%s\n%s\n%s",
        client, method, path, digest_hex, nonce);
    if (used < 0 || used >= sizeof(canonical)) return false;
    mbedtls_sha256((const unsigned char *)canonical, used, canonical_hash, 0);
    uint8_t signature[128], spki[384]; size_t sig_len = 0, pub_len = 0;
    if (mbedtls_base64_decode(signature, sizeof(signature), &sig_len,
        (const unsigned char *)signature_b64, strlen(signature_b64)) != 0 ||
        mbedtls_base64_decode(spki, sizeof(spki), &pub_len,
        (const unsigned char *)paired_pub_b64, strlen(paired_pub_b64)) != 0) return false;
    mbedtls_pk_context pk;
    mbedtls_pk_init(&pk);
    bool valid = mbedtls_pk_parse_public_key(&pk, spki, pub_len) == 0 &&
        mbedtls_pk_verify(&pk, MBEDTLS_MD_SHA256, canonical_hash, sizeof(canonical_hash),
            signature, sig_len) == 0;
    mbedtls_pk_free(&pk);
    return valid;
}

static esp_err_t status_handler(httpd_req_t *req)
{
    if (!verify_signed_request(req, "GET", "/api/v1/power/status", "", 0))
        send_json(req, 401, "{\"error\":\"unauthorized\"}");
    else send_json(req, 200, "{\"controllerOnline\":true,\"pcPowerState\":\"unknown\",\"actuatorReady\":true}");
    return ESP_OK;
}

static void servo_set_us(int pulse_us)
{
    // 50 Hz PWM, 16-bit resolution. Duty = pulse/20000 * 65536.
    uint32_t duty = ((uint32_t)pulse_us * 65536U) / 20000U;
    ledc_set_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0, duty);
    ledc_update_duty(LEDC_LOW_SPEED_MODE, LEDC_CHANNEL_0);
}

static esp_err_t power_handler(httpd_req_t *req)
{
    char body[MAX_BODY]; size_t len = 0;
    if (!request_body(req, body, sizeof(body), &len)) {
        send_json(req, 400, "{\"error\":\"bad body\"}"); return ESP_OK;
    }
    if (!verify_signed_request(req, "POST", "/api/v1/power/press", body, len)) {
        send_json(req, 401, "{\"error\":\"unauthorized\"}"); return ESP_OK;
    }
    cJSON *root = cJSON_ParseWithLength(body, len);
    const cJSON *operation = cJSON_GetObjectItemCaseSensitive(root, "operation");
    bool valid = cJSON_IsString(operation) && strcmp(operation->valuestring, "short_press") == 0;
    cJSON_Delete(root);
    if (!valid) { send_json(req, 400, "{\"error\":\"unsupported operation\"}"); return ESP_OK; }
    int64_t now = esp_timer_get_time();
    if ((now-last_press_us) < ((int64_t)PRESS_COOLDOWN_MS * 1000)) {
        send_json(req, 429, "{\"error\":\"cooldown active\"}"); return ESP_OK;
    }
    last_press_us = now; // Before action: duplicate requests cannot double-press.
    ESP_LOGW(TAG, "Authorized physical power-button press");
    servo_set_us(SERVO_PRESS_US);
    vTaskDelay(pdMS_TO_TICKS(PRESS_HOLD_MS));
    servo_set_us(SERVO_RELEASE_US);
    vTaskDelay(pdMS_TO_TICKS(500));
    send_json(req, 200, "{\"actuated\":true,\"pcPowerState\":\"unknown\"}");
    return ESP_OK;
}

static void start_https_server(void)
{
    httpd_ssl_config_t cfg = HTTPD_SSL_CONFIG_DEFAULT();
    cfg.httpd.max_uri_handlers = 4;
    cfg.httpd.lru_purge_enable = true;
    cfg.servercert = server_cert_start;
    cfg.servercert_len = server_cert_end - server_cert_start;
    cfg.prvtkey_pem = server_key_start;
    cfg.prvtkey_len = server_key_end - server_key_start;
    ESP_ERROR_CHECK(httpd_ssl_start(&server, &cfg));
    httpd_uri_t pair = { .uri="/api/v1/pair", .method=HTTP_POST, .handler=pair_handler };
    httpd_uri_t challenge = { .uri="/api/v1/auth/challenge", .method=HTTP_GET, .handler=challenge_handler };
    httpd_uri_t status = { .uri="/api/v1/power/status", .method=HTTP_GET, .handler=status_handler };
    httpd_uri_t power = { .uri="/api/v1/power/press", .method=HTTP_POST, .handler=power_handler };
    ESP_ERROR_CHECK(httpd_register_uri_handler(server, &pair));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server, &challenge));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server, &status));
    ESP_ERROR_CHECK(httpd_register_uri_handler(server, &power));
}

static void pairing_button_task(void *arg)
{
    int count = 0;
    while (true) {
        if (gpio_get_level(GPIO_NUM_0) == 0) {
            count++;
            if (count == 20) {
                random_hex(pair_token, 20);
                pair_deadline_us = esp_timer_get_time() + (int64_t)PAIR_WINDOW_MS * 1000;
                printf("\nPAIR_JSON: {\"kind\":\"esp32\",\"deviceId\":\"%s\","
                    "\"localHost\":\"" IPSTR "\",\"localPort\":443,"
                    "\"pin\":\"%s\",\"token\":\"%s\"}\n",
                    DEVICE_ID, IP2STR(&local_ip), DEVICE_PIN, pair_token);
                ESP_LOGW(TAG, "PAIRING WINDOW OPEN: 90 seconds, do not publish this token");
            }
        } else count = 0;
        if (count > 22) count = 22;
        vTaskDelay(pdMS_TO_TICKS(100));
    }
}

static void wifi_handler(void *arg, esp_event_base_t event_base, int32_t id, void *data)
{
    if (event_base == WIFI_EVENT && id == WIFI_EVENT_STA_START) esp_wifi_connect();
    if (event_base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        xEventGroupClearBits(wifi_events, WIFI_CONNECTED);
        esp_wifi_connect();
    }
    if (event_base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)data;
        local_ip = event->ip_info.ip;
        xEventGroupSetBits(wifi_events, WIFI_CONNECTED);
        ESP_LOGI(TAG, "Wi-Fi IP " IPSTR, IP2STR(&local_ip));
    }
}

static void init_wifi(void)
{
    wifi_events = xEventGroupCreate();
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    esp_netif_create_default_wifi_sta();
    wifi_init_config_t config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&config));
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID, wifi_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP, wifi_handler, NULL));
    wifi_config_t wifi = {0};
    strlcpy((char *)wifi.sta.ssid, WIFI_SSID, sizeof(wifi.sta.ssid));
    strlcpy((char *)wifi.sta.password, WIFI_PASSWORD, sizeof(wifi.sta.password));
    wifi.sta.threshold.authmode = WIFI_AUTH_WPA2_PSK;
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &wifi));
    ESP_ERROR_CHECK(esp_wifi_start());
    xEventGroupWaitBits(wifi_events, WIFI_CONNECTED, pdFALSE, pdTRUE, portMAX_DELAY);
}

void app_main(void)
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);
    load_paired_client();
    gpio_config_t button = { .pin_bit_mask = (1ULL << GPIO_NUM_0), .mode = GPIO_MODE_INPUT,
        .pull_up_en = GPIO_PULLUP_ENABLE, .pull_down_en = GPIO_PULLDOWN_DISABLE,
        .intr_type = GPIO_INTR_DISABLE };
    ESP_ERROR_CHECK(gpio_config(&button));
    ledc_timer_config_t timer = { .speed_mode=LEDC_LOW_SPEED_MODE,
        .duty_resolution=LEDC_TIMER_16_BIT, .timer_num=LEDC_TIMER_0,
        .freq_hz=50, .clk_cfg=LEDC_AUTO_CLK };
    ESP_ERROR_CHECK(ledc_timer_config(&timer));
    ledc_channel_config_t channel = { .gpio_num=SERVO_GPIO, .speed_mode=LEDC_LOW_SPEED_MODE,
        .channel=LEDC_CHANNEL_0, .intr_type=LEDC_INTR_DISABLE,
        .timer_sel=LEDC_TIMER_0, .duty=0, .hpoint=0 };
    ESP_ERROR_CHECK(ledc_channel_config(&channel));
    servo_set_us(SERVO_RELEASE_US);  // Fail-safe resting position on firmware startup.
    init_wifi();
    start_https_server();
    xTaskCreate(pairing_button_task, "physical_pair", 4096, NULL, 5, NULL);
    ESP_LOGI(TAG, "Controller ready. Hold BOOT for 2 seconds to pair from USB serial.");
}
