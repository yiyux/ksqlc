# Referencia de interfaz (DataGrip 2026.2, New UI)

Medidas tomadas **por observación** de una ventana en ejecución (captura de pantalla analizada con código: colores de píxel y distancias). No se copió código, iconos ni textos. Las capturas originales no están en el proyecto.
Pantalla 1x: 1 px de la captura = 1 dp. Ventana capturada: 1722×1000.

## Estado de la observación
| Vista | Tema oscuro | Tema claro |
|---|---|---|
| Editor de datos de tabla + Database Explorer | ✅ medido | ✅ medido (consola y explorador) |
| Consola SQL (gutter, línea actual, guía de margen) y panel Services | ✅ medido (solo `select 1`: sin colores de cadena/comentario) | ✅ medido (igual) |
| Diálogo «Data Sources and Drivers» | no capturado | ✅ medido |

## Estructura general
- La ventana usa **«islas»**: cada panel (Database Explorer, editor, etc.) es un rectángulo de esquinas redondeadas (radio ≈ **8 dp**) sobre el fondo de la ventana, separado de los vecinos por huecos: **7 dp** al borde izquierdo, **5 dp** entre paneles.
- Barra superior de **43 dp** (ventana, widget de proyecto, acciones a derecha e izquierda; el tinte morado de la captura es el color de proyecto, no forma parte del tema).
- Barra de estado inferior de ≈ **31 dp** sobre el fondo de ventana, texto secundario con migas de pan «Database › conexión › esquema › tables › tabla».
- Las islas terminan a y = 968 de 1000; el resto es barra de estado.

## Database Explorer (isla izquierda, x 7–398 → 392 dp de ancho)
| Elemento | Medida |
|---|---|
| Título «Database Explorer» | franja de **40 dp**, 13 px semibold, color texto `#D1D3D9`, sangría 12 |
| Separadores | 1 dp `#26282C` entre título / barra de iconos / árbol |
| Barra de iconos | **34 dp** de alto, iconos 16 dp de trazo fino, grupos separados por línea vertical |
| Filas del árbol | **24 dp**; chevrón `›` `#84868C`; icono de tabla 16 dp; sangría por nivel ≈ 24 dp |
| Fila seleccionada | fondo `#33353B`, **radio ≈ 4 dp**, inset 13 dp a la izquierda y 28 dp a la derecha (hueco de la scrollbar) |
| Raíz | icono del motor + `usuario@host`, mismo color de texto |

## Editor (isla central) — vista «editor de datos» de una tabla
| Elemento | Medida |
|---|---|
| Pestaña | píldora **28 dp** de alto (y 49–76), 270 dp de ancho, fondo `#26282C`, borde 1 dp `#383B41`, radio ≈ 8; texto seleccionado `#FFFFFF`; icono de tabla + cierre `×` |
| Franja de pestañas | **40 dp** |
| Barra de acciones | **33 dp**; iconos 16 dp; selector «Tx: Auto ⌄»; botones de texto «DDL», «CSV ⌄» |
| Fila de filtro | **29 dp**: «WHERE» y «ORDER BY» en `#868A93` con icono de filtro / orden |
| Cabecera del grid | **26 dp**, fondo `#292A2C`; icono de tipo de columna (azul si es texto, llave si es PK) + nombre `#BCBEC4` + filtro + orden |
| Columna de números de fila | **39 dp**, texto `#4C5058` alineado a la derecha |
| Columnas | ancho por defecto **112 dp** (incluida la línea de 1 dp) |
| Filas | **27 dp** de pitch; líneas de 1 dp `#323438` entre filas y entre columnas; separadores de cabecera `#26282C` |
| Texto del grid | JetBrains Mono ≈ 13 px, `#BCBEC4`; `<null>` en `#7B7E85`; números alineados a la derecha; texto a la izquierda |
| Celda seleccionada | fondo `#284180`; la fila de la selección tiene el número de fila resaltado (`#A1A3AA` sobre `#1F2024`) |
| Scrollbars | pulgar `#3C3D3E`, ≈ 8 dp, redondeado, superpuesto |
| Píldora flotante «230 rows ⌄ ⋮» | abajo a la derecha del grid: 36 dp de alto, fondo `#26282C`, borde 1 dp `#404040`, radio ≈ 8, texto `#D1D3D9` |

