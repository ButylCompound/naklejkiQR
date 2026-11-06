package pl.almara.inwentaryzacja

import android.content.Context
import android.provider.Settings.Secure
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

object DocumentNumbering {
    data class Identity(val installationId: String, val phoneNumber: Int?)
    data class Reservation(val number: String, val installationId: String, val phoneNumber: Int)

    private fun file(context: Context) = File(context.noBackupFilesDir, "document-numbering.json")

    private fun state(context: Context): JSONObject = JsonFiles.read(file(context)) ?: JSONObject().apply {
        put("installationId", UUID.randomUUID().toString())
        put("phoneNumber", JSONObject.NULL)
        put("counters", JSONObject())
        JsonFiles.write(file(context), this)
    }

    @Synchronized
    fun identity(context: Context): Identity = state(context).let {
        Identity(it.getString("installationId"), if (it.isNull("phoneNumber")) null else it.getInt("phoneNumber"))
    }

    fun deviceKey(context: Context): String {
        val androidId = Secure.getString(context.contentResolver, Secure.ANDROID_ID)
        require(!androidId.isNullOrBlank())
        return Digests.sha256((context.packageName + ":" + androidId).toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun counters(context: Context): JSONObject {
        val result = JSONObject(state(context).getJSONObject("counters").toString())
        fun include(key: String, value: Long) { result.put(key, maxOf(if (result.has(key)) result.getString(key).toLong() else 0, value).toString()) }
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val year = prefs.getInt("rw_year", 0)
        if (year > 0) include("RW/$year", prefs.getInt("rw_seq", 0).toLong())
        result.keys().asSequence().toList().forEach { include(it, result.getString(it).toLong()) }
        return result
    }

    @Synchronized
    fun register(context: Context, installationId: String, phoneNumber: Int, serverUrl: String,
                 counters: JSONObject = JSONObject(), recoveryRequired: Boolean = false) {
        require(phoneNumber > 0)
        val state = state(context)
        UUID.fromString(installationId)
        val changed = state.getString("installationId") != installationId
        require(!changed || (state.isNull("phoneNumber") && recoveryRequired)) { "Registration installationId mismatch" }
        val local = state.getJSONObject("counters")
        for (key in counters.keys()) {
            require(Regex("(PZ|RW)/[0-9]{4}").matches(key))
            val value = counters.getString(key).toLong()
            require(value >= 0)
            local.put(key, maxOf(local.optLong(key), value))
        }
        if (changed) {
            state.put("installationId", installationId)
            state.put("recoveryPending", true)
            state.remove("recoveryCursor")
        }
        require(state.isNull("phoneNumber") || state.getInt("phoneNumber") == phoneNumber) { "Phone number changed" }
        state.put("phoneNumber", phoneNumber)
        state.put("serverUrl", serverUrl)
        JsonFiles.write(file(context), state)
    }

    @Synchronized
    fun recoveryPending(context: Context): Boolean = state(context).optBoolean("recoveryPending")

    @Synchronized
    fun recoveryCursor(context: Context): String = state(context).optString("recoveryCursor")

    @Synchronized
    fun recoveredPage(context: Context, nextCursor: String?) {
        val state = state(context)
        state.put("recoveryPending", nextCursor != null)
        state.put("recoveryCursor", nextCursor ?: "")
        JsonFiles.write(file(context), state)
    }

    fun format(module: String, year: Int, phone: Int, sequence: Long): String =
        String.format(Locale.ROOT, "%s/%04d/%02d/%04d", module, year, phone, sequence)

    @Synchronized
    fun reserve(context: Context, module: String, year: Int = LocalDate.now().year, minimum: Long = 0): Reservation {
        require(module in listOf("PZ", "RW"))
        val state = state(context)
        val phone = if (state.isNull("phoneNumber")) 0 else state.getInt("phoneNumber")
        val counters = state.getJSONObject("counters")
        val key = module + "/" + year
        var previous = maxOf(counters.optLong(key, 0), minimum)
        if (module == "RW") {
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            if (prefs.getInt("rw_year", 0) == year) previous = maxOf(previous, prefs.getInt("rw_seq", 0).toLong())
        }
        val sequence = Math.addExact(previous, 1)
        counters.put(key, sequence)
        JsonFiles.write(file(context), state)
        return Reservation(format(module, year, phone, sequence), state.getString("installationId"), phone)
    }
}
