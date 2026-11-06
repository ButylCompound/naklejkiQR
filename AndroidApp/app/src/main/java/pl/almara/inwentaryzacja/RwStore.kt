package pl.almara.inwentaryzacja

import android.content.Context
import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.time.LocalDate

/** Magazyn dokumentów RW: jeden dokument = jeden plik JSON w pamięci aplikacji. */
object RwStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "rw").apply { mkdirs() }

    private fun file(context: Context, id: String): File =
        File(dir(context), "$id.json")

    @Synchronized
    fun list(context: Context): List<RwDocument> =
        dir(context).listFiles { f -> f.name.endsWith(".json") || f.name.endsWith(".json.bak") }
            ?.map { File(it.parentFile, it.name.removeSuffix(".bak")) }
            ?.distinct()
            ?.mapNotNull { read(it) }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    @Synchronized
    fun get(context: Context, id: String): RwDocument? = read(file(context, id))

    private fun read(file: File): RwDocument? =
        runCatching { fromJson(AtomicFile(file).readText()) }.getOrNull()

    @Synchronized
    fun create(context: Context): RwDocument {
        val doc = RwDocument(UUID.randomUUID().toString(), "", SessionStore.timestamp(), "", "", mutableListOf())
        save(context, doc)
        return doc
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val doc = get(context, id) ?: return
        check(doc.number.isEmpty()) { "Finalized RW cannot be deleted" }
        AtomicFile(file(context, id)).delete()
    }

    enum class PutResult { ADDED, UPDATED }

    /**
     * Dodaje pozycję lub — gdy ten sam kod już jest — nadpisuje ją nową ilością
     * wydaną (ponowne skanowanie służy do korekty). Zwraca null, gdy brak dokumentu.
     */
    @Synchronized
    fun putItem(context: Context, id: String, item: ScanItem): PutResult? {
        val doc = get(context, id) ?: return null
        check(doc.number.isEmpty()) { "Finalized RW cannot be edited" }
        val i = doc.items.indexOfFirst { it.sourceKey == item.sourceKey }
        val result = if (i >= 0) {
            doc.items[i] = item
            PutResult.UPDATED
        } else {
            doc.items.add(item)
            PutResult.ADDED
        }
        save(context, doc)
        return result
    }

    @Synchronized
    fun deleteItems(context: Context, id: String, selected: Set<String>) {
        val doc = get(context, id) ?: return
        check(doc.number.isEmpty()) { "Finalized RW cannot be edited" }
        doc.items.removeAll { it.sourceKey in selected }
        save(context, doc)
    }

    /** Zmienia ilość wydaną istniejącej pozycji. */
    @Synchronized
    fun setItemQuantity(context: Context, id: String, key: String, quantity: Double) {
        val doc = get(context, id) ?: return
        check(doc.number.isEmpty()) { "Finalized RW cannot be edited" }
        val i = doc.items.indexOfFirst { it.sourceKey == key }
        if (i >= 0) {
            doc.items[i] = doc.items[i].copy(quantity = quantity)
            save(context, doc)
        }
    }

    @Synchronized
    fun setPeople(context: Context, id: String, requester: String, manager: String) {
        val doc = get(context, id) ?: return
        check(doc.number.isEmpty()) { "Finalized RW cannot be edited" }
        save(context, doc.copy(requester = requester, manager = manager))
    }

    /** Nadaje numer (jeśli jeszcze nie ma) i zwraca zatwierdzony dokument. */
    @Synchronized
    fun finalize(context: Context, id: String): RwDocument? {
        val doc = get(context, id) ?: return null
        if (doc.number.isNotEmpty()) return doc
        val year = LocalDate.now().year
        val previous = list(context).mapNotNull {
            val parts = it.number.split('/')
            if (parts.size in 3..4 && parts[0] == "RW" && parts[1].toIntOrNull() == year)
                parts.last().toLongOrNull() else null
        }.maxOrNull() ?: 0
        val finalized = doc.copy(number = DocumentNumbering.reserve(context, "RW", year, previous).number)
        save(context, finalized)
        return finalized
    }

    @Synchronized
    fun restore(context: Context, doc: RwDocument) {
        UUID.fromString(doc.id)
        require(Regex("RW/[0-9]{4}/(?:[0-9]+/)?[0-9]+").matches(doc.number))
        require(doc.items.isNotEmpty() && doc.items.all { it.quantity.isFinite() && it.quantity > 0 })
        val existing = get(context, doc.id)
        require(existing == null || existing == doc) { "Local RW differs from its backup" }
        save(context, doc)
    }

    private fun save(context: Context, doc: RwDocument) {
        AtomicFile(file(context, doc.id)).writeText(toJson(doc))
    }

    fun toJson(doc: RwDocument): String {
        val arr = JSONArray()
        for (item in doc.items) arr.put(ItemJson.toJson(item))
        return JSONObject().apply {
            put("id", doc.id)
            put("number", doc.number)
            put("createdAt", doc.createdAt)
            put("requester", doc.requester)
            put("manager", doc.manager)
            put("items", arr)
        }.toString()
    }

    fun fromJson(text: String): RwDocument {
        val o = JSONObject(text)
        val items = mutableListOf<ScanItem>()
        val arr = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) items.add(ItemJson.fromJson(arr.getJSONObject(i)))
        return RwDocument(
            id = o.getString("id"),
            number = o.optString("number"),
            createdAt = o.optString("createdAt"),
            requester = o.optString("requester"),
            manager = o.optString("manager"),
            items = items
        )
    }
}
