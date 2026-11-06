package pl.almara.inwentaryzacja

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.UUID

object PzBackupStore {
    fun receipt(context: Context, id: String) = DocumentBackups.receipt(context, "PZ", id)
    fun error(context: Context, id: String) = DocumentBackups.error(context, "PZ", id)
    fun isBackedUp(context: Context, id: String) = DocumentBackups.isBackedUp(context, "PZ", id)
    fun acknowledge(context: Context, id: String, serverUrl: String, sha256: String, storedAt: String) =
        DocumentBackups.acknowledge(context, "PZ", id, serverUrl, sha256, storedAt)
}

object DocumentBackups {
    private fun file(context: Context, module: String, id: String): File {
        require(module in listOf("PZ", "RW"))
        UUID.fromString(id)
        return File(context.filesDir, module.lowercase() + "_backup/" + id + ".json")
    }

    @Synchronized
    fun receipt(context: Context, module: String, id: String): JSONObject? = JsonFiles.read(file(context, module, id))

    fun isBackedUp(context: Context, module: String, id: String): Boolean = receipt(context, module, id)?.let {
        it.optString("serverUrl") in Settings.serverUrls(context) && !it.has("error") && it.optString("sha256").isNotEmpty()
    } == true

    fun error(context: Context, module: String, id: String): String? = receipt(context, module, id)?.let {
        if (it.optString("serverUrl") in Settings.serverUrls(context)) it.optString("error").ifEmpty { null } else null
    }

    @Synchronized
    fun failed(context: Context, module: String, id: String, serverUrl: String, error: String) {
        JsonFiles.write(file(context, module, id), JSONObject().put("documentId", id).put("serverUrl", serverUrl)
            .put("error", error).put("failedAt", PzSummary.now()))
    }

    @Synchronized
    fun acknowledge(context: Context, module: String, id: String, serverUrl: String, sha256: String, storedAt: String) {
        JsonFiles.write(file(context, module, id), JSONObject().put("documentId", id).put("serverUrl", serverUrl)
            .put("sha256", sha256).put("storedAt", storedAt))
    }
}
