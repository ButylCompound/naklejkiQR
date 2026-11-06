package pl.almara.inwentaryzacja

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.util.UUID

object DocumentRecovery {
    fun restore(context: Context, routes: ServerRoutes, active: () -> Boolean) {
        val identity = DocumentNumbering.identity(context)
        val path = "/devices/" + identity.installationId + "/documents"
        val query = "?deviceKey=" + DocumentNumbering.deviceKey(context)
        while (DocumentNumbering.recoveryPending(context)) {
            check(active())
            val cursor = DocumentNumbering.recoveryCursor(context)
            val page = routes.call { api, _ -> api.request("GET", path + query + "&after=" + URLEncoder.encode(cursor, "UTF-8")) }
            val documents = page.getJSONArray("documents")
            for (index in 0 until documents.length()) {
                check(active())
                val entry = documents.getJSONObject(index)
                val module = entry.getString("module")
                require(module in listOf("PZ", "RW"))
                val id = entry.getString("documentId")
                require(UUID.fromString(id).toString() == id)
                val response = routes.call { api, _ -> api.request("GET", "$path/$module/$id$query") }
                require(response.getString("module") == module)
                val document = response.getJSONObject("document")
                require(document.getString("id") == id && document.getString("number") == entry.getString("number"))
                val hash = response.getString("sha256")
                require(Regex("[a-f0-9]{64}").matches(hash))
                val storedAt = response.getString("storedAt")
                OffsetDateTime.parse(storedAt)
                check(active())
                if (module == "PZ") {
                    val doc = PzJson.document(document)
                    val pdf = response.getJSONObject("pdf")
                    require(pdf.getString("fileName") == PzPdf.fileName(doc))
                    val bytes = Base64.decode(pdf.getString("contentBase64"), Base64.DEFAULT)
                    require(bytes.isNotEmpty() && Digests.sha256(bytes) == pdf.getString("sha256"))
                    for (item in doc.items) item.label?.photo?.let { photo ->
                        check(active())
                        val saved = routes.call { api, _ -> api.request("GET", "$path/PZ/$id/entries/${item.id}/photo$query") }
                        require(saved.getString("photoId") == photo.id && saved.getString("sha256") == photo.sha256)
                        val image = Base64.decode(saved.getString("contentBase64"), Base64.DEFAULT)
                        check(active())
                        LabelPhotos.restore(context, photo, image)
                        LabelPhotos.acknowledge(context, id, item.id, photo, checkNotNull(routes.activeUrl))
                    }
                    PzStore.restore(context, doc, bytes)
                } else RwStore.restore(context, RwStore.fromJson(document.toString()))
                DocumentBackups.acknowledge(context, module, id, checkNotNull(routes.activeUrl), hash, storedAt)
            }
            val next = if (page.isNull("nextCursor")) null else page.getString("nextCursor")
            require(next == null || (next > cursor && Regex("(PZ|RW):[a-f0-9-]{36}").matches(next) && documents.length() > 0))
            check(active())
            DocumentNumbering.recoveredPage(context, next)
        }
    }
}
