package ksqlc.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One tab of an [OverflowTabs] row; [tag] is the test tag of its pill. */
class TabSpec(val title: String, val selected: Boolean, val tag: String, val icon: IconKind?, val onClick: () -> Unit, val onClose: (() -> Unit)? = null)

/**
 * A row of pill tabs that scrolls sideways. When some tab is not fully in view a chevron appears at the end with the
 * list of those tabs; picking one selects it, and the selected tab is always scrolled into view.
 */
@Composable
fun OverflowTabs(tabs: List<TabSpec>, modifier: Modifier = Modifier, height: Dp = 28.dp) {
    val scroll = rememberScrollState()
    val starts = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }      // tab index -> (x, width) inside the scrolled content
    var viewport by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    fun hidden(i: Int) = starts[i]?.let { (x, w) -> x < scroll.value || x + w > scroll.value + viewport } ?: false
    val hiddenTabs = tabs.indices.filter { hidden(it) && viewport > 0 }
    val selected = tabs.indexOfFirst { it.selected }
    LaunchedEffect(selected, starts[selected], viewport) {
        val (x, w) = starts[selected] ?: return@LaunchedEffect
        if (x < scroll.value) scroll.scrollTo(x) else if (x + w > scroll.value + viewport) scroll.scrollTo(x + w - viewport)
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f, fill = false).onSizeChanged { viewport = it.width }.horizontalScroll(scroll)) {
            tabs.forEachIndexed { i, t ->
                PillTab(
                    t.title, t.selected, t.onClick, Modifier.testTag(t.tag).onGloballyPositioned { starts[i] = it.positionInParent().x.toInt() to it.size.width },
                    icon = t.icon, height = height, onClose = t.onClose,
                )
            }
        }
        if (hiddenTabs.isNotEmpty()) Box {
            ToolButton(IconKind.ChevronDown, "Mostrar pestañas ocultas", { menu = true }, Modifier.testTag("tabs-overflow"))
            DropdownMenu(menu, { menu = false }) {
                hiddenTabs.forEach { i ->
                    val t = tabs[i]
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                t.icon?.let { KIcon(it, tint = Ksqlc.colors.textSecondary, size = 14.dp) }
                                Text(t.title, fontSize = 13.sp, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                                t.onClose?.let { close -> ToolButton(IconKind.Close, "Cerrar ${t.title}", { close(); if (hiddenTabs.size <= 1) menu = false }, Modifier.size(18.dp)) }
                            }
                        },
                        modifier = Modifier.testTag("tabs-hidden:$i"),
                        onClick = { menu = false; t.onClick() },
                    )
                }
            }
        }
    }
}
