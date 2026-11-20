# Instalación y pruebas del sistema completo v0.5 desde cero

**Versión:** fuentes completas, sin parches previos. **Estado:** compilación .NET, Android y ESP-IDF no verificada aquí por ausencia de sus SDK. Sigue las etapas por orden y conserva los mensajes completos de error si ocurren. El Word adicional `COMPILAR_EXE_WINDOWS_DESDE_CERO.docx` explica el ejecutable con capturas de rutas y comandos.

## A. Preparación sin experiencia previa

- **PC:** Windows 11 actualizado; sesión de usuario ya iniciada; conexión a router doméstico; .NET SDK 10, Android Studio y SDK API 35, JDK 17, Python 3, ESP-IDF 5.5.x.
- **Celular:** Android 8.0 o superior, cámara para QR, acceso a la misma red local durante el primer emparejamiento, depuración USB para instalación del APK.
- **ESP32:** placa ESP32 clásica, cable USB de datos, Wi-Fi de 2,4 GHz; servo de 5 V con alimentación independiente, masa común; GPIO18 como señal. Ver `esp32/HARDWARE.md`.
- **Video acelerado:** FFmpeg compilado con `ddagrab` y codificador compatible NVENC/AMF. Instalarlo localmente y asignar `RCP_FFMPEG_PATH` al ejecutable ffmpeg.exe; la aplicación no descarga FFmpeg ni cambia los drivers.
- **Mando real:** código de cliente ViGEm incluido para compatibilidad, pero no se suministra ni instala el controlador de kernel; ViGEmBus archivado. Sin controlador, el modo Mando usa un perfil KBM.

## B. Archivos de prueba automáticos

1. Extrae el ZIP completo a `C:\ControlRemoto\Control-remoto-propio-v0.5`.
2. Abre PowerShell desde esa carpeta escribiendo `powershell` en la barra de direcciones del Explorador.
3. Ejecuta `python --version`. Si falla, instala Python 3.
4. Instala dependencias de pruebas: `python -m pip install pytest cryptography`.
5. Ejecuta `python -m pytest tests -v`.
6. Resultado esperado: tests `PASSED`, sin `FAILED` ni `ERROR`. Este test revisa empaquetado y firmas del protocolo, NO compila Android/Windows ni demuestra funcionamiento remoto.
7. Ejecuta `python tools/check_sources.py`. Debe imprimir `OK`.

## C. Programa Windows con interfaz

1. Instala .NET 10 SDK: `winget install --id Microsoft.DotNet.SDK.10 --exact`.
2. Cierra y reabre PowerShell. Verifica `dotnet --version` (10.x).
3. Desde carpeta raíz del ZIP ejecuta `./windows/ControlRemoto.UI/build_windows.ps1` en PowerShell (en Windows la forma habitual es `.\windows\ControlRemoto.UI\build_windows.ps1`).
4. Si PowerShell bloquea scripts, usa `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` solo en esa ventana.
5. Comprueba `Test-Path .\dist\ControlRemoto-Windows\ControlRemoto.exe` y `Test-Path .\dist\ControlRemoto-Windows\Host\RemoteHost.exe`. **Ambos True**.
6. Ejecuta `dist\ControlRemoto-Windows\ControlRemoto.exe`. Espera indicador de servidor activo. Genera QR dentro de la ventana.
7. Consulta el manual específico Word o Markdown si falla. No distribuyas sin verificar.

## D. Android APK

1. Instala Android Studio (versión que soporte AGP 8.9.2), JDK17 y Android SDK 35 en SDK Manager.
2. En Android Studio selecciona `File → Open` y la carpeta `android` de la versión 0.5. **No abras el ZIP directamente**.
3. Espera sincronización Gradle. El iniciador `gradle-wrapper.jar` incluido es un bootstrap propio (fuente incluida) que descarga Gradle 8.11.1 oficial con verificación SHA256 la primera vez; requiere acceso a Internet y Java.
4. En terminal Android Studio ejecuta `.\gradlew.bat :app:assembleDebug`.
5. Si termina `BUILD SUCCESSFUL`, busca `android\app\build\outputs\apk\debug\app-debug.apk`.
6. Ejecuta `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug`. Un `NO-SOURCE` no equivale a una prueba aprobada.
7. Conecta Android por USB, habilita opciones de desarrollador → Depuración USB, autoriza el PC y pulsa Run en Android Studio; o instala APK con `adb install -r app-debug.apk` desde la carpeta de salida.
8. Ante `BUILD FAILED`, despliega “Build Output”, copia **primer error**, archivo/línea, e incluye versión exacta de Android Studio, Java y Gradle. No declares que está corregido sin volver a ejecutar la tarea.

## E. Primer emparejamiento Android ↔ Windows

1. Ambos en misma LAN y Windows con sesión iniciada. Si hay red de invitados o aislamiento de clientes, desactívalo en red doméstica de confianza o utiliza red principal.
2. En Windows abre el panel y pulsa **Generar QR** tras verificar IPv4 local.
3. En Android abre **Escanear QR**, acepta permiso de cámara, escanea; pulsa **Vincular dispositivo**.
4. Android guarda una clave ECDSA P-256 protegida en Keystore. No comparte la clave privada.
5. En WPF pulsa **Actualizar** en “Dispositivos autorizados”. El ID nuevo debe aparecer.
6. Selecciona PC → **Consultar estado** → **Conectar al PC**.
7. Comprobar: pantalla visible; monitor 1 (y 2–3 si existen); cursor libre; clic izquierdo/derecho; arrastre solo en modo Arrastrar; modo relativo; teclado PC Ctrl+C/V; selector Mando, audio, reconexión y FPS.
8. Si el certificado TLS SPKI no coincide, no desactives la verificación: genera nuevo QR y confirma que el equipo de destino es correcto.

