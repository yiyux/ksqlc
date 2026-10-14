package ksqlc.app.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

val ControlShape = RoundedCornerShape(4.dp)

/** 24dp icon button with hover/pressed/selected states. [description] is also its accessible name. */
@Composable
fun ToolButton(
    icon: IconKind, description: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, selected: Boolean = false, tint: Color? = null,
) {
    val c = Ksqlc.colors
    val src = remember { MutableInteractionSource() }
    val hovered = src.collectIsHoveredAsState().value
    val pressed = src.collectIsPressedAsState().value
    val bg = when {
        !enabled -> Color.Transparent
        pressed || selected -> c.selectionInactive
        hovered -> c.hover
        else -> Color.Transparent
    }
    Box(
        modifier.size(24.dp).clip(ControlShape).background(bg)
            .hoverable(src, enabled)
            .clickable(src, indication = null, enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { KIcon(icon, tint = tint?.takeIf { enabled } ?: if (enabled) c.textSecondary.let { if (hovered || selected) c.text else it } else c.textMuted) }
}

/** Compact text button: [primary] is the filled accent one (OK, Connect), otherwise a bordered neutral one. */
@Composable
fun TextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, primary: Boolean = false) {
    val c = Ksqlc.colors
    val src = remember { MutableInteractionSource() }
    val hovered = src.collectIsHoveredAsState().value
    val bg = when {
        primary && enabled -> if (hovered) c.accent.copy(alpha = 0.9f) else c.accent
        hovered && enabled -> c.hover
        else -> Color.Transparent
    }
    Box(
        modifier.height(24.dp).clip(ControlShape)
            .background(bg, ControlShape)
            .then(if (primary) Modifier else Modifier.border(1.dp, c.border, ControlShape))
            .hoverable(src, enabled).clickable(src, indication = null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, maxLines = 1, fontSize = 13.sp,
            color = when { !enabled -> c.textMuted; primary -> c.onAccent; else -> c.text },
        )
    }
}

/** Bordered single-line field, 24dp tall. The border turns accent-coloured while focused. */
@Composable
fun CompactTextField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    placeholder: String? = null, enabled: Boolean = true, readOnly: Boolean = false, password: Boolean = false,
    testTag: String? = null, textStyle: TextStyle = UiStyle, height: Dp = 24.dp,
) {
    val c = Ksqlc.colors
    val src = remember { MutableInteractionSource() }
    val focused = src.collectIsFocusedAsState().value
    val tagged = if (testTag != null) Modifier.testTag(testTag) else Modifier
    BasicTextField(
        value, onValueChange, tagged.then(modifier).height(height),
        enabled = enabled, readOnly = readOnly, singleLine = true, interactionSource = src,
        textStyle = textStyle.copy(color = if (enabled) c.text else c.textMuted),
        cursorBrush = SolidColor(c.text),
        keyboardOptions = KeyboardOptions.Default,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxSize().clip(ControlShape).background(if (enabled) c.editorBg else c.windowBg)
                    .border(1.dp, if (focused) c.accent else c.border, ControlShape).padding(horizontal = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, fontSize = 13.sp, color = c.textMuted, maxLines = 1)
                inner()
            }
        },
    )
}

/** Small caption above a form field. */
@Composable
fun FieldLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, modifier, fontSize = 12.sp, color = Ksqlc.colors.textSecondary, maxLines = 1)

/** Tool window title row: [title] on the left, [actions] (usually [ToolButton]s) on the right. */
@Composable
fun PanelHeader(title: String, modifier: Modifier = Modifier, height: Dp = 40.dp, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().height(height).padding(start = 12.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Ksqlc.colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** One tab: selected = full-strength text and an accent underline; [onClose] adds a close button. */
@Composable
fun TabItem(title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, onClose: (() -> Unit)? = null, icon: IconKind? = null) {
    val c = Ksqlc.colors
    val src = remember { MutableInteractionSource() }
    val hovered = src.collectIsHoveredAsState().value
    Box(modifier.height(28.dp).background(if (hovered && !selected) c.hover else Color.Transparent).hoverable(src).clickable(src, null, onClick = onClick)) {
        Row(Modifier.fillMaxHeight().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != null) KIcon(icon, tint = if (selected) c.text else c.textSecondary, size = 14.dp)
            Text(title, fontSize = 13.sp, maxLines = 1, color = if (selected) c.text else c.textSecondary)
            if (onClose != null) ToolButton(IconKind.Close, "Cerrar $title", onClose, Modifier.size(18.dp))
        }
        if (selected) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(2.dp).background(c.accent))
    }
}