## Consola SQL (isla central) y panel Services (isla inferior)
Isla del editor: y 43–484. Isla Services: y 490–968 (hueco de 5 dp). Ambas con las mismas franjas: pestañas 40 dp → separador → barra de acciones 34 dp → separador → contenido.
| Elemento | Medida |
|---|---|
| Pestaña de consola | misma píldora de 28 dp; la activa con borde `#40434A` y fondo `#26282C`; icono del motor + `nombre [conexión]` + `×` |
| Barra de acciones de consola | ▶ ejecutar (verde), ejecutar con plan, historial, ajustes, «Tx: Auto ⌄», stop, selector «Playground ⌄»; a la derecha «esquema actual ⌄» |
| **Gutter** | **63 dp** de ancho (x 404–466), borde derecho 1 dp `#323438`; número de línea alineado a la derecha ≈ 11 dp antes de una columna de iconos (check verde de ejecución OK) |
| Texto del editor | empieza 3 dp tras el gutter; JetBrains Mono 13 px; **altura de línea 22 dp** |
| Línea actual | fondo `#1F2024` en toda la línea (22 dp) |
| Sentencia ejecutada | rectángulo de 1 dp con esquinas pequeñas alrededor de la sentencia, en verde `#4A774C` si salió bien; a su derecha el tiempo en gris `#676D74` («502 ms») |
| Guía de margen derecho | línea vertical 1 dp `#323438` en la columna 120 (x 1406) |
| Marca de éxito (check) | `#639260` en el gutter y arriba a la derecha del editor |
| Panel Services — cabecera | franja 40 dp «Services» semibold; lateral izquierdo de **413 dp** con árbol (Database › conexión › consola/tabla, filas de 24 dp, tiempos en `#73767C`), divisor vertical 1 dp `#26282C` |
| Panel Services — derecha | pestañas «Output» (icono + texto) y «1:integer ×» (píldora de 26 dp con borde `#40434A`), barra de acciones de resultados 33 dp (vista tabla/gráfico, refrescar, historial, stop, anclar, buscar…, «CSV ⌄», exportar/importar, ojo, ajustes) y el grid |
| Píldora flotante de filas | «1 row ⌄ ⋮», igual que en la vista de tabla |
| Barra de estado | izquierda: migas de pan; derecha: `1:9  LF  UTF-8  4 spaces` + iconos de candado/notificaciones, texto `#6F7276`–`#7E8085` |

## Colores — tema oscuro
| Rol | Valor |
|---|---|
| Fondo de ventana (huecos entre islas, barra de estado, píldoras) | `#26282C` |
| Fondo de isla / editor / grid | `#191A1C` |
| Fondo de cabecera de tabla | `#292A2C` |
| Texto principal (UI) | `#D1D3D9` |
| Texto de grid/editor | `#BCBEC4` |
| Texto secundario (hints WHERE/ORDER BY) | `#868A93` |
| Texto de estado / migas | `#6F7276` |
| `<null>` | `#7B7E85` |
| Número de fila | `#4C5058` |
| Chevrón del árbol | `#84868C` |
| Iconos de barra | `#C3C5CB` (normal), `#ABACB2` (secundario) |
| Selección de fila de árbol | `#33353B` |
| Selección de celda del grid | `#284180` |
| Línea del grid | `#323438` |
| Línea de separación de paneles | `#26282C` |
| Borde de píldora/pestaña | `#383B41` / `#404040` |
| Acento (iconos activos) | `#5F88F2` (icono de columna de la cabecera) |
| Línea actual del editor | `#1F2024` |
| Línea de gutter y guía de margen | `#323438` |
| Número de línea (actual / resto) | `#A1A3AA` / `#4C5058` |
| Palabra clave (`select`) | `≈ #CF8E6D` (medida mezclada: `#C79370`) |
| Número | `≈ #2AACB8` (medida mezclada: `#50A7B7`) |
| Tiempo en línea / pista | `#676D74` |
| Éxito (check / contorno de sentencia) | `#639260` / `#4A774C` |
| Cadena / comentario / identificador | **no observados**; provisional `#6AAB73` / `#7A7E85` / `#BCBEC4` |

## Tipografía
- UI: sans-serif geométrica (aspecto de **Inter**), 13 px; títulos en semibold.
- Datos/editor: **JetBrains Mono**, 13 px, interlineado de fila 27 dp en el grid.

