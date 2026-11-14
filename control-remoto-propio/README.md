# Control remoto propio: PC Windows + Android + ESP32

Version de desarrollo **0.1 / prototipo funcional de codigo fuente**.

Este repositorio implementa los componentes del sistema solicitado, sin AnyDesk, RDP,
Wake-on-LAN, VPS, pagina web o servidor VPN. Android conversa por IPv4 con Windows
y con el ESP32 usando canales TLS autentificados. El PC ejecuta el servidor de escritorio;
el ESP32 acciona un servo para pulsar el boton fisico y sigue disponible en S5 si
la placa madre alimenta el USB.

**IMPORTANTE: no se entrega como producto auditado ni como solucion lista para
exponer en Internet.** Los tres proyectos requieren compilacion en sus plataformas y
pruebas reales de seguridad, electrica y de compatibilidad antes de redirigir puertos.
Este ambiente de generacion no tiene SDK .NET, Android SDK ni ESP-IDF: no se han
compilado ni ejecutado esos binarios. Si se abre un puerto sin validar el sistema,
puede exponerse el computador a riesgos relevantes.

## Contenido

- `windows/RemoteHost`: .NET 10 C#, Kestrel TLS/WSS, credenciales de cliente, QR local,
  capturas JPEG a 5 fps, control de mouse, teclado, 2+ monitores, programas permitidos,
  apagado ordinario diferido 30 s.
- `android`: Kotlin/Jetpack Compose, Android Keystore ECDSA, verificacion por SPKI,
  escaneo QR o codigo manual, cliente Windows/ESP32, pantalla remota y gestos.
- `esp32`: firmware ESP-IDF, HTTPS, emparejamiento fisico BOOT + USB serie,
  firma digital, NVS de cliente autorizado, GPIO PWM para servo.
- `protocol/RCP1.md`: contrato exacto de firmas, solicitudes y canales.
- `tools`: generador local de credenciales ESP32, QR, diagnosticos y firewall.
- `tests`: pruebas de interoperabilidad criptografica del protocolo (Python).

## Requisitos

1. PC Windows 10/11, cuenta con sesion interactiva iniciada. Para compilar:
   .NET SDK 10 y PowerShell. Windows no permite que un servicio de sesion 0 capture
   directamente el escritorio interactivo. Por ello v0.1 se ejecuta en la sesion
   del usuario **despues de iniciar sesion**, no en el escritorio de login.
2. Android Studio con Android SDK 35 y Java 17; Android 8.0+ (API 26). Para escanear
   QR con el escaner incluido se requieren Google Play Services; el codigo JSON
   tambien puede ingresarse manualmente sin escaner.
3. ESP32 DevKit clasico, ESP-IDF 5.5 con target `esp32`; Python 3 con
   `cryptography` y opcional `qrcode[pil]`, conexion USB al PC, servo/mecanismo
   compatible con GPIO18, fuente electrica adecuada y soporte mecanico.
4. Router con direccion IPv4 publica realmente accesible, reglas NAT y posibilidad
   de asignar reservas DHCP. No se necesita IPv6 en Android.

## Instalacion inicial, SOLO en red local

### A. Servidor Windows

Abrir PowerShell en Windows dentro de `windows/RemoteHost`:

```powershell
dotnet restore
dotnet publish -c Release -r win-x64 --self-contained true
```

El ejecutable queda dentro de `bin/Release/net10.0-windows/win-x64/publish/RemoteHost.exe`.
Iniciarlo desde PowerShell **en la cuenta Windows a controlar**. Iniciar sesion antes.
El certificado ECDSA se crea en `Cert:\CurrentUser\My`; la clave privada no se manda
por red. El registro local de celulares autorizados se encuentra en LocalAppData.

En otra ventana PowerShell (administrador), habilitar SOLO LAN para pruebas:

```powershell
.\tools\configure_windows_firewall.ps1
```

La ubicacion anterior supone ejecutar desde el directorio raiz del repositorio.
En la consola de `RemoteHost.exe` escribir `pair`. Introducir IPv4 LAN del PC, por
ejemplo `192.168.1.100`. El programa genera y muestra `remotehost-pairing.png`,
un QR temporal con huella SPKI, IP local, puerto y token de un uso. El token expira
en 3 minutos. Nunca compartir el QR por mensajeria o Internet.

### B. Aplicacion Android

Abrir la carpeta `android/` con Android Studio, sincronizar Gradle e instalar en
el telefono propio (Build > Build APK o Run). Si hace falta una wrapper de Gradle,
generarla localmente con `gradle wrapper --gradle-version 8.11.1`.

Con celular en la misma Wi-Fi, tocar `Escanear QR` (o pegar el JSON de la consola)
y `Vincular`. La app genera una clave ECDSA P-256 exclusiva para este destino y
la guarda en Android Keystore. El PC almacena la clave publica del telefono.

### C. Firmware ESP32

Con ESP-IDF configurado para `esp32`, ejecutar desde la raiz del repositorio:

```powershell
python -m pip install cryptography qrcode[pil]
python tools/provision_esp32.py --ssid "WIFI_DEL_HOGAR" --password "CLAVE_WIFI" --device-id "ESP-001"
cd esp32
idf.py set-target esp32
idf.py build
idf.py -p COM5 flash monitor
```

