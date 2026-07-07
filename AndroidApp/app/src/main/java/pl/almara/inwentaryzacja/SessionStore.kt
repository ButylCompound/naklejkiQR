package pl.almara.inwentaryzacja

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Prosty magazyn sesji: jedna sesja = jeden plik JSON w pamięci wewnętrznej aplikacji.
 * Działa w pełni offline; dane nie giną po zamknięciu aplikacji.
 */
object SessionStore {

    enum class AddResult { ADDED, DUPLICATE }

    fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun dir(context: Context): File =
        File(context.filesDir, "sessions").apply { mkdirs() }

    private fun file(context: Context, id: String): File =
        File(dir(context), "$id.json")

    fun listSessions(context: Context): List<Session> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun getSession(context: Context, id: String): Session? {
        val f = file(context, id)
        if (!f.exists()) return null
        return runCatching { fromJson(f.readText()) }.getOrNull()
    }

    fun createSession(context: Context, name: String): Session {
        val session = Session(UUID.randomUUID().toString(), name, timestamp(), mutableListOf())
        save(context, session)
        return session
    }

    fun deleteSession(context: Context, id: String) {
        file(context, id).delete()
    }

    /**
     * Dodaje pozycję do sesji. Zwraca DUPLICATE, jeżeli identyczny kod QR
     * został już zeskanowany w tej sesji (chyba że force = true).
     */
    fun addItem(context: Context, sessionId: String, item: ScanItem, force: Boolean = false): AddResult? {
        val session = getSession(context, sessionId) ?: return null
        if (!force && session.items.any { it.raw == item.raw }) return AddResult.DUPLICATE
        session.items.add(item)
        save(context, session)
        return AddResult.ADDED
    }

    private fun save(context: Context, session: Session) {
        // Zapis przez plik tymczasowy, żeby awaria w trakcie zapisu nie uszkodziła sesji
        val target = file(context, session.id)
        val tmp = File(target.parentFile, "${session.id}.tmp")
        tmp.writeText(toJson(session))
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private fun toJson(s: Session): String {
        val o = JSONObject()
        o.put("id", s.id)
        o.put("name", s.name)
        o.put("createdAt", s.createdAt)
        val arr = JSONArray()
        for (item in s.items) {
            arr.put(JSONObject().apply {
                put("product", item.product)
                put("weightKg", item.weightKg)
                put("labelDate", item.labelDate)
                put("scannedAt", item.scannedAt)
                put("raw", item.raw)
            })
        }
        o.put("items", arr)
        return o.toString()
    }

    private fun fromJson(text: String): Session {
        val o = JSONObject(text)
        val items = mutableListOf<ScanItem>()
        val arr = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.getJSONObject(i)
            items.add(
                ScanItem(
                    product = it.getString("product"),
                    weightKg = it.getDouble("weightKg"),
                    labelDate = it.optString("labelDate"),
                    scannedAt = it.optString("scannedAt"),
                    raw = it.optString("raw")
                )
            )
        }
        return Session(o.getString("id"), o.getString("name"), o.optString("createdAt"), items)
    }
}
