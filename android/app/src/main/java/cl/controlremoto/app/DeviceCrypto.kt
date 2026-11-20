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

/** AndroidKeyStore-backed client identity; the P-256 private key never leaves the phone. */
object DeviceCrypto {
    private fun alias(clientId: String): String = "rcp1-" + clientId

    private fun entry(clientId: String): KeyStore.PrivateKeyEntry {
        require(clientId.matches(Regex("[a-zA-Z0-9-]{8,96}")))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!store.containsAlias(alias(clientId))) {
            val params = KeyGenParameterSpec.Builder(alias(clientId),
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                .apply { initialize(params) }.generateKeyPair()
        }
        val ready = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return ready.getEntry(alias(clientId), null) as KeyStore.PrivateKeyEntry
    }

    fun publicKeySpki(clientId: String): String =
        Base64.encodeToString(entry(clientId).certificate.publicKey.encoded, Base64.NO_WRAP)

    fun sign(clientId: String, data: ByteArray): String {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(entry(clientId).privateKey)
        signer.update(data)
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
    }
}

object SignatureEnvelope {
    fun canonical(clientId: String, method: String, path: String, body: ByteArray, nonce: String): ByteArray {
        val sha = MessageDigest.getInstance("SHA-256").digest(body)
        val hex = sha.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
        return listOf("RCP1", clientId, method.uppercase(java.util.Locale.ROOT), path, hex, nonce)
            .joinToString("\n").toByteArray(StandardCharsets.UTF_8)
    }
}
