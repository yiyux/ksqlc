package ksqlc.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** ksqlc's own glyphs, drawn on a 16x16 grid. Deliberately simple; they are not copies of any other product's icons. */
enum class IconKind {
    Database, Schema, Folder, Table, View, MatView, Sequence, Routine, Column, Key, Index, ForeignKey,
    Play, PlayAll, Stop, Refresh, Plus, Minus, Copy, Filter, Gear, ChevronRight, ChevronDown, Close, Contrast, Format, Sort, Revert, Preview, Submit, Check, Rollback, Calendar,
}

@Composable
fun KIcon(kind: IconKind, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 16.dp) {
    Canvas(modifier.size(size)) {
        val u = this.size.minDimension / 16f
        scale(u, Offset.Zero) { drawGlyph(kind, tint) }
    }
}

private const val W = 1.3f
private val line = Stroke(W, cap = StrokeCap.Round, join = StrokeJoin.Round)

private fun DrawScope.seg(c: Color, x1: Float, y1: Float, x2: Float, y2: Float) =
    drawLine(c, Offset(x1, y1), Offset(x2, y2), W, StrokeCap.Round)

private fun DrawScope.poly(c: Color, vararg p: Float, close: Boolean = false, fill: Boolean = false) {
    val path = Path().apply {
        moveTo(p[0], p[1])
        for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
        if (close) close()
    }
    drawPath(path, c, style = if (fill) Fill else line)
}

private fun DrawScope.box(c: Color, x: Float, y: Float, w: Float, h: Float, fill: Boolean = false) =
    drawRect(c, Offset(x, y), Size(w, h), style = if (fill) Fill else line)

