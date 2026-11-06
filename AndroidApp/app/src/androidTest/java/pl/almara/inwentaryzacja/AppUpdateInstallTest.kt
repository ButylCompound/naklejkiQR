package pl.almara.inwentaryzacja

import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import android.widget.TextView
import java.io.File
import java.io.InterruptedIOException
import java.net.ServerSocket
import java.net.SocketException
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class AppUpdateInstallTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var context: ContextWrapper
    private fun fixture(name: String) = File(base.getExternalFilesDir("verification"), name)
    private fun metadata(bytes: ByteArray): AppUpdate {
        val archive = base.packageManager.getPackageArchiveInfo(fixture("updater-new.apk").path, 0)!!
        return AppUpdate(PackageInfoCompat.getLongVersionCode(archive), archive.versionName!!, bytes.size.toLong(), Digests.sha256(bytes))
    }

    @Before fun setUp() {
        root = File(base.cacheDir, "updates/test-" + UUID.randomUUID()).apply { mkdirs() }
        context = object : ContextWrapper(base) { override fun getCacheDir() = root }
    }
    @After fun tearDown() { root.deleteRecursively() }

    @Test fun detectsOnlyNewerVersionsAndAcceptsUnpublishedState() {
        val current = AppUpdater.installedVersion(context)
        Server(ByteArray(0), null).use { server -> assertNull(AppUpdater.check(context, ServerRoutes(listOf(server.url)))) }
        val update = metadata(fixture("updater-new.apk").readBytes())
        assertTrue(update.versionCode > current)
        for (code in listOf(current - 1, current, update.versionCode)) Server(ByteArray(0), update.copy(versionCode = code)).use { server ->
            assertEquals(if (code > current) update else null, AppUpdater.check(context, ServerRoutes(listOf(server.url))))
            assertEquals(listOf("/materials/app-updates/latest"), server.paths.toList())
        }
    }

    @Test fun downloadsThroughFallbackChecksIdentityAndGrantsReadableContentUri() {
        val bytes = fixture("updater-new.apk").readBytes()
        val update = metadata(bytes)
        Server(bytes, update, status = 503).use { primary -> Server(bytes, update).use { fallback ->
            val progress = mutableListOf<Long>()
            val routes = ServerRoutes(listOf(primary.url, fallback.url))
            val file = AppUpdater.download(context, routes, update, { false }) { progress.add(it) }
            assertEquals(fallback.url, routes.activeUrl)
            assertEquals(listOf("/materials" + update.path), fallback.paths.toList())
            assertEquals(update.sizeBytes, file.length())
            assertEquals(100L, progress.last())
            val uri = FileProvider.getUriForFile(base, base.packageName + ".fileprovider", file)
            base.contentResolver.openInputStream(uri)!!.use { assertEquals(0x50, it.read()) }
            assertEquals(file, AppUpdater.download(context, routes, update, { false }) {})
            assertEquals(1, fallback.paths.size)
        } }
    }

    @Test fun rejectsWrongChecksumsPackagesVersionsAndSigningKeys() {
        val good = fixture("updater-new.apk").readBytes()
        val update = metadata(good)
        val invalid = listOf(good to update.copy(sha256 = "0".repeat(64)),
            good to update.copy(versionCode = update.versionCode + 1), good to update.copy(versionName = "wrong"),
            fixture("updater-other-key.apk").readBytes().let { it to metadata(it) },
            fixture("updater-other-package.apk").readBytes().let { it to metadata(it) })
        for ((bytes, manifest) in invalid) Server(bytes, manifest).use { server ->
            assertThrows(UpdateRejected::class.java) { AppUpdater.download(context, ServerRoutes(listOf(server.url)), manifest, { false }) {} }
            assertFalse(AppUpdater.file(context, manifest).exists())
            assertTrue(File(root, "updates/partial").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun interruptedOrOversizedPayloadNeverBecomesAnInstallableFile() {
        val good = fixture("updater-new.apk").readBytes()
        val update = metadata(good)
        for (bytes in listOf(good.copyOf(good.size / 2), good + byteArrayOf(1))) Server(bytes, update).use { server ->
            assertThrows(UpdateRejected::class.java) { AppUpdater.download(context, ServerRoutes(listOf(server.url)), update, { false }) {} }
            assertFalse(AppUpdater.file(context, update).exists())
        }
        Server(good, update).use { server ->
            assertThrows(InterruptedIOException::class.java) { AppUpdater.download(context, ServerRoutes(listOf(server.url)), update, { true }) {} }
            assertFalse(AppUpdater.file(context, update).exists())
            assertTrue(File(root, "updates/partial").listFiles().orEmpty().isEmpty())
        }
    }

    @Test fun redirectsAreRejectedAndDoNotDownloadAnArbitraryApk() {
        val update = metadata(fixture("updater-new.apk").readBytes())
        Server(ByteArray(0), update, status = 302).use { server ->
            val error = assertThrows(ServerApiException::class.java) { AppUpdater.check(context, ServerRoutes(listOf(server.url))) }
            assertEquals(302, error.status)
            assertEquals(1, server.paths.size)
        }
    }

    @Test fun updateScreenKeepsItsCheckResultAcrossRecreation() {
        val primary = Settings.serverUrl(base)
        val fallback = Settings.fallbackServerUrl(base)
        val update = metadata(fixture("updater-new.apk").readBytes())
        try {
            Server(ByteArray(0), update).use { server ->
                Settings.setServerUrls(base, server.url)
                ActivityScenario.launch(AppUpdateActivity::class.java).use { scenario ->
                    var ready = false
                    val deadline = System.currentTimeMillis() + 15000
                    while (!ready && System.currentTimeMillis() < deadline) {
                        scenario.onActivity { ready = it.findViewById<TextView>(R.id.status).text.contains(update.versionName) }
                        if (!ready) Thread.sleep(100)
                    }
                    assertTrue(ready)
                    scenario.recreate()
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    scenario.onActivity { assertTrue(it.findViewById<TextView>(R.id.status).text.contains(update.versionName)) }
                    assertEquals(listOf("/materials/app-updates/latest"), server.paths.toList())
                }
            }
        } finally { Settings.setServerUrls(base, primary, fallback) }
    }

    private class Server(private val bytes: ByteArray, private val update: AppUpdate?, private val status: Int = 200) : AutoCloseable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:" + socket.localPort
        val paths = Collections.synchronizedList(mutableListOf<String>())
        private val worker = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                client.use {
                    val input = it.getInputStream().bufferedReader()
                    val path = input.readLine().split(" ")[1]
                    paths.add(path)
                    while (!input.readLine().isNullOrEmpty()) { }
                    val json = path.endsWith("/latest")
                    val body = if (json) JSONObject().put("update", update?.json() ?: JSONObject.NULL).toString().toByteArray() else bytes
                    try {
                        val output = it.getOutputStream()
                        output.write(("HTTP/1.1 $status Test\r\nContent-Type: " + (if (json) "application/json" else "application/vnd.android.package-archive") +
                            "\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                        output.write(body)
                        output.flush()
                    } catch (_: java.io.IOException) { }
                }
            }
        }
        override fun close() { socket.close(); worker.join(3000) }
    }
}
