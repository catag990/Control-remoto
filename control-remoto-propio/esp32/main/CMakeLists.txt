idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "."
    REQUIRES esp_https_server esp_wifi esp_netif esp_event nvs_flash esp_timer esp_driver_ledc mbedtls json
    EMBED_TXTFILES "server_cert.pem" "server_key.pem"
)
