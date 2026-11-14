# Hardware: ESP32 y pulsador mecanico

Diseno de prueba: ESP32 DevKit clasico con servo pequeño de 5 V, brazo mecanico que
presiona el boton exterior del gabinete. No toca contactos electricos de la placa.
La MSI B450M BAZOOKA puede tener puertos con energia de espera segun configuracion,
pero hay que medirlo en el puerto USB concreto durante S5 y comprobar la corriente.

Conexiones de control:

- Servo SIGNAL a GPIO18 (ESP32 clasico; revisar pinout si el modulo es ESP32-S3).
- Servo GND a masa comun del circuito de alimentacion del servo y ESP32.
- Servo V+ a una **alimentacion de 5 V correctamente dimensionada**; nunca al GPIO.
- ESP32 USB al puerto que conserve alimentacion cuando Windows este apagado.
- BOOT (GPIO0) del DevKit se utiliza para abrir pairing fisicamente cuando corre
  el firmware. No mantenerlo presionado durante un reset o puede entrar al bootloader.

**No se puede asegurar que el mismo puerto USB de espera alimente fiablemente ESP32
+ servo**: verificar voltaje bajo carga y corriente maxima del puerto, picos del servo,
caidas de tension, movimiento del brazo y posicion segura de reposo. Si es
insuficiente, el servo necesitara una alimentacion adecuada independiente o un
mecanismo fisico de menor consumo. Esto no añade dispositivos de red.

Antes de pegar el brazo al gabinete:

1. Probar el movimiento del servo separado del PC y ajustar
   `SERVO_RELEASE_US` y `SERVO_PRESS_US` mediante provisionamiento local.
2. Verificar que vuelve a la posicion de reposo, incluso despues de reinicio.
3. Establecer tope mecanico para no danar el boton o el gabinete.
4. Comprobar 10 ciclos con el ESP32 alimentado por el USB en S5.
5. Verificar que una pulsacion de ~350 ms inicia normalmente el PC y que el
   cooldown de 20 s impide las pulsaciones repetidas.
6. Repetir pruebas ante cortes de red, reinicios y fallas de energia.

Sin un sensor optico de LED/power, el ESP32 solo informa que el control esta
operativo y el estado real del PC es UNKNOWN. NO desencadena pulsaciones por
suponer que un PC no responde. El usuario confirma la accion.
