package cl.controlremoto.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.WebSocket
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val Bg = Color(0xFF0B1220)
private val Panel = Color(0xFF17263A)
private val PanelOverlay = Color(0xCC132238)
private val Accent = Color(0xFF73C4FF)
private val Soft = Color(0xFFAFC5DD)
private enum class ControlMode { KBM, MANDO }
// CURSOR: swipe moves pointer without holding buttons.
// DRAG: swipe holds the left mouse button until release.
// RELATIVE: swipe sends deltas, suitable for some camera-controlled games.
private enum class PointerMode { CURSOR, DRAG, RELATIVE }

@Composable
fun ConnectionHomeHeader() {
    Surface(shape = RoundedCornerShape(22.dp), color = Color(0xFF16263A), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("CONTROL REMOTO", color = Accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text("Mis dispositivos", color = Color.White, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text("Administra tu PC y el encendido independiente del ESP32.", color = Soft,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun RemoteSessionScreen(
    activity: MainActivity,
    frame: Bitmap?,
    socket: WebSocket?,
    connectionStatus: String,
    observedFps: Int,
    hostFps: Int,
    rttMs: Int,
    fpsLimit: Int,
    streamQuality: Int,
    monitorCount: Int,
    selectedMonitor: Int,
    onMonitorSelected: (Int) -> Unit,
    videoCodec: String,
    h264Decoder: HardwareH264Decoder,
    audioEnabled: Boolean,
    audioVolume: Float,
    lowImpact: Boolean,
    onLowImpactChanged: (Boolean) -> Unit,
    onAudioEnabled: (Boolean) -> Unit,
    onAudioVolume: (Float) -> Unit,
    onFpsSelected: (Int) -> Unit,
    onQualitySelected: (Int) -> Unit,
    onDisconnect: () -> Unit
) {
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var mode by remember { mutableStateOf(ControlMode.KBM) }
    var fillImage by remember { mutableStateOf(false) }
    var pointerMode by remember { mutableStateOf(PointerMode.CURSOR) }
    var mouseSensitivity by remember { mutableFloatStateOf(1.0f) }
    val changePointerMode: (PointerMode) -> Unit = { next ->
        if (next != pointerMode) {
            sendMouseButton(socket, "left", false)
            sendMouseButton(socket, "right", false)
        }
        pointerMode = next
    }
    // Covers switching orientation, mode and disconnecting during a held drag.
    DisposableEffect(socket, pointerMode) {
        onDispose {
            sendMouseButton(socket, "left", false)
            sendMouseButton(socket, "right", false)
        }
    }
    var settingsOpen by remember { mutableStateOf(false) }
    var keyboardOpen by remember { mutableStateOf(false) }
    var textInputOpen by remember { mutableStateOf(false) }
    var keyboardText by remember { mutableStateOf("") }
    BackHandler {
        if (keyboardOpen) { releasePcKeyboard(socket); keyboardOpen = false }
        else onDisconnect()
    }

    DisposableEffect(activity, landscape) {
        val window = activity.window
        val originalFlags = window.decorView.systemUiVisibility
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (landscape) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = originalFlags or View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        onDispose {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = originalFlags
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(socket) {
        while (isActive && socket != null) {
            socket.send(JSONObject().put("type", "ping").put("id", SystemClock.elapsedRealtime()).toString())
            delay(2500L)
        }
    }

    if (textInputOpen) {
        AlertDialog(
            onDismissRequest = { textInputOpen = false },
            title = { Text("Teclado del PC") },
            text = { OutlinedTextField(value = keyboardText, onValueChange = { keyboardText = it.take(256) },
                label = { Text("Texto (máx. 256)") }, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = {
                sendText(socket, keyboardText)
                keyboardText = ""
                textInputOpen = false
            }) { Text("Enviar") } },
            dismissButton = { TextButton(onClick = { textInputOpen = false }) { Text("Cerrar") } }
        )
    }
    if (settingsOpen) {
        AlertDialog(
            onDismissRequest = { settingsOpen = false },
            title = { Text("Transmisión de pantalla") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Priorizar rendimiento del PC")
                            Text("Limita el vídeo a 60 FPS y 4,5 Mbps", fontSize = 10.sp)
                        }
                        Switch(checked = lowImpact, onCheckedChange = onLowImpactChanged)
                    }
                    Text("Bloquear FPS (máximo solicitado)")
                    FpsSelector(fpsLimit, onFpsSelected)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Sonido del PC", modifier = Modifier.weight(1f))
                        Switch(checked = audioEnabled, onCheckedChange = onAudioEnabled)
                    }
                    if (audioEnabled) {
                        Text("Volumen móvil: ${(audioVolume * 100).toInt()} %")
                        Slider(value = audioVolume, onValueChange = onAudioVolume, valueRange = 0f..1f)
                    }
                    Text("Sensibilidad del mouse relativo: ${(mouseSensitivity * 100).roundToInt()} %")
                    Slider(value = mouseSensitivity, onValueChange = { mouseSensitivity = it },
                        valueRange = 0.35f..3f)
                    Text("Movimiento libre: deslizar sin pulsar. Arrastrar: mantiene clic izquierdo mientras desliza. Juego: envia desplazamientos relativos.",
                        style = MaterialTheme.typography.bodySmall)
                    Text("Calidad JPEG de reserva: $streamQuality %")
                    Slider(value = streamQuality.toFloat(), onValueChange = { onQualitySelected(it.toInt()) },
                        valueRange = 35f..85f, steps = 9)
                    Text("H.264 por GPU si esta disponible; reserva JPEG limitada a 30 FPS. Los FPS altos dependen de red, GPU y pantalla.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { TextButton(onClick = { settingsOpen = false }) { Text("Cerrar") } }
        )
    }

    val sendMode: (ControlMode) -> Unit = { next ->
        if (mode != next) {
            socket?.send(JSONObject().put("type", "gamepad-neutral").toString())
            releasePcKeyboard(socket)
            sendMouseButton(socket, "left", false)
            sendMouseButton(socket, "right", false)
            keyboardOpen = false
        }
        mode = next
    }
    if (landscape) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            DesktopSurface(frame, socket, fillImage, videoCodec, h264Decoder,
                mode, pointerMode, mouseSensitivity, Modifier.fillMaxSize())
            Row(Modifier.align(Alignment.TopStart).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                LabelPill("${observedFps} FPS móvil · ${hostFps} FPS PC", maxLines = 1)
                if (rttMs >= 0) LabelPill("${rttMs} ms", maxLines = 1)
            }
            ModeSelector(mode, sendMode, Modifier.align(Alignment.TopCenter).padding(top = 9.dp))
            MonitorSelector(monitorCount, selectedMonitor, onMonitorSelected,
                Modifier.align(Alignment.TopCenter).padding(top = 60.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OverlayButton(if (fillImage) "Ajustar" else "Llenar") { fillImage = !fillImage }
                OverlayButton(if (audioEnabled) "Audio ON" else "Audio OFF") { onAudioEnabled(!audioEnabled) }
                OverlayButton("${if (fpsLimit == 0) "Auto" else "$fpsLimit"} FPS") { settingsOpen = true }
                OverlayButton("Vertical") { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                OverlayButton("Salir") { onDisconnect() }
            }
            if (mode == ControlMode.KBM) {
                if (keyboardOpen) {
                    PcKeyboardPanel(socket, onClose = { keyboardOpen = false },
                        onTextInput = { textInputOpen = true },
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
                } else {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        PointerModeSelector(pointerMode, changePointerMode, compact = true)
                        KbmLandscapeOverlay(socket,
                            onKeyboard = { keyboardOpen = true },
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            } else {
                GamepadLandscapeOverlay(socket, Modifier.fillMaxSize())
            }
        }
    } else {
        Column(Modifier.fillMaxSize().background(Bg)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("ESCRITORIO REMOTO", color = Accent, fontWeight = FontWeight.Bold,
                    fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text("${observedFps} / ${if (fpsLimit == 0) "Auto" else fpsLimit} FPS", color = Soft, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }) {
                    Text("Girar")
                }
                TextButton(onClick = onDisconnect) { Text("Salir") }
            }
            DesktopSurface(frame, socket, fillImage, videoCodec, h264Decoder,
                mode, pointerMode, mouseSensitivity, Modifier.fillMaxWidth().weight(0.53f))
            Box(Modifier.fillMaxWidth().height(62.dp), contentAlignment = Alignment.Center) {
                ModeSelector(mode, sendMode, Modifier)
            }
            Column(Modifier.fillMaxWidth().weight(0.47f).verticalScroll(rememberScrollState())
                .background(Panel).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                MonitorSelector(monitorCount, selectedMonitor, onMonitorSelected,
                    Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(connectionStatus, color = Soft, fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    TextButton(onClick = { settingsOpen = true }) { Text("FPS y calidad") }
                    TextButton(onClick = { fillImage = !fillImage }) {
                        Text(if (fillImage) "Ajustar" else "Llenar")
                    }
                }
                if (mode == ControlMode.KBM) {
                    if (keyboardOpen) {
                        PcKeyboardPanel(socket, onClose = { keyboardOpen = false },
                            onTextInput = { textInputOpen = true })
                    } else {
                        PointerModeSelector(pointerMode, changePointerMode, compact = false)
                        KbmPortraitPanel(socket, onKeyboard = { keyboardOpen = true })
                    }
                } else {
                    GamepadPortraitPanel(socket)
                }
            }
        }
    }
}

@Composable
private fun FpsSelector(fpsLimit: Int, onSelect: (Int) -> Unit) {
    val options = listOf(0, 30, 60, 90, 120, 144)
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        options.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { fps ->
                    FilterChip(selected = fpsLimit == fps, onClick = { onSelect(fps) },
                        label = { Text(if (fps == 0) "Auto" else "$fps", fontSize = 12.sp) })
                }
            }
        }
    }
}

@Composable
private fun ModeSelector(mode: ControlMode, onSelected: (ControlMode) -> Unit, modifier: Modifier) {
    Surface(modifier = modifier, color = Color(0xF01A304A), shape = RoundedCornerShape(50.dp),
        shadowElevation = 8.dp, tonalElevation = 4.dp) {
        Row(Modifier.padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf(ControlMode.KBM, ControlMode.MANDO).forEach { item ->
                val active = mode == item
                Surface(onClick = { onSelected(item) }, color = if (active) Accent else Color.Transparent,
                    shape = RoundedCornerShape(30.dp)) {
                    Text(if (item == ControlMode.KBM) "KBM" else "Mando",
                        color = if (active) Color(0xFF092036) else Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 21.dp, vertical = 10.dp))
                }
            }
        }
    }
}

@Composable
private fun MonitorSelector(
    count: Int, selected: Int, onSelected: (Int) -> Unit, modifier: Modifier = Modifier
) {
    // Display ordinal follows Windows Screen.AllScreens, which may not match
    // physical positioning or the numbers used in Windows Display Settings.
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(PanelOverlay)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text("Pantalla", color = Color.White, fontSize = 11.sp)
        Spacer(Modifier.width(8.dp))
        repeat(3) { screen ->
            val connected = screen < count
            val active = connected && selected == screen
            Surface(onClick = { if (connected) onSelected(screen) }, enabled = connected,
                color = if (active) Accent else if (connected) Color(0xFF30465C) else Color(0xFF1E2938),
                shape = RoundedCornerShape(9.dp), modifier = Modifier.padding(horizontal = 2.dp)) {
                Text("${screen + 1}", color = if (active) Bg else if (connected) Color.White else Soft.copy(alpha = 0.5f),
                    fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp))
            }
        }
        Spacer(Modifier.width(6.dp))
        Text("${count.coerceIn(0, 3)}/3", color = Soft, fontSize = 10.sp)
    }
}

@Composable
private fun PointerModeSelector(
    selected: PointerMode, onSelected: (PointerMode) -> Unit, compact: Boolean
) {
    Row(Modifier.fillMaxWidth().background(Color(0xB917263A))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Mouse", color = Soft, fontSize = 11.sp)
        listOf(PointerMode.CURSOR to "Libre", PointerMode.DRAG to "Arrastrar",
            PointerMode.RELATIVE to "Juego (rel.)").forEach { (mode, label) ->
            Surface(onClick = { onSelected(mode) }, shape = RoundedCornerShape(9.dp),
                color = if (selected == mode) Accent else Color(0xFF294158)) {
                Text(label, fontSize = if (compact) 10.sp else 11.sp,
                    color = if (selected == mode) Bg else Color.White,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp))
            }
        }
        if (!compact) Text("Deslizar sin clic / arrastrar / cámara", color = Soft, fontSize = 10.sp)
    }
}

@Composable
private fun LabelPill(text: String, maxLines: Int = 1) {
    Surface(color = PanelOverlay, shape = RoundedCornerShape(12.dp)) {
        Text(text, modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
            color = Color.White, fontSize = 11.sp, maxLines = maxLines)
    }
}

@Composable
private fun OverlayButton(label: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(10.dp), color = PanelOverlay) {
        Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp))
    }
}

