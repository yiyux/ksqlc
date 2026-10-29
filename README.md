# ksqlc (karl-sql-client)

English | [Español](README.es.md)

Cross-platform desktop SQL client (macOS, Linux and Windows), written in Kotlin with Compose Desktop. Its interface and features are inspired by DataGrip; the code is original.

![ksqlc: database explorer, SQL console and services panel](docs/img/screenshot.png)

## Engines

PostgreSQL, MySQL, MariaDB, SQLite, SQL Server, Oracle, Apache Cassandra and Trino. The JDBC drivers ship inside the application; there is nothing to install.

## Features

- **Several data sources and several consoles at once**, with folders, colors, read-only mode and Auto/Manual transactions per connection.
- **SSH tunnel and SSL/TLS**: passwords and passphrases go in the system keychain, never in `connections.json`.
- **Interface language**: Spanish or English (US). The app asks on first launch and remembers the choice; change it in **Settings → Interface** (applied on restart).
- **Protected passwords**: in **Settings** (button in the top bar) you choose where they are stored: the system keychain (default), a file encrypted with AES-256-GCM and a master password requested when the app starts, or memory only (forgotten on restart). Switching options moves the passwords already saved.
- **Database Explorer**: tree of schemas, tables, columns, indexes and foreign keys; filter, search and automatic refresh.
- **SQL console and editor**: run a statement, the selection or a script; autocompletion, go to declaration, `:name` parameters, execution plan, per-connection history.
- **Results and data**: result tabs with pagination (an error while loading more rows is shown in the status bar), cell editing with a DML preview, resizable columns.
- **DDL**: view, copy and modify tables; drop, truncate and run routines.
- **Import and export**: CSV, TSV, JSON and SQL `INSERT`, with all the rows of the query (read in chunks in the background, with a progress indicator and cancellation); CSV import with column mapping; native dump and restore (`pg_dump`, `mysqldump`, `sqlite3`).
- **Compare and copy**: schema diff, table content diff with "Sync right", and copy a table to another data source.

Details and the status of each feature are in [`docs/spec/feature-inventory.md`](docs/spec/feature-inventory.md).

## Run from source

Requires a JDK 21. `run.sh` looks for one (`KSQLC_JAVA_HOME`, `JAVA_HOME`, SDKMAN or macOS `java_home`) and launches Gradle with it:

```
./run.sh          # opens the application
./run.sh test     # tests (integration tests use Testcontainers and are skipped without Docker)
```

Data sources are stored in `~/.ksqlc/`.

## Building executables

`jpackage` cannot cross-compile: each operating system builds its own packages. They bundle a JRE, so end users do not need to install Java.

| System | Script | Output in `dist/` |
|---|---|---|
| macOS | `./build.sh` | `.dmg` and portable zip |
| Linux | `./build.sh` | `.deb` and portable zip |
| Windows | `.\build.ps1` (JDK 21 in `JAVA_HOME`) | `.msi` and portable zip |

The `.msi` needs [WiX 3](https://github.com/wixtoolset/wix3/releases) (`candle` and `light`) on the `PATH`. WiX validates the package with the Windows Installer service, which does not work in an SSH session (`light.exe` fails with code 216): to build on a remote machine use `.\build-msi.ps1 [-JavaHome path] [-WixDir path]`, which runs `build.ps1` in the user's open desktop session (RDP or console) and returns the log and the exit code.

The [`release.yml`](.github/workflows/release.yml) workflow builds all three at once on GitHub Actions: it is started manually (the packages are kept as artifacts of the run) or by pushing a `v*` tag, which also publishes a Release with all the files. The tag must match `packageVersion` in `app/build.gradle.kts` (`v1.2.0` ↔ `"1.2.0"`); otherwise the workflow fails. The installers are not signed, so macOS (Gatekeeper) and Windows (SmartScreen) will show a warning when opening them.

## Structure

| Module | Contents |
|---|---|
| `core` | Common model: data sources, results, schema; UI translation (`tr`) |
| `sql` | SQL analysis |
| `drivers` | JDBC connections, introspection and per-engine dialects |
| `storage` | Connections, settings, history and keychain |
| `app` | User interface (Compose Desktop) |

## License

GNU GPL v3. See [`LICENSE`](LICENSE). The source code of each version is that of the matching tag in this repository.

### Third-party licenses

The installers include, unmodified, the JDBC drivers for SQLite, PostgreSQL, MySQL, MariaDB, SQL Server, Oracle, Cassandra and Trino, each under its own license (Oracle's is proprietary, with permission for free redistribution). The full list of components and the license texts are in [`packaging/resources/common/`](packaging/resources/common/THIRD-PARTY-NOTICES.md) and also inside each package.
