package pl.almara.inwentaryzacja

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OcrConfidenceTest {
    private fun rules(threshold: Any = 0.8) = LabelRules.parse(JSONObject(File("src/main/assets/label-rules.json").readText())
        .put("ocrWarningThreshold", threshold))

    private fun read(text: String, scores: Map<String, Double> = emptyMap()) = LabelRead(PzSummary.now(), emptyList(), text,
        ocrWords = Regex("\\S+").findAll(text).map { LabelConfidence(it.range.first, it.range.last + 1, scores[it.value] ?: 0.99) }.toList())

    @Test fun fieldConfidenceUsesItsCapturedWordsAndIgnoresTheAddressAndHeading() {
        val input = read("STANDARD\nblurred-address\nŻYWICA WĘGLOWODOROWA PRS\nA1100\nNumer serii: 2604075\nMasa netto: 700 kg",
            mapOf("blurred-address" to 0.1, "serii:" to 0.1, "2604075" to 0.4, "700" to 0.9, "kg" to 0.6))
        val parsed = rules().apply(input)
        assertEquals(setOf("lot", "unit"), parsed.values.filter { it.lowConfidence }.map { it.field }.toSet())
        assertEquals(0.4, parsed.values.single { it.field == "lot" }.confidence!!, 0.0)
        assertEquals(0.9, parsed.values.single { it.field == "quantity" }.confidence!!, 0.0)
        assertEquals(parsed, LabelJson.read(LabelJson.read(parsed)))
    }

    @Test fun thresholdIsStrictAndZeroScoresAreRetainedWhileMissingScoresStayUnknown() {
        val input = read("NET: 1200KG", mapOf("1200KG" to 0.8))
        assertTrue(rules(0.8).apply(input).values.none { it.lowConfidence })
        assertTrue(rules(0.81).apply(input).values.all { it.lowConfidence })
        val zero = read("NET: 1200KG", mapOf("1200KG" to 0.0))
        assertTrue(rules().apply(zero).values.all { it.confidence == 0.0 && it.lowConfidence })
        assertTrue(rules(0.0).apply(zero).values.none { it.lowConfidence })
        assertTrue(rules().apply(input.copy(ocrWords = emptyList())).values.all { it.confidence == null && !it.lowConfidence })
    }

    @Test fun lineConfidenceProvidesAFallbackAndDuplicateValuesUseTheBestEvidence() {
        val text = "NET: 1200KG"
        val line = LabelRead(PzSummary.now(), emptyList(), text, ocrLines = listOf(LabelConfidence(0, text.length, 0.3)))
        assertTrue(rules().apply(line).values.all { it.confidence == 0.3 && it.lowConfidence })
        val repeated = "NET:1200KG\nNET:1200KG"
        val duplicate = LabelRead(PzSummary.now(), emptyList(), repeated,
            ocrWords = listOf(LabelConfidence(0, 10, 0.3), LabelConfidence(11, 21, 0.95)))
        val values = rules().apply(duplicate).values
        assertEquals(2, values.size)
        assertTrue(values.all { it.confidence == 0.95 && !it.lowConfidence })
    }

    @Test fun decodedBarcodeValuesWinOverLowConfidenceOcrForTheSameValues() {
        val input = read("POLIIZOBUTEN PIB32\nNumer serii: SPXFPG005E\nNET:175KG").let {
            it.copy(codes = listOf(LabelCode("PIB32 / SPXFPG005E / 20260721 / 175,000", "QR_CODE")),
                ocrWords = it.ocrWords.map { span -> span.copy(confidence = 0.1) })
        }
        val values = rules().apply(input).values
        assertEquals(4, values.size)
        assertTrue(values.all { it.confidence == null && !it.lowConfidence })
    }

    @Test fun fuzzyAndOcrWarningsRemainIndependentAndEntriesPreserveAppliedWarnings() {
        val parsed = rules().apply(read("CHAMBROAD\nButyl Rubber\nDR-1953", mapOf("DR-1953" to 0.4)))
        val material = parsed.values.single { it.field == "materialName" }
        assertTrue(material.approximate && material.lowConfidence)
        val entry = PzEntry("one", null, material.value, "260502", "", "1200".toBigDecimal(), "kg", PzCondition.OK, "", PzSummary.now(),
            label = parsed, approximateMaterial = true, lowConfidenceFields = setOf("materialName", "lot"))
        assertEquals(entry, PzJson.entry(PzJson.entry(entry)))
        assertEquals(setOf("materialName", "lot"), entry.lowConfidenceFields)
    }

    @Test fun invalidThresholdsAndConfidenceSpansAreRejected() {
        for (threshold in listOf(-0.1, 1.1, "0.8", JSONObject.NULL))
            assertThrows(IllegalArgumentException::class.java) { rules(threshold) }
        val input = LabelRead(PzSummary.now(), emptyList(), "NET:1200KG", ocrWords = listOf(LabelConfidence(0, 30, 0.5)))
        assertThrows(IllegalArgumentException::class.java) { rules().apply(input) }
    }
}
