package pl.almara.inwentaryzacja

import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import pl.almara.inwentaryzacja.databinding.ActivityAppUpdateBinding
import java.util.UUID

class AppUpdateActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppUpdateBinding
    private var update: AppUpdate? = null
    private var workId: UUID? = null
    private var handledInstall: String? = null
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) download()
        else binding.status.setText(R.string.update_permission_needed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.version.text = getString(R.string.settings_version, packageManager.getPackageInfo(packageName, 0).versionName)
        binding.action.setOnClickListener { if (update == null) start(null) else download() }
        handledInstall = savedInstanceState?.getString("handledInstall")
        savedInstanceState?.getString("update")?.let { update = AppUpdate.parse(JSONObject(it)) }
        val savedId = savedInstanceState?.getString("workId")
        if (savedId == null) start(null) else observe(UUID.fromString(savedId))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("workId", workId?.toString())
        outState.putString("update", update?.json()?.toString())
        outState.putString("handledInstall", handledInstall)
        super.onSaveInstanceState(outState)
    }

    private fun download() { update?.let { start(it) } }

    private fun start(candidate: AppUpdate?) {
        val request = OneTimeWorkRequest.Builder(AppUpdateWorker::class.java)
            .setInputData(Data.Builder().putString("update", candidate?.json()?.toString()).build()).build()
        WorkManager.getInstance(this).enqueueUniqueWork("app-update", ExistingWorkPolicy.REPLACE, request)
        observe(request.id)
    }

    private fun observe(id: UUID) {
        workId = id
        binding.action.isEnabled = false
        binding.progress.visibility = View.VISIBLE
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (id != workId || info == null) return@observe
            binding.action.isEnabled = info.state.isFinished
            binding.progress.visibility = if (info.state.isFinished) View.GONE else View.VISIBLE
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    update = info.outputData.getString("update")?.let { AppUpdate.parse(JSONObject(it)) }
                    val candidate = update
                    val downloaded = info.outputData.getBoolean("downloaded", false)
                    binding.status.text = if (candidate == null) getString(R.string.update_current)
                        else getString(if (downloaded) R.string.update_ready else R.string.update_available, candidate.versionName)
                    binding.action.setText(if (candidate == null) R.string.update_check else if (downloaded) R.string.update_install else R.string.update_download)
                    if (downloaded && candidate != null && handledInstall != id.toString()) {
                        handledInstall = id.toString()
                        install(candidate)
                    }
                }
                WorkInfo.State.FAILED -> {
                    val error = info.outputData.getInt("error", R.string.server_invalid_response)
                    binding.status.text = if (error == R.string.update_http_error) getString(error, info.outputData.getInt("status", 0)) else getString(error)
                    binding.action.setText(R.string.update_retry)
                }
                WorkInfo.State.CANCELLED -> { binding.status.setText(R.string.update_cancelled); binding.action.setText(R.string.update_retry) }
                else -> {
                    val percent = info.progress.getInt("percent", -1)
                    binding.progress.isIndeterminate = percent < 0
                    if (percent >= 0) binding.progress.progress = percent
                    binding.status.text = if (update == null) getString(R.string.update_checking)
                        else if (percent < 0) getString(R.string.update_downloading) else getString(R.string.update_download_progress, percent)
                }
            }
        }
    }

    private fun install(candidate: AppUpdate) {
        if (!packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.update_install)
                .setMessage(R.string.update_permission_needed)
                .setPositiveButton(R.string.update_open_permission) { _, _ ->
                    permission.launch(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
                }.setNegativeButton(R.string.cancel, null).show()
            return
        }
        val file = AppUpdater.file(this, candidate)
        if (!file.exists()) { download(); return }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("APK", uri) })
        } catch (_: android.content.ActivityNotFoundException) { binding.status.setText(R.string.update_installer_missing) }
    }
}