/** Horizontal strip of [TabItem]s on the header background with a bottom border. */
@Composable
fun TabStrip(modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}, content: @Composable RowScope.() -> Unit) {
    val c = Ksqlc.colors
    Column(modifier.fillMaxWidth().background(c.headerBg)) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), content = content)
            trailing()
        }
        Separator()
    }
}

@Composable
fun Separator(modifier: Modifier = Modifier, vertical: Boolean = false) {
    val c = Ksqlc.colors
    Box(modifier.then(if (vertical) Modifier.fillMaxHeight().width(1.dp) else Modifier.fillMaxWidth().height(1.dp)).background(c.divider))
}

/**
 * Draggable divider with a [hit]-wide grab area, invisible by default (the gap between islands) or a 1dp [line]. [onDrag] gets the pointer delta in dp
 * along the axis (x for a vertical line, y for a horizontal one).
 */
@Composable
fun Splitter(vertical: Boolean, onDrag: (Dp) -> Unit, modifier: Modifier = Modifier, hit: Dp = 5.dp, line: Boolean = false, onDragEnd: () -> Unit = {}) {
    val density = LocalDensity.current
    val state = rememberDraggableState { px -> onDrag(with(density) { px.toDp() }) }
    val cursor = PointerIcon(Cursor.getPredefinedCursor(if (vertical) Cursor.E_RESIZE_CURSOR else Cursor.N_RESIZE_CURSOR))
    val base = if (vertical) Modifier.fillMaxHeight().width(hit) else Modifier.fillMaxWidth().height(hit)
    Box(
        modifier.then(base).pointerHoverIcon(cursor).draggable(state, if (vertical) Orientation.Horizontal else Orientation.Vertical, onDragStopped = { onDragEnd() }),
        contentAlignment = Alignment.Center,
    ) { if (line) Separator(vertical = vertical) }
}

/** Bottom status bar; contents go left-to-right, use a weighted spacer to push items to the right. */
@Composable
fun StatusBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val c = Ksqlc.colors
    Row(modifier.fillMaxWidth().background(c.windowBg).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
}

val IslandShape = RoundedCornerShape(8.dp)

/** A rounded panel on the window background; the building block of the main layout. */
@Composable
fun Island(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.clip(IslandShape).background(Ksqlc.colors.editorBg), content = content)
}

/** Thin overlaid scrollbar styled like the reference (rounded 8dp thumb, no track). */
@Composable
fun scrollbarStyle(): ScrollbarStyle {
    val c = Ksqlc.colors
    return ScrollbarStyle(
        minimalHeight = 24.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 300,
        unhoverColor = c.gridLine.copy(alpha = 0.9f), hoverColor = c.textMuted.copy(alpha = 0.6f),
    )
}

@Composable
fun ListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    VerticalScrollbar(rememberScrollbarAdapter(state), modifier, style = scrollbarStyle())
}

/**
 * Modal layer: dims everything under it and centres [content] in a bordered card. Clicking outside, or [onDismiss],
 * closes it. Used instead of OS dialogs so it themes and tests like the rest of the window.
 */
@Composable
fun ModalOverlay(onDismiss: () -> Unit, modifier: Modifier = Modifier, width: Dp = 700.dp, height: Dp = 460.dp, content: @Composable BoxScope.() -> Unit) {
    val c = Ksqlc.colors
    // pointerInput, not clickable: clickable would merge the whole card into one accessibility node
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).pointerInput(onDismiss) { detectTapGestures(onTap = { onDismiss() }) },
        contentAlignment = Alignment.Center,
    ) {
        // the requested size, but never more than 92% of the window
        Box(
            modifier.size(minOf(width, maxWidth * 0.92f), minOf(height, maxHeight * 0.92f))
                .clip(IslandShape).background(c.editorBg).border(1.dp, c.border, IslandShape).pointerInput(Unit) { detectTapGestures { } },
            content = content,
        )
    }
}

/** 16dp checkbox with an optional [label], sized for dense forms (Material's has a 48dp touch target). */
@Composable
fun KCheckbox(checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier, label: String? = null, enabled: Boolean = true) {
    val c = Ksqlc.colors
    val shape = RoundedCornerShape(3.dp)
    Row(
        modifier.clickable(remember { MutableInteractionSource() }, null, enabled = enabled) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(16.dp).clip(shape).background(if (checked && enabled) c.accent else c.editorBg, shape).border(1.dp, if (checked && enabled) c.accent else c.border, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) androidx.compose.foundation.Canvas(Modifier.size(10.dp)) {
                val w = size.width
                drawLine(c.onAccent, Offset(w * 0.15f, w * 0.5f), Offset(w * 0.4f, w * 0.75f), 1.6.dp.toPx(), StrokeCap.Round)
                drawLine(c.onAccent, Offset(w * 0.4f, w * 0.75f), Offset(w * 0.88f, w * 0.22f), 1.6.dp.toPx(), StrokeCap.Round)
            }
        }
        if (label != null) Text(label, fontSize = 13.sp, color = if (enabled) c.text else c.textMuted)
    }
}