## Conclusiones para ksqlc
1. Adoptar el esquema de **islas**: fondo de ventana `#26282C`, paneles `#191A1C` con radio 8 y huecos de 5–7 dp. Esto sustituye al diseño «plano con bordes» que preveía el plan (afecta a `MainWindow`).
2. El tema oscuro de referencia es bastante más oscuro que la paleta provisional (`#1E1F22`/`#2B2D30`): se reajustan los tokens `DarkColors` con los valores de arriba.
3. El editor tiene **gutter propio con borde**, línea actual a todo el ancho, guía de margen en la col. 120 y contorno + tiempo de la sentencia ejecutada.
4. El panel inferior se llama **Services** y lleva un árbol lateral (conexión → consola/tabla con su tiempo) además de las pestañas Output/resultado. ksqlc reproducirá la misma estructura con su salida y sus resultados.
5. Falta observar: colores de cadena/comentario, diálogo Data Source y el tema claro.

## Tema claro (medido)
| Rol | Valor |
|---|---|
| Fondo de ventana, huecos, barra de estado, pestaña activa, fila inactiva | `#E9EAEE` |
| Isla / editor / grid | `#FFFFFF` |
| Barras de acciones (editor y resultados) | `#F7F8F9` (en el oscuro son del color de la isla) |
| Cabecera del grid | `#EFEFEF` |
| Texto (UI, grid) | `#000000` / `#080808` |
| Texto secundario (pestañas inactivas) | `#515151` |
| Iconos de barra | `#6D707D`; chevrón de árbol `#A9ABB5` |
| Contadores / sugerencias | `#73767C`; texto de estado `#9C9EA3` (muy bajo contraste; ksqlc usa `#7C7F86`) |
| Línea del grid, gutter y guía de margen | `#EBECF0` |
| Número de fila / número de línea actual | `#AFB3C1` / `#777A89` |
| Línea actual del editor | `#F5F8FE` |
| Palabra clave | `#1233AF` |
| Éxito (check) / contorno de la sentencia ejecutada | `#67A36F` / `#B8AAFB` (lavanda; en oscuro el contorno es verde) |
| Pulgar de scrollbar | `#C6C6C7` |
| Borde de pestaña activa | `#D1D3D9` |
| Selección enfocada (lista del diálogo) | `#D2DEFC` |

## Diálogo «Data Sources and Drivers» (tema claro, ventana 980×737)
- Ventana propia con barra de título (29 dp) y separador. Fondo `#F7F8F9`; campos blancos con borde `#D1D3D9`, **28 dp** de alto.
- Franja izquierda de iconos (45 dp), lista de data sources (≈ 240 dp) con cabecera «Data Sources» (+ flechas atrás/adelante), barra `+ − duplicar ⚙ exportar`, etiqueta de grupo «Project Data Sources» (`#73767C`) y filas con icono del motor; fila seleccionada `#D2DEFC` redondeada. Al pie «Problems».
- Panel derecho: «Name:» + campo + desplegable «Color» + enlace «Add comment»; pestañas **General · Options · SSH/SSL · Schemas · Advanced** (la activa con fondo `#E4EAFD` y borde `#ACC3FC`) y enlace «Save as Template».
- Formulario (etiquetas a la izquierda, 120 dp): Driver (desplegable con icono), «Connection type» (control segmentado *default / URL only*), Host + Port, Authentication (desplegable), User, Password (`<hidden>`) + «Save: Forever ▾», Database (desplegable), URL con la nota «Overrides settings above».
- Pie: enlace azul `#3A5DB5` «Test Connection» + nombre del driver en gris; barra inferior de 50 dp con **Cancel · Apply · OK** (OK relleno `#456FDC`, 72×28).

