package ksqlc.drivers

import ksqlc.core.DbKind
import java.nio.file.Files
import java.nio.file.Path

/** What to dump: a schema (a database, for MySQL) and/or a single table. Both null = the whole database. */
data class DumpScope(val schema: String? = null, val table: String? = null)

data class DumpOptions(val schemaOnly: Boolean = false, val dataOnly: Boolean = false, val custom: Boolean = false, val clean: Boolean = false)

/** A command to run. The password is never in [args]: it travels in [env]. [stdin]/[stdout] redirect to files. */
data class ProcessSpec(val args: List<String>, val env: Map<String, String> = emptyMap(), val stdin: Path? = null, val stdout: Path? = null)

/** Engines whose own dump tools we drive. SQL Server and Oracle have no script-style dump tool, so they are left out. */
fun nativeDumpSupported(kind: DbKind) = kind == DbKind.POSTGRES || kind == DbKind.MYSQL || kind == DbKind.MARIADB || kind == DbKind.SQLITE

/** Why a dump or restore cannot be offered for this connection, or null when it can. */
fun nativeDumpBlocker(kind: DbKind, parts: JdbcUrl.Parts?): String? = when {
    !nativeDumpSupported(kind) -> "${kind.name.lowercase().replaceFirstChar { it.uppercase() }} no tiene una herramienta de volcado compatible"
    parts == null -> "La conexión usa una URL manual: define host, puerto y base de datos en el formulario"
    kind == DbKind.SQLITE && parts.database.isBlank() -> "La base SQLite está en memoria"
    else -> null
}

/** Executable names to try, in order, for dumping [kind]. */
fun dumpToolNames(kind: DbKind): List<String> = when (kind) {
    DbKind.POSTGRES -> listOf("pg_dump")
    DbKind.MYSQL -> listOf("mysqldump")
    DbKind.MARIADB -> listOf("mariadb-dump", "mysqldump")
    DbKind.SQLITE -> listOf("sqlite3")
    else -> emptyList()
}

fun restoreToolNames(kind: DbKind, custom: Boolean): List<String> = when (kind) {
    DbKind.POSTGRES -> listOf(if (custom) "pg_restore" else "psql")
    DbKind.MYSQL -> listOf("mysql")
    DbKind.MARIADB -> listOf("mariadb", "mysql")
    DbKind.SQLITE -> listOf("sqlite3")
    else -> emptyList()
}

/** Where the client tools usually live on macOS, since an app launched from the Dock does not inherit the shell's PATH. */
val DEFAULT_TOOL_DIRS = listOf(
    "/opt/homebrew/bin", "/usr/local/bin", "/opt/homebrew/opt/libpq/bin", "/usr/local/opt/libpq/bin",
    "/opt/homebrew/opt/mysql-client/bin", "/usr/local/opt/mysql-client/bin", "/opt/homebrew/opt/mariadb/bin", "/usr/bin",
    "/Applications/Postgres.app/Contents/Versions/latest/bin",
)

/** The first of [names] that exists as an executable file: [override] if given and valid, else the PATH, else [extraDirs]. */
fun findTool(names: List<String>, override: String? = null, path: String = System.getenv("PATH").orEmpty(), extraDirs: List<String> = DEFAULT_TOOL_DIRS): Path? {
    fun ok(p: Path) = Files.isRegularFile(p) && Files.isExecutable(p)
    override?.takeIf { it.isNotBlank() }?.let { Path.of(it).takeIf(::ok)?.let { p -> return p } }
    val dirs = path.split(':').filter { it.isNotBlank() } + extraDirs
    for (n in names) for (d in dirs) Path.of(d, n).takeIf(::ok)?.let { return it }
    return null
}

private fun pgPattern(s: String) = "\"" + s.replace("\"", "\"\"") + "\""

