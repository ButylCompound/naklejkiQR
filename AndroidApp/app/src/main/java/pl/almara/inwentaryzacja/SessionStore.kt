package pl.almara.inwentaryzacja

import android.content.Context
import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
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
        dir(context).listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".json.bak") }
            ?.map { File(it.parentFile, it.name.removeSuffix(".bak")) }
            ?.distinct()
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun getSession(context: Context, id: String): Session? = read(file(context, id))

    private fun read(file: File): Session? =
        runCatching { fromJson(AtomicFile(file).readText()) }.getOrNull()

    @Synchronized
    fun createSession(context: Context, name: String, type: String = Session.TYPE_PRODUCT): Session? {
        val normalized = name.trim().replace(Regex("\\s+"), " ")
        if (listSessions(context).any {
            it.name.trim().replace(Regex("\\s+"), " ").equals(normalized, ignoreCase = true)
        }) return null
        val session = Session(UUID.randomUUID().toString(), normalized, timestamp(), type, mutableListOf())
        save(context, session)
        return session
    }

    fun deleteSession(context: Context, id: String) {
        AtomicFile(file(context, id)).delete()
    }

    /**
     * Dodaje pozycję; jednostki mają wspólny zapis, a ID nie wpływa na porównanie.
     */
    fun addItem(context: Context, sessionId: String, item: ScanItem): AddResult? =
        addItems(context, sessionId, listOf(item))?.let {
            if (it == 0) AddResult.DUPLICATE else AddResult.ADDED
        }

    fun addItems(context: Context, sessionId: String, items: List<ScanItem>): Int? {
        val session = getSession(context, sessionId) ?: return null
        val keys = session.items.mapTo(mutableSetOf()) { it.key }
        val added = items.filter { keys.add(it.key) }
        if (added.isNotEmpty()) {
            session.items.addAll(added)
            save(context, session)
        }
        return added.size
    }

    /** Usuwa zaznaczone pozycje jednym zapisem sesji. */
    fun deleteItems(context: Context, sessionId: String, selected: Set<String>) {
        val session = getSession(context, sessionId) ?: return
        session.items.removeAll { it.sourceKey in selected }
        save(context, session)
    }

    private fun save(context: Context, session: Session) {
        AtomicFile(file(context, session.id)).writeText(toJson(session))
    }

    private fun toJson(s: Session): String {
        val o = JSONObject()
        o.put("id", s.id)
        o.put("name", s.name)
        o.put("createdAt", s.createdAt)
        o.put("type", s.type)
        val arr = JSONArray()
        for (item in s.items) arr.put(ItemJson.toJson(item))
        o.put("items", arr)
        return o.toString()
    }

    private fun fromJson(text: String): Session {
        val o = JSONObject(text)
        val items = mutableListOf<ScanItem>()
        val arr = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) items.add(ItemJson.fromJson(arr.getJSONObject(i)))
        return Session(
            o.getString("id"),
            o.getString("name"),
            o.optString("createdAt"),
            o.optString("type", Session.TYPE_PRODUCT),
            items
        )
    }
}
