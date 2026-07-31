package pl.almara.inwentaryzacja

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import pl.almara.inwentaryzacja.databinding.ActivitySessionDetailBinding

class SessionDetailActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val NOTIF_CHANNEL_ID = "csv_export"
    }

    private lateinit var binding: ActivitySessionDetailBinding
    private lateinit var sessionId: String
    private var session: Session? = null
    private var lastSavedUri: Uri? = null

    /** "Zapisz CSV" — użytkownik wybiera lokalizację (np. folder OneDrive/SharePoint). */
    private val saveCsvLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val s = session ?: return@registerForActivityResult
            if (uri == null) return@registerForActivityResult
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(CsvExporter.buildCsv(s).toByteArray(Charsets.UTF_8))
                }
                // Bez trwałego uprawnienia dostęp do pliku wygasa wraz z zamknięciem
                // aplikacji i powiadomienie przestałoby otwierać plik
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                lastSavedUri = uri
                notifySaved()
            } catch (e: Exception) {
                Toast.makeText(
                    this, getString(R.string.csv_save_error, e.message), Toast.LENGTH_LONG
                ).show()
            }
        }

    /** Android 13+ wymaga zgody na pokazywanie powiadomień. */
    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                postSaveNotification()
            } else {
                // Brak zgody — chociaż toast, żeby operator wiedział, że zapisano
                Toast.makeText(this, R.string.csv_saved, Toast.LENGTH_LONG).show()
            }
        }

    private fun notifySaved() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            postSaveNotification()
        }
    }

    /** Powiadomienie „Zapisano CSV" — dotknięcie otwiera plik. */
    private fun postSaveNotification() {
        val uri = lastSavedUri ?: return
        val nm = getSystemService(NotificationManager::class.java) ?: return

        nm.createNotificationChannel(
            NotificationChannel(
                NOTIF_CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val openIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/csv")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // requestCode = hash URI, żeby każde powiadomienie otwierało swój plik
        val pendingIntent = PendingIntent.getActivity(
            this, uri.hashCode(),
            Intent.createChooser(openIntent, getString(R.string.csv_open_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fileName = uri.lastPathSegment
            ?.substringAfterLast('/')?.substringAfterLast(':')
            ?: getString(R.string.csv_saved)
        val notification = NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_qr)
            .setContentTitle(getString(R.string.csv_saved))
            .setContentText(getString(R.string.csv_saved_tap_to_open, fileName))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        nm.notify(uri.hashCode(), notification)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySessionDetailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        if (id == null) {
            finish()
            return
        }
        sessionId = id

        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.scanButton.setOnClickListener {
            startActivity(
                Intent(this, ScanActivity::class.java)
                    .putExtra(ScanActivity.EXTRA_SESSION_ID, sessionId)
            )
        }
        binding.shareCsvButton.setOnClickListener {
            session?.let { CsvExporter.share(this, it) }
        }
        binding.saveCsvButton.setOnClickListener {
            session?.let { saveCsvLauncher.launch(CsvExporter.fileName(it)) }
        }
        binding.deleteButton.setOnClickListener { confirmDelete() }
        binding.itemsList.setOnItemLongClickListener { _, _, position, _ ->
            (binding.itemsList.getItemAtPosition(position) as? ScanItem)?.let { confirmDeleteItem(it) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val s = SessionStore.getSession(this, sessionId)
        if (s == null) {
            finish()
            return
        }
        session = s

        binding.toolbar.title = s.name
        binding.statCount.text = s.items.size.toString()
        binding.statWeight.text = Format.totals(s.items)
        binding.itemsList.adapter = ScanItemAdapter(this, s.items)
    }

    private fun confirmDeleteItem(item: ScanItem) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_item)
            .setMessage(getString(R.string.delete_item_confirm, item.product))
            .setPositiveButton(R.string.delete) { _, _ ->
                SessionStore.deleteItem(this, sessionId, item.raw)
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete() {
        val s = session ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_session)
            .setMessage(getString(R.string.delete_confirm, s.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SessionStore.deleteSession(this, sessionId)
                finish()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
