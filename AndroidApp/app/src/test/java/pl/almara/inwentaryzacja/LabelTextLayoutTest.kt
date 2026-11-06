package pl.almara.inwentaryzacja

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class LabelTextLayoutTest {
    private fun line(text: String, x: Double, y: Double, width: Double, angle: Double): LabelTextLine {
        val radians = Math.toRadians(angle)
        val points = listOf(x to y, x + width to y, x + width to y + 12, x to y + 12)
            .map { (px, py) -> LabelTextPoint(px * cos(radians) - py * sin(radians),
                px * sin(radians) + py * cos(radians)) }
        return LabelTextLine(text, angle, points)
    }

    @Test fun rotatedLabelsKeepHeadingsAndValuesInReadingOrder() {
        for (angle in listOf(0.0, 27.0, 90.0, 179.0, -179.0, -90.0)) {
            val lines = listOf(
                line("ŻYWICA WEGLOWODOROWA PRS", 0.0, 0.0, 240.0, angle),
                line("A1100", 0.0, 20.0, 50.0, angle),
                line("Numer serii:", 0.0, 40.0, 90.0, angle),
                line("2604075", 110.0, 40.0, 60.0, angle),
                line("Masa netto:", 0.0, 60.0, 90.0, angle),
                line("700 kg", 110.0, 60.0, 60.0, angle)
            )
            assertEquals("Rotation " + angle,
                "ŻYWICA WEGLOWODOROWA PRS\nA1100\nNumer serii: 2604075\nMasa netto: 700 kg",
                LabelTextLayout.read(lines.reversed()))
        }
    }

    @Test fun aShortSidewaysLogoDoesNotSetTheLabelsReadingDirection() {
        val main = listOf(
            line("ŻYWICA WEGLOWODOROWA PRS", 0.0, 0.0, 240.0, 90.0),
            line("Numer serii: 2604075", 0.0, 30.0, 180.0, 90.0),
            line("Masa netto: 700 kg", 0.0, 60.0, 160.0, 90.0)
        )
        assertEquals("ŻYWICA WEGLOWODOROWA PRS\nNumer serii: 2604075\nMasa netto: 700 kg\nLOGO",
            LabelTextLayout.read(main + line("LOGO", -100.0, 10.0, 30.0, 0.0)))
    }

    @Test fun emptyRecognitionProducesEmptyText() {
        assertEquals("", LabelTextLayout.read(emptyList()))
    }

    @Test fun wordAndLineScoresFollowTheirTextThroughRotationAndRepeatedWords() {
        val heading = line("LOT", 0.0, 0.0, 40.0, 90.0).copy(confidence = 0.9,
            elements = listOf(LabelTextElement("LOT", 0.95)))
        val values = line("260502 260502", 0.0, 30.0, 150.0, 90.0).copy(confidence = 0.8,
            elements = listOf(LabelTextElement("260502", 0.99), LabelTextElement("260502", 0.4)))
        val result = LabelTextLayout.layout(listOf(values, heading))
        assertEquals("LOT\n260502 260502", result.text)
        assertEquals(listOf(LabelConfidence(0, 3, 0.95), LabelConfidence(4, 10, 0.99), LabelConfidence(11, 17, 0.4)), result.words)
        assertEquals(listOf(LabelConfidence(0, 3, 0.9), LabelConfidence(4, 17, 0.8)), result.lines)
    }
}

