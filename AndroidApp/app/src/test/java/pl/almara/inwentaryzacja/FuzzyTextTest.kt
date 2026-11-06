package pl.almara.inwentaryzacja

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FuzzyTextTest {
    private fun rules() = LabelRules.parse(JSONObject(File("src/main/assets/label-rules.json").readText()))
    private fun read(text: String) = LabelRead(PzSummary.now(), emptyList(), text)

    @Test fun OCRVariantsMatchButDifferentDigitsAndMissingDigitsDoNot() {
        for (value in listOf("DR-1953", "IR-1953", "I|R-1953", "IIR-I953"))
            assertEquals(listOf(FuzzyText.Match("IIR-1953", true)), FuzzyText.match(value, listOf("IIR-1953"), 2))
        for (value in listOf("IIR-1954", "IIR-195", "IIR-19533"))
            assertTrue(value, FuzzyText.match(value, listOf("IIR-1953"), 3).isEmpty())
        assertEquals(listOf(FuzzyText.Match("IIR-1953", false)), FuzzyText.match("iir 1953", listOf("IIR-1953"), 2))
    }

    @Test fun tiedChoicesRemainSeparateAndExactMatchWins() {
        assertEquals(2, FuzzyText.match("XIR1953", listOf("IIR1953", "AIR1953"), 2).size)
        assertEquals(listOf(FuzzyText.Match("IIR1953", false)), FuzzyText.match("IIR1953", listOf("IIR1953", "AIR1953"), 2))
    }

    @Test fun correctedMaterialIsMarkedAndExactEvidenceRemovesTheWarning() {
        val text = "CHAMBROAD\nButyl Rubber\nDR-1953\nLOT NO.260502\n#UNET:1200KG"
        val fuzzy = rules().apply(read(text))
        assertEquals(listOf(LabelValue("materialName", "BUTYL IIR-1953", approximate = true)), fuzzy.values.filter { it.field == "materialName" })
        assertFalse(fuzzy.values.first { it.field == "lot" }.approximate)
        assertEquals(fuzzy, LabelJson.read(LabelJson.read(fuzzy)))
        val exact = rules().apply(read(text + "\nIIR-1953"))
        assertEquals(listOf(LabelValue("materialName", "BUTYL IIR-1953")), exact.values.filter { it.field == "materialName" })
    }

    @Test fun oldEntriesAndWarningsRoundTripIndependentlyOfRawEvidence() {
        val entry = PzEntry("one", null, "BUTYL IIR-1953", "260502", "", "1200".toBigDecimal(), "kg", PzCondition.OK, "", PzSummary.now())
        assertFalse(PzJson.entry(PzJson.entry(entry)).approximateMaterial)
        assertTrue(PzJson.entry(PzJson.entry(entry.copy(approximateMaterial = true))).approximateMaterial)
    }
}
