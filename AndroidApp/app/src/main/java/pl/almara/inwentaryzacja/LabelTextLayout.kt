package pl.almara.inwentaryzacja

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class LabelTextPoint(val x: Double, val y: Double)
data class LabelTextElement(val text: String, val confidence: Double?)
data class LabelTextLine(val text: String, val angle: Double, val corners: List<LabelTextPoint>,
    val confidence: Double? = null, val elements: List<LabelTextElement> = emptyList())
data class LabelTextResult(val text: String, val words: List<LabelConfidence>, val lines: List<LabelConfidence>)

object LabelTextLayout {
    private data class Line(val source: LabelTextLine, val left: Double, val top: Double, val bottom: Double) {
        val center get() = (top + bottom) / 2
        val height get() = bottom - top
    }

    fun read(lines: List<LabelTextLine>): String = layout(lines).text

    fun layout(lines: List<LabelTextLine>): LabelTextResult {
        if (lines.isEmpty()) return LabelTextResult("", emptyList(), emptyList())
        fun nearby(first: Double, second: Double) = abs((first - second + 540) % 360 - 180) <= 20
        val dominant = lines.maxBy { candidate ->
            lines.filter { nearby(candidate.angle, it.angle) }.sumOf { it.text.length }
        }.angle
        val aligned = lines.filter { nearby(dominant, it.angle) }
        val angle = atan2(aligned.sumOf { sin(Math.toRadians(it.angle)) * it.text.length },
            aligned.sumOf { cos(Math.toRadians(it.angle)) * it.text.length })
        val cosine = cos(angle)
        val sine = sin(angle)
        val upright = lines.map { line ->
            val points = line.corners.map { LabelTextPoint(it.x * cosine + it.y * sine, -it.x * sine + it.y * cosine) }
            Line(line, points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.y })
        }
        val rows = mutableListOf<MutableList<Line>>()
        for (line in upright.sortedBy { it.top }) {
            val row = rows.lastOrNull()
            val previous = row?.firstOrNull()
            if (previous != null && abs(line.center - previous.center) <= minOf(line.height, previous.height) / 2)
                row.add(line)
            else rows.add(mutableListOf(line))
        }
        val text = StringBuilder()
        val words = mutableListOf<LabelConfidence>()
        val lineScores = mutableListOf<LabelConfidence>()
        for ((rowIndex, row) in rows.withIndex()) {
            if (rowIndex > 0) text.append('\n')
            for ((index, line) in row.sortedBy { it.left }.withIndex()) {
                if (index > 0) text.append(' ')
                val start = text.length
                val source = line.source
                text.append(source.text)
                source.confidence?.takeIf { source.text.isNotEmpty() && it.isFinite() && it in 0.0..1.0 }?.let {
                    lineScores.add(LabelConfidence(start, text.length, it))
                }
                var cursor = 0
                for (element in source.elements) {
                    val offset = source.text.indexOf(element.text, cursor)
                    if (offset < 0 || element.text.isEmpty()) continue
                    cursor = offset + element.text.length
                    element.confidence?.takeIf { it.isFinite() && it in 0.0..1.0 }?.let {
                        words.add(LabelConfidence(start + offset, start + cursor, it))
                    }
                }
            }
        }
        return LabelTextResult(text.toString(), words, lineScores)
    }
}

