# Cómo generar ControlRemoto.exe con interfaz gráfica — guía desde cero

**Objetivo:** transformar el código fuente del ZIP v0.5 en un ejecutable Windows con ventana y código QR incorporado. No hace falta instalar versiones anteriores. El ZIP es de fuentes, no contiene un EXE ya compilado. Los nombres y rutas corresponden a esta entrega.

## 1. ¿Qué es compilar?

Los `.cs` y `.xaml` son instrucciones del programa, no se ejecutan al hacer doble clic. El SDK .NET los convierte en un programa Windows `.exe`. El sistema consta de **dos ejecutables**: `ControlRemoto.exe` (ventana WPF) y `Host/RemoteHost.exe` (servidor de transmisión y autenticación). Ambos son necesarios; el primero inicia al segundo de forma controlada.

## 2. Preparar un PC Windows

1. Inicia sesión con tu usuario habitual de Windows. No uses un escritorio remoto de otra sesión para ejecutar el servidor inicial.
2. Abre el Explorador de archivos (tecla Windows+E).
3. Copia el ZIP recibido a una carpeta conocida, por ejemplo `C:\ControlRemoto`.
4. Haz clic derecho en el ZIP → **Extraer todo** → elige `C:\ControlRemoto`.
5. Entra a la carpeta `Control-remoto-propio-v0.5`. Comprueba que están las subcarpetas `windows`, `android` y `esp32`.
6. **No copies** datos de `backup_antes_v0X`, `base_v03` ni carpetas de parches; no se necesitan.

## 3. Instalar .NET SDK 10

1. Pulsa la tecla Windows y escribe `PowerShell`.
2. Abre Windows PowerShell normal. Para instalar herramientas puede ser necesario usar una ventana con privilegios de administrador.
3. Ejecuta `winget --version`. Si WinGet no está disponible, utiliza Microsoft Store/App Installer o descarga el instalador desde `https://dotnet.microsoft.com/download/dotnet/10.0`.
4. Ejecuta: `winget install --id Microsoft.DotNet.SDK.10 --exact`.
5. Acepta la licencia del instalador si se solicita.
6. Cierra **todas** las ventanas PowerShell y abre otra; el cambio en PATH puede no aparecer en ventanas antiguas.
7. Ejecuta `dotnet --version`. Debe comenzar por `10.`. Si dice “no se reconoce”, revisa instalación y PATH antes de continuar.

> Instalar “.NET Runtime” NO es equivalente a instalar “.NET SDK”. Debe decir SDK porque necesitamos `dotnet publish`.

## 4. Abrir PowerShell en la carpeta correcta

