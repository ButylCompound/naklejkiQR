package pl.almara.inwentaryzacja

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime

data class CatalogMaterial(val id: String, val name: String, val type: String, val group: String, val suppliers: List<String>) {
    override fun toString(): String = listOf(name, type, group).filter { it.isNotBlank() }.joinToString(" · ")
}

data class MaterialCatalog(val updatedAt: String, val materials: List<CatalogMaterial>) {
    val suppliers: List<String> get() = materials.flatMap { it.suppliers }.distinct().sorted()

    companion object {
        fun parse(value: JSONObject): MaterialCatalog {
            val updatedAt = value.getString("updatedAt")
            OffsetDateTime.parse(updatedAt)
            val rows = value.getJSONArray("materials")
            val materials = (0 until rows.length()).map { index ->
                val item = rows.getJSONObject(index)
                val suppliers = item.getJSONArray("suppliers")
                CatalogMaterial(item.getString("id").trim(), item.getString("name").trim(),
                    item.getString("type").trim(), item.getString("group").trim(),
                    (0 until suppliers.length()).map { suppliers.getString(it).trim() }.filter { it.isNotEmpty() }.distinct())
            }
            require(materials.all { it.id.isNotEmpty() && it.name.isNotEmpty() })
            require(materials.map { it.id }.distinct().size == materials.size)
            return MaterialCatalog(updatedAt, materials)
        }
    }

    fun resolve(name: String, selectedId: String? = null): CatalogMaterial? {
        val matches = materials.filter { materialName(it.name) == materialName(name) }
        return matches.find { it.id == selectedId } ?: matches.singleOrNull()
    }

    fun matchScanned(name: String, id: String? = null): CatalogMaterial? {
        materials.find { it.id == id }?.let { return it }
        val names = materials.map { it to FuzzyText.normalize(it.name) }
        val exact = names.filter { it.second == FuzzyText.normalize(name) }
        if (exact.isNotEmpty()) return exact.singleOrNull()?.first
        val words = Regex("[\\p{L}\\p{N}]+").findAll(name).map { FuzzyText.normalize(it.value) }.toList()
        val fragments = words.indices.flatMap { start ->
            (start + 1..words.size).map { end -> words.subList(start, end).joinToString("") }
        }.toSet()
        return names.filter { it.second in fragments }.singleOrNull()?.first
    }

    fun json(): JSONObject = JSONObject().put("updatedAt", updatedAt).put("materials", JSONArray().apply {
        materials.forEach { row -> put(JSONObject().put("id", row.id).put("name", row.name)
            .put("type", row.type).put("group", row.group).put("suppliers", JSONArray(row.suppliers))) }
    })
}

internal fun materialName(value: String): String = value.trim().replace(Regex("\\s+"), " ").lowercase(java.util.Locale.ROOT)

object CatalogStore {
    private fun file(context: Context) = File(context.filesDir, "material-catalog.json")

    @Synchronized
    fun get(context: Context): MaterialCatalog? = JsonFiles.read(file(context))?.let(MaterialCatalog::parse)

    @Synchronized
    fun replace(context: Context, catalog: MaterialCatalog) = JsonFiles.write(file(context), catalog.json())
}