## Implementación en ksqlc (estado)
| Elemento | Estado |
|---|---|
| Islas (radio 8, huecos 5–7 dp), barra superior 43 dp, barra de estado 31 dp | ✅ coincide al píxel con la referencia (comprobado midiendo el render) |
| Database Explorer (cabecera 40, barra 34, filas 24, selección redondeada) | ✅ |
| Consola: pestaña píldora, barra de acciones, gutter 63 dp, línea actual, guía en col. 120, contorno y tiempo de la sentencia | ✅ |
| Services: cabecera, árbol lateral, pestañas Output/resultados cerrables | ✅ (árbol de 240 dp; la referencia usaba 413 dp) |
| Grid: cabecera 26, filas 27, columnas 112, líneas `#323438`, selección de celda, ⌘C, píldora de filas | ✅ |
| Diálogo Data Sources (lista, cabecera, pestaña General, Motor, tipo de conexión segmentado, host/puerto, usuario, contraseña, BD, SSL, URL, enlace «Probar conexión», Cancelar/Aplicar/OK) | ✅ sigue la disposición observada; **sin** pestañas Options/SSH/Schemas/Advanced, Color, Add comment, Save as Template ni «Save: Forever» (funciones que ksqlc no tiene) |
| Tema oscuro | ✅ medido |
| Tema claro | ✅ medido (cadena, número y comentario siguen sin observar) |
| Colores de cadena, comentario e identificador | ⚠️ valores habituales de IDE, no observados (aún no hay una consola de muestra con cadenas y comentarios) |
| Tipografía de interfaz | ✅ **Inter 4.1** incluida (OFL), igual que la referencia |
| Iconos | propios, de trazo fino; no son los de JetBrains |

Capturas generadas por los tests en `app/build/snapshots/` (`window-dark.png`, `window-light.png`, `editor-*.png`).

Desviaciones deliberadas: el azul del botón principal es `#3170E8` (contraste 4,55:1 con texto blanco) y no el `#456FDC` observado (4,2:1); el texto de estado en claro es algo más oscuro que el original por legibilidad.


## Editor inteligente (fase 3)
Colores provisionales (no observados en DataGrip; ajustar cuando se midan):

| Token | Oscuro | Claro | Uso |
|---|---|---|---|
| `occurrence` | `#2F3A52` | `#E3EBFC` | otros usos del identificador bajo el cursor |
| `warning` | `#E5B73B` | `#C68A00` | subrayado ondulado, punto del gutter y texto de aviso |
| `popupBg` | `#26282C` | `#FFFFFF` | lista de autocompletado |
| `popupSelection` | `#2F4A8C` | `#D2DEFC` | fila elegida de la lista |

Lista de autocompletado: 340 dp de ancho, filas de 24 dp, hasta 12 visibles, radio 6 dp, borde 1 dp, bajo el cursor. Errores en rojo (`error`), avisos en ámbar; el mensaje del problema bajo el cursor sustituye al texto de la barra de estado.


## Edición de datos (fase 4)
Colores provisionales de las marcas del grid (no observados en DataGrip):

| Token | Oscuro | Claro | Uso |
|---|---|---|---|
| `pendingEdit` | `#243B63` | `#D8E5FF` | celda editada sin enviar |
| `pendingInsert` | `#1F3A2A` | `#DDF2E3` | fila nueva (su número de fila es `+`) |
| `pendingDelete` | `#4A2327` | `#FBDDE0` | fila marcada para borrar (texto tachado) |

Barra de la pestaña de tabla (34 dp): Recargar | `+` `−` Revertir Ver-SQL Enviar | `Tx: Auto ▾` (en manual, Commit y Rollback, y un punto ámbar si hay trabajo sin confirmar) y el recuento «N cambio(s) sin enviar». Los valores por defecto se muestran como `<default>` atenuado, igual que `<null>`.

Selector de fecha (celda de tipo date/time/timestamp en edición): popup de 250 dp bajo la celda (encima si no cabe), radio 8 dp, mismos colores que la lista de autocompletado (`popupBg`, borde `border`). Calendario con semanas desde el lunes, día elegido en `accent`, hoy con contorno; reloj con flechas por hora/minuto/segundo; «Hoy»/«Ahora», «NULL» y «Aceptar» (las fechas sin hora se cierran al elegir el día). El texto de la celda es la única fuente de verdad: el selector lo reescribe y sigue lo que se escriba.


## Historial, transferencia de datos y comparación (fase 5)
Todos son diálogos sobre la ventana (`ModalOverlay`, como el de DDL); ninguno abre una ventana del sistema salvo el selector de archivos nativo (`FileDialog`, botón «…»).

