package pl.almara.inwentaryzacja

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.time.ZoneId

enum class PzCondition { OK, DAMAGED }

enum class PzProblem {
    MATERIAL, QUANTITY, UNIT, LOT, UNKNOWN_LOT_COMMENT, DAMAGE_COMMENT,
    SUPPLIER, RECEIVER, EMPTY, DELIVERY_MATCH, DELIVERY_COMMENT
}

class PzValidationException(val problem: PzProblem) : IllegalArgumentException(problem.name)

data class PzEntry(
    val id: String,
    val materialId: String?,
    val materialName: String,
    val lot: String?,
    val unknownLotComment: String,
    val quantity: BigDecimal,
    val unit: String,
    val condition: PzCondition,
    val damageComment: String,
    val scannedAt: String,
    val sourceCode: String? = null,
    val barcodeFormat: String? = null,
    val label: LabelRead? = null,
    val approximateMaterial: Boolean = false,
    val lowConfidenceFields: Set<String> = emptySet()
) {
    fun problem(): PzProblem? = when {
        materialName.isBlank() -> PzProblem.MATERIAL
        quantity.signum() <= 0 -> PzProblem.QUANTITY
        unit !in listOf("kg", "szt.") -> PzProblem.UNIT
        lot != null && lot.isBlank() -> PzProblem.LOT
        lot == null && unknownLotComment.isBlank() -> PzProblem.UNKNOWN_LOT_COMMENT
        condition == PzCondition.DAMAGED && damageComment.isBlank() -> PzProblem.DAMAGE_COMMENT
        else -> null
    }
}

data class PzDocument(
    val id: String,
    val createdAt: String,
    val supplier: String = "",
    val receiver: String = "",
    val invoiceNumber: String = "",
    val deliveryMatches: Boolean? = null,
    val deliveryComment: String = "",
    val items: List<PzEntry> = emptyList(),
    val number: String = "",
    val phoneNumber: Int? = null,
    val installationId: String? = null,
    val approvedAt: String? = null
) {
    val approved: Boolean get() = number.isNotEmpty()

    fun problem(): PzProblem? = when {
        supplier.isBlank() -> PzProblem.SUPPLIER
        receiver.isBlank() -> PzProblem.RECEIVER
        items.isEmpty() -> PzProblem.EMPTY
        items.any { it.problem() != null } -> items.firstNotNullOf { it.problem() }
        deliveryMatches == null -> PzProblem.DELIVERY_MATCH
        deliveryMatches == false && deliveryComment.isBlank() -> PzProblem.DELIVERY_COMMENT
        else -> null
    }
}

data class PzGroup(val entries: List<PzEntry>) {
    val quantity: BigDecimal get() = entries.fold(BigDecimal.ZERO) { total, entry -> total + entry.quantity }
    val damagedCount: Int get() = entries.count { it.condition == PzCondition.DAMAGED }
}

object PzSummary {
    private data class Key(val materialId: String?, val manualName: String?, val lot: String?, val unit: String, val unknownEntry: String?)

    fun groups(entries: List<PzEntry>): List<PzGroup> = entries.groupBy {
        Key(it.materialId, if (it.materialId == null) it.materialName else null, it.lot, it.unit,
            if (it.lot == null) it.id else null)
    }.values.map { PzGroup(it) }

    fun totals(entries: List<PzEntry>): String = entries.groupBy { it.unit }.map { (unit, group) ->
        val total = group.fold(BigDecimal.ZERO) { sum, entry -> sum + entry.quantity }
        number(total) + " " + unit
    }.joinToString("  •  ").ifEmpty { "0" }

    fun number(value: BigDecimal): String = value.stripTrailingZeros().toPlainString()

    fun parseQuantity(value: String): BigDecimal? = value.trim().replace(',', '.').toBigDecimalOrNull()
        ?.takeIf { it.signum() > 0 }

    fun now(): String = OffsetDateTime.now().toString()
    fun date(value: String): String = OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
}
