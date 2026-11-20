package cl.controlremoto.app

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.WebSocket
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Physical QR code contains the public TLS pin + ephemeral token; never persist the token. */
data class DeviceRecord(
    val deviceId: String,
    val kind: String,
    val clientId: String,
    val lanHost: String,
    val lanPort: Int,
    val wanPort: Int,
    val spkiSha256: String,
    val wanHost: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("deviceId", deviceId).put("kind", kind)
        .put("clientId", clientId).put("lanHost", lanHost).put("lanPort", lanPort)
        .put("wanPort", wanPort).put("spkiSha256", spkiSha256).put("wanHost", wanHost)

    companion object {
        fun fromJson(json: JSONObject): DeviceRecord = DeviceRecord(
            deviceId = json.getString("deviceId"), kind = json.getString("kind"),
            clientId = json.getString("clientId"), lanHost = json.getString("lanHost"),
            lanPort = json.getInt("lanPort"), wanPort = json.getInt("wanPort"),
            spkiSha256 = json.getString("spkiSha256"), wanHost = json.optString("wanHost", "")
        )

        fun fromQr(qr: JSONObject, clientId: String): DeviceRecord {
            val kind = qr.getString("kind")
            require(kind == "pc" || kind == "esp32") { "QR de dispositivo incompatible" }
            val host = qr.optString("lanHost", qr.optString("host", ""))
            require(host.isNotBlank()) { "El QR no contiene IPv4 local" }
            val port = qr.optInt("lanPort", if (kind == "pc") 8443 else 443)
            val pin = qr.getString("spkiSha256")
            return DeviceRecord(qr.getString("deviceId"), kind, clientId, host, port,
                qr.optInt("wanPort", if (kind == "pc") 8443 else 9443), pin)
        }
    }
}

class MainActivity : ComponentActivity() {
    var scannedQr by mutableStateOf("")
        private set
    private val scanner = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scannedQr = it }
    }
    fun scanPairingQr() {
        scanner.launch(ScanOptions().setPrompt("Escanea el QR del panel Windows o del ESP32")
            .setBeepEnabled(false).setOrientationLocked(false))
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF73C4FF),
                background = Color(0xFF0B1220),
                surface = Color(0xFF17263A))) {
                RemoteApp(this)
            }
        }
    }
}

