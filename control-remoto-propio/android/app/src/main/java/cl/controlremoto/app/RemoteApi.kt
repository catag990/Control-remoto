package cl.controlremoto.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit

// Stored fields are not secrets. Device keys remain non-exportable in Android Keystore.
data class PairedDevice(
    val kind: String,
    val deviceId: String,
    val lanHost: String,
    val port: Int,
    val publicPort: Int,
    val pin: String,
    val clientId: String,
    val alias: String
)

class DeviceRegistry(context: Context) {
    private val prefs = context.getSharedPreferences("paired_devices_v1", Context.MODE_PRIVATE)
    fun list(): List<PairedDevice> {
        val array = JSONArray(prefs.getString("devices", "[]") ?: "[]")
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            PairedDevice(o.getString("kind"), o.getString("deviceId"),
                o.getString("lanHost"), o.getInt("port"),
                o.optInt("publicPort", if (o.getString("kind") == "esp32") 9443 else o.getInt("port")),
                o.getString("pin"),
                o.getString("clientId"), o.getString("alias"))
        }
    }
    fun save(device: PairedDevice) {
        val updated = list().filterNot { it.deviceId == device.deviceId } + device
        val array = JSONArray()
        updated.forEach { d ->
            array.put(JSONObject().put("kind", d.kind).put("deviceId", d.deviceId)
                .put("lanHost", d.lanHost).put("port", d.port).put("publicPort", d.publicPort).put("pin", d.pin)
                .put("clientId", d.clientId).put("alias", d.alias))
        }
        prefs.edit().putString("devices", array.toString()).apply()
    }
    fun remove(deviceId: String) {
        val array = JSONArray()
        list().filterNot { it.deviceId == deviceId }.forEach { d ->
            array.put(JSONObject().put("kind", d.kind).put("deviceId", d.deviceId)
                .put("lanHost", d.lanHost).put("port", d.port).put("publicPort", d.publicPort).put("pin", d.pin)
                .put("clientId", d.clientId).put("alias", d.alias))
        }
        prefs.edit().putString("devices", array.toString()).apply()
    }
}

class RemoteApi(private val device: PairedDevice, private val destination: String,
                private val destinationPort: Int = device.port) {
    private val client = DeviceCrypto.pinnedClient(device.pin)
    // destination is IPv4 or hostname only, without URL scheme or port.
    private val base = "https://$destination:$destinationPort"

    private fun raw(method: String, path: String, body: ByteArray?, headers: Map<String, String>): String {
        val requestBody = body?.toRequestBody(MEDIA_JSON)
        val builder = Request.Builder().url(base + path)
        headers.forEach { (k, v) -> builder.header(k, v) }
        builder.method(method, requestBody)
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string() ?: ""
            if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}: $text")
            return text
        }
    }

    fun pair(token: String): String {
        val body = JSONObject()
            .put("clientId", device.clientId)
            .put("publicKeyBase64", DeviceCrypto.b64(DeviceCrypto.publicKey(device.alias)))
            .put("token", token).toString().toByteArray(StandardCharsets.UTF_8)
        return raw("POST", "/api/v1/pair", body, emptyMap())
    }

    private fun signedHeaders(method: String, path: String, body: ByteArray): Map<String, String> {
        val challenge = JSONObject(raw("GET", "/api/v1/auth/challenge?clientId=" +
            URLEncoder.encode(device.clientId, "UTF-8"), null, emptyMap()))
        val nonce = challenge.getString("nonce")
        val signed = DeviceCrypto.canonical(device.clientId, method, path, body, nonce)
        return mapOf("X-Client-Id" to device.clientId,
            "X-Nonce" to nonce,
            "X-Signature" to DeviceCrypto.sign(device.alias, signed))
    }

    fun authorized(method: String, path: String, json: JSONObject? = null): JSONObject {
        val body = json?.toString()?.toByteArray(StandardCharsets.UTF_8) ?: ByteArray(0)
        return JSONObject(raw(method, path, if (method == "POST") body else null,
            signedHeaders(method, path, body)))
    }

    fun openDesktop(onFrame: (Bitmap) -> Unit, onStatus: (String) -> Unit): WebSocket {
        val path = "/api/v1/desktop"
        val headers = signedHeaders("GET", path, ByteArray(0))
        val request = Request.Builder().url("wss://$destination:$destinationPort$path")
        headers.forEach { (k, v) -> request.header(k, v) }
        val socketClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        return socketClient.newWebSocket(request.build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { onStatus("Sesion conectada") }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                BitmapFactory.decodeByteArray(data, 0, data.size)?.let(onFrame)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Conexion interrumpida: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { onStatus("Sesion cerrada") }
        })
    }

    companion object {
        private val MEDIA_JSON = "application/json; charset=utf-8".toMediaType()
        fun deviceFromPairing(json: String): Pair<PairedDevice, String> {
            val o = JSONObject(json.trim())
            val kind = o.getString("kind")
            require(kind == "pc" || kind == "esp32") { "Tipo de dispositivo no admitido" }
            val id = o.getString("deviceId")
            val port = o.getInt("localPort")
            val host = o.getString("localHost")
            val pin = o.getString("pin")
            require(id.length in 3..80 && port in 1..65535 && pin.length in 40..64)
            require(host.matches(Regex("[A-Za-z0-9.:-]{3,253}")))
            val clientId = "MOB-" + UUID.randomUUID().toString().replace("-", "").take(18)
            val device = PairedDevice(kind, id, host, port,
                if (kind == "esp32") 9443 else port, pin, clientId, "rp-${id}-${clientId}")
            return device to o.getString("token")
        }
    }
}
