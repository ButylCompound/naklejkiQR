package pl.almara.inwentaryzacja

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

class ServerApiException(val status: Int) : IOException("HTTP " + status)

object ServerAddress {
    fun normalize(value: String): String {
        val uri = URI(value.trim())
        require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null &&
            uri.path in listOf("", "/", "/materials", "/materials/"))
        return uri.toASCIIString().trimEnd('/').removeSuffix("/materials")
    }
}

object Digests {
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}

class ServerApi(private val baseUrl: String) {
    internal fun connection(path: String): HttpURLConnection =
        (URL(ServerAddress.normalize(baseUrl) + "/materials" + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 20000
            instanceFollowRedirects = false
        }

    fun request(method: String, path: String, body: ByteArray? = null): JSONObject {
        val connection = connection(path)
        try {
            connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.setRequestProperty("X-Content-SHA256", Digests.sha256(body))
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw ServerApiException(status)
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        } finally { connection.disconnect() }
    }
}

class ServerRoutes(private val urls: List<String>) {
    var activeUrl: String? = null
        private set

    init { require(urls.isNotEmpty()) }

    fun <T> call(request: (ServerApi, String) -> T): T {
        var failure: IOException? = null
        for (url in (listOfNotNull(activeUrl) + urls).distinct()) {
            try {
                val result = request(ServerApi(url), url)
                activeUrl = url
                return result
            } catch (error: IOException) {
                if (error is ServerApiException && error.status != 408 && error.status !in 500..599) throw error
                failure = error
            }
        }
        throw checkNotNull(failure)
    }
}
