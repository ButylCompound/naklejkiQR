package pl.almara.inwentaryzacja

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ustawienia aplikacji zapisane w SharedPreferences. */
object Settings {

    private const val PREFS = "settings"
    private const val KEY_ALLEYS = "alley_count"
    private const val KEY_CUSTOM_ALLEYS = "custom_alleys"
    private const val KEY_REQUESTERS = "rw_requesters"
    private const val KEY_MANAGERS = "rw_managers"
    private const val KEY_RW_YEAR = "rw_year"
    private const val KEY_RW_SEQ = "rw_seq"
    private const val KEY_GROUP_BY_ALLEY = "group_scans_by_alley"
    private const val DEFAULT_ALLEYS = 32
    private val DEFAULT_CUSTOM_ALLEYS = listOf("o1", "o2", "o3", "o4", "o5")

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Liczba alejek (górny limit wyboru w skanerze). */
    fun alleyCount(context: Context): Int =
        prefs(context).getInt(KEY_ALLEYS, DEFAULT_ALLEYS)

    fun setAlleyCount(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_ALLEYS, value.coerceAtLeast(1)).apply()
    }

    /** Alejki niestandardowe (dopisywane na końcu listy wyboru w skanerze). */
    fun customAlleys(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_ALLEYS, null) ?: return DEFAULT_CUSTOM_ALLEYS
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setCustomAlleys(context: Context, value: List<String>) = setNames(context, KEY_CUSTOM_ALLEYS, value)

    /**
     * Nazwa alejki niestandardowej musi się różnić od alejek numerycznych —
     * nie może być pusta ani samą liczbą (np. "5", "01", "007").
     */
    fun isValidCustomAlleyName(name: String): Boolean {
        val trimmed = name.trim()
        return trimmed.isNotEmpty() && !trimmed.matches(Regex("\\d+"))
    }

    /** Lista osób zamawiających materiały (RW). */
    fun requesters(context: Context): List<String> = names(context, KEY_REQUESTERS)

    fun setRequesters(context: Context, value: List<String>) = setNames(context, KEY_REQUESTERS, value)

    /** Lista kierowników zmiany (RW). */
    fun managers(context: Context): List<String> = names(context, KEY_MANAGERS)

    fun setManagers(context: Context, value: List<String>) = setNames(context, KEY_MANAGERS, value)

    private fun names(context: Context, key: String): List<String> =
        prefs(context).getString(key, "").orEmpty()
            .split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    private fun setNames(context: Context, key: String, value: List<String>) {
        val cleaned = value.map { it.trim() }.filter { it.isNotEmpty() }
        prefs(context).edit().putString(key, cleaned.joinToString("\n")).apply()
    }

    /** Widok pozycji w sesji: pogrupowany wg alejki (true) czy wg czasu (false). */
    fun groupScansByAlley(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GROUP_BY_ALLEY, false)

    fun setGroupScansByAlley(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GROUP_BY_ALLEY, value).apply()
    }

    /** Przywraca domyślne: 32 alejki, domyślne alejki niestandardowe, puste listy osób. */
    fun resetDefaults(context: Context) {
        setAlleyCount(context, DEFAULT_ALLEYS)
        setCustomAlleys(context, DEFAULT_CUSTOM_ALLEYS)
        setRequesters(context, emptyList())
        setManagers(context, emptyList())
    }

    /** Kolejny numer RW w formacie "RW/RRRR/NN"; licznik zeruje się co rok. */
    fun nextRwNumber(context: Context): String {
        val year = SimpleDateFormat("yyyy", Locale.getDefault()).format(Date()).toInt()
        val p = prefs(context)
        val seq = if (p.getInt(KEY_RW_YEAR, 0) == year) p.getInt(KEY_RW_SEQ, 0) + 1 else 1
        p.edit().putInt(KEY_RW_YEAR, year).putInt(KEY_RW_SEQ, seq).apply()
        return formatRwNumber(year, seq)
    }

    /** "RW/RRRR/NN" — numer dopełniany do 2 cyfr, powyżej 99 rośnie do 3+. */
    fun formatRwNumber(year: Int, seq: Int): String = "RW/%d/%02d".format(year, seq)
}
