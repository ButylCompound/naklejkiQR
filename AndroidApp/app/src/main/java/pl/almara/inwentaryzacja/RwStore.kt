package pl.almara.inwentaryzacja

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Magazyn dokumentów RW: jeden dokument = jeden plik JSON w pamięci aplikacji. */
object RwStore {

    private fun dir(context: Context): File =
        File(context.filesDir, "rw").apply { mkdirs() }

    private fun file(context: Context, id: String): File =
        File(dir(context), "$id.json")

    fun list(context: Context): List<RwDocument> =
        dir(context).listFiles { f -> f.extension == "json" }
            ?.mapNotNull { runCatching { fromJson(it.readText()) }.getOrNull() }
            ?.sortedByDescending { it.createdAt }
            ?: emptyList()

    fun get(context: Context, id: String): RwDocument? {
        val f = file(context, id)
        if (!f.exists()) return null
        return runCatching { fromJson(f.readText()) }.getOrNull()
    }

    fun create(context: Context): RwDocument {
        val doc = RwDocument(UUID.randomUUID().toString(), "", SessionStore.timestamp(), "", "", mutableListOf())
        save(context, doc)
        return doc
    }

    fun delete(context: Context, id: String) {
        file(context, id).delete()
    }

    enum class PutResult { ADDED, UPDATED }

    /**
     * Dodaje pozycję lub — gdy ten sam kod już jest — nadpisuje ją nową ilością
     * wydaną (ponowne skanowanie służy do korekty). Zwraca null, gdy brak dokumentu.
     */
    fun putItem(context: Context, id: String, item: ScanItem): PutResult? {
        val doc = get(context, id) ?: return null
        val i = doc.items.indexOfFirst { it.raw == item.raw }
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

    fun deleteItem(context: Context, id: String, raw: String) {
        val doc = get(context, id) ?: return
        doc.items.removeAll { it.raw == raw }
        save(context, doc)
    }

    /** Zmienia ilość wydaną istniejącej pozycji. */
    fun setItemQuantity(context: Context, id: String, raw: String, quantity: Double) {
        val doc = get(context, id) ?: return
        val i = doc.items.indexOfFirst { it.raw == raw }
        if (i >= 0) {
            doc.items[i] = doc.items[i].copy(quantity = quantity)
            save(context, doc)
        }
    }

    fun setPeople(context: Context, id: String, requester: String, manager: String) {
        val doc = get(context, id) ?: return
        save(context, doc.copy(requester = requester, manager = manager))
    }

    /** Nadaje numer (jeśli jeszcze nie ma) i zwraca zatwierdzony dokument. */
    fun finalize(context: Context, id: String): RwDocument? {
        val doc = get(context, id) ?: return null
        if (doc.number.isNotEmpty()) return doc
        val finalized = doc.copy(number = Settings.nextRwNumber(context))
        save(context, finalized)
        return finalized
    }

    private fun save(context: Context, doc: RwDocument) {
        val target = file(context, doc.id)
        val tmp = File(target.parentFile, "${doc.id}.tmp")
        tmp.writeText(toJson(doc))
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }

    private fun toJson(doc: RwDocument): String {
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

    private fun fromJson(text: String): RwDocument {
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
