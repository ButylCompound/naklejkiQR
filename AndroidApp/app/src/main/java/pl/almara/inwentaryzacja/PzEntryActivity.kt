package pl.almara.inwentaryzacja

import android.os.Bundle
import android.content.Intent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONObject
import pl.almara.inwentaryzacja.databinding.ActivityPzEntryBinding
import java.util.UUID

class PzEntryActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_ENTRY_ID = "entry_id"
        const val EXTRA_SCAN = "scan_on_open"
    }
    private lateinit var binding: ActivityPzEntryBinding
    private lateinit var documentId: String
    private lateinit var entryId: String
    private lateinit var scannedAt: String
    private var selectedMaterialId: String? = null
    private var selectedMaterialName: String? = null
    private var original: PzEntry? = null
    private var label: LabelRead? = null
    private var unitChosen = false
    private var approximateMaterial = false
    private val lowConfidenceFields = mutableSetOf<String>()
    private val scanner = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.getStringExtra(PzScanActivity.EXTRA_LABEL)?.let { raw ->
            PzUi.guard(this) { applyLabel(LabelRuleStore.get(this).apply(LabelJson.read(JSONObject(raw)))) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        documentId = intent.getStringExtra(PzDetailActivity.EXTRA_ID) ?: run { finish(); return }
        binding = ActivityPzEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val document = PzStore.get(this, documentId) ?: run { finish(); return }
        if (document.approved) { finish(); return }
        original = document.items.find { it.id == intent.getStringExtra(EXTRA_ENTRY_ID) }
        label = savedInstanceState?.getString("label")?.let { LabelJson.read(JSONObject(it)) } ?: original?.label
        unitChosen = savedInstanceState?.getBoolean("unitChosen") ?: (original != null)
        entryId = savedInstanceState?.getString("entryId") ?: original?.id ?: UUID.randomUUID().toString()
        scannedAt = savedInstanceState?.getString("scannedAt") ?: original?.scannedAt ?: PzSummary.now()
        selectedMaterialId = if (savedInstanceState != null) savedInstanceState.getString("materialId") else original?.materialId
        selectedMaterialName = if (savedInstanceState != null) savedInstanceState.getString("materialName") else original?.materialName
        binding.toolbar.setTitle(if (original == null) R.string.pz_entry_new else R.string.pz_entry_edit)
        binding.toolbar.setNavigationOnClickListener { finish() }
        val catalog = CatalogStore.get(this)
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, catalog?.materials.orEmpty())
        binding.materialInput.setAdapter(adapter)
        binding.materialInput.setOnItemClickListener { _, _, position, _ ->
            val material = adapter.getItem(position)!!
            binding.materialInput.setText(material.name, false)
            selectedMaterialId = material.id
            selectedMaterialName = material.name
            clearFieldWarning("materialName")
        }
        binding.materialInput.doAfterTextChanged {
            clearFieldWarning("materialName")
        }
        binding.lotInput.doAfterTextChanged { clearFieldWarning("lot") }
        binding.quantityInput.doAfterTextChanged { clearFieldWarning("quantity") }
        binding.unitInput.doAfterTextChanged { clearFieldWarning("unit") }
        binding.catalogHint.text = if (catalog == null) getString(R.string.pz_catalog_empty)
            else getString(R.string.pz_catalog_count, catalog.materials.size, PzSummary.date(catalog.updatedAt))
        binding.unitInput.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, listOf("kg", "szt.")))
        binding.unitInput.setOnItemClickListener { _, _, _, _ -> unitChosen = true; clearFieldWarning("unit") }
        if (savedInstanceState == null) binding.unitInput.setText("kg", false)
        binding.unknownLot.setOnCheckedChangeListener { _, checked ->
            if (checked) clearFieldWarning("lot")
            updateVisibility()
        }
        binding.conditionGroup.addOnButtonCheckedListener { _, _, _ -> updateVisibility() }
        if (savedInstanceState == null) original?.let { entry ->
            binding.materialInput.setText(entry.materialName, false)
            binding.unknownLot.isChecked = entry.lot == null
            binding.lotInput.setText(entry.lot.orEmpty())
            binding.unknownLotCommentInput.setText(entry.unknownLotComment)
            binding.quantityInput.setText(PzSummary.number(entry.quantity))
            binding.unitInput.setText(entry.unit, false)
            binding.conditionGroup.check(if (entry.condition == PzCondition.OK) R.id.conditionOk else R.id.conditionDamaged)
            binding.damageCommentInput.setText(entry.damageComment)
        }
        updateVisibility()
        updateLabelInfo()
        restoreWarnings(savedInstanceState)
        binding.scanLabelButton.setOnClickListener { scanLabel() }
        binding.labelValuesButton.setOnClickListener { chooseLabelValue() }
        binding.saveButton.setOnClickListener { saveEntry() }
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_SCAN, false)) scanLabel()
    }

    private fun scanLabel() = scanner.launch(Intent(this, PzScanActivity::class.java))

    internal fun applyLabel(read: LabelRead) {
        label?.photo?.takeIf { it != read.photo && it != original?.label?.photo }?.let { LabelPhotos.delete(this, it) }
        label = read
        if (original == null) scannedAt = read.capturedAt
        val choices = read.values.filter { it.field in setOf("materialName", "lot", "quantity") }.groupBy { it.field }
        for ((field, values) in choices) if (values.size == 1) {
            val empty = when (field) {
                "materialName" -> binding.materialInput.text.isBlank()
                "lot" -> binding.lotInput.text.isNullOrBlank() && !binding.unknownLot.isChecked
                else -> binding.quantityInput.text.isNullOrBlank() && (!unitChosen || binding.unitInput.text.toString() == values.first().unit)
            }
            if (empty) applyValue(values.first())
        }
        updateLabelInfo()
        if (choices.values.any { it.size > 1 } || choices.any { (field, values) ->
            val current = when (field) {
                "materialName" -> binding.materialInput.text.toString()
                "lot" -> binding.lotInput.text.toString()
                else -> binding.quantityInput.text.toString()
            }
            values.any { it.value != current }
        }) chooseLabelValue()
    }

    private fun applyValue(value: LabelValue) {
        when (value.field) {
            "materialName" -> {
                val catalog = CatalogStore.get(this)?.materials.orEmpty()
                val material = if (value.materialId != null) catalog.find { it.id == value.materialId }
                    else catalog.singleOrNull { it.name.trim().equals(value.value.trim(), ignoreCase = true) }
                selectedMaterialId = material?.id
                selectedMaterialName = material?.name ?: value.value
                binding.materialInput.setText(selectedMaterialName, false)
                setApproximateMaterial(value.approximate)
            }
            "lot" -> { binding.unknownLot.isChecked = false; binding.lotInput.setText(value.value) }
            "quantity" -> {
                binding.quantityInput.setText(value.value)
                value.unit?.let { binding.unitInput.setText(it, false) }
                setLowConfidence("unit", label?.values?.any { it.field == "unit" && it.value == value.unit && it.lowConfidence } == true)
            }
            "unit" -> binding.unitInput.setText(value.value, false)
        }
        setLowConfidence(value.field, value.lowConfidence)
    }

    private fun updateLabelInfo() {
        binding.labelInfo.visibility = if (label == null) View.GONE else View.VISIBLE
        binding.labelValuesButton.visibility = if (label == null) View.GONE else View.VISIBLE
        binding.labelInfo.setText(if (label?.values.isNullOrEmpty()) R.string.label_no_fields else R.string.label_review)
    }

    private fun setApproximateMaterial(approximate: Boolean) {
        approximateMaterial = approximate
        updateFieldWarning("materialName")
    }

    private fun setLowConfidence(field: String, low: Boolean) {
        if (low) lowConfidenceFields.add(field) else lowConfidenceFields.remove(field)
        updateFieldWarning(field)
    }

    private fun clearFieldWarning(field: String) {
        if (field == "materialName") approximateMaterial = false
        setLowConfidence(field, false)
    }

    private fun resolvedMaterialId(): String? {
        val name = binding.materialInput.text.toString()
        return CatalogStore.get(this)?.resolve(name, selectedMaterialId)?.id
            ?: selectedMaterialId?.takeIf { materialName(name) == materialName(selectedMaterialName.orEmpty()) }
    }

    private fun updateFieldWarning(field: String) {
        val (layout, title) = when (field) {
            "materialName" -> binding.materialInputLayout to R.string.pz_material
            "lot" -> binding.lotInputLayout to R.string.pz_lot
            "quantity" -> binding.quantityInputLayout to R.string.pz_quantity
            "unit" -> binding.unitInputLayout to R.string.pz_unit
            else -> return
        }
        val approximate = field == "materialName" && approximateMaterial
        val low = field in lowConfidenceFields
        layout.hint = getString(title) + if (approximate || low) " ⚠️" else ""
        layout.boxBackgroundColor = getColor(if (approximate || low) R.color.field_approximate else android.R.color.white)
        val warning = when {
            approximate && low -> getString(R.string.label_approximate_low_confidence)
            approximate -> getString(R.string.label_approximate)
            low -> getString(R.string.label_low_confidence)
            else -> null
        }
        val manual = field == "materialName" && binding.materialInput.text.isNotBlank() && resolvedMaterialId() == null
        layout.helperText = listOfNotNull(warning, if (manual) getString(R.string.pz_material_manual) else null)
            .joinToString("\n").ifEmpty { null }
    }

    private fun restoreWarnings(state: Bundle?) {
        approximateMaterial = state?.getBoolean("approximateMaterial") ?: original?.approximateMaterial ?: false
        lowConfidenceFields.clear()
        lowConfidenceFields.addAll(state?.getStringArrayList("lowConfidenceFields") ?: original?.lowConfidenceFields.orEmpty())
        listOf("materialName", "lot", "quantity", "unit").forEach(::updateFieldWarning)
    }

    private fun chooseLabelValue() {
        val read = label ?: return
        val values = read.values.filter { it.field != "materialId" }
        val labels = values.map { value ->
            val name = when (value.field) {
                "materialName" -> R.string.pz_material
                "lot" -> R.string.pz_lot
                "quantity" -> R.string.pz_quantity
                else -> R.string.pz_unit
            }
            getString(name) + ": " + value.value + (value.unit?.let { " " + it }.orEmpty()) + if (value.approximate || value.lowConfidence) " ⚠️" else ""
        } + getString(R.string.label_raw)
        MaterialAlertDialogBuilder(this).setTitle(R.string.label_review_choices).setItems(labels.toTypedArray()) { _, position ->
            if (position < values.size) applyValue(values[position])
            else MaterialAlertDialogBuilder(this).setTitle(R.string.label_raw)
                .setMessage((read.codes.map { it.format + ": " + it.raw } + read.text).filter { it.isNotBlank() }.joinToString("\n\n"))
                .setPositiveButton(R.string.ok, null).show()
                .findViewById<TextView>(android.R.id.message)?.setTextIsSelectable(true)
        }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun updateVisibility() {
        binding.lotInputLayout.visibility = if (binding.unknownLot.isChecked) View.GONE else View.VISIBLE
        binding.unknownLotCommentInputLayout.visibility = if (binding.unknownLot.isChecked) View.VISIBLE else View.GONE
        binding.damageCommentInputLayout.visibility = if (binding.conditionDamaged.isChecked) View.VISIBLE else View.GONE
    }

    private fun saveEntry() {
        val quantity = PzSummary.parseQuantity(binding.quantityInput.text.toString())
        val condition = when (binding.conditionGroup.checkedButtonId) {
            R.id.conditionOk -> PzCondition.OK
            R.id.conditionDamaged -> PzCondition.DAMAGED
            else -> null
        }
        if (quantity == null) { binding.quantityInput.error = getString(R.string.pz_quantity_required); return }
        if (condition == null) { Toast.makeText(this, R.string.pz_condition_required, Toast.LENGTH_SHORT).show(); return }
        val entry = PzEntry(entryId, resolvedMaterialId(), binding.materialInput.text.toString().trim(),
            if (binding.unknownLot.isChecked) null else binding.lotInput.text.toString().trim(),
            if (binding.unknownLot.isChecked) binding.unknownLotCommentInput.text.toString().trim() else "", quantity,
            binding.unitInput.text.toString(), condition,
            if (condition == PzCondition.DAMAGED) binding.damageCommentInput.text.toString().trim() else "", scannedAt,
            if (label != null) label?.codes?.firstOrNull()?.raw else original?.sourceCode,
            if (label != null) label?.codes?.firstOrNull()?.format else original?.barcodeFormat, label, approximateMaterial, lowConfidenceFields.toSet())
        val problem = entry.problem()
        if (problem != null) {
            val input = when (problem) {
                PzProblem.MATERIAL -> binding.materialInput
                PzProblem.LOT -> binding.lotInput
                PzProblem.UNKNOWN_LOT_COMMENT -> binding.unknownLotCommentInput
                PzProblem.DAMAGE_COMMENT -> binding.damageCommentInput
                else -> binding.quantityInput
            }
            input.error = getString(PzUi.message(problem)); input.requestFocus()
            return
        }
        PzUi.guard(this) { PzStore.putEntry(this, documentId, entry); finish() }
    }

    override fun onDestroy() {
        if (isFinishing) label?.photo?.let { photo ->
            if (PzStore.get(this, documentId)?.items?.none { it.label?.photo?.id == photo.id } != false) LabelPhotos.delete(this, photo)
        }
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("entryId", entryId)
        outState.putString("scannedAt", scannedAt)
        outState.putString("materialId", selectedMaterialId)
        outState.putString("materialName", selectedMaterialName)
        label?.let { outState.putString("label", LabelJson.read(it).toString()) }
        outState.putBoolean("unitChosen", unitChosen)
        outState.putBoolean("approximateMaterial", approximateMaterial)
        outState.putStringArrayList("lowConfidenceFields", ArrayList(lowConfidenceFields))
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreWarnings(savedInstanceState)
    }
}
