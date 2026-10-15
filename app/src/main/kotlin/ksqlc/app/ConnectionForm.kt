package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ksqlc.core.ConnectionOptions
import ksqlc.core.DataSource
import ksqlc.core.DbKind
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

    fun toDataSource() = DataSource(currentId ?: "unsaved", name, effectiveKind, url, user.ifBlank { null }, manualTx && effectiveKind != DbKind.CASSANDRA, options)

    fun copyFrom(o: ConnectionForm) {
        currentId = o.currentId; name = o.name; kind = o.kind; host = o.host; portText = o.portText; database = o.database
        ssl = o.ssl; trustCert = o.trustCert; datacenter = o.datacenter; user = o.user; password = o.password; customUrl = o.customUrl; urlText = o.urlText; manualTx = o.manualTx
        readOnly = o.readOnly; timeZone = o.timeZone; keepAliveOn = o.keepAliveOn; keepAliveText = o.keepAliveText; autoDisconnectOn = o.autoDisconnectOn
        autoDisconnectText = o.autoDisconnectText; singleDatabase = o.singleDatabase; startupScript = o.startupScript; autoSync = o.autoSync; introspectionText = o.introspectionText
    }

    /**
     * Fills the form from a saved connection. Only the URL is stored: when it is one the form could have built, host,
     * port and database come back as fields; any other URL opens in manual-URL mode.
     */
    fun load(c: DataSource) {
        currentId = c.id; name = c.name; user = c.user.orEmpty(); password = ""; manualTx = c.manualTx; loadOptions(c.options)
        val p = JdbcUrl.parse(c.url)
        if (p != null) {
            kind = p.kind; host = p.host.ifBlank { "localhost" }; portText = (p.port ?: JdbcUrl.defaultPort(p.kind))?.toString().orEmpty()
            database = p.database; ssl = p.ssl; trustCert = p.trustCert; datacenter = p.datacenter.ifBlank { JdbcUrl.DEFAULT_DATACENTER }; customUrl = false; urlText = ""
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