private fun DrawScope.drawGlyph(kind: IconKind, c: Color) {
    when (kind) {
        IconKind.Database -> {
            drawOval(c, Offset(3f, 2f), Size(10f, 4f), style = line)
            seg(c, 3f, 4f, 3f, 12f); seg(c, 13f, 4f, 13f, 12f)
            drawArc(c, 0f, 180f, false, Offset(3f, 6f), Size(10f, 4f), style = line)
            drawArc(c, 0f, 180f, false, Offset(3f, 10f), Size(10f, 4f), style = line)
        }
        IconKind.Schema -> { box(c, 2.5f, 2.5f, 4.5f, 4.5f); box(c, 9f, 2.5f, 4.5f, 4.5f); box(c, 2.5f, 9f, 4.5f, 4.5f); box(c, 9f, 9f, 4.5f, 4.5f) }
        IconKind.Folder -> poly(c, 2f, 4f, 6f, 4f, 7.5f, 5.5f, 14f, 5.5f, 14f, 13f, 2f, 13f, close = true)
        IconKind.Table -> {
            box(c, 2f, 3f, 12f, 10f); box(c, 2f, 3f, 12f, 3.5f, fill = true)
            seg(c, 6.5f, 6.5f, 6.5f, 13f); seg(c, 2f, 9.8f, 14f, 9.8f)
        }
        IconKind.View -> {
            drawRect(c, Offset(2f, 3f), Size(12f, 10f), style = Stroke(W, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.2f, 1.6f))))
            seg(c, 2f, 6.5f, 14f, 6.5f); seg(c, 6.5f, 6.5f, 6.5f, 13f)
        }
        IconKind.MatView -> {
            box(c, 2f, 3f, 12f, 10f); box(c, 2f, 3f, 12f, 3.5f, fill = true)
            box(c, 4.5f, 8f, 7f, 3.2f, fill = true)
        }
        IconKind.Sequence -> { box(c, 2f, 10f, 3f, 3f, true); box(c, 6.5f, 7f, 3f, 6f, true); box(c, 11f, 4f, 3f, 9f, true) }
        IconKind.Routine -> {
            val p = Path().apply { moveTo(10.5f, 3f); cubicTo(7f, 2.5f, 7f, 5.5f, 7f, 7f); lineTo(7f, 13f) }
            drawPath(p, c, style = line); seg(c, 4.5f, 7f, 10f, 7f)
        }
        IconKind.Column -> { box(c, 5f, 2.5f, 6f, 11f); seg(c, 5f, 6f, 11f, 6f) }
        IconKind.Key -> {
            drawCircle(c, 2.6f, Offset(5.2f, 8f), style = line)
            seg(c, 7.8f, 8f, 14f, 8f); seg(c, 12f, 8f, 12f, 10.5f); seg(c, 14f, 8f, 14f, 10f)
        }
        IconKind.Index -> { seg(c, 3f, 4f, 13f, 4f); seg(c, 3f, 8f, 10f, 8f); seg(c, 3f, 12f, 7f, 12f) }
        IconKind.ForeignKey -> { seg(c, 2f, 8f, 12.5f, 8f); poly(c, 9.5f, 5f, 12.5f, 8f, 9.5f, 11f) }
        IconKind.Play -> poly(c, 4f, 2.5f, 13f, 8f, 4f, 13.5f, close = true, fill = true)
        IconKind.PlayAll -> {
            poly(c, 1.5f, 3f, 7.5f, 8f, 1.5f, 13f, close = true, fill = true)
            poly(c, 8f, 3f, 14.5f, 8f, 8f, 13f, close = true, fill = true)
        }
        IconKind.Stop -> box(c, 3.5f, 3.5f, 9f, 9f, fill = true)
        IconKind.Refresh -> {
            drawArc(c, -40f, 280f, false, Offset(3f, 3f), Size(10f, 10f), style = line)
            poly(c, 9.5f, 1.8f, 12.3f, 4.2f, 8.8f, 5f)
        }
        IconKind.Plus -> { seg(c, 8f, 3f, 8f, 13f); seg(c, 3f, 8f, 13f, 8f) }
        IconKind.Minus -> seg(c, 3f, 8f, 13f, 8f)
        IconKind.Revert -> { poly(c, 6f, 3f, 3f, 6f, 6f, 9f); drawArc(c, -90f, 180f, false, Offset(3.5f, 4.5f), Size(9f, 7.5f), style = line); seg(c, 3f, 6f, 8f, 6f) }
        IconKind.Preview -> { drawOval(c, Offset(1.5f, 4.5f), Size(13f, 7f), style = line); drawCircle(c, 2f, Offset(8f, 8f), style = line) }
        IconKind.Submit -> { seg(c, 8f, 11f, 8f, 3f); poly(c, 4.8f, 6.2f, 8f, 3f, 11.2f, 6.2f); seg(c, 3f, 13f, 13f, 13f) }
        IconKind.Calendar -> { box(c, 2.5f, 3.5f, 11f, 10f); seg(c, 2.5f, 6.5f, 13.5f, 6.5f); seg(c, 5.5f, 2f, 5.5f, 5f); seg(c, 10.5f, 2f, 10.5f, 5f) }
        IconKind.Check -> poly(c, 3f, 8.5f, 6.5f, 12f, 13f, 4.5f)
        IconKind.Rollback -> { drawArc(c, 20f, 250f, false, Offset(3f, 3f), Size(10f, 10f), style = line); poly(c, 2.5f, 2.5f, 3f, 6.2f, 6.8f, 5.6f) }
        IconKind.Sort -> { seg(c, 2.5f, 4.5f, 13.5f, 4.5f); seg(c, 2.5f, 8f, 10f, 8f); seg(c, 2.5f, 11.5f, 6.5f, 11.5f) }
        IconKind.Format -> { seg(c, 2.5f, 3.5f, 13.5f, 3.5f); seg(c, 5.5f, 6.8f, 13.5f, 6.8f); seg(c, 5.5f, 10.1f, 11f, 10.1f); seg(c, 2.5f, 13.4f, 9f, 13.4f) }
        IconKind.Copy -> { box(c, 5.5f, 5.5f, 8f, 8f); poly(c, 3f, 10f, 3f, 2.5f, 10.5f, 2.5f) }
        IconKind.Filter -> poly(c, 2f, 3f, 14f, 3f, 9.5f, 8.5f, 9.5f, 13f, 6.5f, 11.5f, 6.5f, 8.5f, close = true)
        IconKind.Gear -> {
            drawCircle(c, 2.4f, Offset(8f, 8f), style = line)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val (cs, sn) = Math.cos(a).toFloat() to Math.sin(a).toFloat()
                seg(c, 8f + cs * 4.6f, 8f + sn * 4.6f, 8f + cs * 6.4f, 8f + sn * 6.4f)
            }
            drawCircle(c, 4.6f, Offset(8f, 8f), style = line)
        }
        IconKind.ChevronRight -> poly(c, 6f, 3.5f, 10.5f, 8f, 6f, 12.5f)
        IconKind.ChevronDown -> poly(c, 3.5f, 6f, 8f, 10.5f, 12.5f, 6f)
        IconKind.Close -> { seg(c, 4f, 4f, 12f, 12f); seg(c, 12f, 4f, 4f, 12f) }
        IconKind.Contrast -> {
            drawCircle(c, 5.2f, Offset(8f, 8f), style = line)
            drawArc(c, 90f, 180f, true, Offset(2.8f, 2.8f), Size(10.4f, 10.4f), style = Fill)
        }
    }
}
