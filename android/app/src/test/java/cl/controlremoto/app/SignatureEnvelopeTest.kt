package cl.controlremoto.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SignatureEnvelopeTest {
    @Test fun canonicalSignatureVector() {
        val value = SignatureEnvelope.canonical("client-1", "get", "/api/v1/status", byteArrayOf(), "N1")
        assertEquals("RCP1\nclient-1\nGET\n/api/v1/status\n" +
            "E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855\nN1", String(value))
    }
}
