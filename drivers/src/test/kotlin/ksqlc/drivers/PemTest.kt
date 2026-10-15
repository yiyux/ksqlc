package ksqlc.drivers

import ksqlc.drivers.server.TestCerts
import java.nio.file.Files
import java.security.KeyStore
import kotlin.test.*

class PemTest {
    private val certs = TestCerts().also { it.authority(); it.issue("server", "localhost", "DNS:localhost") }

    @Test fun readsACertificateAndBothKeyFormats() {
        assertEquals(1, Pem.certificates(certs.file("server.crt").toString()).size)
        val pkcs8 = Pem.privateKey(certs.file("server.key").toString())
        val pkcs1 = Pem.privateKey(certs.file("server.rsa.key").toString())      // BEGIN RSA PRIVATE KEY, wrapped into PKCS#8
        assertEquals("RSA", pkcs8.algorithm); assertContentEquals(pkcs8.encoded, pkcs1.encoded)
    }

    @Test fun aBundleKeepsEveryCertificate() {
        val bundle = Files.createTempFile("bundle", ".pem")
        Files.writeString(bundle, Files.readString(certs.file("server.crt")) + Files.readString(certs.file("ca.crt")))
        assertEquals(2, Pem.certificates(bundle.toString()).size)
    }

    @Test fun makesKeystoresTheDriversCanOpen() {
        val (ts, tpw) = Pem.trustStore(certs.file("ca.crt").toString())
        val trust = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(ts).use { load(it, tpw.toCharArray()) } }
        assertEquals(listOf("ca0"), trust.aliases().toList())
        val (ks, kpw) = Pem.keyStore(certs.file("server.crt").toString(), certs.file("server.rsa.key").toString())
        val keys = KeyStore.getInstance("PKCS12").apply { Files.newInputStream(ks).use { load(it, kpw.toCharArray()) } }
        assertTrue(keys.isKeyEntry("client")); assertEquals(1, keys.getCertificateChain("client").size)
        assertContentEquals(Pem.privateKey(certs.file("server.key").toString()).encoded, Files.readAllBytes(Pem.pkcs8DerFile(certs.file("server.key").toString())))
    }

    @Test fun refusesWhatItCannotReadWithAMessageThatSaysWhat() {
        val notPem = Files.createTempFile("x", ".pem").also { Files.writeString(it, "hola") }
        assertTrue("ningún certificado" in assertFailsWith<IllegalArgumentException> { Pem.certificates(notPem.toString()) }.message.orEmpty())
        assertTrue("no es una clave PEM" in assertFailsWith<IllegalArgumentException> { Pem.privateKey(notPem.toString()) }.message.orEmpty())
        val encrypted = Files.createTempFile("e", ".pem").also { Files.writeString(it, "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n") }
        assertTrue("frase de paso" in assertFailsWith<IllegalArgumentException> { Pem.privateKey(encrypted.toString()) }.message.orEmpty())
    }
}