## F. Audio, video y rendimiento del juego

- Abre Administrador de tareas → Rendimiento; registra FPS del juego, CPU y GPU con streaming cerrado.
- Con streaming a 30 y 60 FPS registra los mismos valores. Después prueba 90/120/144 solo si hardware admite.
- Primero activa **Priorizar rendimiento del PC**. Ese perfil limita H.264 a 60 FPS y 4,5 Mbps; JPEG reserva nunca supera 30 FPS.
- Confirma en consola si hay mensajes `GPUVIDEO:fallback`: significa que FFmpeg/GPU no ejecutó el camino acelerado; no representa 144 FPS reales.
- Para sonido reproduce audio Windows y activa `Audio ON` en Android. Ajusta volumen del teléfono. El audio usa WASAPI loopback PCM estéreo y consume ~1,54 Mbps a 48 kHz, sin compresión Opus.
- Para el mando, abre `joy.cpl` desde Win+R y busca el mando Xbox virtual **solo si instalaste expresamente** un controlador compatible. Sin él, el respaldo es KBM y `joy.cpl` puede no mostrarlo.

## G. ESP32 y alimentación con Windows apagado

1. No conectes el servo al botón del gabinete antes de calibrarlo.
2. En PC ejecuta `python -m pip install cryptography qrcode[pil]`.
3. Desde raíz: `python tools/provision_esp32.py --ssid "NOMBRE_WIFI" --password "CLAVE_WIFI" --device-id "ESP-001"`.
4. El script crea `esp32/main/config_local.h`, `server.crt`, `server.key`. **No subas esos secretos a GitHub**; no los incluyas en ZIP público.
5. Abre la terminal ESP-IDF 5.5.x y ejecuta `cd esp32`, `idf.py set-target esp32`, `idf.py build`.
6. Conecta ESP32 por USB de datos y mira el puerto en Administrador de dispositivos (por ejemplo COM5). Ejecuta `idf.py -p COM5 flash monitor`.
7. Verifica Wi-Fi 2,4 GHz y salida de IP. Mantén BOOT alrededor de 2 segundos mientras el firmware está ejecutándose; copia la línea `PAIR_JSON:` sin incluir ese prefijo al campo QR/JSON en Android. El token caduca en 3 minutos.
8. En Android pulsa Vincular; prueba Consultar estado; prueba pulsación **con el servo separado del gabinete**. Debe pulsar 350 ms y bloquear repetición durante 20 segundos.
9. Calibra mecanismo real. Apaga Windows y verifica con multímetro y observación que USB ESP32/servo sigue energizado en S5. Nunca supongas disponibilidad por la configuración BIOS solamente.

## H. Fuera de Wi-Fi (WAN): NO habilitar antes de seguridad

La arquitectura prevista para redirección de puertos es: WAN TCP 8443 → Windows LAN 8443 y WAN TCP 9443 → ESP32 LAN 443. **No configurar todavía** si faltan pruebas de autenticación, revocación, inyección, replay, seguridad del firmware o se detecta CGNAT. Reservar IP LAN por DHCP en el router y comparar WAN pública contra la dirección WAN del router. En Android activar “Usar Internet (WAN)” e introducir IPv4 pública, una vez la implementación haya pasado los controles. Evitar abrir puertos de prueba a todo Internet.

## I. Registro mínimo de pruebas

| ID | Prueba | Resultado exigido |
|---|---|---|
| S01 | `python -m pytest tests -v` | Todo PASSED |
| W01 | `dotnet publish` | Compilación sin errores |
| W02 | `ControlRemoto.exe` y `Host/RemoteHost.exe` | Archivos existen |
| W03 | WPF QR | QR visible y caduca a 3 minutos |
| A01 | `:app:assembleDebug` | BUILD SUCCESSFUL + APK |
| A02 | `:app:lintDebug` | Sin errores bloqueantes |
| L01 | Android ↔ PC LAN | Conexión TLS firmada |
| L02 | Mouse libre/arrastrar/relativo | Acciones diferenciadas |
| L03 | Tres monitores | Solo muestra conectados |
| L04 | Teclado F1–F24/combos | Entradas correctas, liberadas al salir |
| L05 | Audio PC ↔ Android | Reproducción sin fallas severas |
| L06 | FPS 30/60/120/144 | FPS efectivos medidos, no inferidos |
| E01 | `idf.py build` | Proyecto completo |
| E02 | Servo fuera del PC | Pulso 350 ms sin repetición |
| SEC01 | Cliente no autorizado | Rechazo |
| SEC02 | Token y nonce usado | Rechazo |
| SEC03 | Cliente revocado | Pierde autorización |
| WAN01 | Android datos móviles | Solo tras SEC y pruebas de LAN |

Conserva Build Output y logs sin claves, contraseñas ni códigos QR privados. Un test de estructura correcto **no autoriza** publicar puertos WAN.
