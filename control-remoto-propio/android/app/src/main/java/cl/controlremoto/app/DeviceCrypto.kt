package cl.controlremoto.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import javax.net.ssl.SSLPeerUnverifiedException
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object DeviceCrypto {
    private const val KEY_STORE = "AndroidKeyStore"
    fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    fun fromB64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    fun publicKey(alias: String): ByteArray {
        val ks = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEY_STORE)
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            generator.initialize(spec)
            generator.generateKeyPair()
        }
        return ks.getCertificate(alias).publicKey.encoded
    }

    fun canonical(clientId: String, method: String, path: String,
                  body: ByteArray, nonce: String): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256").digest(body)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        return "RCP1\n$clientId\n${method.uppercase()}\n$path\n$hash\n$nonce"
            .toByteArray(StandardCharsets.UTF_8)
    }

    fun sign(alias: String, text: ByteArray): String {
        val ks = KeyStore.getInstance(KEY_STORE).apply { load(null) }
        val key = ks.getKey(alias, null) as java.security.PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA")
        signature.initSign(key)
        signature.update(text)
        return b64(signature.sign())  // ASN.1 DER, verified with ECDSA P-256 on targets.
    }

    private fun pinMatches(cert: X509Certificate, pin: String): Boolean {
        val expected = fromB64(pin)
        val actual = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
        return expected.size == 32 && MessageDigest.isEqual(expected, actual)
    }

    // Deliberately device-specific, NEVER a global "trust any certificate" override.
    // Trust anchor is the exact public key verified out-of-band during physical pairing.
    fun pinnedClient(pin: String): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw SSLPeerUnverifiedException("Client certificate not supported")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty() || !pinMatches(chain[0], pin))
                    throw java.security.cert.CertificateException("Server public-key pin mismatch")
                chain[0].checkValidity()
            }
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(tm), java.security.SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            // Since endpoints can be raw IPv4, the OOB SPKI pin is the server identity.
            // This verifier RECHECKS the exact leaf key; it does NOT accept arbitrary certs.
            .hostnameVerifier { _, session ->
                try {
                    val cert = session.peerCertificates.first() as X509Certificate
                    cert.checkValidity()
                    pinMatches(cert, pin)
                } catch (_: Exception) { false }
            }
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
