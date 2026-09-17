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
    private var rawMaterials = false
    /** Kolejność wyboru: alejki numeryczne 1..N, potem niestandardowe (np. o1). */
    private var alleys: List<String> = listOf("1")
    private var alleyIndex = 0

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
        alleys = (1..Settings.alleyCount(this)).map { it.toString() } + Settings.customAlleys(this)
        val session = SessionStore.getSession(this, sessionId)
        rawMaterials = session?.type == Session.TYPE_RAW
        val existing = session?.items
        scannedCount = existing?.size ?: 0
        alleyIndex = alleys.indexOf(existing?.lastOrNull()?.alley).coerceAtLeast(0)
        updateCounter()
        updateAlley()

        // Zawijanie: w lewo z pierwszej alejki -> ostatnia, w prawo z ostatniej -> pierwsza
        binding.alleyPrevButton.setOnClickListener {
            alleyIndex = (alleyIndex - 1 + alleys.size) % alleys.size
            updateAlley()
        }
        binding.alleyNextButton.setOnClickListener {
            alleyIndex = (alleyIndex + 1) % alleys.size
            updateAlley()
        }
        binding.finishButton.setOnClickListener { finishSession() }

        startScanning()
    }

    override fun onQr(raw: String) {
        val item = QrParser.parse(raw, SessionStore.timestamp())?.copy(alley = alleys[alleyIndex])
        if (item == null) {
            showStatus(getString(R.string.status_invalid), R.color.status_error)
            feedback(error = true)
            return
        }
        if (rawMaterials) promptPallets(item) else addSingle(item)
    }

    private fun addSingle(item: ScanItem) {
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

    /** Surowce: pytamy o liczbę palet i rozbijamy skan na oddzielne wpisy (1)..(n). */
    private fun promptPallets(item: ScanItem) {
        pauseScanning()
        val base = PalletName.base(item.product)
        PalletPrompt.show(
            context = this,
            base = base,
            prefill = PalletName.number(item.product) ?: 1,
            onOk = { count -> addPallets(item, base, count) },
            onCancel = { resumeScanning() }
        )
    }

    private fun addPallets(item: ScanItem, base: String, count: Int) {
        var added = 0
        for (entry in PalletExpansion.expand(item, count)) {
            when (SessionStore.addItem(this, sessionId, entry)) {
                SessionStore.AddResult.ADDED -> added++
                SessionStore.AddResult.DUPLICATE -> {}
                null -> {
                    showStatus(getString(R.string.status_session_missing), R.color.status_error)
                    feedback(error = true)
                    resumeScanning()
                    return
                }
            }
        }
        scannedCount += added
        updateCounter()
        if (added > 0) {
            showStatus(getString(R.string.status_pallets_added, base, added), R.color.status_ok)
            feedback(error = false)
        } else {
            showStatus(getString(R.string.status_pallets_dup, base), R.color.status_warn)
            feedback(error = true)
        }
        resumeScanning()
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
        binding.alleyText.text = getString(R.string.alley_label, alleys[alleyIndex])
    }
}