@Composable
private fun DesktopSurface(
    frame: Bitmap?, socket: WebSocket?, fill: Boolean, videoCodec: String,
    h264Decoder: HardwareH264Decoder, controlMode: ControlMode,
    pointerMode: PointerMode, sensitivity: Float, modifier: Modifier
) {
    var dimensions by remember { mutableStateOf(IntSize.Zero) }
    val imageWidth = if (videoCodec == "h264") h264Decoder.videoWidth else frame?.width ?: 0
    val imageHeight = if (videoCodec == "h264") h264Decoder.videoHeight else frame?.height ?: 0
    Box(modifier.background(Color.Black).onSizeChanged { dimensions = it },
        contentAlignment = Alignment.Center) {
        if (videoCodec == "h264") {
            AndroidView(factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) { h264Decoder.attachSurface(holder.surface) }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { }
                        override fun surfaceDestroyed(holder: SurfaceHolder) { h264Decoder.detachSurface(holder.surface) }
                    })
                }
            }, modifier = Modifier.fillMaxSize())
        } else if (frame == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Conectando al escritorio...", color = Color.White, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("Esperando fotogramas del PC", color = Soft, fontSize = 12.sp)
            }
        } else {
            Image(frame.asImageBitmap(), contentDescription = "Pantalla remota del PC",
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize())
        }
        // Only KBM consumes touches on the image. Mando buttons must not accidentally
        // move/click the PC pointer beneath their translucent overlays.
        if (controlMode == ControlMode.KBM && imageWidth > 0 && imageHeight > 0) {
            Box(Modifier.fillMaxSize()
                .pointerInput(socket, fill, imageWidth, imageHeight, pointerMode, dimensions) {
                    detectTapGestures(
                        onTap = { point ->
                            if (pointerMode == PointerMode.RELATIVE) {
                                sendSimpleClick(socket, "left")
                            } else {
                                mapDecodedPointer(point, dimensions, imageWidth, imageHeight, fill)?.let { (x, y) ->
                                    sendPositionedClick(socket, "left", x, y)
                                }
                            }
                        },
                        onLongPress = { point ->
                            if (pointerMode == PointerMode.RELATIVE) {
                                sendSimpleClick(socket, "right")
                            } else {
                                mapDecodedPointer(point, dimensions, imageWidth, imageHeight, fill)?.let { (x, y) ->
                                    sendPositionedClick(socket, "right", x, y)
                                }
                            }
                        }
                    )
                }
                .pointerInput(socket, fill, imageWidth, imageHeight, pointerMode, sensitivity, dimensions) {
                    var leftHeld = false
                    var residualX = 0f
                    var residualY = 0f
                    var lastRelative = 0L
                    fun sendPendingRelative() {
                        val dx = residualX.roundToInt().coerceIn(-160, 160)
                        val dy = residualY.roundToInt().coerceIn(-160, 160)
                        if (dx != 0 || dy != 0) {
                            sendMouseRelative(socket, dx, dy)
                            residualX -= dx
                            residualY -= dy
                        }
                        lastRelative = SystemClock.uptimeMillis()
                    }
                    detectDragGestures(
                        onDragStart = { start ->
                            residualX = 0f; residualY = 0f; lastRelative = 0L
                            if (pointerMode == PointerMode.DRAG) {
                                mapDecodedPointer(start, dimensions, imageWidth, imageHeight, fill)?.let { (x, y) ->
                                    sendPointerMove(socket, x, y)
                                    sendMouseButton(socket, "left", true)
                                    leftHeld = true
                                }
                            } else if (pointerMode == PointerMode.CURSOR) {
                                mapDecodedPointer(start, dimensions, imageWidth, imageHeight, fill)?.let { (x, y) ->
                                    sendPointerMove(socket, x, y)
                                }
                            }
                        },
                        onDragEnd = {
                            if (pointerMode == PointerMode.RELATIVE) sendPendingRelative()
                            if (leftHeld) sendMouseButton(socket, "left", false)
                            leftHeld = false
                        },
                        onDragCancel = {
                            if (leftHeld) sendMouseButton(socket, "left", false)
                            leftHeld = false
                            residualX = 0f; residualY = 0f
                        }
                    ) { change, dragAmount ->
                        when (pointerMode) {
                            PointerMode.CURSOR, PointerMode.DRAG -> {
                                if (pointerMode != PointerMode.DRAG || leftHeld) {
                                    mapDecodedPointer(change.position, dimensions, imageWidth, imageHeight, fill)
                                        ?.let { (x, y) -> sendPointerMove(socket, x, y) }
                                }
                            }
                            PointerMode.RELATIVE -> {
                                residualX += dragAmount.x * sensitivity
                                residualY += dragAmount.y * sensitivity
                                val now = SystemClock.uptimeMillis()
                                // Coalesce touch events to <= ~100 mouse messages/sec.
                                if (lastRelative == 0L || now - lastRelative >= 10L) sendPendingRelative()
                            }
                        }
                        change.consume()
                    }
                }
            )
        }
    }
}