Ajustar `COM5` al puerto serie real. El aprovisionador genera claves y
certificado propios en `esp32/main/`. **No subir estos archivos a Git ni compartir
el proyecto ya aprovisionado**. Se excluyen por `.gitignore`. La clave del ESP32
va embebida en el firmware: para despliegue final habilitar Flash Encryption,
Secure Boot y NVS Encryption segun el modelo y el proceso de provisionamiento.

Con el ESP conectado a la Wi-Fi y el PC encendido, mantener presionado el boton
`BOOT` del DevKit durante 2 segundos. En el monitor serie aparece la linea
`PAIR_JSON: {...}`. Copiar su JSON directamente en Android y pulsar `Vincular`.
O generar localmente un QR:

```powershell
python tools/pairing_qr.py --json '{"kind":"esp32",...}' --out esp32-pairing.png
```

Reemplazar el texto de ejemplo por el objeto real completo emitido por el ESP32.
El token expira a los 90 segundos y se consume al emparejar.

### D. Pruebas de funcionamiento local

En Android dejar `Conexion: LAN`. Seleccionar `PC-...` y usar `Estado` y `Conectar`.
La pantalla transmite JPEG sobre WebSocket cifrado; la pulsacion toca (clic),
el arrastre mantiene boton izquierdo, `Enviar texto` escribe, y los botones
lanzan aplicaciones permitidas. Seleccionar `ESP-001`, consultar estado y
probar el servomecanismo **separado del boton del PC la primera vez** para
verificar direccion, angulo, retorno y alimentacion.

El estado `pcPowerState: unknown` es intencional: sin sensor el ESP32 NO puede
certificar si el PC esta realmente apagado. Android exige confirmacion para
accionar el boton y para apagar Windows.

### E. Acceso externo IPv4, solo despues de las validaciones

1. Reservar DHCP LAN para Windows y ESP32 (por ejemplo 192.168.1.100 y .101).
2. Verificar que la IPv4 WAN del router corresponde a una IPv4 publica accesible.
3. Comprobar y registrar huellas, control de acceso, revocacion y pruebas negativas.
4. Cambiar la regla firewall Windows a permitir WAN, de forma expresamente autorizada:
   ejecutar `tools/configure_windows_firewall.ps1 -Publish` como administrador.
5. Configurar manualmente en el router dos redirecciones **TCP**:
   WAN 8443 -> PC:8443; WAN 9443 -> ESP32:443.
6. En Android activar `Conexion: Internet`, indicar la IPv4 publica actual y probar
   usando datos moviles con Wi-Fi desactivado. La misma IP publica sirve para
   Windows y ESP32 porque utilizan puertos diferentes.

La aplicacion NO necesita pagina HTML ni dominio. Si cambia la IPv4 publica,
habra que actualizarla manualmente (sin DNS dinamico en esta version).
No se habilita ninguna regla automaticamente en el router.

## Alcance realmente implementado

| Funcion | Estado en el codigo |
| --- | --- |
| Separacion Windows/ESP32 | Implementado |
| Emparejamiento local con QR/token y ECDSA | Implementado en codigo fuente |
| TLS con pinning de clave publica | Implementado en codigo fuente |
| Prueba contra replay con nonce de un uso | Implementado en codigo fuente |
| Captura pantalla Windows y visualizacion Android | JPEG/WSS, hasta 5 fps |
| Mouse, arrastre, scroll, texto y atajos | Basico, despues del login |
| Cambiar monitor / aplicaciones permitidas | Basico |
| Apagado ordinario Windows | `shutdown /s /t 30` autentificado |
| Encendido fisico | Servo controlado, requiere montaje y calibracion |
| Autoarranque Windows tras login | Script opcional `windows/install-startup.ps1` |
| Control previo al login / UAC escritorio seguro | No implementado |
| Video H.264/60fps/audio/multiple sesiones | No implementado |
| Pruebas en hardware, .NET, Android, IDF | Pendientes de ejecutar en equipos reales |
| Auditoria/penetracion antes de Internet | Pendiente |

## Advertencias operativas

- Un servicio con control de teclado/mouse tiene impacto de seguridad alto. No
  reenviar puertos WAN sin un proceso de pruebas. Evitar DMZ, UPnP, RDP, SMB o
  administracion remota del router abiertos indiscriminadamente.
- El emparejamiento es fisico, no por una API publica abierta permanentemente.
  Windows requiere `pair` en consola; ESP32 requiere `BOOT` y token por USB serie.
- La version 0.1 es un proceso de usuario interactivo. Su autorun visible no
  convierte el host en un servicio Windows previo al login.
- Al perder el control remoto, Android no debe interpretar una falla de red
  como confirmacion de apagado. El boton fisico podria apagar un PC encendido.
- El ESP32 no debe alimentar directamente un servomotor desde un GPIO. Consultar
  `esp32/HARDWARE.md`; USB standby S5 no esta garantizado sin prueba en esta placa.
- Para revocar el movil, ejecutar `revoke <clientId>` en la consola Windows y
  volver a emparejar el ESP32 localmente desde el dispositivo seguro.
- Ningun archivo de secretos creado durante el aprovisionamiento debe incluirse
  en archivos ZIP que se compartan, copias de seguridad no cifradas o repositorios.

## Pruebas del contrato

```bash
python -m pytest tests/ -q
```

Estas pruebas validan el contrato criptografico a nivel de especificacion Python,
NO sustituyen compilar .NET/Kotlin/ESP-IDF ni pruebas de interaccion de firmware.
