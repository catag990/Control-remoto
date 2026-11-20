idf_component_register(
    SRCS "main.c"
    INCLUDE_DIRS "."
    EMBED_TXTFILES "server.crt" "server.key"
    REQUIRES esp_https_server esp_wifi esp_netif esp_event nvs_flash json mbedtls esp_driver_ledc
)
