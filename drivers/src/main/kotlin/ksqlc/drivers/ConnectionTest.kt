package ksqlc.drivers

import ksqlc.core.DataSource
import java.sql.DriverManager

/**
 * A new JDBC connection to [ds]. Cassandra's driver gives each request 2 s by default, too little for DDL or a scan on a busy
 * node, so it is raised to 30 s (the wrapper reads `requestTimeout` in milliseconds).
 */
fun openConnection(source: DataSource, password: String?): java.sql.Connection {
    val ds = throughTunnel(source)
    val (url, ssl) = sslFor(ds)
    if (ds.kind != ksqlc.core.DbKind.CASSANDRA && ssl.isEmpty()) return DriverManager.getConnection(url, ds.user, password)
    val props = java.util.Properties().apply {
        ds.user?.let { setProperty("user", it) }; password?.let { setProperty("password", it) }
        ssl.forEach { (k, v) -> setProperty(k, v) }
        if (ds.kind == ksqlc.core.DbKind.CASSANDRA) setProperty("requestTimeout", "30000")
    }
    return DriverManager.getConnection(url, props)
}

/**
 * The URL and extra connection properties that apply the SSL section of [ds]: how far the server is verified, its CA, and a
 * client certificate. Only for a connection the form built (its URL says encryption is on) on PostgreSQL, MySQL, MariaDB and SQL Server;
 * the drivers want different things (PostgreSQL reads PEM certificates and a DER key, MySQL and SQL Server Java keystores, MariaDB a PEM CA
 * and a keystore), so [Pem] makes what each one reads. Verifying means the plain "encrypted" URL flags give way to the driver's own mode.
 */
internal fun sslFor(ds: DataSource): Pair<String, Map<String, String>> {
    val kinds = setOf(ksqlc.core.DbKind.POSTGRES, ksqlc.core.DbKind.MYSQL, ksqlc.core.DbKind.MARIADB, ksqlc.core.DbKind.MSSQL)
    val parts = JdbcUrl.parse(ds.url)
    if (ds.kind !in kinds || parts == null || !parts.ssl) return ds.url to emptyMap()
    val s = ds.ssl
    val verify = s.verify
    val client = s.clientCertFile.isNotBlank() && s.clientKeyFile.isNotBlank()
    if (verify == ksqlc.core.SslVerify.NONE && !client) return ds.url to emptyMap()
    val props = linkedMapOf<String, String>()
    val url = if (verify == ksqlc.core.SslVerify.NONE) ds.url
        else JdbcUrl.build(ds.kind, parts.host, parts.port, parts.database, ssl = ds.kind == ksqlc.core.DbKind.MSSQL, trustCert = false, datacenter = parts.datacenter)
    val full = verify == ksqlc.core.SslVerify.FULL
    try {
        when (ds.kind) {
            ksqlc.core.DbKind.POSTGRES -> {
                if (verify != ksqlc.core.SslVerify.NONE) {
                    props["sslmode"] = if (full) "verify-full" else "verify-ca"
                    if (s.caFile.isNotBlank()) props["sslrootcert"] = s.caFile.trim().replaceFirst(Regex("^~"), System.getProperty("user.home"))
                }
                if (client) { props["sslcert"] = s.clientCertFile.trim().replaceFirst(Regex("^~"), System.getProperty("user.home")); props["sslkey"] = Pem.pkcs8DerFile(s.clientKeyFile).toString() }
            }
            ksqlc.core.DbKind.MYSQL -> {
                if (verify != ksqlc.core.SslVerify.NONE) {
                    props["sslMode"] = if (full) "VERIFY_IDENTITY" else "VERIFY_CA"
                    if (s.caFile.isNotBlank()) Pem.trustStore(s.caFile).let { (f, pw) -> props["trustCertificateKeyStoreUrl"] = f.toUri().toString(); props["trustCertificateKeyStoreType"] = "PKCS12"; props["trustCertificateKeyStorePassword"] = pw }
                }
                if (client) Pem.keyStore(s.clientCertFile, s.clientKeyFile).let { (f, pw) -> props["clientCertificateKeyStoreUrl"] = f.toUri().toString(); props["clientCertificateKeyStoreType"] = "PKCS12"; props["clientCertificateKeyStorePassword"] = pw }
            }
            ksqlc.core.DbKind.MARIADB -> {
                if (verify != ksqlc.core.SslVerify.NONE) {
                    props["sslMode"] = if (full) "verify-full" else "verify-ca"
                    if (s.caFile.isNotBlank()) props["serverSslCert"] = s.caFile.trim().replaceFirst(Regex("^~"), System.getProperty("user.home"))
                }
                if (client) Pem.keyStore(s.clientCertFile, s.clientKeyFile).let { (f, pw) -> props["keyStore"] = f.toString(); props["keyStorePassword"] = pw; props["keyStoreType"] = "PKCS12" }
            }
            ksqlc.core.DbKind.MSSQL -> if (verify != ksqlc.core.SslVerify.NONE && s.caFile.isNotBlank()) Pem.trustStore(s.caFile).let { (f, pw) -> props["trustStore"] = f.toString(); props["trustStorePassword"] = pw; props["trustStoreType"] = "PKCS12" }
            else -> {}
        }
    } catch (e: Exception) { throw java.sql.SQLException("Certificados SSL: ${e.message}", e) }
    return url to props
}

/** With an SSH tunnel on, the same data source aimed at the local end of the tunnel; otherwise [ds] itself. */
internal fun throughTunnel(ds: DataSource): DataSource {
    if (!ds.ssh.enabled || ds.kind == ksqlc.core.DbKind.SQLITE) return ds
    val parts = JdbcUrl.parse(ds.url) ?: throw java.sql.SQLException("El túnel SSH necesita una conexión definida con host y puerto, no una URL manual")
    val port = try {
        Tunnels.forward(ds.ssh, ds.sshSecret, parts.host, parts.port ?: JdbcUrl.defaultPort(ds.kind) ?: error("sin puerto"))
    } catch (e: Exception) { throw java.sql.SQLException("No se pudo abrir el túnel SSH: ${e.message}", e) }
    return ds.copy(url = JdbcUrl.build(ds.kind, "127.0.0.1", port, parts.database, parts.ssl, parts.trustCert, parts.datacenter))
}

/** Opens a connection and returns what answered, e.g. "PostgreSQL 16.4"; throws the driver's exception otherwise. */
fun testConnection(ds: DataSource, password: String?): String {
    DriverManager.setLoginTimeout(10)
    return openConnection(ds, password).use { c ->
        val m = c.metaData
        "${m.databaseProductName} ${m.databaseProductVersion}".trim()
    }
}
