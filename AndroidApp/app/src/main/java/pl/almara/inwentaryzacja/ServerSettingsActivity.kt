package pl.almara.inwentaryzacja

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.work.WorkManager
import androidx.work.WorkInfo
import pl.almara.inwentaryzacja.databinding.ActivityServerSettingsBinding
import kotlin.concurrent.thread

class ServerSettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityServerSettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        if (savedInstanceState == null) {
            binding.serverUrlInput.setText(Settings.serverUrl(this).let { if (it.isEmpty()) it else ServerAddress.normalize(it) })
            binding.fallbackServerUrlInput.setText(Settings.fallbackServerUrl(this).let { if (it.isEmpty()) it else ServerAddress.normalize(it) })
        }
        binding.testConnectionButton.setOnClickListener { testConnection() }
        binding.connectButton.setOnClickListener {
            val urls = addresses() ?: return@setOnClickListener
            Settings.setServerUrls(this, urls[0], urls[1])
            if (urls.all { it.isEmpty() }) { refresh(); return@setOnClickListener }
            val id = SyncScheduler.request(this, replace = true)
            binding.syncProgress.visibility = View.VISIBLE
            binding.connectButton.isEnabled = false
            binding.syncValue.setText(R.string.server_connecting)
            WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
                if (info != null && (info.state.isFinished || info.state == WorkInfo.State.ENQUEUED && info.runAttemptCount > 0)) {
                    binding.syncProgress.visibility = View.GONE
                    binding.connectButton.isEnabled = true
                    PzUi.guard(this) { refresh() }
                }
            }
        }
    }

    override fun onResume() { super.onResume(); PzUi.guard(this) { refresh() } }

    private fun addresses(): List<String>? = listOf(binding.serverUrlInput, binding.fallbackServerUrlInput).map { input ->
        input.error = null
        val value = input.text.toString().trim()
        try { if (value.isEmpty()) "" else ServerAddress.normalize(value) }
        catch (_: Exception) { input.error = getString(R.string.server_invalid_url); return null }
    }

    private fun testConnection() {
        val urls = addresses()?.filter { it.isNotEmpty() }?.distinct() ?: return
        if (urls.isEmpty()) { binding.serverUrlInput.error = getString(R.string.server_invalid_url); return }
        binding.serverUrlInput.isEnabled = false
        binding.fallbackServerUrlInput.isEnabled = false
        binding.testConnectionButton.isEnabled = false
        binding.testResult.visibility = View.VISIBLE
        binding.testResult.setText(R.string.server_connecting)
        thread(name = "server-connection-test") {
            val result = urls.joinToString("\n\n") { url ->
                val response = runCatching { MaterialCatalog.parse(ServerApi(url).request("GET", "/catalogue")) }
                url + "\n" + response.fold(
                    { getString(R.string.server_test_success) },
                    { getString(R.string.server_test_failed, ServerSync.reason(this, it)) })
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                binding.serverUrlInput.isEnabled = true
                binding.fallbackServerUrlInput.isEnabled = true
                binding.testConnectionButton.isEnabled = true
                binding.testResult.text = result
            }
        }
    }

    private fun refresh() {
        val identity = DocumentNumbering.identity(this)
        binding.phoneValue.text = getString(R.string.server_phone,
            identity.phoneNumber?.toString() ?: getString(R.string.server_not_registered))
        val catalog = CatalogStore.get(this)
        binding.catalogValue.text = if (catalog == null) getString(R.string.pz_catalog_empty)
            else getString(R.string.pz_catalog_count, catalog.materials.size, PzSummary.date(catalog.updatedAt))
        binding.rulesValue.text = getString(R.string.label_rules_updated, PzSummary.date(LabelRuleStore.get(this).updatedAt))
        val at = Settings.lastSyncAt(this)
        binding.syncValue.text = if (Settings.serverUrls(this).isEmpty()) getString(R.string.server_no_address)
            else if (at.isEmpty()) getString(R.string.server_connecting)
            else getString(R.string.server_last_attempt, PzSummary.date(at)) + "\n" +
                Settings.lastSyncError(this).ifEmpty { getString(R.string.server_success) }
    }
}