1. Abre la carpeta `C:\ControlRemoto\Control-remoto-propio-v0.5` en el Explorador.
2. Haz clic en la barra de direcciones superior, escribe `powershell` y pulsa Enter. Se abrirá PowerShell en esa misma ruta.
3. Para comprobarlo escribe `Get-Location`: la ruta mostrada debe terminar en `Control-remoto-propio-v0.5`.
4. Escribe `dir .\windows\ControlRemoto.UI\` y confirma que existe `build_windows.ps1`.

## 5. Compilar ambos ejecutables

Desde esa ventana **exactamente**:

```powershell
.\windows\ControlRemoto.UI\build_windows.ps1
```

El script ejecuta dos acciones por orden:

- `dotnet publish windows/RemoteHost/RemoteHost.csproj -c Release -r win-x64 --self-contained true -o dist/ControlRemoto-Windows/Host`
- `dotnet publish windows/ControlRemoto.UI/ControlRemoto.UI.csproj -c Release -r win-x64 --self-contained true -o dist/ControlRemoto-Windows`

**Qué debe ocurrir:** salen mensajes de “restaurando proyectos”, “compilando” y “publish” y finalmente la ruta de `ControlRemoto.exe`. La primera vez se descargarán dependencias NuGet (requiere Internet). Si muestra cualquier línea `error CS...`, `error XDG...` o `NU...`, la compilación **no** ha terminado correctamente.

**Si PowerShell bloquea el script:** abre PowerShell normal y ejecuta `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass`; responde `S` si lo solicita y vuelve a ejecutar el script. Esta opción solo dura en esa ventana, no altera permanentemente el equipo. Nunca desactives permanentemente la protección del sistema para este proyecto.

## 6. Verificar que los archivos existen

En la misma PowerShell:

```powershell
Test-Path .\dist\ControlRemoto-Windows\ControlRemoto.exe
Test-Path .\dist\ControlRemoto-Windows\Host\RemoteHost.exe
```

**Ambos deben devolver `True`.** Si alguno devuelve `False`, NO está instalada la versión completa; examina las últimas líneas del proceso `dotnet publish`.

## 7. Abrir el programa gráfico

1. Entra a `dist\ControlRemoto-Windows`.
2. Haz doble clic en `ControlRemoto.exe`.
3. Windows puede mostrar SmartScreen porque el programa no tiene firma de distribución comercial. No ignores alertas en equipos ajenos. Comprueba que el archivo proviene de tu compilación local.
4. Aparecerá la ventana con las secciones **Servidor local**, **Transmisión**, **Dispositivos autorizados** y **Vincular un teléfono**.
5. Espera a que el panel muestre “Servidor activo”. Si informa “Host no instalado”, el ejecutable secundario falta en `Host`.
6. Deja abierta la aplicación (puedes minimizarla a la bandeja). Si cierras la ventana, la sesión sigue activa hasta “Salir completamente” en el icono de la bandeja.

## 8. Comprobar el servidor y los puertos

1. Abre una segunda PowerShell y ejecuta `Get-NetTCPConnection -LocalPort 8443 -State Listen`.
2. Si aparece una fila, Windows está escuchando en TCP 8443. Si no, revisa el panel, el Firewall y si otro programa ya ocupa el puerto.
3. **No abras aún el puerto 8443 en el router**. Primero prueba en la red local y completa las pruebas de seguridad.
4. Comprueba el perfil de red: Configuración → Red e Internet → Propiedades de la conexión → **Red privada**, si es tu red doméstica de confianza.
5. Si necesitas acceso local, configura una regla del Firewall que permita TCP 8443 **solo en perfil privado y únicamente desde tu subred LAN**. El sistema no crea una regla pública automáticamente.

## 9. Generar QR dentro de la ventana WPF

1. Conecta el PC y Android al **mismo router/Wi-Fi local**. El Android puede usar 2,4 o 5 GHz mientras ambos equipos estén en la misma LAN y sin aislamiento de clientes.
2. El panel mostrará la “IPv4 LAN”, por ejemplo `192.168.1.100` (es solo un ejemplo: utiliza la tuya).
3. En el bloque “Vincular un teléfono” confirma esa IP. Pulsa **Generar QR**.
4. El QR aparece **dentro de la ventana** y caduca a los 3 minutos. No envíes fotos del QR por redes sociales ni servicios de terceros.
5. En Android abre `Control remoto propio` → **Escanear QR** → **Vincular dispositivo**.
6. El teléfono genera una clave privada que permanece en AndroidKeyStore y envía su clave pública para registrarse.
7. Pulsa “Actualizar” en **Dispositivos autorizados**; debe mostrarse el ID del celular.
8. Luego selecciona el PC desde Android y pulsa **Conectar al PC**.

> El host genera un PNG temporal dentro de Windows pero **no abre un visor de imágenes**: el panel carga ese PNG y presenta el QR directamente en la interfaz. Al caducar elimina el temporal cuando es posible.

## 10. Prueba funcional básica

1. Con Android conectado pulsa **Estado**; la respuesta debe ser HTTPS autenticada.
2. En modo vertical verifica el monitor superior y controles inferiores.
3. Gira Android horizontal: el monitor ocupa el fondo y los controles KBM/Mando están superpuestos.
4. Comprueba mouse libre → clic → arrastrar; luego “Juego relativo” en un programa no crítico.
5. Prueba `Ctrl+C`, `Ctrl+V` en Bloc de notas desde el teclado PC.
6. Si hay dos o tres monitores, selecciona Pantalla 1/2/3 según disponibilidad.
7. Activa Audio y verifica salida PCM; si hay latencia, baja calidad/FPS.
8. Comprueba FPS PC frente a FPS Android; no esperes 144 FPS sin GPU compatible y FFmpeg. La reserva JPEG está limitada a 30 FPS.
9. Desconecta y verifica que teclado y mouse no queden pulsados.

## 11. Problemas frecuentes

| Síntoma | Causa probable | Solución segura |
|---|---|---|
| `dotnet` no se reconoce | SDK no instalado o terminal antigua | Instala SDK 10 y reabre PowerShell |
| `NU1301` | NuGet sin acceso o proxy | Verifica Internet y proxy; no desactives TLS |
| `CSxxxx` | Error de compilación C# | Copia salida completa, archivo y línea; no afirmar EXE creado |
| `XDGxxxx`, `MCxxxx` | Error XAML/WPF | Revisa registro de compilación y bindings |
| `Test-Path` devuelve `False` | El script no publicó un componente | Busca el primer `error` en la consola |
| “Host no instalado” | Falta `Host/RemoteHost.exe` | Repite publicación de `RemoteHost.csproj` |
| QR no aparece | Host detenido o IP inválida | Revisa panel y dirección privada |
| Android rechaza QR | Token caducado, IP incorrecta, TLS pin diferente | Genera QR nuevo en LAN, no desactives verificación TLS |
| Pantalla negra | Captura GPU incompatible | Comprueba FFmpeg; usar respaldo JPEG |
| Se cierra con la X | Minimización a bandeja configurada | Abre icono de la bandeja o “Salir completamente” |

## 12. Controles y límites técnicos

- Windows solo controla la sesión del usuario que ha iniciado sesión; no permite login o UAC remoto.
- Un `.exe` autocontenido de .NET incluye runtime, no significa que el proyecto se compiló en esta entrega.
- El QR debe permanecer temporal y físicamente autorizado.
- El backend XInput de pruebas requiere controlador virtual compatible instalado por el propietario; Steam Input no convierte por sí solo mensajes táctiles en XInput.
- Pruebas LAN y seguridad **antes** de mapear puertos en el router.

## 13. Verificación final antes de distribuir

**No marcar como terminado hasta que se cumpla:** .NET publish sin errores; ambos EXE presentes; QR integrado visible; cliente autorizado se conecta LAN; cliente desconocido rechazado; tres monitores solo si están conectados; audio, teclado y mouse funcionan; reconexión limpia; antivirus sin alertas críticas; pruebas WAN realizadas solo tras validación de seguridad. Para distribuir comercialmente sería necesario firmar el ejecutable y revisar dependencias, licencia y controladores de terceros.