/** The command that dumps [scope] of the database [parts] into [out]. Throws [IllegalArgumentException] for an option the engine lacks. */
fun dumpCommand(tool: String, kind: DbKind, parts: JdbcUrl.Parts, user: String?, password: String?, scope: DumpScope, options: DumpOptions, out: Path): ProcessSpec {
    require(!(options.schemaOnly && options.dataOnly)) { "«solo esquema» y «solo datos» a la vez no tienen sentido" }
    val host = parts.host.ifBlank { "localhost" }
    val port = (parts.port ?: JdbcUrl.defaultPort(kind))?.toString()
    return when (kind) {
        DbKind.POSTGRES -> ProcessSpec(
            buildList {
                add(tool); add("-h"); add(host); port?.let { add("-p"); add(it) }; user?.let { add("-U"); add(it) }; add("-w")
                add("-d"); add(parts.database)
                scope.schema?.let { s -> add("-n"); add(pgPattern(s)) }
                scope.table?.let { t -> add("-t"); add(scope.schema?.let { pgPattern(it) + "." }.orEmpty() + pgPattern(t)) }
                if (options.schemaOnly) add("--schema-only")
                if (options.dataOnly) add("--data-only")
                if (options.clean) { add("--clean"); add("--if-exists") }
                add("-F"); add(if (options.custom) "c" else "p")
                add("-f"); add(out.toString())
            },
            env = listOfNotNull(password?.let { "PGPASSWORD" to it }).toMap(),
        )
        DbKind.MYSQL, DbKind.MARIADB -> {
            require(!options.custom) { "el formato personalizado es solo de PostgreSQL" }
            ProcessSpec(
                buildList {
                    add(tool); add("-h"); add(host); port?.let { add("-P"); add(it) }; user?.let { add("-u"); add(it) }; add("--protocol=tcp")
                    add("--single-transaction"); add("--routines"); add("--triggers")
                    if (options.schemaOnly) add("--no-data")
                    if (options.dataOnly) add("--no-create-info")
                    if (!options.clean) add("--skip-add-drop-table")
                    add("--result-file=$out")
                    add(scope.schema ?: parts.database)
                    scope.table?.let { add(it) }
                },
                env = listOfNotNull(password?.let { "MYSQL_PWD" to it }).toMap(),
            )
        }
        DbKind.SQLITE -> {
            require(!options.dataOnly) { "SQLite no puede volcar solo los datos" }
            require(!options.custom) { "el formato personalizado es solo de PostgreSQL" }
            // the shell runs each argument as a command, so the table goes inside the dot-command
            // shortcut: the name is a LIKE pattern for .dump, so `_` and `%` in a table name also match other tables
            val cmd = (if (options.schemaOnly) ".schema" else ".dump") + (scope.table?.let { require('\'' !in it) { "el nombre de la tabla lleva una comilla simple" }; " '$it'" } ?: "")
            ProcessSpec(listOf(tool, parts.database, cmd), stdout = out)
        }
        else -> throw IllegalArgumentException("no hay herramienta de volcado para $kind")
    }
}

/** The command that loads [input] into the database [parts]. [custom] is PostgreSQL's archive format (pg_restore) rather than plain SQL (psql). */
fun restoreCommand(tool: String, kind: DbKind, parts: JdbcUrl.Parts, user: String?, password: String?, input: Path, custom: Boolean = false): ProcessSpec {
    val host = parts.host.ifBlank { "localhost" }
    val port = (parts.port ?: JdbcUrl.defaultPort(kind))?.toString()
    return when (kind) {
        DbKind.POSTGRES -> ProcessSpec(
            buildList {
                add(tool); add("-h"); add(host); port?.let { add("-p"); add(it) }; user?.let { add("-U"); add(it) }; add("-w"); add("-d"); add(parts.database)
                if (custom) add("--single-transaction") else { add("-v"); add("ON_ERROR_STOP=1"); add("-f"); add(input.toString()) }
                if (custom) add(input.toString())
            },
            env = listOfNotNull(password?.let { "PGPASSWORD" to it }).toMap(),
        )
        DbKind.MYSQL, DbKind.MARIADB -> ProcessSpec(
            buildList { add(tool); add("-h"); add(host); port?.let { add("-P"); add(it) }; user?.let { add("-u"); add(it) }; add("--protocol=tcp"); add(parts.database) },
            env = listOfNotNull(password?.let { "MYSQL_PWD" to it }).toMap(), stdin = input,
        )
        DbKind.SQLITE -> ProcessSpec(listOf(tool, parts.database), stdin = input)
        else -> throw IllegalArgumentException("no hay herramienta de restauración para $kind")
    }
}

/**
 * Runs [spec] and passes each output line (stderr merged in unless stdout goes to a file) to [onLine]; [onStart] gets the
 * process so the caller can stop it. Returns the exit code.
 */
fun runProcess(spec: ProcessSpec, onLine: (String) -> Unit, onStart: (Process) -> Unit = {}): Int {
    val pb = ProcessBuilder(spec.args)
    pb.environment().putAll(spec.env)
    spec.stdin?.let { pb.redirectInput(it.toFile()) }
    if (spec.stdout != null) pb.redirectOutput(spec.stdout.toFile()) else pb.redirectErrorStream(true)
    val p = pb.start()
    onStart(p)
    // with stdout in a file only stderr is left to read; with them merged, everything
    val stream = if (spec.stdout != null) p.errorStream else p.inputStream
    stream.bufferedReader().useLines { it.forEach(onLine) }
    return p.waitFor()
}
