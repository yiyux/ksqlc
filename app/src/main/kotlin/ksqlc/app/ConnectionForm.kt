package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ksqlc.core.ConnectionOptions
import ksqlc.core.DataSource
import ksqlc.core.DbKind
import ksqlc.core.SchemaFilter
import ksqlc.core.SshAuth
import ksqlc.core.SslSettings
import ksqlc.core.SslVerify
import ksqlc.core.SshSettings
import ksqlc.drivers.JdbcUrl
import ksqlc.drivers.supportsSingleDatabase
import ksqlc.drivers.supportsTimeZone

/** The connection fields on screen. The URL is derived from them unless [customUrl] is on. */
class ConnectionForm {
    var currentId by mutableStateOf<String?>(null)
    var name by mutableStateOf("local")
    var kind by mutableStateOf(DbKind.SQLITE)
    var host by mutableStateOf("localhost")
    var portText by mutableStateOf("")
    var database by mutableStateOf("")
    var ssl by mutableStateOf(false)
    var trustCert by mutableStateOf(false)
    /** Cassandra only: the local datacenter its driver routes requests to. */
    var datacenter by mutableStateOf(JdbcUrl.DEFAULT_DATACENTER)
    var user by mutableStateOf("")
    var password by mutableStateOf("")
    var customUrl by mutableStateOf(false)
    var urlText by mutableStateOf("")
    var manualTx by mutableStateOf(false)

    // the "Options" tab; each switch has its number beside it, kept as text while it is being typed
    // the "Esquemas" tab: which schemas the explorer lists
    var schemasAll by mutableStateOf(true)
    var schemasSelected by mutableStateOf<List<String>>(emptyList())
    var schemaPattern by mutableStateOf("")

    val schemas: SchemaFilter get() = SchemaFilter(schemasAll, schemasSelected, schemaPattern.trim())

    // the SSL section of the "SSH/SSL" tab; `ssl` and `trustCert` above are what ends up in the URL
    var sslVerify by mutableStateOf(SslVerify.NONE)
    var sslCa by mutableStateOf("")
    var sslCert by mutableStateOf("")
    var sslKey by mutableStateOf("")

    /** 0 off, 1 encrypted without checking the server, 2 verify its CA, 3 verify CA and host name. Choosing sets the URL flags to match. */
    var sslMode: Int
        get() = if (!ssl) 0 else when (sslVerify) { SslVerify.NONE -> 1; SslVerify.CA -> 2; SslVerify.FULL -> 3 }
        set(i) {
            ssl = i != 0
            sslVerify = when (i) { 2 -> SslVerify.CA; 3 -> SslVerify.FULL; else -> SslVerify.NONE }
            trustCert = i == 1      // SQL Server: "require" accepts any certificate, the verifying modes check it
        }

    val sslSettings: SslSettings get() = if (!ssl) SslSettings() else SslSettings(sslVerify, sslCa.trim(), sslCert.trim(), sslKey.trim())

    private fun loadSsl(s: SslSettings, urlSsl: Boolean, urlTrust: Boolean, kind: DbKind) {
        // a SQL Server URL that encrypts without trusting everything already verifies the certificate: show it as such
        sslVerify = if (s.verify == SslVerify.NONE && urlSsl && !urlTrust && kind == DbKind.MSSQL) SslVerify.FULL else s.verify
        sslCa = s.caFile; sslCert = s.clientCertFile; sslKey = s.clientKeyFile
    }

    // the SSH tunnel of the "SSH/SSL" tab
    var sshEnabled by mutableStateOf(false)
    var sshHost by mutableStateOf("")
    var sshPortText by mutableStateOf("22")
    var sshUser by mutableStateOf("")
    var sshAuth by mutableStateOf(SshAuth.PASSWORD)
    var sshKeyFile by mutableStateOf("")
    /** The SSH password, or the key's passphrase. */
    var sshSecret by mutableStateOf("")

    val ssh: SshSettings get() = SshSettings(sshEnabled && kind != DbKind.SQLITE && !customUrl, sshHost.trim(), sshPortText.toIntOrNull() ?: 22, sshUser.trim(), sshAuth, sshKeyFile.trim())

    private fun loadSsh(s: SshSettings) {
        sshEnabled = s.enabled; sshHost = s.host; sshPortText = s.port.toString(); sshUser = s.user; sshAuth = s.auth; sshKeyFile = s.keyFile
    }

    var readOnly by mutableStateOf(false)
    var timeZone by mutableStateOf("")
    var keepAliveOn by mutableStateOf(false)
    var keepAliveText by mutableStateOf("60")
    var autoDisconnectOn by mutableStateOf(false)
    var autoDisconnectText by mutableStateOf("300")
    var singleDatabase by mutableStateOf(false)
    var startupScript by mutableStateOf("")
    var autoSync by mutableStateOf(true)
    var introspectionText by mutableStateOf("")

