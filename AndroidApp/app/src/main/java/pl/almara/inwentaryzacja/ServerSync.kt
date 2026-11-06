package pl.almara.inwentaryzacja

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.TimeUnit

object ServerSync {
    @Synchronized
    fun perform(context: Context, stopped: () -> Boolean = { false }): Boolean {
        val urls = Settings.serverUrls(context)
        if (urls.isEmpty()) return true
        val routes = ServerRoutes(urls)
        val errors = mutableListOf<String>()
        fun active() = !stopped() && Settings.serverUrls(context) == urls
        val registeredRoutes = mutableSetOf<String>()
        fun register(api: ServerApi, url: String) {
            check(active())
            if (url in registeredRoutes) return
            val identity = DocumentNumbering.identity(context)
            val body = JSONObject().put("installationId", identity.installationId)
                .put("deviceName", Build.MANUFACTURER + " " + Build.MODEL)
                .put("deviceKey", DocumentNumbering.deviceKey(context)).put("counters", DocumentNumbering.counters(context)).toString().toByteArray(Charsets.UTF_8)
            val response = api.request("POST", "/devices/register", body)
            check(active())
            val rawNumber = response.get("phoneNumber")
            require(rawNumber is Number)
            val phoneNumber = rawNumber.toString().toBigDecimal().intValueExact()
            DocumentNumbering.register(context, response.getString("installationId"), phoneNumber, url,
                response.getJSONObject("counters"), response.getBoolean("recoveryRequired"))
            registeredRoutes.add(url)
        }
        var registered = false
        try {
            routes.call { api, url -> register(api, url) }
            registered = true
        } catch (error: Exception) { errors.add(context.getString(R.string.server_registration_failed) + ": " + reason(context, error)) }

        if (registered && active()) {
            try { DocumentRecovery.restore(context, routes, ::active) }
            catch (error: Exception) { errors.add(context.getString(R.string.server_recovery_failed) + ": " + reason(context, error)) }
        }

        if (!active()) return true
        try {
            val catalog = routes.call { api, _ -> MaterialCatalog.parse(api.request("GET", "/catalogue")) }
            if (!active()) return true
            CatalogStore.replace(context, catalog)
        } catch (error: Exception) { errors.add(context.getString(R.string.server_catalog_failed) + ": " + reason(context, error)) }

        if (!active()) return true
        try {
            val rules = routes.call { api, _ -> LabelRules.parse(api.request("GET", "/label-rules")) }
            if (!active()) return true
            LabelRuleStore.replace(context, rules)
        } catch (error: Exception) { errors.add(context.getString(R.string.server_rules_failed) + ": " + reason(context, error)) }

        if (registered && active() && !DocumentNumbering.recoveryPending(context)) {
            val identity = DocumentNumbering.identity(context)
            for (doc in PzStore.list(context).filter { it.approved && !PzBackupStore.isBackedUp(context, it.id) }) {
                if (!active()) return true
                try {
                    val pdf = PzPdf.build(context, doc)
                    val bytes = pdf.readBytes()
                    val payload = JSONObject().put("installationId", identity.installationId)
                        .put("phoneNumber", identity.phoneNumber).put("document", PzJson.document(doc))
                        .put("pdf", JSONObject().put("fileName", PzPdf.fileName(doc))
                            .put("sha256", Digests.sha256(bytes)).put("contentBase64", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                        .toString().toByteArray(Charsets.UTF_8)
                    val sha256 = Digests.sha256(payload)
                    val response = routes.call { api, url ->
                        register(api, url)
                        LabelPhotos.upload(context, doc, api, url)
                        api.request("PUT", "/pz/" + doc.id + "/backup", payload)
                    }
                    require(response.getString("documentId") == doc.id && response.getString("sha256") == sha256)
                    val storedAt = response.getString("storedAt")
                    OffsetDateTime.parse(storedAt)
                    if (!active()) return true
                    PzBackupStore.acknowledge(context, doc.id, urls.first(), sha256, storedAt)
                } catch (error: Exception) {
                    if (!active()) return true
                    val message = reason(context, error)
                    DocumentBackups.failed(context, "PZ", doc.id, urls.first(), message)
                    errors.add(context.getString(R.string.server_backup_failed) + " (" + doc.number + "): " + message)
                }
            }
            for (doc in RwStore.list(context).filter { it.number.isNotEmpty() && !DocumentBackups.isBackedUp(context, "RW", it.id) }) {
                if (!active()) return true
                try {
                    val payload = JSONObject().put("installationId", identity.installationId)
                        .put("phoneNumber", identity.phoneNumber).put("document", JSONObject(RwStore.toJson(doc)))
                        .toString().toByteArray(Charsets.UTF_8)
                    val hash = Digests.sha256(payload)
                    val response = routes.call { api, url -> register(api, url); api.request("PUT", "/rw/" + doc.id + "/backup", payload) }
                    require(response.getString("documentId") == doc.id && response.getString("sha256") == hash)
                    val storedAt = response.getString("storedAt")
                    OffsetDateTime.parse(storedAt)
                    if (!active()) return true
                    DocumentBackups.acknowledge(context, "RW", doc.id, checkNotNull(routes.activeUrl), hash, storedAt)
                } catch (error: Exception) {
                    if (!active()) return true
                    val message = reason(context, error)
                    DocumentBackups.failed(context, "RW", doc.id, urls.first(), message)
                    errors.add(context.getString(R.string.server_rw_backup_failed) + " (" + doc.number + "): " + message)
                }
            }
        }
        if (active()) Settings.setSyncResult(context, PzSummary.now(), errors.joinToString("\n"))
        return errors.isEmpty()
    }

    internal fun reason(context: Context, error: Throwable): String = when (error) {
        is ServerApiException -> error.message.orEmpty()
        is java.io.IOException -> context.getString(R.string.server_unreachable)
        else -> context.getString(R.string.server_invalid_response)
    }
}

class ServerSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result = if (ServerSync.perform(applicationContext) { isStopped }) Result.success() else Result.retry()
}

object SyncScheduler {
    fun start(context: Context) {
        val periodic = PeriodicWorkRequest.Builder(ServerSyncWorker::class.java, 1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("warehouse-periodic-sync", ExistingPeriodicWorkPolicy.KEEP, periodic)
        if (Settings.serverUrls(context).isNotEmpty()) request(context)
    }

    fun request(context: Context, replace: Boolean = false): UUID {
        val work = OneTimeWorkRequest.Builder(ServerSyncWorker::class.java)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("warehouse-sync",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, work)
        return work.id
    }
}
