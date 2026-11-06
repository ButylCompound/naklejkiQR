package pl.almara.inwentaryzacja

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class GeneratedLabelsTest {
    @Test fun generatorPayloadsKeepAllFieldsAndIdsRemainDisplayOnly() {
        val fixtures = JSONArray(javaClass.getResourceAsStream("/generated-labels.json")!!.bufferedReader().use { it.readText() })
        for (i in 0 until fixtures.length()) {
            val row = fixtures.getJSONObject(i)
            val item = requireNotNull(QrParser.parse(row.getString("payload"), "2026-11-02 09:00:00")) { row.getString("name") }
            assertEquals(row.getString("product"), item.product)
            assertEquals(row.getDouble("quantity"), item.quantity, 0.0)
            assertEquals(row.getString("unit"), item.unit)
            assertEquals(row.getString("labelDate"), item.labelDate)
            assertEquals(row.getString("initials"), item.initials)
            assertEquals(row.getString("labelId"), item.labelId)
            assertEquals(row.getString("lot"), item.lot)
            assertEquals(item, ItemJson.fromJson(ItemJson.toJson(item)))
            val changedId = QrParser.parse(row.getString("payload").replace("aB3dE_fG", "00000001"), item.scannedAt)!!
            assertEquals(item.key, changedId.key)
            assertEquals(item.sourceKey, changedId.sourceKey)
        }
    }
}
