package pl.almara.inwentaryzacja

import org.json.JSONArray
import org.json.JSONObject

object PzJson {
    private fun JSONObject.nullableString(name: String): String? = if (isNull(name)) null else getString(name)

    fun entry(entry: PzEntry): JSONObject = JSONObject().apply {
        put("id", entry.id)
        put("materialId", entry.materialId ?: JSONObject.NULL)
        put("materialName", entry.materialName)
        put("lot", entry.lot ?: JSONObject.NULL)
        put("unknownLotComment", entry.unknownLotComment)
        put("quantity", PzSummary.number(entry.quantity))
        put("unit", entry.unit)
        put("condition", entry.condition.name)
        put("damageComment", entry.damageComment)
        put("scannedAt", entry.scannedAt)
        put("sourceCode", entry.sourceCode ?: JSONObject.NULL)
        put("barcodeFormat", entry.barcodeFormat ?: JSONObject.NULL)
        entry.label?.let { put("label", LabelJson.read(it)) }
        if (entry.approximateMaterial) put("approximateMaterial", true)
        if (entry.lowConfidenceFields.isNotEmpty()) put("lowConfidenceFields", JSONArray(entry.lowConfidenceFields.toList()))
    }

    fun entry(value: JSONObject): PzEntry = PzEntry(
        value.getString("id"), value.nullableString("materialId"), value.getString("materialName"),
        value.nullableString("lot"), value.getString("unknownLotComment"),
        value.getString("quantity").toBigDecimal(), value.getString("unit"),
        PzCondition.valueOf(value.getString("condition")), value.getString("damageComment"),
        value.getString("scannedAt"), value.nullableString("sourceCode"), value.nullableString("barcodeFormat"),
        value.optJSONObject("label")?.let(LabelJson::read), value.optBoolean("approximateMaterial"),
        value.optJSONArray("lowConfidenceFields")?.let { fields -> (0 until fields.length()).map { fields.getString(it) }.toSet() }.orEmpty()
    )

    fun document(doc: PzDocument): JSONObject = JSONObject().apply {
        put("id", doc.id)
        put("createdAt", doc.createdAt)
        put("supplier", doc.supplier)
        put("receiver", doc.receiver)
        put("invoiceNumber", doc.invoiceNumber)
        put("deliveryMatches", doc.deliveryMatches ?: JSONObject.NULL)
        put("deliveryComment", doc.deliveryComment)
        put("items", JSONArray().apply { doc.items.forEach { put(entry(it)) } })
        put("number", doc.number)
        put("phoneNumber", doc.phoneNumber ?: JSONObject.NULL)
        put("installationId", doc.installationId ?: JSONObject.NULL)
        put("approvedAt", doc.approvedAt ?: JSONObject.NULL)
    }

    fun document(value: JSONObject): PzDocument = PzDocument(
        id = value.getString("id"), createdAt = value.getString("createdAt"),
        supplier = value.getString("supplier"), receiver = value.getString("receiver"),
        invoiceNumber = value.getString("invoiceNumber"),
        deliveryMatches = if (value.isNull("deliveryMatches")) null else value.getBoolean("deliveryMatches"),
        deliveryComment = value.getString("deliveryComment"),
        items = value.getJSONArray("items").let { array -> (0 until array.length()).map { entry(array.getJSONObject(it)) } },
        number = value.getString("number"),
        phoneNumber = if (value.isNull("phoneNumber")) null else value.getInt("phoneNumber"),
        installationId = value.nullableString("installationId"), approvedAt = value.nullableString("approvedAt")
    )
}
