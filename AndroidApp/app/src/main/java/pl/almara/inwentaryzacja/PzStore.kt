package pl.almara.inwentaryzacja

import android.content.Context
import java.io.File
import java.util.UUID

object PzStore {
    private fun dir(context: Context) = File(context.filesDir, "pz")
    private fun file(context: Context, id: String): File {
        UUID.fromString(id)
        return File(dir(context), id + ".json")
    }

    @Synchronized
    fun list(context: Context): List<PzDocument> = JsonFiles.list(dir(context))
        .mapNotNull { JsonFiles.read(it)?.let(PzJson::document) }.sortedByDescending { it.createdAt }

    @Synchronized
    fun get(context: Context, id: String): PzDocument? = JsonFiles.read(file(context, id))?.let(PzJson::document)

    @Synchronized
    fun create(context: Context): PzDocument = PzDocument(UUID.randomUUID().toString(), PzSummary.now()).also {
        JsonFiles.write(file(context, it.id), PzJson.document(it))
    }

    @Synchronized
    fun edit(context: Context, id: String, transform: (PzDocument) -> PzDocument): PzDocument {
        val current = get(context, id) ?: throw IllegalArgumentException("Missing PZ")
        check(!current.approved)
        val changed = transform(current)
        require(changed.id == current.id && changed.createdAt == current.createdAt && !changed.approved)
        changed.items.mapNotNull { it.label?.photo }.filter { photo -> current.items.none { it.label?.photo == photo } }
            .forEach { LabelPhotos.bytes(context, it) }
        JsonFiles.write(file(context, id), PzJson.document(changed))
        current.items.mapNotNull { it.label?.photo }.filter { photo -> changed.items.none { it.label?.photo == photo } }
            .forEach { LabelPhotos.delete(context, it) }
        return changed
    }

    fun putEntry(context: Context, id: String, entry: PzEntry) = edit(context, id) { doc ->
        UUID.fromString(entry.id)
        entry.problem()?.let { throw PzValidationException(it) }
        doc.copy(items = if (doc.items.any { it.id == entry.id }) doc.items.map { if (it.id == entry.id) entry else it }
            else doc.items + entry)
    }

    fun deleteEntries(context: Context, id: String, selected: Set<String>) = edit(context, id) { doc ->
        doc.copy(items = doc.items.filterNot { it.id in selected })
    }

    @Synchronized
    fun approve(context: Context, id: String): PzDocument {
        val doc = get(context, id) ?: throw IllegalArgumentException("Missing PZ")
        if (doc.approved) return doc
        doc.problem()?.let { throw PzValidationException(it) }
        val reservation = DocumentNumbering.reserve(context, "PZ")
        val approved = doc.copy(number = reservation.number, phoneNumber = reservation.phoneNumber,
            installationId = reservation.installationId, approvedAt = PzSummary.now())
        JsonFiles.write(file(context, id), PzJson.document(approved))
        return approved
    }

    @Synchronized
    fun restore(context: Context, doc: PzDocument, pdf: ByteArray) {
        require(doc.approved && doc.problem() == null)
        require(Regex("PZ/[0-9]{4}/[0-9]+/[0-9]+").matches(doc.number))
        val existing = get(context, doc.id)
        require(existing == null || existing == doc) { "Local PZ differs from its backup" }
        doc.items.mapNotNull { it.label?.photo }.forEach { LabelPhotos.bytes(context, it) }
        PzPdf.restore(context, doc, pdf)
        JsonFiles.write(file(context, doc.id), PzJson.document(doc))
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        val doc = get(context, id) ?: return
        check(!doc.approved)
        JsonFiles.delete(file(context, id))
        doc.items.mapNotNull { it.label?.photo }.forEach { LabelPhotos.delete(context, it) }
    }
}
