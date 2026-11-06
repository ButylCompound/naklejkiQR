package pl.almara.inwentaryzacja

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LabelRulesTest {
    private fun defaults() = JSONObject(File("src/main/assets/label-rules.json").readText())
    private fun read(raw: String = "", format: String = "QR_CODE", text: String = "") =
        LabelRead("2026-10-27T14:00:00+01:00", if (raw.isEmpty()) emptyList() else listOf(LabelCode(raw, format)), text)

    @Test fun actualAndroidReadsFromAllSuppliedPhotographsMatchTheVerifiedPrintedFields() {
        val fixtures = JSONArray(File("src/test/resources/label-photo-reads.json").readText())
        val rules = LabelRules.parse(defaults())
        assertEquals(21, fixtures.length())
        for (i in 0 until fixtures.length()) {
            val fixture = fixtures.getJSONObject(i)
            val codes = fixture.getJSONArray("codes")
            val actual = rules.apply(LabelRead("2026-10-27T14:00:00Z", (0 until codes.length()).map {
                val code = codes.getJSONObject(it); LabelCode(code.getString("raw"), code.getString("format"))
            }, fixture.getString("text"))).values
            val expected = fixture.getJSONObject("expected")
            for (field in expected.keys()) assertEquals(fixture.getString("file") + ": " + field,
                if (expected.isNull(field)) emptyList<String>() else listOf(expected.getString(field)),
                actual.filter { it.field == field }.map { it.value }.distinct())
        }
    }

    @Test fun bundledExamplesValidateAndBraskemPreservesLotAndDecimalComma() {
        val parsed = LabelRules.parse(defaults()).apply(read("PIB32 / SPXFPG005E / 20260721 / 175,000"))
        assertTrue(parsed.values.contains(LabelValue("quantity", "175", "kg")))
        assertTrue(parsed.values.contains(LabelValue("lot", "SPXFPG005E")))
        assertTrue(parsed.values.contains(LabelValue("materialName", "Poliizobuten PIB32")))
    }

    @Test fun trackingLinksAndShippingIdentifiersDoNotInventManufacturerLots() {
        val rules = LabelRules.parse(defaults())
        for ((raw, format) in listOf(
            "10260001773503" to "CODE_128",
            "000000000000741102" to "CODE_128",
            "267453          " to "CODE_39",
            "https://kgwms.chambroad.com:12000/gemreport/report.rpt?traceid=027821671" to "QR_CODE")) {
            assertTrue(rules.apply(read(raw, format)).values.isEmpty())
        }
    }

    @Test fun chambroadQrProvidesProductAndFullLotButNotAnAssumedPalletWeight() {
        val result = LabelRules.parse(defaults()).apply(read("http://58.59.122.5:9999?4240622319&ph:1953&pc:4240622319&zxsj:2145-2200&dw:京博橡胶&bm:100044938"))
        assertTrue(result.values.contains(LabelValue("lot", "4240622319")))
        assertTrue(result.values.none { it.field == "quantity" })
    }

    @Test fun standardManufacturerLotComesFromThePrintedSeriesNotItsDeliveryBarcode() {
        val result = LabelRules.parse(defaults()).apply(read("10260001773503", "CODE_128",
            "Numer serii: 2604075\nNumer dostawy: 26-017735\nMasa netto: 700 kg"))
        assertEquals(listOf("2604075"), result.values.filter { it.field == "lot" }.map { it.value })
    }

    @Test fun germanNetWeightDoesNotBecomeTheGrossWeightOrTwentyFiveKilogramBaleWeight() {
        val result = LabelRules.parse(defaults()).apply(read(text =
            "BUTYL 268S 25 KG BAL\nCharge T26013025H\nBruttogewicht/Nettogewicht (KG)\n5621170000 1.225 1.200"))
        assertEquals(listOf("1200"), result.values.filter { it.field == "quantity" }.map { it.value })
    }

    @Test fun aPackageCountAndOrderNumberDoNotBecomeMaterialQuantityOrLot() {
        val result = LabelRules.parse(defaults()).apply(read("267459          ", "CODE_39",
            "PRODUCT: CALOFORT SV800 HTCP3\nORDER: E15741\nPCS 1"))
        assertTrue(result.values.none { it.field == "lot" || it.field == "quantity" })
    }

    @Test fun gs1UsesSeparatorsAndKeepsLeadingZerosInTheLot() {
        val values = Gs1Labels.values(listOf(LabelCode("]C10101234567890128100001AB\u001d3103001250", "CODE_128")))
        assertTrue(values.contains(LabelValue("lot", "0001AB")))
        assertTrue(values.contains(LabelValue("quantity", "1.25", "kg")))
        assertTrue(Gs1Labels.values(listOf(LabelCode("]C1010123456789012910LOT", "CODE_128"))).none { it.field == "lot" })
    }

    @Test fun unmarkedNumbersAreNotAssumedToBeGs1NetWeight() {
        assertTrue(Gs1Labels.values(listOf(LabelCode("3100123456", "CODE_128"))).isEmpty())
        assertTrue(Gs1Labels.values(listOf(LabelCode("(3102)012345", "CODE_128"))).contains(LabelValue("quantity", "123.45", "kg")))
    }

    @Test fun conflictsStayAvailableForOperatorChoiceInsteadOfSelectingAnArbitraryValue() {
        val rules = LabelRules.parse(defaults())
        val result = rules.apply(read("PIB32 / LOT001 / 20260721 / 175,000", text = "Masa netto: 170 kg"))
        assertEquals(setOf("175", "170"), result.values.filter { it.field == "quantity" }.map { it.value }.toSet())
        val matching = rules.apply(read("PIB32 / LOT001 / 20260721 / 175,000", text = "Masa netto: 175 kg"))
        assertEquals(1, matching.values.count { it.field == "quantity" })
    }

    @Test fun aNewServerRuleCanAddAFormatWithoutChangingTheParser() {
        val json = JSONObject("""{"updatedAt":"2026-10-27T14:00:00Z","gs1":false,"rules":[{"id":"new-supplier","source":"barcode","formats":["DATA_MATRIX"],"pattern":"^PART=([A-Z0-9]+);G=([0-9]+)$","fields":[{"field":"lot","group":1},{"field":"quantity","group":2,"factor":"0.001"},{"field":"unit","value":"kg"},{"field":"materialName","value":"Custom material"},{"field":"materialId","value":"42"}]}],"tests":[]}""")
        val result = LabelRules.parse(json).apply(read("PART=0001A;G=1250", "DATA_MATRIX"))
        assertTrue(result.values.contains(LabelValue("quantity", "1.25", "kg")))
        assertTrue(result.values.contains(LabelValue("materialName", "Custom material", materialId = "42")))
    }

    @Test fun invalidPatternsUnknownOperationsAndBrokenSelfTestsRejectTheEntireBundle() {
        val invalidPattern = defaults().apply { getJSONArray("rules").getJSONObject(0).put("pattern", "(?<=unsafe)match") }
        val unknown = defaults().put("script", "anything")
        val invalidGroup = defaults().apply { getJSONArray("rules").getJSONObject(0).getJSONArray("fields").getJSONObject(0).put("group", 99) }
        val failedTest = defaults().apply { getJSONArray("tests").getJSONObject(0).getJSONObject("expected").put("quantity", "175000") }
        for (json in listOf(invalidPattern, unknown, invalidGroup, failedTest)) assertTrue(runCatching { LabelRules.parse(json) }.isFailure)
    }

    @Test(timeout = 2000) fun nestedQuantifiersDoNotHangTheRuleEngine() {
        val json = JSONObject("""{"updatedAt":"2026-10-27T14:00:00Z","gs1":false,"rules":[{"id":"bounded","source":"barcode","formats":[],"pattern":"(a+)+$","fields":[{"field":"lot","group":1}]}],"tests":[]}""")
        assertTrue(LabelRules.parse(json).apply(read("a".repeat(8190) + "b")).values.isEmpty())
    }

    @Test fun rawEvidenceAndOriginalSuggestionsRoundTripIndependentlyOfManualCorrections() {
        val label = LabelRules.parse(defaults()).apply(read("PIB32 / ORIGINAL / 20260721 / 175,000"))
        val entry = PzEntry("entry", null, "Manually corrected name", "MANUAL-LOT", "", "170".toBigDecimal(), "kg",
            PzCondition.DAMAGED, "Torn", label.capturedAt, label = label)
        val restored = PzJson.entry(JSONObject(PzJson.entry(entry).toString()))
        assertEquals(entry, restored)
        assertEquals("MANUAL-LOT", restored.lot)
        assertTrue(restored.label!!.values.contains(LabelValue("lot", "ORIGINAL")))
    }
}
