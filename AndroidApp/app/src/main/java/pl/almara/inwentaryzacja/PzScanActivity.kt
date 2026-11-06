package pl.almara.inwentaryzacja

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.net.Uri
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import pl.almara.inwentaryzacja.databinding.ActivityPzScanBinding

class PzScanActivity : AppCompatActivity() {
    companion object { const val EXTRA_LABEL = "label_read" }
    private lateinit var binding: ActivityPzScanBinding
    private val vision = LabelVision()
    private var capture: ImageCapture? = null
    private var camera: ProcessCameraProvider? = null
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) startCamera() else {
            Toast.makeText(this, R.string.camera_permission_denied, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPzScanBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.manualButton.setOnClickListener { finish() }
        binding.captureButton.isEnabled = false
        binding.captureButton.setOnClickListener { captureLabel() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (isDestroyed) return@addListener
            try {
                val provider = future.get()
                camera = provider
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }
                val imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(90).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
                capture = imageCapture
                binding.captureButton.isEnabled = true
            } catch (error: Exception) {
                android.util.Log.e("PZ", "Camera initialization failed", error)
                Toast.makeText(this, getString(R.string.camera_error, error.message), Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun captureLabel() {
        val camera = capture ?: return
        binding.captureButton.isEnabled = false
        binding.captureButton.setText(R.string.label_reading)
        val file = LabelPhotos.captureFile(this)
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        camera.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                if (isDestroyed) { file.delete(); return }
                try {
                    val photo = LabelPhotos.describe(file)
                    vision.read(InputImage.fromFilePath(this@PzScanActivity, Uri.fromFile(file)))
                        .addOnSuccessListener { read ->
                            if (isDestroyed) file.delete()
                            else {
                                setResult(RESULT_OK, Intent().putExtra(EXTRA_LABEL, LabelJson.read(read.copy(photo = photo)).toString()))
                                finish()
                            }
                        }.addOnFailureListener { file.delete(); failed() }
                } catch (error: Exception) { file.delete(); failed() }
            }
            override fun onError(exception: ImageCaptureException) { file.delete(); failed() }
        })
    }

    private fun failed() {
        if (isDestroyed) return
        binding.captureButton.isEnabled = true
        binding.captureButton.setText(R.string.label_capture)
        Toast.makeText(this, R.string.label_scan_failed, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() { super.onDestroy(); camera?.unbindAll(); vision.close() }
}
