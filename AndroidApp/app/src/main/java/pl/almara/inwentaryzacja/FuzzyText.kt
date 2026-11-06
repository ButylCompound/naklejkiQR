package pl.almara.inwentaryzacja

import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

object FuzzyText {
    data class Match(val value: String, val approximate: Boolean)

    fun normalize(value: String): String = Normalizer.normalize(value.uppercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace('Ł', 'L').filter { it.isLetterOrDigit() || it == '|' }

    fun match(value: String, choices: List<String>, maxDistance: Int): List<Match> {
        if (value.length > 512) return emptyList()
        val input = normalize(value)
        val scored = choices.map { it to distance(input, normalize(it), maxDistance) }.filter { it.second <= maxDistance }
        val best = scored.minOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == best }.map { Match(it.first, best != 0) }
    }

    private fun distance(first: String, second: String, limit: Int): Int {
        if (kotlin.math.abs(first.length - second.length) > limit) return limit + 1
        val blocked = limit + 1
        var previous = IntArray(second.length + 1) { blocked }
        previous[0] = 0
        for (j in second.indices) previous[j + 1] = previous[j] + if (second[j].isDigit()) blocked else 1
        for (a in first) {
            val current = IntArray(second.length + 1)
            current[0] = previous[0] + if (a.isDigit()) blocked else 1
            for (j in second.indices) {
                val b = second[j]
                val cost = when {
                    a == b -> 0
                    a.isDigit() || b.isDigit() -> if (listOf("0O", "0Ø", "1I", "1L", "1|", "2Z", "5S", "6G", "8B")
                        .any { a in it && b in it }) 1 else blocked
                    else -> 1
                }
                current[j + 1] = min(previous[j] + cost, min(previous[j + 1] + if (a.isDigit()) blocked else 1,
                    current[j] + if (b.isDigit()) blocked else 1))
            }
            if (current.min() > limit) return blocked
            previous = current
        }
        return previous.last()
    }
}