private fun sendPointerMove(socket: WebSocket?, x: Float, y: Float) {
    socket?.send(JSONObject().put("type", "move").put("x", x).put("y", y).toString())
}
private fun sendPositionedClick(socket: WebSocket?, button: String, x: Float, y: Float) {
    socket?.send(JSONObject().put("type", "click").put("button", button)
        .put("x", x).put("y", y).toString())
}
private fun sendSimpleClick(socket: WebSocket?, button: String) {
    sendMouseButton(socket, button, true)
    sendMouseButton(socket, button, false)
}
private fun sendMouseRelative(socket: WebSocket?, dx: Int, dy: Int) {
    if (dx == 0 && dy == 0) return
    socket?.send(JSONObject().put("type", "mouse-relative")
        .put("dx", dx).put("dy", dy).toString())
}

private fun mapDecodedPointer(point: Offset, view: IntSize, imageWidth: Int, imageHeight: Int,
                              fill: Boolean): Pair<Float, Float>? {
    if (view.width <= 0 || view.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return null
    val w = view.width.toFloat(); val h = view.height.toFloat()
    val scale = if (fill) max(w / imageWidth, h / imageHeight) else min(w / imageWidth, h / imageHeight)
    val drawnWidth = imageWidth * scale; val drawnHeight = imageHeight * scale
    val x = (point.x - (w - drawnWidth) / 2f) / drawnWidth
    val y = (point.y - (h - drawnHeight) / 2f) / drawnHeight
    if (!fill && (x !in 0f..1f || y !in 0f..1f)) return null
    return x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
}

private fun sendMouseButton(socket: WebSocket?, which: String, down: Boolean) {
    socket?.send(JSONObject().put("type", "button").put("button", which)
        .put("action", if (down) "down" else "up").toString())
}
private fun sendShortcut(socket: WebSocket?, name: String) {
    socket?.send(JSONObject().put("type", "shortcut").put("name", name).toString())
}
private fun sendApp(socket: WebSocket?, name: String) {
    socket?.send(JSONObject().put("type", "app").put("name", name).toString())
}
private fun sendScroll(socket: WebSocket?, delta: Int) {
    socket?.send(JSONObject().put("type", "scroll").put("delta", delta).toString())
}
private fun sendText(socket: WebSocket?, text: String) {
    if (text.isNotEmpty() && text.length <= 256)
        socket?.send(JSONObject().put("type", "text").put("text", text).toString())
}

@Composable
private fun KbmPortraitPanel(socket: WebSocket?, onKeyboard: () -> Unit) {
    Text("Teclado y mouse del PC", color = Color.White, fontWeight = FontWeight.SemiBold)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        KbmKey("Teclado PC", Modifier.weight(1.25f), onKeyboard)
        MouseHoldKey("Izquierdo", "left", socket, Modifier.weight(1f))
        MouseHoldKey("Derecho", "right", socket, Modifier.weight(1f))
    }
    Text("Mantén Izquierdo o Derecho mientras deslizas para mantener un botón pulsado.",
        color = Soft, fontSize = 10.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        KbmKey("Alt + Tab", Modifier.weight(1f)) { sendShortcut(socket, "alt-tab") }
        KbmKey("Win + D", Modifier.weight(1f)) { sendShortcut(socket, "win-d") }
        KbmKey("Enter", Modifier.weight(1f)) { sendShortcut(socket, "enter") }
        KbmKey("Borrar", Modifier.weight(1f)) { sendShortcut(socket, "backspace") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        KbmKey("Scroll ↑", Modifier.weight(1f)) { sendScroll(socket, 120) }
        KbmKey("Scroll ↓", Modifier.weight(1f)) { sendScroll(socket, -120) }
        KbmKey("Notas", Modifier.weight(1f)) { sendApp(socket, "notepad") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        KbmKey("Explorador", Modifier.weight(1f)) { sendApp(socket, "explorer") }
        KbmKey("Calculadora", Modifier.weight(1f)) { sendApp(socket, "calculator") }
    }
}

@Composable
private fun KbmKey(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(modifier = modifier, onClick = onClick, color = Color(0xFF294158),
        shape = RoundedCornerShape(12.dp)) {
        Box(Modifier.heightIn(min = 42.dp).padding(horizontal = 5.dp, vertical = 7.dp),
            contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun MouseHoldKey(
    label: String, button: String, socket: WebSocket?, modifier: Modifier = Modifier
) {
    var pressed by remember(socket, button) { mutableStateOf(false) }
    DisposableEffect(socket, button) {
        onDispose { sendMouseButton(socket, button, false) }
    }
    Box(modifier.heightIn(min = 40.dp)
        .clip(RoundedCornerShape(10.dp))
        .background(if (pressed) Accent else Color(0xCC294158))
        .pointerInput(socket, button) {
            detectTapGestures(onPress = {
                pressed = true
                sendMouseButton(socket, button, true)
                try { tryAwaitRelease() }
                finally {
                    sendMouseButton(socket, button, false)
                    pressed = false
                }
            })
        }
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (pressed) Bg else Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
private fun KbmLandscapeOverlay(socket: WebSocket?, onKeyboard: () -> Unit, modifier: Modifier) {
    Row(modifier.background(Color(0xA9112238))
        .horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
        OverlayButton("Teclado PC") { onKeyboard() }
        MouseHoldKey("Izquierdo", "left", socket)
        MouseHoldKey("Derecho", "right", socket)
        OverlayButton("Alt+Tab") { sendShortcut(socket, "alt-tab") }
        OverlayButton("Win+D") { sendShortcut(socket, "win-d") }
        OverlayButton("Enter") { sendShortcut(socket, "enter") }
        OverlayButton("Scroll ↑") { sendScroll(socket, 120) }
        OverlayButton("Scroll ↓") { sendScroll(socket, -120) }
    }
}

private fun sendGamepadButton(socket: WebSocket?, control: String, down: Boolean) {
    socket?.send(JSONObject().put("type", "gamepad").put("control", control)
        .put("pressed", down).toString())
}
private fun sendStick(socket: WebSocket?, control: String, x: Float, y: Float) {
    socket?.send(JSONObject().put("type", "gamepad").put("control", control)
        .put("x", x.toDouble()).put("y", y.toDouble()).toString())
}

@Composable
private fun AnalogStick(label: String, socket: WebSocket?, control: String, modifier: Modifier = Modifier) {
    var vector by remember { mutableStateOf(Offset.Zero) }
    var lastSent by remember { mutableLongStateOf(0L) }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(112.dp)
            .pointerInput(socket, control) {
                detectDragGestures(
                    onDragStart = { start ->
                        val radius = size.width / 2f
                        vector = Offset(((start.x - radius) / radius).coerceIn(-1f, 1f),
                            ((start.y - radius) / radius).coerceIn(-1f, 1f))
                        sendStick(socket, control, vector.x, vector.y)
                    },
                    onDragEnd = { vector = Offset.Zero; sendStick(socket, control, 0f, 0f) },
                    onDragCancel = { vector = Offset.Zero; sendStick(socket, control, 0f, 0f) }
                ) { change, _ ->
                    val radius = size.width / 2f
                    val x = ((change.position.x - radius) / radius).coerceIn(-1f, 1f)
                    val y = ((change.position.y - radius) / radius).coerceIn(-1f, 1f)
                    vector = Offset(x, y)
                    val now = SystemClock.uptimeMillis()
                    if (now - lastSent >= 28L) {
                        sendStick(socket, control, x, y)
                        lastSent = now
                    }
                    change.consume()
                }
            }) {
            drawCircle(color = Color(0x66365F80), radius = size.minDimension * 0.49f)
            drawCircle(color = Color(0xAA8FB9D7), radius = size.minDimension * 0.48f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            drawCircle(color = Color(0xCC8ABDDD), radius = size.minDimension * 0.19f,
                center = center + Offset(vector.x * size.minDimension * 0.23f,
                    vector.y * size.minDimension * 0.23f))
        }
        Text(label, color = Soft, fontSize = 10.sp)
    }
}

@Composable
private fun GameButton(text: String, control: String, socket: WebSocket?, modifier: Modifier = Modifier,
                       diameter: Int = 47) {
    Box(modifier.size(diameter.dp).clip(CircleShape).border(1.dp, Color(0xAA7EACCF), CircleShape)
        .background(Color(0xB9365570))
        .pointerInput(socket, control) {
            detectTapGestures(onPress = {
                sendGamepadButton(socket, control, true)
                tryAwaitRelease()
                sendGamepadButton(socket, control, false)
            })
        }, contentAlignment = Alignment.Center) {
        Text(text, fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.sp)
    }
}

@Composable
private fun FaceButtons(socket: WebSocket?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp)) {
        GameButton("Y", "Y", socket, diameter = 40)
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            GameButton("X", "X", socket, diameter = 40)
            GameButton("B", "B", socket, diameter = 40)
        }
        GameButton("A", "A", socket, diameter = 40)
    }
}

@Composable
private fun Dpad(socket: WebSocket?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        GameButton("↑", "DUP", socket, diameter = 35)
        Row(verticalAlignment = Alignment.CenterVertically) {
            GameButton("←", "DLEFT", socket, diameter = 35)
            Spacer(Modifier.size(30.dp))
            GameButton("→", "DRIGHT", socket, diameter = 35)
        }
        GameButton("↓", "DDOWN", socket, diameter = 35)
    }
}

@Composable
private fun GamepadPortraitPanel(socket: WebSocket?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        AnalogStick("Mover", socket, "left-stick")
        FaceButtons(socket)
        AnalogStick("Apuntar", socket, "right-stick")
    }
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically) {
        Dpad(socket)
        Text("Sticks analógicos y cruceta", color = Soft, fontSize = 11.sp)
    }
    Spacer(Modifier.height(5.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        listOf("LB", "LT", "SELECT", "START", "RT", "RB").forEach { button ->
            GameButton(button, button, socket, diameter = 43)
        }
    }
    Text("Mando XInput si el PC dispone de driver compatible; en caso contrario, perfil KBM.",
        color = Soft, fontSize = 10.sp)
}

@Composable
private fun GamepadLandscapeOverlay(socket: WebSocket?, modifier: Modifier) {
    Box(modifier) {
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnalogStick("Movimiento", socket, "left-stick")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameButton("LB", "LB", socket, diameter = 42)
                    GameButton("LT", "LT", socket, diameter = 42)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Dpad(socket)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GameButton("SEL", "SELECT", socket, diameter = 40)
                    GameButton("ST", "START", socket, diameter = 40)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FaceButtons(socket)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GameButton("RT", "RT", socket, diameter = 42)
                    GameButton("RB", "RB", socket, diameter = 42)
                }
            }
            AnalogStick("Vista", socket, "right-stick")
        }
    }
}
