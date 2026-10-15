package ksqlc.drivers.server

import org.junit.Assume.assumeTrue
import java.nio.file.Files
import java.nio.file.Path

/** A throwaway certificate authority and the certificates it signs, made with the `openssl` of the machine running the tests. */
class TestCerts(val dir: Path = Files.createTempDirectory("ksqlc-certs")) {
    init { assumeTrue("openssl no disponible", runCatching { run("openssl", "version") }.isSuccess) }

    private fun run(vararg cmd: String) {
        val p = ProcessBuilder(*cmd).directory(dir.toFile()).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "${cmd.joinToString(" ")}: $out" }
    }

    val caCert: Path get() = dir.resolve("ca.crt")

    /** A new CA; [name] keeps several apart in one directory. */
    fun authority(name: String = "ca"): Path {
        run("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", "$name.key", "-out", "$name.crt", "-days", "2", "-subj", "/CN=ksqlc test $name")
        return dir.resolve("$name.crt")
    }

    /** A certificate signed by authority [ca]; [san] is e.g. `DNS:localhost` (the host names it is valid for). Writes `<name>.crt`, `<name>.key` (PKCS#8) and `<name>.rsa.key` (PKCS#1). */
    fun issue(name: String, cn: String, san: String? = null, ca: String = "ca") {
        run("openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", "$name.key", "-out", "$name.csr", "-subj", "/CN=$cn")
        val ext = dir.resolve("$name.ext"); Files.writeString(ext, if (san != null) "subjectAltName=$san\n" else "basicConstraints=CA:FALSE\n")
        run("openssl", "x509", "-req", "-in", "$name.csr", "-CA", "$ca.crt", "-CAkey", "$ca.key", "-CAcreateserial", "-out", "$name.crt", "-days", "2", "-extfile", ext.toString())
        run("openssl", "rsa", "-in", "$name.key", "-traditional", "-out", "$name.rsa.key")
    }

    fun file(name: String): Path = dir.resolve(name)
}
