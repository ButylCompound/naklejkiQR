package pl.almara.inwentaryzacja

import android.content.Context
import com.google.re2j.Pattern
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.time.OffsetDateTime

class LabelRules private constructor(val json: JSONObject, val updatedAt: String, private val gs1: Boolean,
    private val rules: List<Rule>, val ocrWarningThreshold: Double) {
    private data class Field(val field: String, val group: Int?, val value: String?, val prefix: String, val suffix: String,
        val numberFormat: String, val factor: BigDecimal, val fuzzy: Fuzzy?)
    private data class Fuzzy(val choices: List<String>, val maxDistance: Int)
    private data class Rule(val id: String, val source: String, val formats: Set<String>, val pattern: Pattern,
        val context: Pattern?, val fields: List<Field>)

    fun apply(read: LabelRead): LabelRead {
        require(read.codes.size <= 10 && read.codes.all { it.raw.length <= 8192 } && read.text.length <= 65536)
        require(listOf(read.ocrWords, read.ocrLines).all { spans -> spans.size <= 65536 && spans.all {
            it.start >= 0 && it.end > it.start && it.end <= read.text.length && it.confidence.isFinite() && it.confidence in 0.0..1.0
        } })
        val values = mutableListOf<LabelValue>()
        val barcodeValues = mutableSetOf<LabelValue>()
        val ids = mutableSetOf<String>()
        if (gs1) {
            val found = Gs1Labels.values(read.codes)
            values.addAll(found)
            barcodeValues.addAll(found)
            if (found.isNotEmpty()) ids.add("gs1")
        }
        for (rule in rules) {
            if (rule.context != null && !rule.context.matcher(read.text).find()) continue
            val inputs = if (rule.source == "text") listOf(read.text)
                else read.codes.filter { rule.formats.isEmpty() || it.format in rule.formats }.map { it.raw }
            for (input in inputs) {
                val matcher = rule.pattern.matcher(input)
                var matches = 0
                while (matcher.find()) {
                    require(++matches <= 32)
                    val fields = rule.fields.flatMap { field ->
                        val raw = field.value ?: matcher.group(field.group!!).orEmpty()
                        val confidence = if (rule.source != "text") null else if (field.group != null)
                            LabelConfidence.score(read, matcher.start(field.group), matcher.end(field.group))
                        else {
                            val unitOffset = if (field.field == "unit") matcher.group().lastIndexOf(raw, ignoreCase = true) else -1
                            val start = matcher.start() + unitOffset.coerceAtLeast(0)
                            LabelConfidence.score(read, start, if (unitOffset >= 0) start + raw.length else matcher.end())
                        }
                        val candidates = field.fuzzy?.let { FuzzyText.match(raw, it.choices, it.maxDistance) }
                            ?: listOf(FuzzyText.Match(raw, false))
                        candidates.mapNotNull { candidate ->
                            val value = (field.prefix + candidate.value.trim() + field.suffix).trim()
                            val normalized = when (field.field) {
                                "quantity" -> quantity(value, field.numberFormat)?.multiply(field.factor)
                                    ?.takeIf { it.signum() > 0 }?.let(PzSummary::number)
                                "unit" -> unit(value)
                                else -> value.takeIf { it.isNotEmpty() }
                            }
                            normalized?.takeIf { it.length <= 512 }?.let { LabelValue(field.field, it,
                                approximate = candidate.approximate, confidence = confidence,
                                lowConfidence = confidence != null && confidence < ocrWarningThreshold) }
                        }
                    }.groupBy { it.field }
                    val matched = fields.values.flatten().filter { it.field != "materialId" && (it.field != "quantity" || fields["unit"] != null) }.map { value ->
                        value.copy(unit = if (value.field == "quantity") fields["unit"]?.singleOrNull()?.value else null,
                            materialId = if (value.field == "materialName") fields["materialId"]?.singleOrNull()?.value else null)
                    }
                    if (matched.isNotEmpty()) { values.addAll(matched); ids.add(rule.id) }
                    if (rule.source == "barcode") barcodeValues.addAll(matched)
                }
            }
        }
        val distinct = values.groupBy { it.copy(approximate = false, confidence = null, lowConfidence = false) }.map { (key, group) ->
            if (key in barcodeValues) key else group.filter { !it.approximate }.ifEmpty { group }.maxBy { it.confidence ?: -1.0 }
        }
        require(distinct.size <= 128)
        return read.copy(rulesUpdatedAt = updatedAt, ruleIds = ids.toList(), values = distinct)
    }

    companion object {
        val fields = setOf("materialName", "materialId", "lot", "quantity", "unit")
        val formats = setOf("QR_CODE", "CODE_128", "CODE_39", "CODE_93", "DATA_MATRIX", "PDF417", "AZTEC", "EAN_13", "EAN_8", "UPC_A", "UPC_E", "ITF", "CODABAR")

        fun parse(json: JSONObject): LabelRules {
            fun keys(value: JSONObject, allowed: Set<String>) { require(value.keys().asSequence().all { it in allowed }) }
            fun strings(value: JSONObject, names: Set<String>) { for (name in names) if (value.has(name)) require(value.get(name) is String) }
            keys(json, setOf("updatedAt", "gs1", "rules", "tests", "ocrWarningThreshold"))
            strings(json, setOf("updatedAt"))
            require(json.get("gs1") is Boolean)
            val at = json.getString("updatedAt")
            OffsetDateTime.parse(at)
            if (json.has("ocrWarningThreshold")) require(json.get("ocrWarningThreshold") is Number)
            val threshold = json.optDouble("ocrWarningThreshold", 0.8)
            require(threshold.isFinite() && threshold in 0.0..1.0)
            val entries = json.getJSONArray("rules")
            require(entries.length() <= 200)
            fun pattern(value: String): Pattern { require(value.length <= 2048); return Pattern.compile(value) }
            val rules = (0 until entries.length()).map { index ->
                val rule = entries.getJSONObject(index)
                keys(rule, setOf("id", "source", "formats", "pattern", "contextPattern", "fields"))
                strings(rule, setOf("id", "source", "pattern", "contextPattern"))
                val source = rule.getString("source")
                require(source in setOf("barcode", "text"))
                val formats = rule.getJSONArray("formats").let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }
                require(formats.all { it in this.formats } && (source != "text" || formats.isEmpty()))
                val compiled = pattern(rule.getString("pattern"))
                require(!compiled.matcher("").find())
                val context = if (rule.has("contextPattern")) pattern(rule.getString("contextPattern")) else null
                val fields = rule.getJSONArray("fields").let { array ->
                    require(array.length() in 1..5)
                    (0 until array.length()).map {
                        val field = array.getJSONObject(it)
                        keys(field, setOf("field", "group", "value", "prefix", "suffix", "numberFormat", "factor", "fuzzy"))
                        strings(field, setOf("field", "value", "prefix", "suffix", "numberFormat", "factor"))
                        val target = field.getString("field")
                        require(target in this.fields && field.has("group") != field.has("value"))
                        val group = if (field.has("group")) {
                            require(field.get("group") is Number)
                            field.get("group").toString().toBigDecimal().intValueExact().also { require(it in 0..compiled.matcher("").groupCount()) }
                        } else null
                        val prefix = field.optString("prefix", "")
                        val suffix = field.optString("suffix", "")
                        val literal = if (field.has("value")) field.getString("value") else null
                        require(prefix.length <= 128 && suffix.length <= 128 && (literal == null || literal.length <= 512))
                        val numberFormat = field.optString("numberFormat", "decimal")
                        require(numberFormat in setOf("decimal", "decimal-dot", "decimal-comma", "thousands-dot"))
                        val factor = field.optString("factor", "1")
                        require(factor.matches(Regex("[0-9]{1,12}(?:\\.[0-9]{1,12})?")) && factor.toBigDecimal().signum() > 0)
                        val fuzzy = if (field.has("fuzzy")) field.getJSONObject("fuzzy").let { options ->
                            keys(options, setOf("choices", "maxDistance"))
                            require(source == "text" && target == "materialName" && group != null)
                            val distance = options.get("maxDistance").toString().toBigDecimal().intValueExact()
                            require(options.get("maxDistance") is Number && distance in 1..3)
                            val choices = options.getJSONArray("choices").let { values ->
                                require(values.length() in 1..100)
                                (0 until values.length()).map { values.getString(it).also { choice ->
                                    require(choice.length <= 512 && FuzzyText.normalize(choice).length >= 3)
                                } }
                            }
                            require(choices.map(FuzzyText::normalize).distinct().size == choices.size)
                            Fuzzy(choices, distance)
                        } else null
                        Field(target, group, literal, prefix, suffix, numberFormat, factor.toBigDecimal(), fuzzy)
                    }
                }
                require(fields.map { it.field }.distinct().size == fields.size)
                require(fields.none { it.field == "quantity" } || fields.any { it.field == "unit" })
                require(fields.none { it.field == "materialId" } || fields.any { it.field == "materialName" })
                Rule(rule.getString("id").also { require(it.isNotBlank() && it.length <= 128 && it != "gs1") }, source, formats, compiled, context, fields)
            }
            require(rules.map { it.id }.distinct().size == rules.size)
            val bundle = LabelRules(json, at, json.getBoolean("gs1"), rules, threshold)
            val tests = json.getJSONArray("tests")
            require(tests.length() <= 200)
            for (index in 0 until tests.length()) {
                val test = tests.getJSONObject(index)
                keys(test, setOf("name", "codes", "text", "expected"))
                strings(test, setOf("name", "text"))
                val codes = test.getJSONArray("codes")
                val read = LabelRead(at, (0 until codes.length()).map {
                    val code = codes.getJSONObject(it)
                    keys(code, setOf("raw", "format"))
                    strings(code, setOf("raw", "format"))
                    LabelCode(code.getString("raw"), code.getString("format").also { require(it in formats) })
                }, test.getString("text"))
                val actual = bundle.apply(read).values
                val expected = test.getJSONObject("expected")
                for (field in expected.keys()) {
                    require(field in this.fields)
                    require(expected.isNull(field) || expected.get(field) is String)
                    val found = if (field == "materialId") actual.mapNotNull { it.materialId }.distinct()
                        else actual.filter { it.field == field }.map { it.value }.distinct()
                    require(if (expected.isNull(field)) found.isEmpty() else found == listOf(expected.getString(field)))
                }
            }
            return bundle
        }

        fun unit(value: String): String? = when (value.trim().lowercase(java.util.Locale.ROOT)) {
            "kg" -> "kg"
            "szt", "szt." -> "szt."
            else -> null
        }

        private fun quantity(value: String, format: String): BigDecimal? {
            val input = value.replace(Regex("\\s+"), "")
            val normalized = when (format) {
                "thousands-dot" -> if (input.matches(Regex("(?:[0-9]+|[0-9]{1,3}(?:\\.[0-9]{3})+)(?:,[0-9]+)?"))) input.replace(".", "").replace(',', '.') else return null
                "decimal-dot" -> if (input.matches(Regex("[0-9]+(?:\\.[0-9]+)?"))) input else return null
                "decimal-comma" -> if (input.matches(Regex("[0-9]+(?:,[0-9]+)?"))) input.replace(',', '.') else return null
                else -> if (input.matches(Regex("[0-9]+(?:[.,][0-9]+)?"))) input.replace(',', '.') else return null
            }
            return normalized.takeIf { it.length <= 64 }?.toBigDecimalOrNull()
        }
    }
}

object LabelRuleStore {
    private fun file(context: Context) = File(context.filesDir, "label-rules.json")
    @Synchronized
    fun get(context: Context): LabelRules = LabelRules.parse(JsonFiles.read(file(context))
        ?: JSONObject(context.assets.open("label-rules.json").bufferedReader().use { it.readText() }))
    @Synchronized
    fun replace(context: Context, rules: LabelRules) = JsonFiles.write(file(context), rules.json)
}
