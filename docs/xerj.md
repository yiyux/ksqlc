# XERJ en este proyecto

XERJ es el motor de búsqueda que usan los agentes (Claude Code y similares) para buscar código y guardar memoria del proyecto. **Hay que instalarlo y habilitarlo antes de trabajar con un agente**; sin él, las reglas de `AGENTS.md` no se pueden cumplir. La aplicación y su compilación no lo necesitan.

Fuente: https://github.com/xerj-org/xerj (README). Lo marcado como «comprobado» se probó en este proyecto con `v1.0.0-rc.96`.

## 1. Instalar

Un único binario estático, sin JVM ni dependencias, para Linux, macOS y Windows (x86-64 y arm64). El instalador lo deja en `~/.local/bin`.

```
curl -fsSL https://xerj.org/get | sh          # Linux y macOS
irm https://xerj.org/get.ps1 | iex            # Windows (PowerShell)
```

Esos comandos ejecutan un script descargado: léelo antes si no te fías. Para compilar desde el código fuente hace falta Rust estable:

```
git clone https://github.com/xerj-org/xerj
cd xerj/engine
cargo build --release -p xerj-server
```

Comprobar: `command -v xerj` y `xerj --version`. Para equipos sin red, el README remite a `air-gapped-deployment.md` de su repositorio. Desinstalar y fijar versiones no está documentado.

## 2. Arrancar el servidor

El servidor escucha por defecto en `http://localhost:9200` (compatible con Elasticsearch), solo en `127.0.0.1`.

```
xerj --data-dir ./data               # con autenticación (por defecto)
xerj --insecure --data-dir ./data    # solo desarrollo local: sin TLS ni clave
```

Con autenticación, la clave se crea en el primer arranque en `<data_dir>/admin.key`; sin ella las peticiones dan `401`:

```
export XERJ_API_KEY="$(cat ./data/admin.key)"
```

Comprobar que responde: `curl localhost:9200/`. Más sobre claves y el namespace reservado `.xerj-memory-*`: `docs/SECURITY_MODEL.md` en el repositorio de XERJ.

## 3. Habilitar el servidor MCP

**Automático:** `xerj init` conecta XERJ con Claude Code o Cursor (MCP y skill) en un solo comando.

**Manual:** `xerj mcp` es un servidor MCP por stdio que reenvía a `XERJ_URL` (por defecto `http://localhost:9200`); **no arranca el servidor**, así que haz antes el paso 2. `.mcp.json` en la raíz del repositorio lo registra para Claude Code:

```json
{ "mcpServers": { "xerj": { "command": "/ruta/absoluta/a/xerj", "args": ["mcp"], "env": { "XERJ_URL": "http://localhost:9200" } } } }
```

- `command` debe ser una ruta absoluta (`command -v xerj`): las aplicaciones de escritorio no heredan el `PATH` de tu shell. Es distinta en cada máquina, así que `.mcp.json` hay que ajustarlo (aquí no está en el repositorio).
- Si el servidor lleva clave, añade en `env` `"XERJ_AUTH": "ApiKey <clave>"`.
- Reinicia Claude Code y aprueba el servidor `xerj` si lo pide. `xerj mcp --help` imprime el bloque de configuración.

Sin este paso no existen las herramientas `xerj_memory_*` ni `xerj_search` (son 13 en total, entre ellas `xerj_semantic_search`, `xerj_vector_search`, `xerj_hybrid_search`, `xerj_map`, `xerj_plan` y `xerj_code_search`).

## 4. Indexar el repositorio

```
xerj autoindex /ruta/a/ksqlc          # con clave: --api-key <clave> o la variable XERJ_API_KEY
xerj autoindex map                    # ver qué se indexó
```

Detecta el tipo de cada archivo y crea un índice por conjunto de datos; el código se analiza con tree-sitter, así que se conservan símbolos y números de línea. Es incremental (los archivos idénticos se saltan), por lo que se puede repetir tras cambios grandes. Es necesario para que `xerj def` y `xerj search` devuelvan algo.

## Uso (reglas de `AGENTS.md`)

- **Buscar código:** antes de tocar código poco conocido, `xerj def "<símbolo>"` (definición con `archivo:línea` y firma) o `xerj search "<palabras>"` (pasajes ordenados con `archivo:línea`). Citar `archivo:línea` de lo que se use. `xerj gain` muestra lo que XERJ ha ahorrado.
- **Memoria, namespace `ksqlc`:** al empezar una tarea, `xerj_memory_recall` con la descripción de la tarea. Un hecho duradero se guarda con `xerj_memory_store` y `metadata {"kind": "decision|convention|gotcha|preference"}`. La API es `/_memory/{namespace}`; el recall puede ser por texto, por significado (`semantic`) o híbrido.
- **Hechos que dejan de ser ciertos:** se olvidan, no se contradicen con otro. No hay herramienta MCP para borrar y el README no documenta el borrado. Comprobado en `v1.0.0-rc.96` con el servidor en marcha:

  ```
  curl -X DELETE localhost:9200/_memory/ksqlc/<id>
  ```

  El `id` sale de `xerj_memory_store` o de un recall; la respuesta trae `"forgotten":true`. Con autenticación, añade la cabecera `Authorization: ApiKey <clave>`.

## Si algo falla

- `xerj_memory_*` no aparece como herramienta: falta el paso 3 o hay que reiniciar Claude Code.
- Error de conexión o `401`: el servidor (paso 2) no está arrancado, `XERJ_URL` apunta a otro puerto o falta la clave.
- `xerj search` no devuelve resultados: falta el paso 4.
- ¿Un fallo o algo confuso de XERJ? Se informa en https://github.com/xerj-org/xerj/issues; `xerj feedback` prepara el informe.