@Composable
fun RemoteApp(activity: MainActivity) {
    val preferences = remember { activity.getSharedPreferences("rcp_clients", 0) }
    val scope = rememberCoroutineScope()
    val devices = remember {
        mutableStateListOf<DeviceRecord>().apply {
            try {
                val data = JSONArray(preferences.getString("devices", "[]") ?: "[]")
                for (i in 0 until data.length()) add(DeviceRecord.fromJson(data.getJSONObject(i)))
            } catch (_: Exception) { }
        }
    }
    fun saveDevices() {
        val arr = JSONArray()
        devices.forEach { arr.put(it.toJson()) }
        preferences.edit().putString("devices", arr.toString()).apply()
    }

    var selected by remember { mutableStateOf<DeviceRecord?>(null) }
    var pairText by remember { mutableStateOf("") }
    var wanIp by remember { mutableStateOf("") }
    var useInternet by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Sin conexión") }
    var working by remember { mutableStateOf(false) }
    var remoteShown by remember { mutableStateOf(false) }
    var socket by remember { mutableStateOf<WebSocket?>(null) }
    var frame by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var fpsLimit by remember { mutableIntStateOf(preferences.getInt("fps", 60)) }
    var quality by remember { mutableIntStateOf(preferences.getInt("jpeg", 62)) }
    var hostFps by remember { mutableIntStateOf(0) }
    var observedFps by remember { mutableIntStateOf(0) }
    var elapsedFps by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var frameCount by remember { mutableIntStateOf(0) }
    var rttMs by remember { mutableIntStateOf(-1) }
    var monitorCount by remember { mutableIntStateOf(1) }
    var selectedMonitor by remember { mutableIntStateOf(0) }
    var videoCodec by remember { mutableStateOf("jpeg") }
    var audioEnabled by remember { mutableStateOf(false) }
    var audioVolume by remember { mutableFloatStateOf(1f) }
    var lowImpact by remember { mutableStateOf(true) }
    var confirmShutdown by remember { mutableStateOf(false) }
    var confirmPower by remember { mutableStateOf(false) }
    val h264Decoder = remember { HardwareH264Decoder() }
    val audioRenderer = remember { PcmAudioRenderer() }

    DisposableEffect(Unit) {
        onDispose {
            socket?.close(1000, "app closed")
            h264Decoder.close()
            audioRenderer.close()
        }
    }
    LaunchedEffect(activity.scannedQr) {
        if (activity.scannedQr.isNotBlank()) pairText = activity.scannedQr
    }

    fun countFrame() {
        frameCount += 1
        val now = SystemClock.elapsedRealtime()
        if (now - elapsedFps >= 1000L) {
            observedFps = (frameCount * 1000L / (now - elapsedFps).coerceAtLeast(1L)).toInt()
            elapsedFps = now
            frameCount = 0
        }
    }

    fun disconnect() {
        socket?.send(JSONObject().put("type", "pc-key-release-all").toString())
        socket?.send(JSONObject().put("type", "gamepad-neutral").toString())
        socket?.close(1000, "user disconnect")
        socket = null
        remoteShown = false
        frame = null
        h264Decoder.reset()
        audioRenderer.reset()
        videoCodec = "jpeg"
        monitorCount = 1
        selectedMonitor = 0
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        status = "Desconectado"
    }

    fun sendStreamConfiguration() {
        socket?.send(JSONObject().put("type", "stream-config")
            .put("codec", "h264-hw").put("fps", fpsLimit)
            .put("quality", quality).put("bitrateKbps", 6000)
            .put("lowImpact", lowImpact).toString())
    }

    if (confirmPower) {
        AlertDialog(onDismissRequest = { confirmPower = false },
            title = { Text("Accionar botón físico") },
            text = { Text("El ESP32 accionará el botón de encendido del PC. Asegúrate de que el mecanismo está calibrado.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmPower = false
                    val d = selected ?: return@TextButton
                    scope.launch {
                        try {
                            val reply = withContext(Dispatchers.IO) { RemoteApi(d, useInternet).pressPower() }
                            status = "ESP32: $reply"
                        } catch (e: Exception) { status = "ESP32: ${e.message}" }
                    }
                }) { Text("Accionar") }
            }, dismissButton = { TextButton(onClick = { confirmPower = false }) { Text("Cancelar") } })
    }
    if (confirmShutdown) {
        AlertDialog(onDismissRequest = { confirmShutdown = false },
            title = { Text("Apagar Windows") },
            text = { Text("Windows iniciará el apagado normal dentro de 30 segundos.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmShutdown = false
                    val d = selected ?: return@TextButton
                    scope.launch {
                        try { status = withContext(Dispatchers.IO) { RemoteApi(d, useInternet).shutdown() } }
                        catch (e: Exception) { status = "Error: ${e.message}" }
                    }
                }) { Text("Solicitar apagado") }
            }, dismissButton = { TextButton(onClick = { confirmShutdown = false }) { Text("Cancelar") } })
    }

    if (remoteShown && selected?.kind == "pc") {
        RemoteSessionScreen(
            activity = activity, frame = frame, socket = socket, connectionStatus = status,
            observedFps = observedFps, hostFps = hostFps, rttMs = rttMs,
            fpsLimit = fpsLimit, streamQuality = quality,
            monitorCount = monitorCount, selectedMonitor = selectedMonitor,
            onMonitorSelected = { index ->
                if (index in 0 until monitorCount) {
                    selectedMonitor = index
                    frame = null
                    h264Decoder.restart()
                    socket?.send(JSONObject().put("type", "monitor").put("index", index).toString())
                }
            },
            videoCodec = videoCodec, h264Decoder = h264Decoder,
            audioEnabled = audioEnabled, audioVolume = audioVolume,
            lowImpact = lowImpact,
            onLowImpactChanged = { lowImpact = it; sendStreamConfiguration() },
            onAudioEnabled = { on ->
                audioEnabled = on
                audioRenderer.setEnabled(on)
                socket?.send(JSONObject().put("type", "audio-config").put("enabled", on).toString())
            },
            onAudioVolume = { volume -> audioVolume = volume; audioRenderer.setVolume(volume) },
            onFpsSelected = { count ->
                fpsLimit = count
                preferences.edit().putInt("fps", count).apply()
                sendStreamConfiguration()
            },
            onQualitySelected = { value ->
                quality = value
                preferences.edit().putInt("jpeg", value).apply()
                sendStreamConfiguration()
            }, onDisconnect = { disconnect() }
        )
        return
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF0B1220)).verticalScroll(rememberScrollState())
        .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ConnectionHomeHeader()
        Text("Vinculación segura", style = MaterialTheme.typography.titleMedium)
        Text("Conecta PC y celular a la misma Wi-Fi para emparejar. Pulsa 'Generar QR' en el panel Windows; los QR caducan a los 3 minutos.",
            style = MaterialTheme.typography.bodySmall)
        Button(onClick = { activity.scanPairingQr() }, enabled = !working) { Text("Escanear QR") }
        OutlinedTextField(pairText, { pairText = it.take(4096) },
            label = { Text("JSON de emparejamiento (QR o copia manual)") },
            modifier = Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
        Button(onClick = {
            working = true
            scope.launch {
                try {
                    val qr = JSONObject(pairText)
                    val record = DeviceRecord.fromQr(qr, UUID.randomUUID().toString())
                    require(record.lanHost.matches(Regex("[0-9.]+"))) { "Usa IPv4 LAN para el primer emparejamiento" }
                    withContext(Dispatchers.IO) { RemoteApi(record, false).pair(qr.getString("token")) }
                    devices.removeAll { it.deviceId == record.deviceId }
                    devices.add(record)
                    saveDevices()
                    selected = record
                    wanIp = record.wanHost
                    pairText = ""
                    status = "Vinculado: ${record.deviceId}"
                } catch (e: Exception) { status = "No se pudo vincular: ${e.message}" }
                finally { working = false }
            }
        }, enabled = !working && pairText.isNotBlank()) { Text("Vincular dispositivo") }

        HorizontalDivider()
        Text("Dispositivos autorizados", style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) Text("Todavía no hay dispositivos vinculados.")
        devices.forEach { device ->
            val checked = selected?.deviceId == device.deviceId
            Surface(onClick = {
                selected = device
                wanIp = device.wanHost
            }, shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                color = if (checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Text((if (device.kind == "pc") "PC Windows" else "ESP32") + " · ${device.deviceId}")
                    Text("IP LAN ${device.lanHost}:${device.lanPort}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (selected != null) {
            val chosen = selected!!
            HorizontalDivider()
            Text("Conexión: " + if (useInternet) "Internet (datos móviles)" else "LAN (misma Wi-Fi)",
                style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Usar Internet (WAN)", modifier = Modifier.weight(1f))
                Switch(checked = useInternet, onCheckedChange = { useInternet = it })
            }
            OutlinedTextField(wanIp, { wanIp = it.take(255) },
                label = { Text("IPv4 pública del router") }, modifier = Modifier.fillMaxWidth(),
                enabled = useInternet, singleLine = true)
            Button(onClick = {
                val index = devices.indexOfFirst { it.deviceId == chosen.deviceId }
                if (index >= 0) {
                    val updated = chosen.copy(wanHost = wanIp.trim())
                    devices[index] = updated
                    selected = updated
                    saveDevices()
                    status = "Dirección WAN guardada"
                }
            }, enabled = useInternet) { Text("Guardar IPv4 pública") }
            val actual = selected ?: chosen
            Button(onClick = {
                working = true
                scope.launch {
                    try {
                        status = withContext(Dispatchers.IO) { RemoteApi(actual, useInternet).status() }
                    } catch (e: Exception) { status = "Error de estado: ${e.message}" }
                    finally { working = false }
                }
            }, enabled = !working) { Text("Consultar estado") }
            if (chosen.kind == "pc") {
                Button(onClick = {
                    remoteShown = true
                    working = true
                    scope.launch {
                        try {
                            val api = RemoteApi(actual, useInternet)
                            socket = withContext(Dispatchers.IO) {
                                api.openDesktop(
                                    onFrame = { picture -> activity.runOnUiThread {
                                        frame = picture
                                        countFrame()
                                    } },
                                    onStatus = { message -> activity.runOnUiThread { status = message } },
                                    initialFps = fpsLimit, initialQuality = quality,
                                    initialLowImpact = lowImpact, initialAudioEnabled = audioEnabled,
                                    onH264 = { packet ->
                                        h264Decoder.pushAccessUnit(packet)
                                        activity.runOnUiThread { countFrame() }
                                    },
                                    onPcm = { packet -> audioRenderer.push(packet) },
                                    onTelemetry = { json -> activity.runOnUiThread {
                                        when (json.optString("type")) {
                                            "video-format" -> {
                                                videoCodec = json.optString("codec", "jpeg")
                                                if (videoCodec == "h264") h264Decoder.reset()
                                            }
                                            "video-fallback" -> {
                                                videoCodec = "jpeg"; h264Decoder.reset(); frame = null
                                            }
                                            "monitor-list" -> {
                                                monitorCount = json.optInt("count", 1).coerceIn(0, 3)
                                                val next = json.optInt("selected", 0)
                                                selectedMonitor = next.coerceIn(0, (monitorCount - 1).coerceAtLeast(0))
                                            }
                                            "telemetry" -> hostFps = json.optInt("sentFps", 0)
                                            "audio-format" -> audioRenderer.configure(json.optInt("sampleRate", 48000), json.optInt("channels", 2))
                                            "pong" -> rttMs = (SystemClock.elapsedRealtime() - json.optLong("id", SystemClock.elapsedRealtime())).toInt().coerceAtLeast(0)
                                        }
                                    } }
                                )
                            }
                        } catch (e: Exception) {
                            status = "No se pudo abrir el escritorio: ${e.message}"
                            remoteShown = false
                        } finally { working = false }
                    }
                }, enabled = !working) { Text("Conectar al PC") }
                OutlinedButton(onClick = { confirmShutdown = true }) { Text("Apagar Windows (30 s)") }
            } else {
                Button(onClick = { confirmPower = true }) { Text("Pulsar botón físico del PC") }
            }
        }
        Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    }
}
