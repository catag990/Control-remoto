# Estado verificable del paquete v0.5

Este paquete se creó integrando componentes y reemplazos v0.2–v0.4 disponibles, además de reconstituir fuentes que los parches NO incluían. Es **código fuente completo e independiente**; no es una exportación exacta ni un compilado verificado de la rama GitHub.

| Control | Estado | Evidencia |
|---|---|---|
| Estructura completa y sin parches previos | Comprobable | `python tools/check_sources.py` |
| Pruebas Python de integridad/contrato | Comprobable | `python -m pytest tests -v` |
| Bootstrap Gradle Java | JAR generado en este entorno | `javac` y `jar` del JDK |
| Sintaxis XML XAML/Manifest | Revisada por parser XML | Script check_sources |
| Kotlin/Compose compila con SDK Android | **NO VERIFICADO** | Android SDK/Gradle dependencias no disponibles aquí |
| WPF/RemoteHost compila en Windows .NET10 | **NO VERIFICADO** | .NET SDK Windows no disponible aquí |
| ESP32 compila en ESP-IDF 5.5 | **NO VERIFICADO** | ESP-IDF y hardware ausentes |
| APK, EXE, firmware .bin entregados | **NO** | Este ZIP es código fuente; scripts de compilación incluidos |
| Video H264 real hasta 144 FPS | **NO VERIFICADO** | Depende de GPU, ffmpeg, red y pantalla |
| Mando XInput real | **CONDICIONAL** | Depende de driver virtual compatible, no incluido |
| Acceso WAN seguro | **NO AUTORIZADO PARA PRODUCCIÓN** | Faltan pruebas negativas e integración |

Los “10 errores de Android” reportados en v0.4 no se recibieron con su registro `Build Output`; sin reproducirlos en SDK no se puede acreditar una corrección completa. Android v0.5 contiene un proyecto de configuración independiente y archivos principales reconstituidos con interfaces explícitas, pero debe compilarse y corregir los fallos concretos que aparezcan antes de afirmar que existe un APK funcional.

No compartir archivos `esp32/main/config_local.h`, `esp32/main/server.key`, `host.pfx`, tokens de emparejamiento ni capturas QR que permitan vinculación.
