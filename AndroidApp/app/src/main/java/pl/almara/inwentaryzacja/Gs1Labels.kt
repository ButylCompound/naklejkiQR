package pl.almara.inwentaryzacja

object Gs1Labels {
    fun values(codes: List<LabelCode>): List<LabelValue> = codes.flatMap { code ->
        val fields = decode(code.raw) ?: return@flatMap emptyList()
        val gtin = (fields["01"] ?: fields["02"])?.takeIf(::validGtin)
        val identified = gtin != null || code.raw.startsWith('(') || listOf("]C1", "]d2", "]Q3").any { code.raw.startsWith(it) }
        buildList {
            if (gtin != null) fields["10"]?.takeIf { it.isNotBlank() }?.let { add(LabelValue("lot", it)) }
            fields.filterKeys { identified && it.startsWith("310") }.forEach { (ai, raw) ->
                val weight = raw.toBigDecimal().movePointLeft(ai.last().digitToInt())
                if (weight.signum() > 0) { add(LabelValue("quantity", PzSummary.number(weight), "kg")); add(LabelValue("unit", "kg")) }
            }
        }
    }

    private fun validGtin(value: String): Boolean {
        if (value.length != 14 || !value.all { it.isDigit() }) return false
        val sum = value.dropLast(1).reversed().mapIndexed { index, char -> char.digitToInt() * if (index % 2 == 0) 3 else 1 }.sum()
        return (10 - sum % 10) % 10 == value.last().digitToInt()
    }

    private fun decode(original: String): Map<String, String>? {
        var input = original.trim()
        if (input.startsWith("]C1") || input.startsWith("]d2") || input.startsWith("]Q3")) input = input.drop(3)
        val result = linkedMapOf<String, String>()
        var position = 0
        while (position < input.length) {
            if (input[position] == '\u001d') { position++; continue }
            val parenthesized = input[position] == '('
            val close = if (parenthesized) input.indexOf(')', position) else -1
            val ai = if (parenthesized && close > position) input.substring(position + 1, close) else when {
                input.startsWith("310", position) && position + 4 <= input.length -> input.substring(position, position + 4)
                position + 2 <= input.length -> input.substring(position, position + 2)
                else -> return null
            }
            val fixed = when {
                ai == "00" -> 18
                ai == "01" || ai == "02" -> 14
                ai in setOf("11", "15", "17") -> 6
                ai.length == 4 && ai.startsWith("310") && ai.last().isDigit() -> 6
                else -> null
            }
            val max = when (ai) { "10", "21" -> 20; "37" -> 8; else -> fixed ?: return null }
            position = if (parenthesized) close + 1 else position + ai.length
            val end = if (fixed != null) position + fixed else {
                val separator = input.indexOf('\u001d', position).takeIf { it >= 0 } ?: input.length
                val bracket = if (parenthesized) input.indexOf('(', position).takeIf { it >= 0 } ?: input.length else input.length
                minOf(separator, bracket)
            }
            if (end > input.length || end <= position || end - position > max || ai in result) return null
            val value = input.substring(position, end)
            if ((fixed != null || ai == "37") && !value.all { it.isDigit() }) return null
            result[ai] = value
            position = end
        }
        return result.takeIf { it.isNotEmpty() }
    }
}
