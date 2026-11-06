package pl.almara.inwentaryzacja

import org.json.JSONObject

/** Wspólna (de)serializacja pozycji — używana przez SessionStore i RwStore. */
object ItemJson {

    fun toJson(item: ScanItem): JSONObject = JSONObject().apply {
        put("product", item.product)
        put("quantity", item.quantity)
        put("unit", item.unit)
        put("labelDate", item.labelDate)
        put("scannedAt", item.scannedAt)
        put("initials", item.initials)
        put("raw", item.raw)
        put("alley", item.alley)
        put("labelId", item.labelId)
        if (item.lot.isNotEmpty()) put("lot", item.lot)
    }

    fun fromJson(o: JSONObject): ScanItem = ScanItem(
        product = o.getString("product"),
        quantity = o.optDouble("quantity", o.optDouble("weightKg", 0.0)),
        unit = Units.normalize(o.optString("unit", "kg")),
        labelDate = o.optString("labelDate"),
        scannedAt = o.optString("scannedAt"),
        initials = o.optString("initials"),
        raw = o.optString("raw"),
        alley = o.optString("alley", "1"),
        labelId = o.optString("labelId"),
        lot = o.optString("lot")
    )
}
