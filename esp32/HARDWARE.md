# ESP32 y botón del PC — instrucciones de seguridad

- Destino: ESP32 clásico con ESP-IDF 5.5.x. GPIO18 es **solo señal PWM**.
- Servomotor con alimentación de 5 V regulada, corriente suficiente y masa GND común.
- Nunca alimentes un servo desde un GPIO. No conectes el servo al header del botón Power de la placa base.
- Calibra primero fuera del PC: el ángulo y el desplazamiento dependen del montaje.
- El USB de la placa base debe seguir energizado en S5 (Windows apagado). Compruébalo realmente.
- Un pulso de 350 ms solo cuando la firma RCP1 sea válida y haya transcurrido el enfriamiento de 20 s.
- El botón BOOT/GPIO0 activa un nuevo token de emparejamiento, no permite accionar el servo.
- La salida HTTPS es 443 del ESP32; el mapeo WAN previsto es 9443→443 **solo tras pruebas de seguridad**.
- Firmware experimental: no se ha compilado con ESP-IDF en este entorno ni validado en hardware.

### QR del ESP32

El ESP32 no tiene pantalla. Al mantener BOOT 2 s imprime una línea `PAIR_JSON:...` en la consola serie. Puedes pegar el JSON en la app Android o convertirlo a imagen local:

```powershell
python tools/esp32_pair_qr.py --json '{"kind":"esp32",...}' --out esp32-pair.png
```

Debe utilizarse el JSON **real completo** que imprime el ESP32, no los puntos suspensivos del ejemplo. El token caduca tras 180 s.
