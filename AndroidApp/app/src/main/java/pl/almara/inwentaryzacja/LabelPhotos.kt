package pl.almara.inwentaryzacja

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import androidx.core.util.tryWrite
import org.json.JSONObject
import java.io.File
import java.util.UUID

object LabelPhotos {
    private const val MAX_BYTES = 16 * 1024 * 1024

    fun file(context: Context, id: String): File {
        require(UUID.fromString(id).toString() == id)
        return File(context.filesDir, "label_photos/$id.jpg")
    }

    fun captureFile(context: Context): File = file(context, UUID.randomUUID().toString()).also { it.parentFile!!.mkdirs() }

    fun describe(file: File): LabelPhoto = LabelPhoto(file.nameWithoutExtension, Digests.sha256(file.readBytes()))

    fun bytes(context: Context, photo: LabelPhoto): ByteArray = AtomicFile(file(context, photo.id)).openRead().use { it.readBytes() }
        .also { validate(photo, it) }

    private fun validate(photo: LabelPhoto, bytes: ByteArray) {
        UUID.fromString(photo.id)
        require(bytes.size in 4..MAX_BYTES && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() &&
            bytes[bytes.lastIndex - 1] == 0xff.toByte() && bytes.last() == 0xd9.toByte())
        require(Digests.sha256(bytes) == photo.sha256)
    }

    fun restore(context: Context, photo: LabelPhoto, bytes: ByteArray) {
        validate(photo, bytes)
        AtomicFile(file(context, photo.id)).tryWrite { it.write(bytes) }
    }

    fun delete(context: Context, photo: LabelPhoto) = AtomicFile(file(context, photo.id)).delete()

    fun upload(context: Context, doc: PzDocument, api: ServerApi, serverUrl: String) {
        val identity = DocumentNumbering.identity(context)
        for (entry in doc.items) {
            val photo = entry.label?.photo ?: continue
            if (receipt(context, doc.id, entry.id)?.let { it.optString("serverUrl") in Settings.serverUrls(context) &&
                    it.optString("photoSha256") == photo.sha256 } == true) continue
            val body = JSONObject().put("installationId", identity.installationId).put("phoneNumber", identity.phoneNumber)
                .put("photo", JSONObject().put("id", photo.id).put("sha256", photo.sha256)
                    .put("contentBase64", Base64.encodeToString(bytes(context, photo), Base64.NO_WRAP)))
                .toString().toByteArray(Charsets.UTF_8)
            val response = api.request("PUT", "/pz/${doc.id}/entries/${entry.id}/photo", body)
            require(response.getString("photoId") == photo.id && response.getString("sha256") == photo.sha256)
            acknowledge(context, doc.id, entry.id, photo, serverUrl)
        }
    }

    fun acknowledge(context: Context, documentId: String, entryId: String, photo: LabelPhoto, serverUrl: String) =
        JsonFiles.write(receiptFile(context, documentId, entryId), JSONObject().put("serverUrl", serverUrl).put("photoSha256", photo.sha256))

    private fun receipt(context: Context, documentId: String, entryId: String) = JsonFiles.read(receiptFile(context, documentId, entryId))
    private fun receiptFile(context: Context, documentId: String, entryId: String): File {
        UUID.fromString(documentId)
        UUID.fromString(entryId)
        return File(context.filesDir, "pz_photo_backup/$documentId/$entryId.json")
    }
}