/** Rounded tab: the selected one is filled with a border, the others are plain text. [height] 28dp for editors, 26dp for results. */
@Composable
fun PillTab(
    title: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: IconKind? = null,
    height: Dp = 28.dp, onClose: (() -> Unit)? = null,
) {
    val c = Ksqlc.colors
    val src = remember { MutableInteractionSource() }
    val hovered = src.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.height(height).clip(shape)
            .background(if (selected) c.windowBg else if (hovered) c.hover else Color.Transparent, shape)
            .then(if (selected) Modifier.border(1.dp, c.border, shape) else Modifier)
            .hoverable(src).clickable(src, null, onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) KIcon(icon, tint = if (selected) c.text else c.textSecondary, size = 14.dp)
        Text(title, fontSize = 13.sp, maxLines = 1, color = if (selected) c.text else c.textSecondary)
        if (onClose != null) ToolButton(IconKind.Close, "Cerrar $title", onClose, Modifier.size(18.dp))
    }
}

@Composable
fun VSeparator(modifier: Modifier = Modifier) = Box(modifier.padding(horizontal = 4.dp).width(1.dp).height(16.dp).background(Ksqlc.colors.divider))

/** Accent-coloured text action without a box, for secondary commands such as "Probar conexión". */
@Composable
fun LinkAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = Ksqlc.colors
    val src = remember { MutableInteractionSource() }
    val hovered = src.collectIsHoveredAsState().value
    Text(
        text, modifier.hoverable(src, enabled).clickable(src, null, enabled = enabled, onClick = onClick),
        fontSize = 13.sp, color = if (enabled) c.link else c.textMuted, maxLines = 1,
        textDecoration = if (hovered && enabled) androidx.compose.ui.text.style.TextDecoration.Underline else null,
    )
}

/** Two or more joined options, one selected (white/filled with a border), like "default | URL only". */
@Composable
fun SegmentedControl(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, tag: String = "seg") {
    val c = Ksqlc.colors
    val shape = RoundedCornerShape(6.dp)
    Row(modifier.height(28.dp).clip(shape).border(1.dp, c.border, shape)) {
        options.forEachIndexed { i, label ->
            Box(
                Modifier.fillMaxHeight().background(if (i == selected) c.editorBg else Color.Transparent)
                    .clickable(remember { MutableInteractionSource() }, null) { onSelect(i) }.padding(horizontal = 14.dp).testTag("$tag:$i"),
                contentAlignment = Alignment.Center,
            ) { Text(label, fontSize = 13.sp, color = c.text, maxLines = 1) }
            if (i < options.lastIndex) Separator(vertical = true)
        }
    }
}

/** Closed drop-down: white box with an optional icon, the current value and a chevron. The menu itself is the caller's. */
@Composable
fun DropdownField(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: IconKind? = null, enabled: Boolean = true) {
    val c = Ksqlc.colors
    Row(
        modifier.height(28.dp).clip(ControlShape).background(if (enabled) c.editorBg else c.windowBg, ControlShape)
            .border(1.dp, c.border, ControlShape).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) KIcon(icon, tint = c.accentIcon, size = 14.dp)
        Text(text, Modifier.weight(1f), fontSize = 13.sp, color = if (enabled) c.text else c.textMuted, maxLines = 1)
        KIcon(IconKind.ChevronDown, tint = c.textSecondary, size = 12.dp)
    }
}


/**
 * Small hover card near the pointer: [name] in semibold, then ": [detail]". Appears after a short delay and
 * is a floating popup, so it never changes the layout of what it covers.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun Tip(name: String, detail: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val c = Ksqlc.colors
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            val shape = RoundedCornerShape(6.dp)
            Row(Modifier.clip(shape).background(c.popupBg, shape).border(1.dp, c.border, shape).padding(horizontal = 10.dp, vertical = 6.dp).testTag("tip")) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)); append(name); pop()
                        append(": "); append(detail)
                    },
                    fontSize = 13.sp, color = c.text, maxLines = 1,
                )
            }
        },
        modifier = modifier, delayMillis = 400,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(0.dp, 16.dp)),
        content = content,
    )
}
