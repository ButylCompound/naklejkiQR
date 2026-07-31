package pl.almara.inwentaryzacja

import android.content.Intent
import android.os.Bundle
import pl.almara.inwentaryzacja.databinding.ActivityScanBinding

/**
 * Skanowanie do inwentaryzacji: celujesz aparatem w naklejkę, aplikacja odczytuje
 * kod, dopisuje pozycję z bieżącą alejką i pokazuje status. Alejkę ustawiasz
 * strzałkami; wybór jest ograniczony liczbą alejek z ustawień.
 */
class ScanActivity : BaseScanActivity() {

    companion object {
        const val EXTRA_SESSION_ID = "session_id"
    }

    private lateinit var binding: ActivityScanBinding
    private lateinit var sessionId: String

    private var scannedCount = 0
    private var currentAlley = 1
    private var maxAlley = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScanBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val id = intent.getStringExtra(EXTRA_SESSION_ID)
        if (id == null) {
            finish()
            return
        }
        sessionId = id
        maxAlley = Settings.alleyCount(this)
        val existing = SessionStore.getSession(this, sessionId)?.items
        scannedCount = existing?.size ?: 0
        currentAlley = (existing?.lastOrNull()?.alley ?: 1).coerceIn(1, maxAlley)
        updateCounter()
        updateAlley()

        binding.alleyPrevButton.setOnClickListener {
            if (currentAlley > 1) { currentAlley--; updateAlley() }
        }
        binding.alleyNextButton.setOnClickListener {
            if (currentAlley < maxAlley) { currentAlley++; updateAlley() }
        }
        binding.finishButton.setOnClickListener { finishSession() }

        startScanning()
    }

    override fun onQr(raw: String) {
        val item = QrParser.parse(raw, SessionStore.timestamp())?.copy(alley = currentAlley)
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
                    getString(R.string.status_ok_format, item.product, Format.quantity(item)),
                    R.color.status_ok
                )
                feedback(error = false)
            }
            SessionStore.AddResult.DUPLICATE -> {
                showStatus(getString(R.string.status_duplicate), R.color.status_warn)
                feedback(error = true)
            }
            null -> {
                showStatus(getString(R.string.status_session_missing), R.color.status_error)
                feedback(error = true)
            }
        }
    }

    private fun finishSession() {
        startActivity(
            Intent(this, SessionDetailActivity::class.java)
                .putExtra(SessionDetailActivity.EXTRA_SESSION_ID, sessionId)
        )
        finish()
    }

    private fun updateCounter() {
        binding.counterText.text = getString(R.string.scanned_count, scannedCount)
    }

    private fun updateAlley() {
        binding.alleyText.text = getString(R.string.alley_label, currentAlley)
        binding.alleyPrevButton.isEnabled = currentAlley > 1
        binding.alleyNextButton.isEnabled = currentAlley < maxAlley
    }
}
