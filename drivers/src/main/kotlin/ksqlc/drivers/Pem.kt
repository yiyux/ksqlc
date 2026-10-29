package ksqlc.drivers

import ksqlc.core.tr

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.UUID

/** Certificate and key files in PEM form, turned into what each JDBC driver wants (Java keystores, DER keys). */
object Pem {
    /** Every certificate in a PEM file (a bundle holds several). */
    fun certificates(file: String): List<X509Certificate> {
        val bytes = Files.readAllBytes(Path.of(expand(file)))
        val certs = try {
            CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(bytes)).filterIsInstance<X509Certificate>()
        } catch (e: java.security.cert.CertificateException) { emptyList() }
        require(certs.isNotEmpty()) { tr("«%s» no contiene ningún certificado PEM", file) }
        return certs
    }

    /** The private key of a PEM file: PKCS#8 (`BEGIN PRIVATE KEY`) or an RSA PKCS#1 key (`BEGIN RSA PRIVATE KEY`), without a passphrase. */
    fun privateKey(file: String): PrivateKey {
        val text = Files.readString(Path.of(expand(file)))
        val der = when {
            "BEGIN ENCRYPTED PRIVATE KEY" in text || "Proc-Type: 4,ENCRYPTED" in text -> throw IllegalArgumentException(tr("la clave de «%s» tiene frase de paso: quítala con `openssl pkcs8 -topk8 -nocrypt`", file))
            "BEGIN PRIVATE KEY" in text -> body(text, "PRIVATE KEY")
            "BEGIN RSA PRIVATE KEY" in text -> wrapPkcs1(body(text, "RSA PRIVATE KEY"))
            else -> throw IllegalArgumentException(tr("«%s» no es una clave PEM admitida (PKCS#8 o RSA); conviértela con `openssl pkcs8 -topk8 -nocrypt`", file))
        }
        return pkcs8(der)
    }

    /** The key as a PKCS#8 DER file, the format PostgreSQL's driver reads. */
    fun pkcs8DerFile(keyFile: String): Path = tempFile(".pk8").also { Files.write(it, privateKey(keyFile).encoded) }

    /** A PKCS#12 truststore holding the certificates of [caFile]; returns its path and password. */
    fun trustStore(caFile: String): Pair<Path, String> {
        val pw = UUID.randomUUID().toString()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        certificates(caFile).forEachIndexed { i, c -> ks.setCertificateEntry("ca$i", c) }
        return tempFile(".p12").also { f -> Files.newOutputStream(f).use { ks.store(it, pw.toCharArray()) } } to pw
    }

    /** A PKCS#12 keystore with the client certificate (and its chain) and private key; returns its path and password. */
    fun keyStore(certFile: String, keyFile: String): Pair<Path, String> {
        val pw = UUID.randomUUID().toString()
        val ks = KeyStore.getInstance("PKCS12").apply { load(null, null) }
        ks.setKeyEntry("client", privateKey(keyFile), pw.toCharArray(), certificates(certFile).toTypedArray())
        return tempFile(".p12").also { f -> Files.newOutputStream(f).use { ks.store(it, pw.toCharArray()) } } to pw
    }

    private fun expand(path: String) = path.trim().replaceFirst(Regex("^~"), System.getProperty("user.home"))

    private fun body(text: String, label: String): ByteArray =
        Base64.getMimeDecoder().decode(text.substringAfter("-----BEGIN $label-----").substringBefore("-----END $label-----").trim())

    private fun pkcs8(der: ByteArray): PrivateKey {
        val spec = PKCS8EncodedKeySpec(der)
        for (alg in listOf("RSA", "EC", "Ed25519", "DSA")) runCatching { return KeyFactory.getInstance(alg).generatePrivate(spec) }
        throw IllegalArgumentException(tr("tipo de clave privada no admitido"))
    }

    /** An RSA PKCS#1 key inside the PKCS#8 envelope: SEQUENCE { INTEGER 0, SEQUENCE { rsaEncryption, NULL }, OCTET STRING key }. */
    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        fun len(n: Int): ByteArray = when {
            n < 0x80 -> byteArrayOf(n.toByte())
            n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
            else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        }
        val algorithm = byteArrayOf(0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00)
        val inner = byteArrayOf(0x02, 0x01, 0x00) + algorithm + byteArrayOf(0x04) + len(pkcs1.size) + pkcs1
        return byteArrayOf(0x30) + len(inner.size) + inner
    }

    private fun tempFile(suffix: String): Path {
        val f = Files.createTempFile("ksqlc-ssl-", suffix)
        runCatching { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")) }
        f.toFile().deleteOnExit()
        return f
    }
}
