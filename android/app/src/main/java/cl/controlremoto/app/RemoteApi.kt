package cl.controlremoto.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject

/** Public SPKI-pinned HTTPS/WSS RCP1 client. No plaintext HTTP fallback. */
class RemoteApi(private val device: DeviceRecord, internet: Boolean) {
    private val host: String = if (internet) device.wanHost.ifBlank { device.lanHost } else device.lanHost
    private val port: Int = if (internet) device.wanPort else device.lanPort
    private val baseUrl: String = "https://$host:$port"
    private val client: OkHttpClient

    init {
        require(host.matches(Regex("[0-9.]+")) || host.matches(Regex("[A-Za-z0-9.-]+")))
        require(port in 1..65535)
        val pin = device.spkiSha256.uppercase(java.util.Locale.ROOT)
        require(pin.matches(Regex("[0-9A-F]{64}")))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
                if (chain.isNullOrEmpty()) throw java.security.cert.CertificateException("Sin certificado TLS")
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(chain[0].publicKey.encoded)
                    .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                if (!MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII), pin.toByteArray(Charsets.US_ASCII)))
                    throw java.security.cert.CertificateException("La clave TLS del servidor no coincide con el QR")
            }
        }
        val tls = SSLContext.getInstance("TLS")
        tls.init(null, arrayOf<TrustManager>(trust), SecureRandom())
        client = OkHttpClient.Builder().sslSocketFactory(tls.socketFactory, trust)
            // Certificate identity is established by the exact SPKI SHA-256 from physical pairing.
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS).build()
    }

    private fun challenge(): String {
        val id = URLEncoder.encode(device.clientId, "UTF-8")
        val request = Request.Builder().url("$baseUrl/api/v1/auth/challenge?clientId=$id").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Challenge HTTP ${response.code}")
            val json = JSONObject(response.body?.string() ?: "")
            return json.getString("nonce")
        }
    }

    private fun signedHeaders(builder: Request.Builder, method: String, path: String, body: ByteArray): Request.Builder {
        val nonce = challenge()
        val canonical = SignatureEnvelope.canonical(device.clientId, method, path, body, nonce)
        return builder.header("X-Client-Id", device.clientId)
            .header("X-Nonce", nonce)
            .header("X-Signature", DeviceCrypto.sign(device.clientId, canonical))
    }

    fun pair(token: String): String {
        val json = JSONObject().put("token", token).put("clientId", device.clientId)
            .put("publicKeySpki", DeviceCrypto.publicKeySpki(device.clientId))
        val payload = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("$baseUrl/api/v1/pair").post(payload).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Emparejamiento rechazado HTTP ${response.code}")
            return response.body?.string() ?: "OK"
        }
    }

    fun status(): String {
        val path = if (device.kind == "esp32") "/api/v1/power/status" else "/api/v1/status"
        val request = signedHeaders(Request.Builder().url(baseUrl + path), "GET", path, ByteArray(0)).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Estado HTTP ${response.code}")
            return response.body?.string() ?: "Sin respuesta"
        }
    }

    fun pressPower(): String {
        require(device.kind == "esp32")
        val path = "/api/v1/power/press"
        val body = ByteArray(0)
        val request = signedHeaders(Request.Builder().url(baseUrl + path), "POST", path, body)
            .post(body.toRequestBody("application/octet-stream".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Orden rechazada HTTP ${response.code}")
            return response.body?.string() ?: "OK"
        }
    }

    fun shutdown(): String {
        require(device.kind == "pc")
        val path = "/api/v1/shutdown"
        val body = ByteArray(0)
        val request = signedHeaders(Request.Builder().url(baseUrl + path), "POST", path, body)
            .post(body.toRequestBody("application/octet-stream".toMediaType())).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Apagado rechazado HTTP ${response.code}")
            return response.body?.string() ?: "OK"
        }
    }

    fun openDesktop(
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit,
        initialFps: Int = 60,
        initialQuality: Int = 62,
        initialLowImpact: Boolean = true,
        onTelemetry: (JSONObject) -> Unit = {},
        onH264: (ByteArray) -> Unit = {},
        onPcm: (ByteArray) -> Unit = {},
        initialAudioEnabled: Boolean = false
    ): WebSocket {
        require(device.kind == "pc")
        val path = "/api/v1/desktop"
        val builder = Request.Builder().url("wss://$host:$port$path")
        val request = signedHeaders(builder, "GET", path, ByteArray(0)).build()
        val socketClient = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        return socketClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("type", "stream-config")
                    .put("codec", "h264-hw").put("fps", initialFps)
                    .put("bitrateKbps", 6000).put("quality", initialQuality)
                    .put("lowImpact", initialLowImpact).toString())
                webSocket.send(JSONObject().put("type", "audio-config")
                    .put("enabled", initialAudioEnabled).toString())
                onStatus("Sesión conectada")
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val packet = bytes.toByteArray()
                if (packet.size >= 4 && packet[0] == 82.toByte() && packet[1] == 67.toByte()
                    && packet[2] == 65.toByte() && packet[3] == 51.toByte()) {
                    onPcm(packet.copyOfRange(4, packet.size))
                } else if (packet.size > 4 && packet[0] == 82.toByte() && packet[1] == 67.toByte()
                    && packet[2] == 72.toByte() && packet[3] == 51.toByte()) {
                    onH264(packet.copyOfRange(4, packet.size))
                } else if (packet.isNotEmpty()) {
                    BitmapFactory.decodeByteArray(packet, 0, packet.size)?.let(onFrame)
                }
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try { onTelemetry(JSONObject(text)) } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onStatus("Conexión interrumpida: ${t.message}")
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onStatus("Sesión cerrada")
            }
        })
    }
}
