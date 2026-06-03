package io.github.liki4.peek.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * iGPSPORT-style heart-rate curve.
 *
 * - Color-codes the line by zone (Z1 grey → Z5 red), switching color where
 *   the sample crosses a zone boundary.
 * - Dynamic Y axis: pinned to the visible window's actual min/max (± a small
 *   pad), so a steady ride zooms in and a noisy interval zooms out.
 * - Visible window: last [windowSeconds] samples, or the full series if null.
 *
 * Samples are bpm ints with 0 meaning "no data" (e.g. HR strap dropout) —
 * those points break the line, leaving a gap rather than dropping to zero.
 */
@Composable
fun HrChart(
    samples: IntArray,
    maxHr: Int,
    windowSeconds: Int?,
    modifier: Modifier = Modifier,
    showAxisLabels: Boolean = true,
) {
    val visible = sliceVisible(samples, windowSeconds)

    if (visible.isEmpty() || visible.all { it == 0 }) {
        EmptyState(modifier = modifier)
        return
    }

    val nonZero = visible.filter { it > 0 }
    val rawMin = nonZero.min()
    val rawMax = nonZero.max()
    // Pad ±5 bpm, then snap to 5-bpm grid lines, and ensure ≥ 20 bpm span so
    // a flat ride doesn't render as a single horizontal line on the top edge.
    val span = max(20, rawMax - rawMin + 10)
    var yMin = ((rawMin - 5) / 5) * 5
    var yMax = yMin + span
    yMin = max(40, yMin)
    yMax = min(230, max(yMax, yMin + span))

    Column(modifier = modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            if (showAxisLabels) {
                Column(
                    modifier = Modifier.fillMaxHeight().padding(end = 4.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                ) {
                    Text("$yMax", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
            Canvas(
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 2.dp),
            ) {
                val w = size.width
                val h = size.height
                val n = visible.size
                if (n < 2) return@Canvas

                fun yFor(bpm: Int): Float {
                    val frac = (bpm - yMin).toFloat() / (yMax - yMin).toFloat()
                    return h - frac.coerceIn(0f, 1f) * h
                }
                fun xFor(i: Int): Float = if (n == 1) 0f else w * i / (n - 1).toFloat()

                // Build line segments split by zone changes.
                // Walk samples; whenever the current sample's zone differs from
                // the previous, close the current segment and start a new one.
                val segments = mutableListOf<Triple<Color, Path, Path>>() // line path, fill path
                var curColor: Color? = null
                var curLine: Path? = null
                var curFill: Path? = null
                var lastX = 0f
                var lastY = 0f
                var lastValid = false

                for (i in 0 until n) {
                    val bpm = visible[i]
                    if (bpm == 0) {
                        // Gap: close current segment so the line breaks here.
                        curLine?.let { line ->
                            curFill!!.lineTo(lastX, h)
                            curFill.close()
                            segments += Triple(curColor!!, line, curFill)
                        }
                        curLine = null
                        curFill = null
                        curColor = null
                        lastValid = false
                        continue
                    }
                    val x = xFor(i)
                    val y = yFor(bpm)
                    val zoneColor = zoneColorFor(bpm, maxHr)

                    if (!lastValid) {
                        curColor = zoneColor
                        curLine = Path().apply { moveTo(x, y) }
                        curFill = Path().apply { moveTo(x, h); lineTo(x, y) }
                    } else if (zoneColor != curColor) {
                        // Close old segment at this boundary, then open a new
                        // one starting at the same (x, y) so the line is
                        // continuous but colored by the new zone.
                        curLine!!.lineTo(x, y)
                        curFill!!.lineTo(x, y)
                        curFill.lineTo(x, h)
                        curFill.close()
                        segments += Triple(curColor!!, curLine, curFill)

                        curColor = zoneColor
                        curLine = Path().apply { moveTo(x, y) }
                        curFill = Path().apply { moveTo(x, h); lineTo(x, y) }
                    } else {
                        curLine!!.lineTo(x, y)
                        curFill!!.lineTo(x, y)
                    }
                    lastX = x; lastY = y; lastValid = true
                }
                // Close trailing segment.
                curLine?.let { line ->
                    curFill!!.lineTo(lastX, h)
                    curFill.close()
                    segments += Triple(curColor!!, line, curFill)
                }

                // Draw fills first (under) then strokes (over).
                for ((color, _, fill) in segments) {
                    drawPath(
                        path = fill,
                        brush = Brush.verticalGradient(
                            colors = listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0.02f)),
                        ),
                    )
                }
                for ((color, line, _) in segments) {
                    drawPath(
                        path = line,
                        color = color,
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        if (showAxisLabels) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
            ) {
                Text("$yMin bpm", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp)
                Text(
                    text = windowLabel(visible.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp,
                )
            }
        }
    }
}

/** 5-zone color palette aligned with HrZoneBar in RideScreen. */
fun zoneColorFor(bpm: Int, maxHr: Int): Color {
    val pct = bpm.toFloat() / maxHr
    return when {
        pct < 0.60f -> Color(0xFFB0B0B0)
        pct < 0.70f -> Color(0xFF4DA6FF)
        pct < 0.80f -> Color(0xFF40C26C)
        pct < 0.90f -> Color(0xFFFF8A24)
        else        -> Color(0xFFE53935)
    }
}

private fun sliceVisible(samples: IntArray, windowSeconds: Int?): IntArray {
    if (windowSeconds == null || samples.size <= windowSeconds) return samples
    return samples.copyOfRange(samples.size - windowSeconds, samples.size)
}

private fun windowLabel(n: Int): String {
    val m = n / 60
    val s = n % 60
    return if (m > 0) "${m}m${if (s > 0) "${s}s" else ""}" else "${s}s"
}

@Composable
private fun EmptyState(modifier: Modifier) {
    Box(
        modifier = modifier.fillMaxSize()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "等待心率数据…",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
