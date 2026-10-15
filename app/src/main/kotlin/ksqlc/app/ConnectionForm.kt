package ksqlc.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ksqlc.core.DataSource
import ksqlc.core.DbKind
import ksqlc.drivers.JdbcUrl

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

    val url: String get() = if (customUrl) urlText else JdbcUrl.build(kind, host, portText.toIntOrNull(), database, ssl, trustCert, datacenter)
    val effectiveKind: DbKind get() = if (customUrl) JdbcUrl.kindOf(url) else kind
    val passwordOrNull: String? get() = password.ifBlank { null }

    fun toDataSource() = DataSource(currentId ?: "unsaved", name, effectiveKind, url, user.ifBlank { null }, manualTx && effectiveKind != DbKind.CASSANDRA)

    fun copyFrom(o: ConnectionForm) {
        currentId = o.currentId; name = o.name; kind = o.kind; host = o.host; portText = o.portText; database = o.database
        ssl = o.ssl; trustCert = o.trustCert; datacenter = o.datacenter; user = o.user; password = o.password; customUrl = o.customUrl; urlText = o.urlText; manualTx = o.manualTx
    }

    /**
     * Fills the form from a saved connection. Only the URL is stored: when it is one the form could have built, host,
     * port and database come back as fields; any other URL opens in manual-URL mode.
     */
    fun load(c: DataSource) {
        currentId = c.id; name = c.name; user = c.user.orEmpty(); password = ""; manualTx = c.manualTx
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
