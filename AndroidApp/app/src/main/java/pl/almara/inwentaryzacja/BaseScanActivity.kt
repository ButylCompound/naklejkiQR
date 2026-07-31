package pl.almara.inwentaryzacja

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.widget.TextView
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
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * Wspólny silnik skanowania: aparat, odczyt QR (ML Kit), cooldown, sygnał
 * dźwiękowy/wibracja i baner statusu. Podklasa dostarcza layout z widokami
 * o id `previewView` i `statusText`, wywołuje [startScanning] po setContentView
 * i obsługuje odczytany kod w [onQr].
 */
abstract class BaseScanActivity : AppCompatActivity() {

    companion object {
        private const val COOLDOWN_MS = 2000L
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()
    )

    /** Czas ostatniego obsłużonego kodu — steruje cooldownem. */
    @Volatile
    private var lastHandledAt = 0L

    /** Wstrzymanie analizy klatek (np. gdy otwarty jest dialog). */
    @Volatile
    private var paused = false

    private var toneGen: ToneGenerator? = null

    protected val previewView: PreviewView by lazy { findViewById(R.id.previewView) }
    protected val statusText: TextView by lazy { findViewById(R.id.statusText) }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startCamera()
            } else {
                Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
                finish()
            }
        }

    /** Obsługa odczytanego kodu (wątek główny). */
    protected abstract fun onQr(raw: String)

    /** Wywołaj po setContentView w onCreate podklasy. */
    protected fun startScanning() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        toneGen = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 85) }.getOrNull()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    protected fun pauseScanning() {
        paused = true
    }

    protected fun resumeScanning() {
        lastHandledAt = SystemClock.elapsedRealtime()
        paused = false
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = future.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
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
        if (paused || SystemClock.elapsedRealtime() - lastHandledAt < COOLDOWN_MS) {
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
                // rawValue zgaduje kodowanie (często ISO-8859-1) i gubi polskie znaki —
                // rawBytes to surowa zawartość QR (generator koduje UTF-8), więc dekodujemy jawnie
                val barcode = barcodes.firstOrNull { it.rawBytes != null || !it.rawValue.isNullOrBlank() }
                val raw = barcode?.rawBytes?.let { String(it, Charsets.UTF_8) } ?: barcode?.rawValue
                if (!raw.isNullOrBlank()) handleQr(raw)
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun handleQr(raw: String) {
        val now = SystemClock.elapsedRealtime()
        if (paused || now - lastHandledAt < COOLDOWN_MS) return
        lastHandledAt = now
        onQr(raw)
    }

    protected fun showStatus(text: String, @ColorRes colorRes: Int) {
        statusText.text = text
        statusText.setBackgroundColor(ContextCompat.getColor(this, colorRes))
        handler.removeCallbacks(resetStatus)
        handler.postDelayed(resetStatus, COOLDOWN_MS)
    }

    private val resetStatus = Runnable {
        statusText.text = getString(R.string.scan_prompt)
        statusText.setBackgroundColor(ContextCompat.getColor(this, R.color.status_neutral))
    }

    /** Sygnał dźwiękowy + wibracja — operator nie musi patrzeć na ekran. */
    protected fun feedback(error: Boolean) {
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