    /** The options as saved: only those the engine offers count, and a number that is not one means off. */
    val options: ConnectionOptions get() = ConnectionOptions(
        readOnly = readOnly,
        timeZone = if (supportsTimeZone(effectiveKind)) timeZone.trim() else "",
        keepAliveSeconds = if (keepAliveOn) keepAliveText.toIntOrNull()?.coerceAtLeast(5) ?: 0 else 0,
        autoDisconnectSeconds = if (autoDisconnectOn) autoDisconnectText.toIntOrNull()?.coerceAtLeast(5) ?: 0 else 0,
        singleDatabase = singleDatabase && supportsSingleDatabase(effectiveKind),
        startupScript = startupScript,
        autoSync = autoSync,
        introspectionMinutes = introspectionText.toIntOrNull()?.coerceAtLeast(1) ?: 0,
    )

    private fun loadOptions(o: ConnectionOptions) {
        readOnly = o.readOnly; timeZone = o.timeZone
        keepAliveOn = o.keepAliveSeconds > 0; keepAliveText = if (o.keepAliveSeconds > 0) o.keepAliveSeconds.toString() else "60"
        autoDisconnectOn = o.autoDisconnectSeconds > 0; autoDisconnectText = if (o.autoDisconnectSeconds > 0) o.autoDisconnectSeconds.toString() else "300"
        singleDatabase = o.singleDatabase; startupScript = o.startupScript; autoSync = o.autoSync
        introspectionText = if (o.introspectionMinutes > 0) o.introspectionMinutes.toString() else ""
    }

    val url: String get() = if (customUrl) urlText else JdbcUrl.build(kind, host, portText.toIntOrNull(), database, ssl, trustCert, datacenter)
    val effectiveKind: DbKind get() = if (customUrl) JdbcUrl.kindOf(url) else kind
    val passwordOrNull: String? get() = password.ifBlank { null }

    fun toDataSource() = DataSource(currentId ?: "unsaved", name, effectiveKind, url, user.ifBlank { null }, manualTx && effectiveKind != DbKind.CASSANDRA, options, ssh, sslSettings, schemas, sshSecret.ifBlank { null })

    fun copyFrom(o: ConnectionForm) {
        currentId = o.currentId; name = o.name; kind = o.kind; host = o.host; portText = o.portText; database = o.database
        ssl = o.ssl; trustCert = o.trustCert; datacenter = o.datacenter; user = o.user; password = o.password; customUrl = o.customUrl; urlText = o.urlText; manualTx = o.manualTx
        readOnly = o.readOnly; timeZone = o.timeZone; keepAliveOn = o.keepAliveOn; keepAliveText = o.keepAliveText; autoDisconnectOn = o.autoDisconnectOn
        autoDisconnectText = o.autoDisconnectText; singleDatabase = o.singleDatabase; startupScript = o.startupScript; autoSync = o.autoSync; introspectionText = o.introspectionText
        schemasAll = o.schemasAll; schemasSelected = o.schemasSelected; schemaPattern = o.schemaPattern
        sslVerify = o.sslVerify; sslCa = o.sslCa; sslCert = o.sslCert; sslKey = o.sslKey
        sshEnabled = o.sshEnabled; sshHost = o.sshHost; sshPortText = o.sshPortText; sshUser = o.sshUser; sshAuth = o.sshAuth; sshKeyFile = o.sshKeyFile; sshSecret = o.sshSecret
    }

    /**
     * Fills the form from a saved connection. Only the URL is stored: when it is one the form could have built, host,
     * port and database come back as fields; any other URL opens in manual-URL mode.
     */
    fun load(c: DataSource) {
        currentId = c.id; name = c.name; user = c.user.orEmpty(); password = ""; manualTx = c.manualTx; loadOptions(c.options); loadSsh(c.ssh); schemasAll = c.schemas.all; schemasSelected = c.schemas.selected; schemaPattern = c.schemas.pattern; sshSecret = ""
        val p = JdbcUrl.parse(c.url)
        if (p != null) {
            kind = p.kind; host = p.host.ifBlank { "localhost" }; portText = (p.port ?: JdbcUrl.defaultPort(p.kind))?.toString().orEmpty()
            database = p.database; ssl = p.ssl; trustCert = p.trustCert; loadSsl(c.ssl, p.ssl, p.trustCert, p.kind); datacenter = p.datacenter.ifBlank { JdbcUrl.DEFAULT_DATACENTER }; customUrl = false; urlText = ""
        } else {
            kind = c.kind; customUrl = true; urlText = c.url
        }
    }
}

/** Product name for display ("PostgreSQL", "SQL Server"…). */
fun DbKind.label(): String = when (this) {
    DbKind.POSTGRES -> "PostgreSQL"
    DbKind.MYSQL -> "MySQL"
    DbKind.MARIADB -> "MariaDB"
    DbKind.SQLITE -> "SQLite"
    DbKind.MSSQL -> "SQL Server"
    DbKind.ORACLE -> "Oracle"
    DbKind.CASSANDRA -> "Apache Cassandra"
}