| Diálogo | Tamaño | Contenido |
|---|---|---|
| Historial (⌥⌘E, botón de reloj de la consola) | 980×600 | búsqueda y lista (hora, SQL; en rojo si falló) a la izquierda, SQL completo y duración a la derecha; Insertar en consola, Copiar, Vaciar historial (con confirmación) |
| Exportar datos | 860×590 | dos columnas: a la izquierda origen (solo lectura), extractor (CSV, TSV, JSON, SQL INSERT), «Transponer», «Añadir cabecera de columnas», «Añadir cabecera de filas» (solo CSV/TSV) y archivo de salida con botón de carpeta; a la derecha vista previa con números de línea. Pie: Cancelar, Copiar al portapapeles (tope de 10 000 filas), Exportar a archivo. Sin cabeceras por defecto |
| Importar CSV | 780×600 | archivo, separador, «primera fila es cabecera», vista previa de 6 filas y un desplegable por columna del archivo hacia columna de la tabla o «(omitir)» |
| Dump / Restaurar | 640×440 / 360 | ámbito (tabla, esquema, base), ruta de la herramienta (detectada, editable, recordada), archivo, opciones; la salida va a Output |
| Comparar esquemas | 1180×760 | origen izquierdo fijo y derecho elegible (cualquier data source guardado), lista de objetos con `−` `+` `≠` `=`, DDL en dos columnas con números de línea y ajuste de línea |

Colores provisionales de la comparación (no observados en DataGrip):

| Token | Oscuro | Claro | Uso |
|---|---|---|---|
| `diffAdded` | `#1F3A2A` | `#DDF2E3` | línea solo en el lado derecho |
| `diffRemoved` | `#4A2327` | `#FBDDE0` | línea solo en el lado izquierdo |
| `diffChanged` | `#3A3A24` | `#FCF3CC` | línea distinta en los dos lados |

Capturas en `app/build/snapshots/` (`history-*`, `export-*`, `import-*`, `dump-*`, `diff-*`).


## Tooltips
Al pasar el ratón sobre el nombre de una columna del grid aparece, tras 400 ms y junto al puntero (16 dp más abajo), una tarjeta «**nombre**: tipo» (tipo en minúsculas, como lo informa el driver): fondo `popupBg`, borde `border`, radio 6 dp, texto de 13 sp. Es un popup flotante; no mueve nada.


## Filtro local por columna
Cada cabecera del grid lleva un embudo de 12 dp a la derecha del nombre (gris; azul `accent` si la columna está filtrada). Abre una tarjeta de 250×340 dp bajo el embudo (radio 8 dp, `popupBg`, borde `border`): título «Filtro local de 'col'», buscador con lupa, casilla de cabecera (marca o desmarca lo listado) con «Valor» y «Cantidad», una fila de 24 dp por valor distinto (orden natural: números por valor, NULL al final) y el pie «Marca una casilla para filtrar las filas». Sin nada marcado no hay filtro; marcar valores deja solo las filas que tienen alguno. Varias columnas se combinan con «y». Solo filtra las filas ya cargadas; las filas nuevas siguen visibles y los números de fila no cambian. La píldora de filas muestra «M de N filas». Recargar la tabla borra los filtros.


## Orden por columna
En el extremo derecho de cada cabecera hay un icono de orden de 12 dp: dos chevrones grises (sin orden), flecha arriba azul (`accent`, ASC) o flecha abajo azul (DESC). Cada clic avanza sin orden → ASC → DESC → sin orden; elegir otra columna empieza en ASC. En una pestaña de tabla el orden se hace en el servidor: se reescribe el cuadro ORDER BY (`"col" asc|desc`) y se recarga, con lo que ordena todas las filas y no solo la página cargada (no se permite con cambios sin enviar); recargar a mano o escribir otro ORDER BY quita la flecha. En los resultados de la consola ordena las filas ya cargadas: NULL primero en ASC, números por valor, texto sin distinguir mayúsculas, y las filas nuevas siguen al final. Orden y filtro local se combinan. Las cabeceras llevan, de izquierda a derecha, icono de columna, nombre, embudo del filtro y flecha de orden; un nombre largo se corta con «…» antes que los iconos.

## Pestañas del editor con desbordamiento
La fila de pestañas del editor (consola y tablas abiertas) y la de resultados del panel Services (Output y resultados) se desplazan en horizontal. Cuando alguna pestaña no cabe entera aparece al final un chevron («Mostrar pestañas ocultas») con la lista de las que no se ven, cada una con su icono y su botón de cerrar; elegir una la selecciona y la desplaza a la vista. La pestaña activa siempre se mantiene a la vista.

## Mostrar y ocultar Services
Services se oculta con la ✕ de su cabecera y se vuelve a mostrar con el acceso «Services» del extremo izquierdo de la barra de estado, con ⌘8 o con el botón ▶ de la barra superior. Ejecutar consultas lo muestra de nuevo.
