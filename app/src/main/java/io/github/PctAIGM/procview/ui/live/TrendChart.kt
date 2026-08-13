package io.github.PctAIGM.procview.ui.live

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.PctAIGM.procview.R
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun TrendCard(
    @StringRes titleRes: Int,
    currentValue: String,
    points: List<Float?>,
    color: Color,
    modifier: Modifier = Modifier,
    fixedMaximum: Float? = null,
    cursorFraction: Float? = null,
    highlightedRegions: List<Boolean> = emptyList(),
    highlightedRegionDescription: String? = null,
) {
    val title = stringResource(titleRes)
    val valid = points.filterNotNull()
    val minimum = valid.minOrNull()
    val maximum = valid.maxOrNull()
    val trend = stringResource(trendDirection(points).stringResource)
    val metricSummary = if (valid.isEmpty()) {
        stringResource(R.string.chart_no_data)
    } else {
        stringResource(
            R.string.chart_accessibility_summary,
            currentValue,
            formatChartValue(minimum),
            formatChartValue(maximum),
            trend,
        )
    }
    val hasHighlightedRegion = highlightedRegions.any { it }
    val summary = listOfNotNull(
        metricSummary,
        highlightedRegionDescription?.takeIf { hasHighlightedRegion },
    ).joinToString(" ")
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = currentValue,
                    modifier = Modifier.weight(1.35f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.End,
                )
            }
            Sparkline(
                points = points,
                color = color,
                fixedMaximum = fixedMaximum,
                cursorFraction = cursorFraction,
                highlightedRegions = highlightedRegions,
                contentDescription = "$title. $summary",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal data class TrendSeries(
    val label: String,
    val points: List<Float?>,
    val color: Color,
)

@Composable
internal fun MultiTrendCard(
    @StringRes titleRes: Int,
    series: List<TrendSeries>,
    modifier: Modifier = Modifier,
    fixedMaximum: Float? = null,
    cursorFraction: Float? = null,
) {
    val title = stringResource(titleRes)
    val rising = stringResource(R.string.chart_trend_rising)
    val falling = stringResource(R.string.chart_trend_falling)
    val steady = stringResource(R.string.chart_trend_steady)
    val unknown = stringResource(R.string.chart_trend_unknown)
    val descriptions = series.joinToString(separator = "; ") { item ->
        val valid = item.points.filterNotNull()
        val current = selectedPoint(item.points, cursorFraction)
        "${item.label}: ${formatChartValue(current)}, ${formatChartValue(valid.minOrNull())}–" +
            "${formatChartValue(valid.maxOrNull())}, ${trendDirection(item.points).label(
                rising = rising,
                falling = falling,
                steady = steady,
                unknown = unknown,
            )}"
    }
    val summary = descriptions.ifBlank { stringResource(R.string.chart_no_data) }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            series.forEach { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "●",
                            modifier = Modifier.clearAndSetSemantics {},
                            style = MaterialTheme.typography.bodySmall,
                            color = item.color,
                        )
                        Text(
                            item.label,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        formatChartValue(selectedPoint(item.points, cursorFraction)),
                        modifier = Modifier.weight(0.55f),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.End,
                    )
                }
            }
            MultiSparkline(
                series = series,
                fixedMaximum = fixedMaximum,
                cursorFraction = cursorFraction,
                contentDescription = "$title. $summary",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MultiSparkline(
    series: List<TrendSeries>,
    fixedMaximum: Float?,
    cursorFraction: Float?,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        repeat(3) { index ->
            val y = size.height * index / 2f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
        val allPoints = series.flatMap { it.points }.filterNotNull()
        if (allPoints.isEmpty()) return@Canvas
        val maximum = (fixedMaximum ?: allPoints.maxOrNull() ?: 0f).coerceAtLeast(1f)
        series.forEach { item ->
            if (item.points.size < 2) return@forEach
            val path = Path()
            var drawing = false
            item.points.forEachIndexed { index, point ->
                if (point == null) {
                    drawing = false
                } else {
                    val x = size.width * index / item.points.lastIndex.coerceAtLeast(1).toFloat()
                    val y = size.height * (1f - (point / maximum).coerceIn(0f, 1f))
                    if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                    drawing = true
                }
            }
            drawPath(
                path = path,
                color = item.color,
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
        cursorFraction?.let { fraction ->
            val x = size.width * fraction.coerceIn(0f, 1f)
            drawLine(
                color = gridColor.copy(alpha = 0.85f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

@Composable
private fun Sparkline(
    points: List<Float?>,
    color: Color,
    fixedMaximum: Float?,
    cursorFraction: Float?,
    highlightedRegions: List<Boolean>,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)
    val highlightedColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f)
    Canvas(modifier = modifier.semantics { this.contentDescription = contentDescription }) {
        drawHighlightedRegions(highlightedRegions, highlightedColor)
        repeat(3) { index ->
            val y = size.height * index / 2f
            drawLine(
                color = gridColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
        }
        if (points.size < 2) return@Canvas
        val available = points.filterNotNull()
        if (available.isEmpty()) return@Canvas
        val maximum = (fixedMaximum ?: available.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val path = Path()
        var drawing = false
        points.forEachIndexed { index, point ->
            if (point == null) {
                drawing = false
            } else {
                val x = size.width * index / (points.lastIndex.coerceAtLeast(1)).toFloat()
                val y = size.height * (1f - (point / maximum).coerceIn(0f, 1f))
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
        )
        cursorFraction?.let { fraction ->
            val x = size.width * fraction.coerceIn(0f, 1f)
            drawLine(
                color = color.copy(alpha = 0.55f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1.5.dp.toPx(),
            )
        }
    }
}

private fun DrawScope.drawHighlightedRegions(regions: List<Boolean>, color: Color) {
    if (regions.isEmpty()) return
    val regionWidth = size.width / regions.size.toFloat()
    regions.forEachIndexed { index, highlighted ->
        if (highlighted) {
            drawRect(
                color = color,
                topLeft = Offset(regionWidth * index, 0f),
                size = Size(regionWidth + 1f, size.height),
            )
        }
    }
}

private fun selectedPoint(points: List<Float?>, cursorFraction: Float?): Float? {
    if (points.isEmpty()) return null
    if (cursorFraction == null) return points.lastOrNull { it != null }
    val index = (cursorFraction.coerceIn(0f, 1f) * points.lastIndex.toFloat())
        .roundToInt()
    return points.getOrNull(index)
}

private fun formatChartValue(value: Float?): String = value?.let {
    if (it >= 100f) "%.0f".format(it) else "%.1f".format(it)
} ?: "—"

private enum class TrendDirection(val stringResource: Int) {
    RISING(R.string.chart_trend_rising),
    FALLING(R.string.chart_trend_falling),
    STEADY(R.string.chart_trend_steady),
    UNKNOWN(R.string.chart_trend_unknown),
    ;

    fun label(rising: String, falling: String, steady: String, unknown: String): String = when (this) {
        RISING -> rising
        FALLING -> falling
        STEADY -> steady
        UNKNOWN -> unknown
    }
}

private fun trendDirection(points: List<Float?>): TrendDirection {
    val valid = points.filterNotNull()
    if (valid.size < 2) return TrendDirection.UNKNOWN
    val first = valid.first()
    val last = valid.last()
    val tolerance = maxOf(0.1f, maxOf(abs(first), abs(last), 1f) * 0.01f)
    return when {
        last - first > tolerance -> TrendDirection.RISING
        first - last > tolerance -> TrendDirection.FALLING
        else -> TrendDirection.STEADY
    }
}
