package cl.controlremoto.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import okhttp3.WebSocket
import org.json.JSONObject

/**
 * Standard PC keyboard. All keys are available, including a number pad and
 * F1..F24. The keyboard can scroll horizontally on narrow displays; this is
 * preferable to tiny, untappable keys. Modifier keys latch for combinations.
 * The PC host receives virtual key-down and key-up, not text substitutions.
 */
private data class PcKey(val label: String, val id: String = label, val width: Int = 45,
                         val modifier: Boolean = false)
private fun k(label: String, id: String = label, width: Int = 45) = PcKey(label, id, width)
private fun mod(label: String, id: String, width: Int = 65) = PcKey(label, id, width, true)

private val mainRows = listOf(
    listOf(k("Esc", "ESC", 57)) + (1..12).map { k("F$it") } +
        listOf(k("Impr", "PRTSC", 54), k("BloqDes", "SCROLLLOCK", 62), k("Pausa", "PAUSE", 56)),
    listOf(k("`", "GRAVE")) + (1..9).map { k("$it") } + listOf(k("0"), k("-", "MINUS"),
        k("=", "EQUAL"), k("Retroceso", "BACKSPACE", 108)),
    listOf(k("Tab", "TAB", 72)) + "QWERTYUIOP".map { k("$it") } +
        listOf(k("[", "LBRACKET"), k("]", "RBRACKET"), k("\\", "BACKSLASH", 76)),
    listOf(k("BloqMay", "CAPSLOCK", 92)) + "ASDFGHJKL".map { k("$it") } +
        listOf(k(";", "SEMICOLON"), k("'", "APOSTROPHE"), k("Intro", "ENTER", 102)),
    listOf(mod("Mayús", "LSHIFT", 102), k("<>", "ISO102")) + "ZXCVBNM".map { k("$it") } +
        listOf(k(",", "COMMA"), k(".", "PERIOD"), k("/", "SLASH"), mod("Mayús D", "RSHIFT", 114)),
    listOf(mod("Ctrl", "LCTRL"), mod("Win", "LWIN", 56), mod("Alt", "LALT", 55),
        k("Espacio", "SPACE", 290), mod("AltGr", "RALT", 63), mod("Win D", "RWIN", 60),
        k("Menú", "APPS", 58), mod("Ctrl D", "RCTRL", 62))
)
private val navRows = listOf(
    listOf(k("Insert", "INS", 68), k("Inicio", "HOME", 68), k("RePág", "PGUP", 68)),
    listOf(k("Supr", "DEL", 68), k("Fin", "END", 68), k("AvPág", "PGDN", 68)),
    listOf(k("↑", "UP", 68)),
    listOf(k("←", "LEFT", 68), k("↓", "DOWN", 68), k("→", "RIGHT", 68))
)
private val numberRows = listOf(
    listOf(k("NumLock", "NUMLOCK", 82), k("/", "NUMDIV"), k("*", "NUMMUL"), k("-", "NUMSUB")),
    listOf(k("7", "NUM7"), k("8", "NUM8"), k("9", "NUM9"), k("+", "NUMADD")),
    listOf(k("4", "NUM4"), k("5", "NUM5"), k("6", "NUM6"), k("Intro", "NUMENTER", 70)),
    listOf(k("1", "NUM1"), k("2", "NUM2"), k("3", "NUM3"), k("0", "NUM0"), k(".", "NUMDOT"))
)
private val extendedRows = listOf(
    (13..24).map { k("F$it", width = 46) },
    listOf(mod("Ctrl D", "RCTRL", 80), mod("Alt D", "RALT", 80), mod("Mayús D", "RSHIFT", 80),
        mod("Win D", "RWIN", 80), k("Opciones", "APPS", 88)),
    listOf(k("Vol-", "VOLUME_DOWN", 70), k("Vol+", "VOLUME_UP", 70),
        k("Silenciar", "VOLUME_MUTE", 86), k("Play/Pausa", "MEDIA_PLAY_PAUSE", 90),
        k("Anterior", "MEDIA_PREV", 78), k("Siguiente", "MEDIA_NEXT", 78))
)

private fun dispatchKey(socket: WebSocket?, key: String, down: Boolean) {
    socket?.send(JSONObject().put("type", "pc-key").put("key", key)
        .put("down", down).toString())
}

/** Explicit release used when changing mode, closing keyboard or disconnecting. */
fun releasePcKeyboard(socket: WebSocket?) {
    socket?.send(JSONObject().put("type", "pc-key-release-all").toString())
}

@Composable
fun PcKeyboardPanel(socket: WebSocket?, onClose: () -> Unit, onTextInput: () -> Unit,
                    modifier: Modifier = Modifier) {
    var tab by remember { mutableIntStateOf(0) }
    val latched = remember(socket) { mutableStateMapOf<String, Boolean>() }
    DisposableEffect(socket) {
        onDispose { releasePcKeyboard(socket) }
    }
    val rows = when (tab) { 1 -> navRows; 2 -> numberRows; 3 -> extendedRows; else -> mainRows }
    Column(modifier.background(Color(0xED13263B)).padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("TECLADO DE PC", color = Color(0xFFAFCDED), fontSize = 12.sp,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = onTextInput) { Text("Escribir texto", fontSize = 11.sp) }
            TextButton(onClick = {
                latched.keys.toList().forEach { id -> if (latched[id] == true) dispatchKey(socket, id, false) }
                latched.clear(); releasePcKeyboard(socket); onClose()
            }) { Text("Cerrar", fontSize = 11.sp) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("PC", "Navegación", "Numérico", "F13–F24").forEachIndexed { index, text ->
                FilterChip(selected = tab == index, onClick = { tab = index },
                    label = { Text(text, fontSize = 10.sp) }, modifier = Modifier.height(36.dp))
            }
        }
        Text("Desliza lateralmente para ver las teclas. Ctrl, Alt, Win y Mayús quedan presionadas al tocarlas; vuelve a tocar para soltarlas.",
            color = Color(0xFFB5C7DA), fontSize = 10.sp, maxLines = 2)
        val scrollHorizontal = rememberScrollState()
        Row(Modifier.fillMaxWidth().horizontalScroll(scrollHorizontal)) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        row.forEach { key ->
                            PcKeyCap(key, socket, latched)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PcKeyCap(spec: PcKey, socket: WebSocket?, latched: MutableMap<String, Boolean>) {
    val active = latched[spec.id] == true
    val color = if (active) Color(0xFF77C9FF) else Color(0xFF304C69)
    Surface(
        modifier = Modifier.width(spec.width.dp).height(40.dp)
            .pointerInput(socket, spec.id, spec.modifier) {
                detectTapGestures(onPress = {
                    if (spec.modifier) {
                        val next = latched[spec.id] != true
                        latched[spec.id] = next
                        dispatchKey(socket, spec.id, next)
                        tryAwaitRelease()
                    } else {
                        dispatchKey(socket, spec.id, true)
                        try { tryAwaitRelease() } finally { dispatchKey(socket, spec.id, false) }
                    }
                })
            },
        shape = RoundedCornerShape(7.dp), color = color
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 2.dp)) {
            Text(spec.label, color = if (active) Color(0xFF062238) else Color.White,
                fontSize = 11.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
