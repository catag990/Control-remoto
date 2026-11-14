package cl.controlremoto.app

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { RemoteApp(this) } }
    }
}

@Composable
fun RemoteApp(activity: MainActivity) {
    val context = LocalContext.current
    val registry = remember { DeviceRegistry(context) }
    val prefs = remember { context.getSharedPreferences("network_config_v1", 0) }
    val scope = rememberCoroutineScope()
    var paired by remember { mutableStateOf(registry.list()) }
    var pairJson by remember { mutableStateOf("") }
    var publicAddress by remember { mutableStateOf(prefs.getString("wan_host", "") ?: "") }
    var remoteMode by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Emparejamiento local antes de abrir puertos.") }
    var selected by remember { mutableStateOf<PairedDevice?>(null) }
    var showRemote by remember { mutableStateOf(false) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var socket by remember { mutableStateOf<WebSocket?>(null) }
    var inputText by remember { mutableStateOf("") }
    var editedPublicPort by remember { mutableStateOf("") }
    var confirmShutdown by remember { mutableStateOf(false) }
    var confirmPower by remember { mutableStateOf(false) }

    fun address(device: PairedDevice): String {
        if (!remoteMode) return device.lanHost
        return publicAddress.trim().also {
            require(it.matches(Regex("[A-Za-z0-9.-]{3,253}"))) { "Indica IPv4 publica o dominio valido" }
        }
    }
    fun port(device: PairedDevice): Int = if (remoteMode) device.publicPort else device.port
    fun submit(operation: (RemoteApi, PairedDevice) -> String) {
        val device = selected ?: return
        scope.launch(Dispatchers.IO) {
            try {
                val api = RemoteApi(device, address(device), port(device))
                val result = operation(api, device)
                withContext(Dispatchers.Main) { status = result }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { status = "Error: ${e.message}" }
            }
        }
    }
    DisposableEffect(showRemote) {
        onDispose {
            if (showRemote) {
                socket?.close(1000, "UI closed")
                socket = null
            }
        }
    }
    if (confirmShutdown) AlertDialog(
        onDismissRequest = { confirmShutdown = false },
        title = { Text("Apagar Windows") },
        text = { Text("Windows iniciara un apagado normal en 30 segundos. Confirma solo si deseas apagar el PC.") },
        confirmButton = { TextButton(onClick = {
            confirmShutdown = false
            submit { api, _ -> api.authorized("POST", "/api/v1/shutdown",
                JSONObject().put("confirm", true)).toString() }
        }) { Text("Apagar") } },
        dismissButton = { TextButton(onClick = { confirmShutdown = false }) { Text("Cancelar") } }
    )
    if (confirmPower) AlertDialog(
        onDismissRequest = { confirmPower = false },
        title = { Text("Accionar boton fisico") },
        text = { Text("El estado del PC puede ser desconocido. Acciona el boton solo cuando corresponda.") },
        confirmButton = { TextButton(onClick = {
            confirmPower = false
            submit { api, _ -> api.authorized("POST", "/api/v1/power/press",
                JSONObject().put("operation", "short_press")).toString() }
        }) { Text("Accionar") } },
        dismissButton = { TextButton(onClick = { confirmPower = false }) { Text("Cancelar") } }
    )

    Column(Modifier.fillMaxSize().padding(14.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Control remoto propio", style = MaterialTheme.typography.headlineSmall)
        Text("PC Windows + ESP32, sin servicios externos de escritorio")
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Conexion: ${if (remoteMode) "Internet" else "LAN"}")
            Switch(checked = remoteMode, onCheckedChange = { remoteMode = it })
        }
        OutlinedTextField(value = publicAddress,
            onValueChange = {
                publicAddress = it
                prefs.edit().putString("wan_host", it).apply()
            }, label = { Text("IPv4 publica del domicilio (para Internet)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("Para emparejar utiliza siempre la Wi-Fi local y el QR fisico del dispositivo.",
            style = MaterialTheme.typography.bodySmall)
        Text("Emparejar nuevo dispositivo", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val options = GmsBarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
                GmsBarcodeScanning.getClient(activity, options).startScan()
                    .addOnSuccessListener { result -> pairJson = result.rawValue ?: "" }
                    .addOnFailureListener { e -> status = "Escaner no disponible: ${e.message}. Pega el codigo JSON." }
            }) { Text("Escanear QR") }
            Button(onClick = {
                scope.launch(Dispatchers.IO) {
                    try {
                        val (device, token) = RemoteApi.deviceFromPairing(pairJson)
                        val answer = RemoteApi(device, device.lanHost).pair(token)
                        registry.save(device)
                        withContext(Dispatchers.Main) {
                            paired = registry.list()
                            selected = device
                            editedPublicPort = device.publicPort.toString()
                            pairJson = ""
                            status = "Vinculado ${device.deviceId}: $answer"
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { status = "No se pudo emparejar: ${e.message}" }
                    }
                }
            }) { Text("Vincular") }
        }
        OutlinedTextField(value = pairJson, onValueChange = { pairJson = it },
            modifier = Modifier.fillMaxWidth(), maxLines = 3,
            label = { Text("Codigo de emparejamiento (QR o JSON)") })
        HorizontalDivider()
        Text("Dispositivos vinculados", style = MaterialTheme.typography.titleMedium)
        if (paired.isEmpty()) Text("Todavia no hay dispositivos emparejados")
        paired.forEach { device ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    selected = device
                    editedPublicPort = device.publicPort.toString()
                    showRemote = false
                },
                    modifier = Modifier.weight(1f)) {
                    Text("${device.kind.uppercase()} ${device.deviceId.take(15)}")
                }
                if (selected?.deviceId == device.deviceId) Text("Seleccionado")
            }
        }
        selected?.let { device ->
            HorizontalDivider()
            Text("Dispositivo: ${device.deviceId}")
            Text("Destino: ${if (remoteMode) publicAddress else device.lanHost}:${port(device)}",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = editedPublicPort,
                    onValueChange = { editedPublicPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Puerto externo WAN") },
                    singleLine = true, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = {
                    val p = editedPublicPort.toIntOrNull()
                    if (p != null && p in 1..65535) {
                        val updated = device.copy(publicPort = p)
                        registry.save(updated)
                        paired = registry.list()
                        selected = updated
                        status = "Puerto publico actualizado"
                    } else status = "Puerto publico no valido"
                }) { Text("Guardar puerto") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    submit { api, dev ->
                        api.authorized("GET", if (dev.kind == "pc") "/api/v1/status"
                            else "/api/v1/power/status").toString()
                    }
                }) { Text("Estado") }
                if (device.kind == "pc") {
                    Button(onClick = {
                        showRemote = true
                        val target = device
                        scope.launch(Dispatchers.IO) {
                            try {
                                socket?.close(1000, "Reconnecting")
                                val newSocket = RemoteApi(target, address(target), port(target)).openDesktop(
                                    onFrame = { bitmap -> activity.runOnUiThread { frame = bitmap } },
                                    onStatus = { s -> activity.runOnUiThread { status = s } }
                                )
                                withContext(Dispatchers.Main) { socket = newSocket }
                            } catch (e: Exception) {
                                withContext(Dispatchers.Main) { status = "Conexion fallida: ${e.message}" }
                            }
                        }
                    }) { Text("Conectar") }
                    OutlinedButton(onClick = { confirmShutdown = true }) { Text("Apagar") }
                } else {
                    Button(onClick = { confirmPower = true }) { Text("Encender / pulsar") }
                }
            }
        }
        if (showRemote && selected?.kind == "pc") {
            HorizontalDivider()
            Text("Escritorio remoto (JPEG/WSS, hasta 5 fps)")
            frame?.let { bitmap ->
                Image(bitmap.asImageBitmap(), contentDescription = "Escritorio remoto",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .pointerInput(bitmap.width, bitmap.height) {
                            detectTapGestures { offset ->
                                val x = (offset.x / size.width).coerceIn(0f, 1f)
                                val y = (offset.y / size.height).coerceIn(0f, 1f)
                                socket?.send(JSONObject().put("type", "click")
                                    .put("x", x).put("y", y).put("button", "left").toString())
                            }
                        }
                        .pointerInput(bitmap.width, bitmap.height) {
                            detectDragGestures(
                                onDragStart = { sendButton(socket, "left", "down") },
                                onDragEnd = { sendButton(socket, "left", "up") },
                                onDragCancel = { sendButton(socket, "left", "up") }
                            ) { change, _ ->
                                val x = (change.position.x / size.width).coerceIn(0f, 1f)
                                val y = (change.position.y / size.height).coerceIn(0f, 1f)
                                socket?.send(JSONObject().put("type", "move")
                                    .put("x", x).put("y", y).toString())
                            }
                        }
                )
            } ?: Text("Esperando primer fotograma...")
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SmallAction("Alt+Tab") { sendShortcut(socket, "alt-tab") }
                SmallAction("Win+D") { sendShortcut(socket, "win-d") }
                SmallAction("Intro") { sendShortcut(socket, "enter") }
                SmallAction("Borrar") { sendShortcut(socket, "backspace") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SmallAction("Monitor 1") { socket?.send(JSONObject().put("type", "monitor").put("index", 0).toString()) }
                SmallAction("Monitor 2") { socket?.send(JSONObject().put("type", "monitor").put("index", 1).toString()) }
                SmallAction("Scroll +") { sendScroll(socket, 120) }
                SmallAction("Scroll -") { sendScroll(socket, -120) }
                SmallAction("Derecho") { sendButton(socket, "right", "down"); sendButton(socket, "right", "up") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                SmallAction("Bloc de notas") { sendApp(socket, "notepad") }
                SmallAction("Calculadora") { sendApp(socket, "calculator") }
                SmallAction("Explorador") { sendApp(socket, "explorer") }
            }
            OutlinedTextField(value = inputText, onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth(), label = { Text("Teclado: texto que se enviara al PC") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (inputText.length <= 256) {
                        socket?.send(JSONObject().put("type", "text").put("text", inputText).toString())
                        inputText = ""
                    } else status = "Maximo 256 caracteres por envio"
                }) { Text("Enviar texto") }
                OutlinedButton(onClick = {
                    socket?.close(1000, "User disconnected")
                    socket = null
                    showRemote = false
                    frame = null
                }) { Text("Desconectar") }
            }
        }
        HorizontalDivider()
        Text(status, color = MaterialTheme.colorScheme.primary)
        Text("No abras los puertos del router hasta concluir las pruebas locales de seguridad.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SmallAction(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 7.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
private fun sendShortcut(ws: WebSocket?, name: String) {
    ws?.send(JSONObject().put("type", "shortcut").put("name", name).toString())
}
private fun sendScroll(ws: WebSocket?, delta: Int) {
    ws?.send(JSONObject().put("type", "scroll").put("delta", delta).toString())
}
private fun sendApp(ws: WebSocket?, name: String) {
    ws?.send(JSONObject().put("type", "app").put("name", name).toString())
}

private fun sendButton(ws: WebSocket?, button: String, action: String) {
    ws?.send(JSONObject().put("type", "button").put("button", button).put("action", action).toString())
}
