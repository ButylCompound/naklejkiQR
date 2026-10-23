package pl.almara.inwentaryzacja

import java.util.Locale

object Units {
    fun normalize(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
        "", "kg" -> "kg"
        "szt", "szt." -> "szt."
        else -> value.trim()
    }
}
