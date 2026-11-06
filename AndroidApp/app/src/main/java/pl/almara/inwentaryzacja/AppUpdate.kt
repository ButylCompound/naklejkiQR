package pl.almara.inwentaryzacja

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.Data
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.io.File
import java.io.InterruptedIOException
import java.security.MessageDigest

data class AppUpdate(val versionCode: Long, val versionName: String, val sizeBytes: Long, val sha256: String) {
    val path get() = "/app-updates/$versionCode/$sha256.apk"
    fun json(): JSONObject = JSONObject().put("versionCode", versionCode).put("versionName", versionName)
        .put("sizeBytes", sizeBytes).put("sha256", sha256)

    companion object {
        fun parse(json: JSONObject): AppUpdate {
            fun integer(key: String): Long {
                val value = json.get(key)
                require(value is Number)
                return value.toString().toBigDecimal().longValueExact()
            }
            return AppUpdate(integer("versionCode"), json.getString("versionName"), integer("sizeBytes"), json.getString("sha256")).also {
                require(it.versionCode in 1..Int.MAX_VALUE.toLong() && it.versionName.isNotBlank() && it.sizeBytes > 0 &&
                    it.sha256.matches(Regex("[a-f0-9]{64}")))
            }
        }
    }
}

class UpdateRejected : Exception()

object AppUpdater {
    private fun installed(context: Context) = context.packageManager.getPackageInfo(context.packageName, signatureFlags())
    fun installedVersion(context: Context) = PackageInfoCompat.getLongVersionCode(installed(context))
    @Suppress("DEPRECATION")
    private fun signatureFlags() = PackageManager.GET_SIGNATURES or
        if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0

    @Suppress("DEPRECATION")
    private fun signatures(info: PackageInfo): Set<String> =
        (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures)
            .orEmpty().map { Digests.sha256(it.toByteArray()) }.toSet()

    fun file(context: Context, update: AppUpdate) = File(context.cacheDir, "updates/${update.versionCode}-${update.sha256}.apk")

    fun check(context: Context, routes: ServerRoutes): AppUpdate? {
        val json = routes.call { api, _ -> api.request("GET", "/app-updates/latest") }
        require(json.has("update"))
        return if (json.isNull("update")) null else AppUpdate.parse(json.getJSONObject("update"))
            .takeIf { it.versionCode > installedVersion(context) }
    }

    fun validate(context: Context, update: AppUpdate, file: File) {
        if (file.length() != update.sizeBytes) throw UpdateRejected()
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        if (hash != update.sha256) throw UpdateRejected()
        val archive = context.packageManager.getPackageArchiveInfo(file.path, signatureFlags()) ?: throw UpdateRejected()
        val current = installed(context)
        if (archive.packageName != context.packageName || PackageInfoCompat.getLongVersionCode(archive) != update.versionCode ||
            update.versionCode <= PackageInfoCompat.getLongVersionCode(current) || archive.versionName != update.versionName ||
            (archive.applicationInfo?.minSdkVersion ?: Int.MAX_VALUE) > Build.VERSION.SDK_INT ||
            signatures(archive).isEmpty() || signatures(archive) != signatures(current)) throw UpdateRejected()
    }

    fun download(context: Context, routes: ServerRoutes, update: AppUpdate, stopped: () -> Boolean,
        progress: (Long) -> Unit): File {
        val destination = file(context, update)
        destination.parentFile!!.mkdirs()
        if (destination.exists()) {
            try { validate(context, update, destination); return destination }
            catch (_: UpdateRejected) { destination.delete() }
        }
        routes.call { api, _ ->
            val partials = File(destination.parentFile, "partial").apply { mkdirs() }
            val partial = File.createTempFile("download-", ".apk", partials)
            val connection = api.connection(update.path)
            try {
                connection.readTimeout = 60000
                connection.setRequestProperty("Accept", "application/vnd.android.package-archive")
                val status = connection.responseCode
                if (status != 200) throw ServerApiException(status)
                val length = connection.contentLengthLong
                if (length >= 0 && length != update.sizeBytes) throw UpdateRejected()
                var received = 0L
                var lastPercent = -1L
                connection.inputStream.use { input -> partial.outputStream().use { output ->
                    val bytes = ByteArray(64 * 1024)
                    while (true) {
                        if (stopped()) throw InterruptedIOException()
                        val count = input.read(bytes)
                        if (count < 0) break
                        received += count
                        if (received > update.sizeBytes) throw UpdateRejected()
                        output.write(bytes, 0, count)
                        val percent = (received.toDouble() / update.sizeBytes * 100).toLong()
                        if (percent != lastPercent) { progress(percent); lastPercent = percent }
                    }
                } }
                validate(context, update, partial)
                if (stopped()) throw InterruptedIOException()
                check(partial.renameTo(destination))
                destination.parentFile!!.listFiles()?.filter { it != destination && it.extension == "apk" }?.forEach { it.delete() }
            } finally { connection.disconnect(); partial.delete() }
        }
        return destination
    }
}

class AppUpdateWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = try {
        val urls = Settings.serverUrls(applicationContext)
        if (urls.isEmpty()) Result.failure(Data.Builder().putInt("error", R.string.update_no_server).build())
        else {
            val routes = ServerRoutes(urls)
            val requested = inputData.getString("update")
            val update = if (requested == null) AppUpdater.check(applicationContext, routes) else AppUpdate.parse(JSONObject(requested))
            if (requested != null && update != null) AppUpdater.download(applicationContext, routes, update, { isStopped }) {
                setProgressAsync(Data.Builder().putInt("percent", it.toInt()).build())
            }
            Result.success(Data.Builder().putString("update", update?.json()?.toString()).putBoolean("downloaded", requested != null).build())
        }
    } catch (error: Exception) {
        if (isStopped) Result.failure() else Result.failure(Data.Builder().putInt("error", when (error) {
            is UpdateRejected -> R.string.update_invalid_apk
            is ServerApiException -> R.string.update_http_error
            is java.io.IOException -> R.string.server_unreachable
            else -> R.string.server_invalid_response
        }).putInt("status", (error as? ServerApiException)?.status ?: 0).build())
    }
}
