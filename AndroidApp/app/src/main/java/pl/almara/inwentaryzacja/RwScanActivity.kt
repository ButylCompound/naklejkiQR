package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import pl.almara.inwentaryzacja.databinding.ActivityRwScanBinding

/**
 * Skanowanie materiałów do dokumentu RW. Po każdym odczycie pyta o ilość
 * pobraną (ilość wydana); aparat jest wtedy wstrzymany.
 */
class RwScanActivity : BaseScanActivity() {

    companion object {
        const val EXTRA_RW_ID = "rw_id"
        /** true = wróć do wywołującego (wznawianie); false = przejdź do szczegółów. */
        const val EXTRA_RETURN_ONLY = "return_only"
    }

    private lateinit var binding: ActivityRwScanBinding
    private lateinit var rwId: String
    private var returnOnly = false
    private var scannedCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRwScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_RW_ID)
        if (id == null) {
            finish()
            return
        }
        rwId = id
        returnOnly = intent.getBooleanExtra(EXTRA_RETURN_ONLY, false)
        scannedCount = RwStore.get(this, rwId)?.items?.size ?: 0
        updateCounter()

        binding.finishButton.setOnClickListener { finishScanning() }

        startScanning()
    }

    override fun onQr(raw: String) {
        val item = QrParser.parse(raw, SessionStore.timestamp())
        if (item == null) {
            showStatus(getString(R.string.status_invalid), R.color.status_error)
            feedback(error = true)
            return
        }
        pauseScanning()
        AmountPrompt.show(
            context = this,
            product = item.product,
            unit = item.unit,
            prefill = item.quantity,
            onOk = { amount -> addItem(item.copy(quantity = amount)) },
            onCancel = { resumeScanning() }
        )
    }

    private fun addItem(item: ScanItem) {
        when (RwStore.putItem(this, rwId, item)) {
            RwStore.PutResult.ADDED -> {
                scannedCount++
                updateCounter()
                showStatus(
                    getString(R.string.status_ok_format, item.product, Format.quantity(item)),
                    R.color.status_ok
                )
                feedback(error = false)
            }
            RwStore.PutResult.UPDATED -> {
                showStatus(
                    getString(R.string.status_updated_format, item.product, Format.quantity(item)),
                    R.color.status_warn
                )
                feedback(error = false)
            }
            null -> {
                showStatus(getString(R.string.status_session_missing), R.color.status_error)
                feedback(error = true)
            }
        }
        resumeScanning()
    }

    private fun finishScanning() {
        if (!returnOnly) {
            startActivity(
                Intent(this, RwDetailActivity::class.java)
                    .putExtra(RwDetailActivity.EXTRA_RW_ID, rwId)
            )
        }
        finish()
    }

    private fun updateCounter() {
        binding.counterText.text = getString(R.string.scanned_count, scannedCount)
    }
}
