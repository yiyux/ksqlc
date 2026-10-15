package ksqlc.drivers

import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import ksqlc.core.SshAuth
import ksqlc.core.SshSettings
import java.nio.file.Files
import java.nio.file.Path

/** Asked (on the thread that is connecting) whether to trust a host key seen for the first time; [message] says which host and its fingerprint. */
fun interface HostKeyDecider { fun trust(message: String): Boolean }

/**
 * SSH tunnels to databases: one SSH connection per jump host, shared by every database connection that goes through it, with a
 * local port forwarded to each database. A host is trusted the first time only if [hostKeyDecider] agrees, and is then remembered in
 * [knownHosts] (ksqlc's own file, not `~/.ssh/known_hosts`); a host whose key has changed is refused.
 */
object Tunnels {
    @Volatile var knownHosts: Path = Path.of(System.getProperty("user.home"), ".ksqlc", "known_hosts")
    /** null = a host that is not known yet is refused. */
    @Volatile var hostKeyDecider: HostKeyDecider? = null

    private class Hop(val session: Session) { val forwards = HashMap<String, Int>() }
    private val hops = HashMap<String, Hop>()

    /** The local port that reaches [remoteHost]:[remotePort] through [ssh]; opens the SSH connection and the forward if they are not there yet. */
    @Synchronized fun forward(ssh: SshSettings, secret: String?, remoteHost: String, remotePort: Int): Int {
        require(ssh.host.isNotBlank()) { "Falta el host del servidor SSH" }
        val key = "${ssh.user}@${ssh.host}:${ssh.port}:${ssh.auth}:${ssh.keyFile}"
        var hop = hops[key]?.takeIf { it.session.isConnected }
        if (hop == null) { hop = Hop(connect(ssh, secret)); hops[key] = hop }
        return hop.forwards.getOrPut("$remoteHost:$remotePort") { hop.session.setPortForwardingL("127.0.0.1", 0, remoteHost, remotePort) }
    }

    @Synchronized fun closeAll() {
        hops.values.forEach { runCatching { it.session.disconnect() } }
        hops.clear()
    }

    private fun connect(ssh: SshSettings, secret: String?): Session {
        val jsch = JSch()
        Files.createDirectories(knownHosts.parent)
        if (!Files.exists(knownHosts)) Files.createFile(knownHosts)
        jsch.setKnownHosts(knownHosts.toString())
        if (ssh.auth == SshAuth.KEY) {
            require(ssh.keyFile.isNotBlank()) { "Falta el archivo de la clave privada" }
            val path = ssh.keyFile.replaceFirst(Regex("^~"), System.getProperty("user.home"))
            if (secret.isNullOrEmpty()) jsch.addIdentity(path) else jsch.addIdentity(path, secret)
        }
        val session = jsch.getSession(ssh.user, ssh.host, ssh.port)
        session.setConfig("StrictHostKeyChecking", "ask")
        session.setConfig("PreferredAuthentications", if (ssh.auth == SshAuth.PASSWORD) "password,keyboard-interactive" else "publickey")
        if (ssh.auth == SshAuth.PASSWORD) session.setPassword(secret.orEmpty())
        var keyChanged = false
        session.userInfo = object : UserInfo {
            override fun getPassphrase() = secret
            override fun getPassword() = secret
            override fun promptPassword(message: String?) = ssh.auth == SshAuth.PASSWORD
            override fun promptPassphrase(message: String?) = ssh.auth == SshAuth.KEY
            // in "ask" mode JSch asks about a changed key too; that is never a question for the user
            override fun promptYesNo(message: String?): Boolean {
                if ("HAS CHANGED" in message.orEmpty()) { keyChanged = true; return false }
                return hostKeyDecider?.trust(message.orEmpty()) ?: false
            }
            override fun showMessage(message: String?) {}
        }
        session.setServerAliveInterval(30_000)
        try { session.connect(15_000) } catch (e: JSchException) { throw SshTunnelException(if (keyChanged) describe(JSchException("HostKey has been changed")) else describe(e), e) }
        return session
    }

    private fun describe(e: JSchException): String = when {
        listOf("Auth fail", "Auth cancel", "authentication failures").any { it in e.message.orEmpty() } -> "el servidor SSH rechazó las credenciales"
        "HostKey has been changed" in e.message.orEmpty() || "REMOTE HOST IDENTIFICATION" in e.message.orEmpty() -> "la clave del servidor SSH ha cambiado respecto a la guardada: puede ser un ataque; borra su línea de ${knownHosts} si el cambio es legítimo"
        "UnknownHostKey" in e.message.orEmpty() || "reject HostKey" in e.message.orEmpty() -> "no se confió en la clave del servidor SSH"
        else -> e.message ?: e.toString()
    }
}

class SshTunnelException(message: String, cause: Throwable? = null) : Exception(message, cause)
