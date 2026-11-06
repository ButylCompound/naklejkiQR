package pl.almara.inwentaryzacja

import android.util.AtomicFile
import androidx.core.util.readText
import androidx.core.util.writeText
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

object JsonFiles {
    fun read(file: File): JSONObject? = try {
        JSONObject(AtomicFile(file).readText())
    } catch (_: FileNotFoundException) { null }

    fun write(file: File, value: JSONObject) = AtomicFile(file).writeText(value.toString())
    fun delete(file: File) = AtomicFile(file).delete()

    fun list(dir: File): List<File> = dir.listFiles { file ->
        file.name.endsWith(".json") || file.name.endsWith(".json.bak")
    }?.map { File(it.parentFile, it.name.removeSuffix(".bak")) }?.distinct().orEmpty()
}
