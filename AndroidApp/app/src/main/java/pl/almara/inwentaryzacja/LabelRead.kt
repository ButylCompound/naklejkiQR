package pl.almara.inwentaryzacja

import org.json.JSONArray
import org.json.JSONObject

data class LabelCode(val raw: String, val format: String)
data class LabelValue(val field: String, val value: String, val unit: String? = null, val materialId: String? = null,
    val approximate: Boolean = false, val confidence: Double? = null, val lowConfidence: Boolean = false)
data class LabelConfidence(val start: Int, val end: Int, val confidence: Double) {
    companion object {
        fun score(read: LabelRead, start: Int, end: Int): Double? {
            if (start < 0 || end <= start) return null
            fun List<LabelConfidence>.overlapping() = filter { it.start < end && it.end > start }
            return read.ocrWords.overlapping().ifEmpty { read.ocrLines.overlapping() }.minOfOrNull { it.confidence }
        }
    }
}
data class LabelPhoto(val id: String, val sha256: String)

data class LabelRead(
    val capturedAt: String,
    val codes: List<LabelCode>,
    val text: String,
    val rulesUpdatedAt: String = "",
    val ruleIds: List<String> = emptyList(),
    val values: List<LabelValue> = emptyList(),
    val ocrWords: List<LabelConfidence> = emptyList(),
    val ocrLines: List<LabelConfidence> = emptyList(),
    val photo: LabelPhoto? = null
)

object LabelJson {
    fun read(read: LabelRead): JSONObject = JSONObject().put("capturedAt", read.capturedAt)
        .put("codes", JSONArray().apply { read.codes.forEach { put(JSONObject().put("raw", it.raw).put("format", it.format)) } })
        .put("text", read.text).put("rulesUpdatedAt", read.rulesUpdatedAt).put("ruleIds", JSONArray(read.ruleIds))
        .put("values", JSONArray().apply { read.values.forEach {
            put(JSONObject().put("field", it.field).put("value", it.value)
                .put("unit", it.unit ?: JSONObject.NULL).put("materialId", it.materialId ?: JSONObject.NULL)
                .apply {
                    if (it.approximate) put("approximate", true)
                    it.confidence?.let { score -> put("confidence", score) }
                    if (it.lowConfidence) put("lowConfidence", true)
                })
        } })
        .apply {
            read.photo?.let { put("photo", JSONObject().put("id", it.id).put("sha256", it.sha256)) }
            if (read.ocrWords.isNotEmpty()) put("ocrWords", spans(read.ocrWords))
            if (read.ocrLines.isNotEmpty()) put("ocrLines", spans(read.ocrLines))
        }

    private fun spans(values: List<LabelConfidence>) = JSONArray().apply {
        values.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("confidence", it.confidence)) }
    }

    private fun spans(json: JSONObject, name: String): List<LabelConfidence> {
        val values = json.optJSONArray(name) ?: return emptyList()
        return (0 until values.length()).map {
            val value = values.getJSONObject(it)
            LabelConfidence(value.getInt("start"), value.getInt("end"), value.getDouble("confidence"))
        }
    }

    fun read(json: JSONObject): LabelRead {
        val codes = json.getJSONArray("codes")
        val ids = json.getJSONArray("ruleIds")
        val values = json.getJSONArray("values")
        return LabelRead(json.getString("capturedAt"), (0 until codes.length()).map {
            val code = codes.getJSONObject(it)
            LabelCode(code.getString("raw"), code.getString("format"))
        }, json.getString("text"), json.getString("rulesUpdatedAt"),
            (0 until ids.length()).map { ids.getString(it) }, (0 until values.length()).map {
                val value = values.getJSONObject(it)
                LabelValue(value.getString("field"), value.getString("value"),
                    if (value.isNull("unit")) null else value.getString("unit"),
                    if (value.isNull("materialId")) null else value.getString("materialId"), value.optBoolean("approximate"),
                    if (value.isNull("confidence")) null else value.getDouble("confidence"), value.optBoolean("lowConfidence"))
            }, spans(json, "ocrWords"), spans(json, "ocrLines"), json.optJSONObject("photo")?.let {
                LabelPhoto(it.getString("id"), it.getString("sha256"))
            })
    }
}
