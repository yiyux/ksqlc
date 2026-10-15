package ksqlc.app

import androidx.compose.ui.graphics.Color
import ksqlc.core.DataSource

/** The colours a connection can be marked with, by the key that is saved with it. */
val CONNECTION_COLORS = linkedMapOf(
    "rojo" to Color(0xFFE5534B), "naranja" to Color(0xFFE08A2E), "amarillo" to Color(0xFFD4B02A),
    "verde" to Color(0xFF4CAF6A), "azul" to Color(0xFF3F7FE0), "violeta" to Color(0xFF9B6BDF), "gris" to Color(0xFF8A8F98),
)

sealed interface ConnectionRow {
    data class Folder(val name: String, val open: Boolean, val count: Int) : ConnectionRow
    data class Item(val ds: DataSource, val inFolder: Boolean) : ConnectionRow
}

/** The saved connections as the explorer lists them: folders first (by name, a closed one without its connections), then the ones with no folder. */
fun connectionRows(saved: List<DataSource>, closed: Set<String>): List<ConnectionRow> = buildList {
    val byFolder = saved.groupBy { it.folder.trim() }
    for ((name, items) in byFolder.filterKeys { it.isNotEmpty() }.toSortedMap(String.CASE_INSENSITIVE_ORDER)) {
        add(ConnectionRow.Folder(name, name !in closed, items.size))
        if (name !in closed) items.forEach { add(ConnectionRow.Item(it, true)) }
    }
    byFolder[""]?.forEach { add(ConnectionRow.Item(it, false)) }
}

/** The icon colour of a connection: its own colour when it has one, else the accent when active and muted when not. */
@androidx.compose.runtime.Composable
fun connectionTint(color: String?, active: Boolean): Color =
    CONNECTION_COLORS[color] ?: if (active) ksqlc.app.ui.Ksqlc.colors.accentIcon else ksqlc.app.ui.Ksqlc.colors.textSecondary
