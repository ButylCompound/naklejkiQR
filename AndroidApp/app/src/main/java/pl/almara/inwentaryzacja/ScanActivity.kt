package pl.almara.inwentaryzacja

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import pl.almara.inwentaryzacja.databinding.ActivityScanBinding
import java.util.concurrent.Executors

/**
 * Ekran skanowania: celujesz aparatem w naklejkę, aplikacja sama odczytuje kod,
 * pokazuje status (OK / Już zeskanowano / Błąd) i przez 2 sekundy ignoruje
 * kolejne kody (cooldown), żeby nie zliczyć tej samej naklejki dwa razy.
 */
class ScanActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
        private const val COOLDOWN_MS = 2000L
    }

    private lateinit var binding: ActivityScanBinding
    private lateinit var sessionId: String

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    /** Czas (elapsedRealtime) ostatniego obsłużonego kodu — steruje cooldownem. */
    @Volatile
    private var lastHandledAt = 0L

    private var scannedCount = 0
    private var pendingDuplicate: ScanItem? = null
    private var toneGen: ToneGenerator? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ekran nie gaśnie podczas skanowania
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        if (id == null) {
            finish()
            return
        }
        sessionId = id
        scannedCount = SessionStore.getSession(this, sessionId)?.items?.size ?: 0
        updateCounter()

        toneGen = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()

        binding.addAnywayButton.setOnClickListener { addPendingDuplicate() }
        binding.finishButton.setOnClickListener { finishSession() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun finishSession() {
        startActivity(
            Intent(this, SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, sessionId)
        )
        finish()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.previewView.surfaceProvider)
            }

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { it.setAnalyzer(cameraExecutor) { proxy -> analyze(proxy) } }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
                )
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.camera_error, e.message), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        // Cooldown: w trakcie 2 s po odczycie nie analizujemy kolejnych klatek
        if (SystemClock.elapsedRealtime() - lastHandledAt < COOLDOWN_MS) {
            proxy.close()
            return
        }
        val mediaImage = proxy.image
        if (mediaImage == null) {
            proxy.close()
            return
        }
        val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        scanner.process(input)
            .addOnSuccessListener { barcodes ->
                val raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (raw != null) handleQr(raw)
            }
            .addOnCompleteListener { proxy.close() }
    }

    /** Wywoływane na wątku głównym (callback ML Kit). */
    private fun handleQr(raw: String) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastHandledAt < COOLDOWN_MS) return
        lastHandledAt = now

        binding.addAnywayButton.visibility = View.GONE
        pendingDuplicate = null

        val item = QrParser.parse(raw, SessionStore.timestamp())
        if (item == null) {
            showStatus(getString(R.string.status_invalid), R.color.status_error)
            feedback(error = true)
            return
        }

        when (SessionStore.addItem(this, sessionId, item)) {
            SessionStore.AddResult.ADDED -> {
                scannedCount++
                updateCounter()
                showStatus(
                    getString(R.string.status_ok_format, item.product, Format.weight(item.weightKg)),
                    R.color.status_ok
                )
                feedback(error = false)
            }
            SessionStore.AddResult.DUPLICATE -> {
                pendingDuplicate = item
                binding.addAnywayButton.visibility = View.VISIBLE
                showStatus(getString(R.string.status_duplicate), R.color.status_warn)
                feedback(error = true)
            }
            null -> {
                showStatus(getString(R.string.status_session_missing), R.color.status_error)
                feedback(error = true)
            }
        }
    }

    /**
     * Naklejki drukowane w kilku kopiach mają identyczny kod QR — ten przycisk
     * pozwala świadomie dodać drugą paletę z taką samą naklejką.
     */
    private fun addPendingDuplicate() {
        val item = pendingDuplicate ?: return
        pendingDuplicate = null
        binding.addAnywayButton.visibility = View.GONE

        if (SessionStore.addItem(this, sessionId, item, force = true) == SessionStore.AddResult.ADDED) {
            scannedCount++
            updateCounter()
            showStatus(getString(R.string.status_duplicate_added), R.color.status_ok)
            feedback(error = false)
        }
        lastHandledAt = SystemClock.elapsedRealtime()
    }

    private fun showStatus(text: String, @ColorRes colorRes: Int) {
        binding.statusText.text = text
        binding.statusText.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        handler.removeCallbacks(resetStatus)
        handler.postDelayed(resetStatus, COOLDOWN_MS)
    }

    private val resetStatus = Runnable {
        binding.statusText.text = getString(R.string.scan_prompt)
        binding.statusText.setBackgroundColor(ContextCompat.getColor(this, R.color.status_neutral))
    }

    private fun updateCounter() {
        binding.counterText.text = getString(R.string.scanned_count, scannedCount)
    }

    /** Sygnał dźwiękowy + wibracja — operator nie musi patrzeć na ekran. */
    private fun feedback(error: Boolean) {
        toneGen?.startTone(
            if (error) ToneGenerator.TONE_SUP_ERROR else ToneGenerator.TONE_PROP_BEEP,
            200
        )
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        vibrator?.vibrate(
            VibrationEffect.createOneShot(
                if (error) 300 else 100,
                VibrationEffect.DEFAULT_AMPLITUDE
            )
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        toneGen?.release()
        scanner.close()
    }
}
