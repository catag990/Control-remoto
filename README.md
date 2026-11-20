# Control remoto propio — versión 0.5.0 (fuentes completas)

**Entrega independiente.** Este archivo contiene todos los archivos fuente del proyecto: PC Windows, panel WPF, Android, ESP32, protocolo, pruebas y manuales. **No requiere descargar ni aplicar versiones 0.1–0.4.** Sí requiere herramientas oficiales de desarrollo, dependencias NuGet/Maven/ESP-IDF y acceso a Internet para la primera compilación.

> **Aviso de validación importante**: paquete de código fuente, NO ejecutable Windows/APK/firmware ya compilado. No se dispone de .NET SDK, Android SDK, ESP-IDF ni dispositivos reales en este entorno. Se validaron estructura, sintaxis XML, Java del bootstrap y pruebas Python; NO se certifica compilación Windows/Android/ESP32. Evitar abrir puertos WAN hasta completar pruebas de seguridad e integración. Si aparece un error de compilación, registrar el Build Output y corregirlo antes de exponer servicios.

## Carpetas

| Ruta | Contenido |
|---|---|
| `windows/RemoteHost` | C# .NET 10, HTTPS/WSS, emparejamiento, escritorio y entrada |
| `windows/ControlRemoto.UI` | Programa gráfico WPF `ControlRemoto.exe`, QR dentro del panel |
| `android` | Proyecto Android Studio completo en Kotlin/Compose, con iniciador Gradle |
| `esp32` | Proyecto ESP-IDF clásico con HTTPS y control de servo |
| `protocol` | Contrato de firma ECDSA y formatos multimedia |
| `tools` | Aprovisionamiento ESP32, verificación y compilación |
| `tests` | Pruebas Python del empaquetado y protocolo |
| `docs` | Procedimientos, Word y estado de validación |

## Compilación Windows

Abrir **PowerShell en Windows con .NET SDK 10 instalado**:

```powershell
cd C:\Ruta\Control-remoto-propio-v0.5
.\windows\ControlRemoto.UI\build_windows.ps1
```

Resultado esperado tras compilar:

```text
dist\ControlRemoto-Windows\ControlRemoto.exe
dist\ControlRemoto-Windows\Host\RemoteHost.exe
```

Abrir `ControlRemoto.exe` desde sesión Windows iniciada. Generar QR en el mismo panel para Android. Ver `docs/COMPILAR_EXE_WINDOWS_DESDE_CERO.md` (y DOCX).

## Compilación Android

Abrir `android/` en Android Studio (JDK 17, SDK API 35). El proyecto incluye un **bootstrap Gradle independiente** dentro de `gradle/wrapper/gradle-wrapper.jar`: es código propio, NO el wrapper oficial, y descarga/verifica Gradle 8.11.1 desde `services.gradle.org` cuando es necesario. Requiere Internet para resolver dependencias. En terminal:

```powershell
cd android
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

APK esperado: `android/app/build/outputs/apk/debug/app-debug.apk` (solo si compila sin errores). La compilación aquí no fue verificada con SDK real.

## ESP32

Requiere ESP-IDF 5.5.x, ESP32 clásico, Wi-Fi 2,4 GHz, servo con 5V separada, y Python `cryptography`. Ejecuta `python tools/provision_esp32.py --ssid ... --password ...` y luego `idf.py set-target esp32`, `idf.py build`, `idf.py -p COMx flash monitor` desde `esp32/`. No contiene secretos privados generados. Consultar `esp32/HARDWARE.md`.

## Capacidades de v0.5

- Android vertical: video arriba, KBM/Mando y teclado de PC en controles inferiores.
- Android horizontal: video a pantalla completa y controles sobrepuestos; selector KBM/Mando superior.
- Mouse libre, arrastrar, relativo (juegos compatibles), botones sostenidos; hasta tres monitores detectados por Windows.
- Video H.264 con FFmpeg local más DXGI/encoder compatible; de lo contrario JPEG limitado a 30 FPS (no 144 reales).
- FPS solicitados 30/60/90/120/144/Auto y modo bajo impacto para proteger FPS del juego.
- Audio WASAPI loopback desde Windows hacia AudioTrack Android, PCM16 estéreo; consume ancho de banda.
- Teclado Windows completo F1-F24 y modificadores; no permite controlar el escritorio seguro ni SAS Ctrl+Alt+Supr.
- Emulación Xbox XInput OPCIONAL vía cliente ViGEm y controlador del usuario; si no está, respaldo KBM. ViGEmBus está archivado y no es la recomendación final.
- ESP32 independiente para accionamiento físico, condicionado a alimentación USB S5 real.

## Seguridad y limitaciones

El primer emparejamiento debe ocurrir en LAN física mediante código temporal generado por Windows o botón BOOT del ESP32. Android conserva la identidad privada P-256 en AndroidKeyStore y fija el SHA-256 SPKI TLS mostrado en el QR. Solicitudes posteriores se firman con RCP1 y nonce de uso único. La salida WAN requiere pruebas de autenticación, revocación, latencia, seguridad y configuración del router; **no se expone automáticamente**. El panel Windows trabaja solo después del inicio de sesión; no es un servicio Windows de pre-login.

**Proveniencia:** proyecto integrado a partir de los componentes disponibles de las actualizaciones v0.2–v0.4; las fuentes base ausentes en los ZIP incrementales fueron reconstituidas dentro de esta entrega. No es una exportación byte-a-byte de la rama GitHub actual, cuyo archivo completo no estuvo disponible para descarga aquí. Por eso se exige recompilar y validar antes del despliegue.
