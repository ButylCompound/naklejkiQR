package pl.almara.inwentaryzacja

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.AtomicFile
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.ServerSocket
import java.net.SocketException
import java.time.LocalDate
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class PzStorageTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var context: TestContext

    private class TestContext(private val base: Context) : ContextWrapper(base) {
        val key = UUID.randomUUID().toString()
        val root = File(base.cacheDir, "pz-tests/" + key).apply { mkdirs() }
        private val preferences = mutableSetOf<String>()
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
            val pref = key + name
            preferences.add(pref)
            return base.getSharedPreferences(pref, mode)
        }
        fun close() { preferences.forEach { base.deleteSharedPreferences(it) }; root.deleteRecursively() }
    }

    @Before fun setUp() { context = TestContext(base) }
    @After fun tearDown() { context.close() }

    private fun entry(lot: String? = "0001", damaged: Boolean = false) = PzEntry(
        UUID.randomUUID().toString(), null, "Materiał PA 6 — Łódź", lot,
        if (lot == null) "Brak etykiety partii" else "", "125.5".toBigDecimal(), "kg",
        if (damaged) PzCondition.DAMAGED else PzCondition.OK, if (damaged) "Rozerwane opakowanie" else "",
        PzSummary.now(), "same-code", "QR_CODE")

    private fun register(url: String = "http://server") {
        Settings.setServerUrls(context, url)
        DocumentNumbering.register(context, DocumentNumbering.identity(context).installationId, 3, url)
    }

    private fun complete(): PzDocument {
        val doc = PzStore.create(context)
        PzStore.edit(context, doc.id) { it.copy(supplier = "Dostawca", receiver = "Jan", deliveryMatches = false, deliveryComment = "Różnica ilości") }
        PzStore.putEntry(context, doc.id, entry())
        PzStore.putEntry(context, doc.id, entry(lot = null, damaged = true))
        return PzStore.get(context, doc.id)!!
    }

    @Test fun physicalEntriesSurviveUpdatesAndApprovalLocksAllMutations() {
        val doc = complete()
        assertEquals(2, doc.items.size)
        assertEquals(doc.items[0].sourceCode, doc.items[1].sourceCode)
        val approved = PzStore.approve(context, doc.id)
        assertTrue(approved.number.endsWith("/00/0001"))
        assertEquals(0, approved.phoneNumber)
        assertEquals(approved, PzStore.approve(context, doc.id))
        assertThrows(IllegalStateException::class.java) { PzStore.putEntry(context, doc.id, entry()) }
        assertThrows(IllegalStateException::class.java) { PzStore.edit(context, doc.id) { it.copy(supplier = "Changed") } }
        assertThrows(IllegalStateException::class.java) { PzStore.deleteEntries(context, doc.id, setOf(doc.items[0].id)) }
        assertThrows(IllegalStateException::class.java) { PzStore.delete(context, doc.id) }
        assertEquals(approved, PzStore.get(context, doc.id))
    }

    @Test fun unregisteredDocumentsKeepZeroAfterBackgroundRegistrationAndAreBackedUp() {
        val pz = PzStore.approve(context, complete().id)
        val rw = finalizedRw()
        val pzFile = File(context.filesDir, "pz/" + pz.id + ".json")
        val rwFile = File(context.filesDir, "rw/" + rw.id + ".json")
        val pzBytes = pzFile.readBytes()
        val rwBytes = rwFile.readBytes()
        val pdfBytes = PzPdf.build(context, pz).readBytes()
        assertTrue(pz.number.endsWith("/00/0001"))
        assertTrue(rw.number.endsWith("/00/0001"))
        assertNull(DocumentNumbering.identity(context).phoneNumber)
        assertTrue(ServerSync.perform(context))
        assertFalse(PzBackupStore.isBackedUp(context, pz.id))

        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            server.failRegistration = true
            assertFalse(ServerSync.perform(context))
            assertNull(DocumentNumbering.identity(context).phoneNumber)
            assertEquals(0, server.documents.size)
            assertEquals(0, DocumentNumbering.reserve(context, "PZ").phoneNumber)

            server.failRegistration = false
            assertTrue(ServerSync.perform(context))
            assertEquals(3, DocumentNumbering.identity(context).phoneNumber)
            assertArrayEquals(pzBytes, pzFile.readBytes())
            assertArrayEquals(rwBytes, rwFile.readBytes())
            assertArrayEquals(pdfBytes, PzPdf.build(context, pz).readBytes())
            assertTrue(PzBackupStore.isBackedUp(context, pz.id))
            val payload = server.documents.getValue(pz.id)
            assertEquals(3, payload.getInt("phoneNumber"))
            assertEquals(0, payload.getJSONObject("document").getInt("phoneNumber"))
            assertEquals(pz.number, payload.getJSONObject("document").getString("number"))
            assertTrue(PzStore.approve(context, complete().id).number.endsWith("/03/0003"))
            assertTrue(RwStore.finalize(context, RwStore.create(context).id)!!.number.endsWith("/03/0002"))
        }
    }

    @Test fun removingOneIdenticalLabelDoesNotRemoveAnotherPhysicalPackage() {
        val doc = complete()
        PzStore.deleteEntries(context, doc.id, setOf(doc.items.first().id))
        assertEquals(listOf(doc.items.last()), PzStore.get(context, doc.id)!!.items)
    }

    @Test fun interruptedPzAndNumberingWritesRetainTheLastCompleteFiles() {
        register()
        val doc = complete()
        fun interrupt(file: File) { AtomicFile(file).startWrite().use { it.write("{broken".toByteArray()) } }
        val stored = File(context.filesDir, "pz/" + doc.id + ".json")
        interrupt(stored)
        assertEquals(doc, PzStore.get(context, doc.id))
        assertTrue(stored.renameTo(File(stored.path + ".bak")))
        assertEquals(listOf(doc), PzStore.list(context))
        val first = DocumentNumbering.reserve(context, "PZ", 2026)
        interrupt(File(context.noBackupFilesDir, "document-numbering.json"))
        assertNotEquals(first, DocumentNumbering.reserve(context, "PZ", 2026))
    }

    @Test fun separateAnnualCountersSurviveYearRollbackAndConcurrentReservations() {
        register()
        assertEquals("PZ/2026/03/0001", DocumentNumbering.reserve(context, "PZ", 2026).number)
        assertEquals("RW/2026/03/0001", DocumentNumbering.reserve(context, "RW", 2026).number)
        assertEquals("PZ/2027/03/0001", DocumentNumbering.reserve(context, "PZ", 2027).number)
        assertEquals("PZ/2026/03/0002", DocumentNumbering.reserve(context, "PZ", 2026).number)
        val results = Collections.synchronizedSet(mutableSetOf<String>())
        val threads = (1..8).map { thread { repeat(5) { results.add(DocumentNumbering.reserve(context, "PZ", 2026).number) } } }
        threads.forEach { it.join() }
        assertEquals(40, results.size)
        assertEquals("PZ/2026/03/0043", DocumentNumbering.reserve(context, "PZ", 2026).number)
        val identity = DocumentNumbering.identity(context)
        assertThrows(IllegalArgumentException::class.java) { DocumentNumbering.register(context, identity.installationId, 4, "http://other") }
        assertEquals(3, DocumentNumbering.identity(context).phoneNumber)
    }

    @Test fun existingRwNumbersRemainUnchangedAndTheirSequenceContinues() {
        val year = LocalDate.now().year
        val legacy = RwStore.create(context)
        val file = File(context.filesDir, "rw/" + legacy.id + ".json")
        val old = JsonFiles.read(file)!!.put("number", "RW/" + year + "/71")
        JsonFiles.write(file, old)
        val original = file.readBytes()
        context.getSharedPreferences("settings", 0).edit().putInt("rw_year", year).putInt("rw_seq", 50).commit()
        val draft = RwStore.create(context)
        assertEquals("RW/" + year + "/00/0072", RwStore.finalize(context, draft.id)!!.number)
        register()
        assertEquals("RW/" + year + "/03/0073", RwStore.finalize(context, RwStore.create(context).id)!!.number)
        assertEquals("RW/" + year + "/00/0072", RwStore.get(context, draft.id)!!.number)
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun rejectedCatalogueRefreshKeepsCacheWhileApprovedBackupCanStillSucceed() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val catalog = CatalogStore.get(context)!!
            val draft = complete()
            assertTrue(ServerSync.perform(context))
            assertEquals(0, server.documents.size)
            val approved = PzStore.approve(context, draft.id)
            server.invalidCatalog = true
            assertFalse(ServerSync.perform(context))
            assertEquals(catalog, CatalogStore.get(context))
            assertTrue(PzBackupStore.isBackedUp(context, approved.id))
            assertEquals(1, server.documents.size)
        }
    }

    @Test fun lostBackupAcknowledgmentRetriesWithoutDuplicatingDocuments() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val approved = PzStore.approve(context, complete().id)
            server.failBackup = true
            assertFalse(ServerSync.perform(context))
            assertFalse(PzBackupStore.isBackedUp(context, approved.id))
            assertEquals(1, server.documents.size)
            server.failBackup = false
            assertTrue(ServerSync.perform(context))
            assertTrue(PzBackupStore.isBackedUp(context, approved.id))
            assertEquals(1, server.documents.size)
            val received = server.documents.getValue(approved.id)
            assertEquals(2, received.getJSONObject("document").getJSONArray("items").length())
            assertEquals(3, server.registrations)
        }
    }

    @Test fun wrongChecksumDoesNotMarkBackupComplete() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val approved = PzStore.approve(context, complete().id)
            server.wrongChecksum = true
            assertFalse(ServerSync.perform(context))
            assertFalse(PzBackupStore.isBackedUp(context, approved.id))
            server.wrongChecksum = false
            assertTrue(ServerSync.perform(context))
            assertTrue(PzBackupStore.isBackedUp(context, approved.id))
        }
    }

    @Test fun pdfWrapsLongNotesAndEmailProvidesAReadableAttachment() {
        register()
        val doc = complete()
        val long = doc.items.last().copy(damageComment = (1..600).joinToString(" ") { "oznaczenie-" + it })
        PzStore.putEntry(context, doc.id, long)
        val approved = PzStore.approve(context, doc.id)
        val pdf = PzPdf.build(context, approved)
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { assertTrue(it.pageCount > 1) }
        }
        val artifacts = base.getExternalFilesDir("verification")!!.apply { mkdirs() }
        pdf.copyTo(File(artifacts, "pz-long-notes.pdf"), overwrite = true)
        PzJson.document(approved).toString().let { File(artifacts, "pz-long-notes.json").writeText(it) }
        val intent = PzPdf.emailIntent(base, approved)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertNull(intent.selector)
        val uri = intent.clipData!!.getItemAt(0).uri
        base.contentResolver.openInputStream(uri)!!.use { assertEquals('%'.code, it.read()) }
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        File(base.filesDir, "pz_pdf/" + approved.id).deleteRecursively()
    }

    @Test fun localAndFallbackRoutesKeepTheSamePhoneNumberAndBackupReceipts() {
        TestServer().use { local ->
            TestServer(local.documents).use { fallback ->
                Settings.setServerUrls(context, local.url, fallback.url)
                local.unavailable = true
                val first = PzStore.approve(context, complete().id)
                assertTrue(ServerSync.perform(context))
                assertEquals(3, DocumentNumbering.identity(context).phoneNumber)
                assertTrue(PzBackupStore.isBackedUp(context, first.id))
                assertEquals(1, local.requests)
                assertEquals(1, fallback.backups)

                local.unavailable = false
                fallback.unavailable = true
                assertTrue(PzBackupStore.isBackedUp(context, first.id))
                val second = PzStore.approve(context, complete().id)
                assertTrue(ServerSync.perform(context))
                assertEquals(1, local.backups)
                assertEquals(1, fallback.backups)
                assertEquals(2, local.documents.size)
                assertEquals(first, PzStore.get(context, first.id))
                assertTrue(second.number.endsWith("/03/0002"))
                assertTrue(PzBackupStore.isBackedUp(context, first.id))

                val catalog = CatalogStore.get(context)
                local.unavailable = true
                val pending = PzStore.approve(context, complete().id)
                assertFalse(ServerSync.perform(context))
                assertFalse(PzBackupStore.isBackedUp(context, pending.id))
                assertEquals(catalog, CatalogStore.get(context))
                assertEquals(3, DocumentNumbering.identity(context).phoneNumber)
            }
        }
    }

    @Test fun failedUploadAcknowledgmentOnOneRouteRetriesIdenticalBytesOnTheOther() {
        TestServer().use { local ->
            TestServer(local.documents).use { fallback ->
                Settings.setServerUrls(context, local.url, fallback.url)
                assertTrue(ServerSync.perform(context))
                val approved = PzStore.approve(context, complete().id)
                local.failBackup = true
                assertTrue(ServerSync.perform(context))
                assertEquals(1, local.backups)
                assertEquals(1, fallback.backups)
                assertArrayEquals(local.lastBackupBody, fallback.lastBackupBody)
                assertEquals(1, local.documents.size)
                assertTrue(PzBackupStore.isBackedUp(context, approved.id))
                assertEquals(approved, PzStore.get(context, approved.id))
            }
        }
    }

    @Test fun unreachablePrimaryAndFallbackOnlyConfigurationCanRegister() {
        TestServer().use { fallback ->
            val unreachable = ServerSocket(0).use { "http://127.0.0.1:" + it.localPort }
            Settings.setServerUrls(context, unreachable, fallback.url)
            assertTrue(ServerSync.perform(context))
            assertEquals(3, DocumentNumbering.identity(context).phoneNumber)
            Settings.setServerUrls(context, "", fallback.url)
            assertEquals(listOf(fallback.url), Settings.serverUrls(context))
            assertTrue(ServerSync.perform(context))
            assertEquals(2, fallback.registrations)
        }
    }

    @Test fun rejectedLabelRulesKeepTheLastGoodCacheAndDoNotBlockBackup() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val cached = LabelRuleStore.get(context).json.toString()
            val read = LabelRuleStore.get(context).apply(LabelRead(PzSummary.now(),
                listOf(LabelCode("PIB32 / SPXFPG005E / 20260721 / 175,000", "QR_CODE")), ""))
            val draft = complete()
            PzStore.putEntry(context, draft.id, draft.items.first().copy(label = read))
            val approved = PzStore.approve(context, draft.id)
            val original = PzJson.document(approved).toString()
            server.labelRules = JSONObject(cached).apply {
                getJSONArray("rules").getJSONObject(0).put("pattern", "[")
            }
            assertFalse(ServerSync.perform(context))
            assertEquals(cached, LabelRuleStore.get(context).json.toString())
            assertTrue(PzBackupStore.isBackedUp(context, approved.id))
            assertEquals("175", server.documents.getValue(approved.id).getJSONObject("document")
                .getJSONArray("items").getJSONObject(0).getJSONObject("label")
                .getJSONArray("values").let { values -> (0 until values.length()).map { values.getJSONObject(it) }
                    .first { it.getString("field") == "quantity" }.getString("value") })
            File(base.getExternalFilesDir("verification")!!.apply { mkdirs() }, "pz-scanned-backup.json")
                .writeBytes(server.lastBackupBody!!)
            server.labelRules = JSONObject(cached).put("updatedAt", "2026-10-28T10:00:00Z")
            assertTrue(ServerSync.perform(context))
            assertEquals("2026-10-28T10:00:00Z", LabelRuleStore.get(context).updatedAt)
            assertEquals(original, PzJson.document(PzStore.get(context, approved.id)!!).toString())
        }
    }

    private fun finalizedRw(): RwDocument {
        val doc = RwStore.create(context)
        RwStore.setPeople(context, doc.id, "Jan", "Ewa")
        RwStore.putItem(context, doc.id, ItemJson.fromJson(JSONObject().put("product", "PA 6").put("quantity", 20)
            .put("unit", "kg").put("raw", "label").put("labelId", "abcdefgh")))
        return RwStore.finalize(context, doc.id)!!
    }

    @Test fun reinstallRecoversIdentityBothHistoriesOriginalPdfAndCounterFloors() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val identity = DocumentNumbering.identity(context)
            val pz = PzStore.approve(context, complete().id)
            val rw = finalizedRw()
            val pdf = PzPdf.build(context, pz).readBytes()
            assertTrue(ServerSync.perform(context))
            DocumentNumbering.reserve(context, "PZ")
            assertTrue(ServerSync.perform(context))
            val key = DocumentNumbering.deviceKey(context)
            context.close()
            context = TestContext(base)
            assertEquals(key, DocumentNumbering.deviceKey(context))
            val newId = DocumentNumbering.identity(context).installationId
            assertNotEquals(identity.installationId, newId)
            // An offline draft created before reconnection must survive recovery.
            val draft = complete()
            Settings.setServerUrls(context, server.url)
            server.failRestoreModule = "RW"
            assertFalse(ServerSync.perform(context))
            assertEquals(identity, DocumentNumbering.identity(context))
            assertTrue(DocumentNumbering.recoveryPending(context))
            assertEquals(pz, PzStore.get(context, pz.id))
            assertArrayEquals(pdf, PzPdf.build(context, pz).readBytes())
            assertNull(RwStore.get(context, rw.id))
            assertEquals(1, server.backups)
            server.failRestoreModule = ""
            assertTrue(ServerSync.perform(context))
            assertFalse(DocumentNumbering.recoveryPending(context))
            assertEquals(rw, RwStore.get(context, rw.id))
            assertEquals(draft, PzStore.get(context, draft.id))
            assertTrue(PzBackupStore.isBackedUp(context, pz.id))
            assertTrue(DocumentBackups.isBackedUp(context, "RW", rw.id))
            assertEquals(1, server.backups)
            assertEquals(1, server.rwBackups)
            assertTrue(PzStore.approve(context, draft.id).number.endsWith("/03/0003"))
            assertTrue(finalizedRw().number.endsWith("/03/0002"))
        }
    }

    @Test fun invalidRecoveryPdfCannotCreateDocumentAndRetryRestoresIt() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val pz = PzStore.approve(context, complete().id)
            assertTrue(ServerSync.perform(context))
            context.close()
            context = TestContext(base)
            Settings.setServerUrls(context, server.url)
            server.wrongRecoveryPdf = true
            assertFalse(ServerSync.perform(context))
            assertNull(PzStore.get(context, pz.id))
            assertTrue(DocumentNumbering.recoveryPending(context))
            server.wrongRecoveryPdf = false
            assertTrue(ServerSync.perform(context))
            assertEquals(pz, PzStore.get(context, pz.id))
        }
    }

    @Test fun counterCheckpointsKeepLongPrecisionAndNeverMoveBackwards() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("rw_year", 2033).putInt("rw_seq", 7).commit()
        val id = DocumentNumbering.identity(context).installationId
        val value = 9007199254740995L
        DocumentNumbering.register(context, id, 3, "http://server", JSONObject().put("RW/2033", value.toString()))
        assertEquals(value.toString(), DocumentNumbering.counters(context).getString("RW/2033"))
        DocumentNumbering.register(context, id, 3, "http://server", JSONObject().put("RW/2033", "1"))
        assertEquals(value + 1, DocumentNumbering.reserve(context, "RW", 2033).number.substringAfterLast('/').toLong())
    }

    @Test fun restoreDoesNotOverwriteAnExistingDifferentLocalDocument() {
        val pz = PzStore.approve(context, complete().id)
        val pdf = PzPdf.build(context, pz).readBytes()
        assertThrows(IllegalArgumentException::class.java) { PzStore.restore(context, pz.copy(supplier = "Other"), pdf) }
        assertEquals(pz, PzStore.get(context, pz.id))
        val rw = finalizedRw()
        assertThrows(IllegalArgumentException::class.java) { RwStore.restore(context, rw.copy(manager = "Other")) }
        assertEquals(rw, RwStore.get(context, rw.id))
    }

    private fun photograph(): Pair<LabelPhoto, ByteArray> {
        val bitmap = android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.WHITE)
        val output = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)
        bitmap.recycle()
        val bytes = output.toByteArray()
        val photo = LabelPhoto(UUID.randomUUID().toString(), Digests.sha256(bytes))
        LabelPhotos.restore(context, photo, bytes)
        return photo to bytes
    }

    @Test fun photoAndReadStayPairedAcrossBackupFailureAndReinstallRecovery() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val draft = complete()
            val (photo, bytes) = photograph()
            val read = LabelRead(PzSummary.now(), listOf(LabelCode("net700", "CODE_128")), "NET 700 KG",
                values = listOf(LabelValue("quantity", "700", "kg")), photo = photo)
            val confirmed = draft.items.first().copy(quantity = "705".toBigDecimal(), label = read)
            PzStore.putEntry(context, draft.id, confirmed)
            assertTrue(ServerSync.perform(context))
            assertEquals(0, server.photoUploads)
            val approved = PzStore.approve(context, draft.id)
            server.failPhoto = true
            assertFalse(ServerSync.perform(context))
            assertEquals(0, server.backups)
            assertFalse(PzBackupStore.isBackedUp(context, approved.id))
            server.failPhoto = false
            assertTrue(ServerSync.perform(context))
            assertTrue(PzBackupStore.isBackedUp(context, approved.id))
            assertEquals(confirmed, PzStore.get(context, approved.id)!!.items.first())
            assertArrayEquals(bytes, Base64.decode(server.photos.getValue(confirmed.id).getJSONObject("photo").getString("contentBase64"), Base64.DEFAULT))
            context.close()
            context = TestContext(base)
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            assertEquals(approved, PzStore.get(context, approved.id))
            assertArrayEquals(bytes, LabelPhotos.bytes(context, photo))
            assertEquals(read, PzStore.get(context, approved.id)!!.items.first().label)
            assertEquals(1, server.backups)
        }
    }

    @Test fun replacedAndDeletedDraftEntriesRemoveTheirOldPhotos() {
        val draft = complete()
        val first = photograph().first
        val second = photograph().first
        val item = draft.items.first().copy(label = LabelRead(PzSummary.now(), emptyList(), "read", photo = first))
        PzStore.putEntry(context, draft.id, item)
        PzStore.putEntry(context, draft.id, item.copy(label = item.label!!.copy(photo = second)))
        assertFalse(LabelPhotos.file(context, first.id).exists())
        assertTrue(LabelPhotos.file(context, second.id).exists())
        PzStore.deleteEntries(context, draft.id, setOf(item.id))
        assertFalse(LabelPhotos.file(context, second.id).exists())
        val third = photograph().first
        PzStore.putEntry(context, draft.id, item.copy(label = item.label.copy(photo = third)))
        PzStore.delete(context, draft.id)
        assertFalse(LabelPhotos.file(context, third.id).exists())
    }

    @Test fun missingOrCorruptPhotosCannotBeAttachedToSavedEntries() {
        val draft = complete()
        val (photo, bytes) = photograph()
        LabelPhotos.file(context, photo.id).writeText("corrupt")
        val item = draft.items.first().copy(label = LabelRead(PzSummary.now(), emptyList(), "read", photo = photo))
        assertThrows(IllegalArgumentException::class.java) { PzStore.putEntry(context, draft.id, item) }
        assertEquals(draft, PzStore.get(context, draft.id))
        assertThrows(IllegalArgumentException::class.java) { LabelPhotos.restore(context, photo.copy(sha256 = "0".repeat(64)), bytes) }
    }

    @Test fun oneRejectedReceiptDoesNotBlockOtherPzsOrRwAndRetryClearsItsError() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val first = PzStore.approve(context, complete().id)
            val second = PzStore.approve(context, complete().id)
            val failed = PzStore.list(context).first { it.approved }
            val other = if (failed.id == first.id) second else first
            val rw = finalizedRw()
            server.failDocumentId = failed.id
            assertFalse(ServerSync.perform(context))
            assertEquals(2, server.backups)
            assertFalse(PzBackupStore.isBackedUp(context, failed.id))
            assertNotNull(PzBackupStore.error(context, failed.id))
            assertTrue(Settings.lastSyncError(context).contains(failed.number))
            assertTrue(PzBackupStore.isBackedUp(context, other.id))
            assertTrue(DocumentBackups.isBackedUp(context, "RW", rw.id))
            assertEquals(1, server.rwBackups)
            server.failDocumentId = ""
            assertTrue(ServerSync.perform(context))
            assertEquals(3, server.backups)
            assertEquals(1, server.rwBackups)
            assertTrue(PzBackupStore.isBackedUp(context, failed.id))
            assertNull(PzBackupStore.error(context, failed.id))
        }
    }

    @Test fun failedPhotoDoesNotBlockLaterReceiptsAndTheDocumentErrorSurvivesReload() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            repeat(2) {
                val draft = complete()
                val photoEntry = draft.items.first().copy(label = LabelRead(PzSummary.now(), emptyList(), "NET 700 KG", photo = photograph().first))
                PzStore.putEntry(context, draft.id, photoEntry)
                PzStore.approve(context, draft.id)
            }
            val docs = PzStore.list(context)
            val failed = docs.first()
            val other = docs.last()
            server.failPhotoEntryId = failed.items.first().id
            assertFalse(ServerSync.perform(context))
            assertFalse(PzBackupStore.isBackedUp(context, failed.id))
            assertNotNull(PzBackupStore.error(context, PzStore.get(context, failed.id)!!.id))
            assertTrue(PzBackupStore.isBackedUp(context, other.id))
            assertEquals(1, server.backups)
            assertEquals(2, server.photoUploads)
            server.failPhotoEntryId = ""
            assertTrue(ServerSync.perform(context))
            assertTrue(PzBackupStore.isBackedUp(context, failed.id))
            assertNull(PzBackupStore.error(context, failed.id))
            assertEquals(2, server.backups)
            assertEquals(3, server.photoUploads)
        }
    }

    @Test fun offlineFinalizedRwRejectsEveryWriteAndDeletionWithoutChangingItsFile() {
        val doc = finalizedRw()
        assertFalse(DocumentBackups.isBackedUp(context, "RW", doc.id))
        val file = File(context.filesDir, "rw/" + doc.id + ".json")
        val original = file.readBytes()
        val item = doc.items.single()
        val edits = listOf<() -> Unit>(
            { RwStore.delete(context, doc.id) },
            { RwStore.putItem(context, doc.id, item.copy(quantity = 99.0)) },
            { RwStore.deleteItems(context, doc.id, setOf(item.sourceKey)) },
            { RwStore.setItemQuantity(context, doc.id, item.sourceKey, 99.0) },
            { RwStore.setPeople(context, doc.id, "Changed", "Changed") })
        for (edit in edits) {
            assertThrows(IllegalStateException::class.java) { edit() }
            assertArrayEquals(original, file.readBytes())
            assertEquals(doc, RwStore.get(context, doc.id))
        }
        assertEquals(doc, RwStore.finalize(context, doc.id))
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun lotsSurviveInventoryRwEditsDeletionBackupAndRecovery() {
        val first = QrParser.parse("Materiał (1) | 500 kg | 2026-11-02 08:00 | QC | aB3dE_fG | 0001", "2026-11-02 09:00:00")!!
        val second = first.copy(lot = "0002")
        val session = SessionStore.createSession(context, "Lots")!!
        assertEquals(SessionStore.AddResult.ADDED, SessionStore.addItem(context, session.id, first))
        assertEquals(SessionStore.AddResult.ADDED, SessionStore.addItem(context, session.id, second))
        assertEquals(SessionStore.AddResult.DUPLICATE, SessionStore.addItem(context, session.id, first.copy(labelId = "00000001")))
        assertEquals(listOf("0001", "0002"), SessionStore.getSession(context, session.id)!!.items.map { it.lot })
        SessionStore.deleteItems(context, session.id, setOf(first.sourceKey))
        assertEquals(listOf(second), SessionStore.getSession(context, session.id)!!.items)
        val rw = RwStore.create(context)
        assertEquals(RwStore.PutResult.ADDED, RwStore.putItem(context, rw.id, first.copy(quantity = 20.0)))
        assertEquals(RwStore.PutResult.ADDED, RwStore.putItem(context, rw.id, second.copy(quantity = 30.0)))
        assertEquals(RwStore.PutResult.UPDATED, RwStore.putItem(context, rw.id, first.copy(quantity = 25.0, labelId = "00000001")))
        RwStore.setItemQuantity(context, rw.id, second.sourceKey, 35.0)
        var saved = RwStore.get(context, rw.id)!!
        assertEquals(listOf(25.0, 35.0), saved.items.map { it.quantity })
        RwStore.deleteItems(context, rw.id, setOf(first.sourceKey))
        saved = RwStore.get(context, rw.id)!!
        assertEquals(listOf(second.copy(quantity = 35.0)), saved.items)
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            RwStore.setPeople(context, rw.id, "Jan", "Ewa")
            val approved = RwStore.finalize(context, rw.id)!!
            assertTrue(ServerSync.perform(context))
            context.close()
            context = TestContext(base)
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            assertEquals(approved, RwStore.get(context, rw.id))
            assertEquals("0002", RwStore.get(context, rw.id)!!.items.single().lot)
        }
    }

    @Test fun packageCountCreatesPhysicalEntriesAndKeepsOnlyTheScannedPackagesPhoto() {
        val draft = PzStore.create(context)
        val photo = photograph().first
        val read = LabelRead(PzSummary.now(), listOf(LabelCode("barcode", "CODE_128")), "NET 25 KG", photo = photo)
        val scanned = entry(damaged = true).copy(quantity = "25".toBigDecimal(), label = read)
        PzStore.putEntry(context, draft.id, scanned, 4)
        val saved = PzStore.get(context, draft.id)!!
        assertEquals(4, saved.items.size)
        assertEquals(4, saved.items.map { it.id }.toSet().size)
        assertEquals(scanned, saved.items.first())
        assertTrue(saved.items.drop(1).all { it == scanned.copy(id = it.id, label = read.copy(photo = null)) })
        assertEquals("100 kg", PzSummary.totals(saved.items))
        assertEquals(4, PzSummary.groups(saved.items).single().damagedCount)
        assertEquals(1, File(context.filesDir, "label_photos").listFiles()!!.size)
        PzStore.deleteEntries(context, draft.id, setOf(scanned.id))
        assertFalse(LabelPhotos.file(context, photo.id).exists())
        assertEquals(3, PzStore.get(context, draft.id)!!.items.size)
        assertTrue(PzStore.get(context, draft.id)!!.items.all { it.label == read.copy(photo = null) })
    }

    @Test fun packageCountsKeepLotsUnitsAndUnknownPhysicalPackagesSeparate() {
        val draft = PzStore.create(context)
        PzStore.putEntry(context, draft.id, entry().copy(quantity = "25".toBigDecimal()), 2)
        PzStore.putEntry(context, draft.id, entry(lot = "0002").copy(quantity = "25".toBigDecimal()), 3)
        PzStore.putEntry(context, draft.id, entry().copy(quantity = "20".toBigDecimal(), unit = "szt."), 2)
        PzStore.putEntry(context, draft.id, entry(lot = null).copy(quantity = "10".toBigDecimal()), 2)
        val saved = PzStore.get(context, draft.id)!!
        assertEquals(9, saved.items.size)
        assertEquals(listOf(2, 3, 2, 1, 1), PzSummary.groups(saved.items).map { it.entries.size })
        assertEquals("145 kg  •  40 szt.", PzSummary.totals(saved.items))
    }

    @Test fun packageCountValidationAndLocksRejectChangesWithoutPartialWrites() {
        val draft = complete()
        val path = File(context.filesDir, "pz/" + draft.id + ".json")
        val bytes = path.readBytes()
        for (count in listOf(-1, 0, 1000)) {
            assertThrows(IllegalArgumentException::class.java) { PzStore.putEntry(context, draft.id, entry(), count) }
            assertArrayEquals(bytes, path.readBytes())
        }
        assertThrows(IllegalArgumentException::class.java) { PzStore.putEntry(context, draft.id, draft.items.first(), 2) }
        assertThrows(PzValidationException::class.java) { PzStore.putEntry(context, draft.id, entry().copy(materialName = ""), 4) }
        assertArrayEquals(bytes, path.readBytes())
        val large = PzStore.create(context)
        PzStore.putEntry(context, large.id, entry(), 999)
        assertEquals(999, PzStore.get(context, large.id)!!.items.map { it.id }.toSet().size)
        PzStore.approve(context, draft.id)
        val approved = path.readBytes()
        assertThrows(IllegalStateException::class.java) { PzStore.putEntry(context, draft.id, entry(), 4) }
        assertArrayEquals(approved, path.readBytes())
    }

    @Test fun countedPackagesBackUpAndRecoverWithOnePhotoAndTheSameScanText() {
        TestServer().use { server ->
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val draft = PzStore.create(context)
            PzStore.edit(context, draft.id) { it.copy(supplier = "Dostawca", receiver = "Jan", deliveryMatches = true) }
            val photo = photograph().first
            val read = LabelRead(PzSummary.now(), emptyList(), "NET 25 KG", photo = photo)
            PzStore.putEntry(context, draft.id, entry().copy(quantity = "25".toBigDecimal(), label = read), 4)
            val approved = PzStore.approve(context, draft.id)
            assertTrue(ServerSync.perform(context))
            assertEquals(1, server.photoUploads)
            assertEquals(1, server.backups)
            assertEquals(4, server.documents.getValue(draft.id).getJSONObject("document").getJSONArray("items").length())
            context.close()
            context = TestContext(base)
            Settings.setServerUrls(context, server.url)
            assertTrue(ServerSync.perform(context))
            val recovered = PzStore.get(context, draft.id)!!
            assertEquals(approved, recovered)
            assertEquals(1, recovered.items.count { it.label?.photo != null })
            assertTrue(recovered.items.all { it.label?.text == read.text })
            assertEquals(1, File(context.filesDir, "label_photos").listFiles()!!.size)
        }
    }

    private class TestServer(
        val documents: MutableMap<String, JSONObject> = Collections.synchronizedMap(mutableMapOf())
    ) : java.io.Closeable {
        private val socket = ServerSocket(0)
        val url = "http://127.0.0.1:" + socket.localPort
        @Volatile var unavailable = false
        @Volatile var requests = 0
        @Volatile var backups = 0
        @Volatile var lastBackupBody: ByteArray? = null
        @Volatile var invalidCatalog = false
        @Volatile var labelRules = JSONObject(InstrumentationRegistry.getInstrumentation().targetContext
            .assets.open("label-rules.json").bufferedReader().use { it.readText() })
        @Volatile var failRegistration = false
        @Volatile var failBackup = false
        @Volatile var failDocumentId = ""
        @Volatile var failPhotoEntryId = ""
        @Volatile var wrongChecksum = false
        @Volatile var registrations = 0
        @Volatile var failPhoto = false
        @Volatile var photoUploads = 0
        val photos = mutableMapOf<String, JSONObject>()
        @Volatile var rwBackups = 0
        @Volatile var failRestoreModule = ""
        @Volatile var wrongRecoveryPdf = false
        private var installationId: String? = null
        private val counters = JSONObject()
        private val rwDocuments = mutableMapOf<String, JSONObject>()
        private val hashes = mutableMapOf<String, String>()
        private val worker = thread {
            while (!socket.isClosed) try {
                socket.accept().use { client ->
                    val input = client.getInputStream()
                    fun line(): String {
                        val out = StringBuilder()
                        while (true) { val next = input.read(); if (next < 0 || next == 10) break; if (next != 13) out.append(next.toChar()) }
                        return out.toString()
                    }
                    val request = line().split(' ')
                    val headers = mutableMapOf<String, String>()
                    while (true) { val row = line(); if (row.isEmpty()) break; headers[row.substringBefore(':').lowercase()] = row.substringAfter(':').trim() }
                    val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
                    var position = 0
                    while (position < body.size) { val read = input.read(body, position, body.size - position); check(read > 0); position += read }
                    var status = 200
                    requests++
                    val response = when {
                        unavailable -> { status = 503; JSONObject() }
                        request[1] == "/materials/devices/register" -> {
                            registrations++
                            if (failRegistration) status = 503
                            val payload = JSONObject(String(body, Charsets.UTF_8))
                            val incomingId = payload.getString("installationId")
                            if (installationId == null) installationId = incomingId
                            val incoming = payload.getJSONObject("counters")
                            for (key in incoming.keys()) counters.put(key, maxOf(counters.optLong(key), incoming.getString(key).toLong()).toString())
                            JSONObject().put("installationId", installationId).put("phoneNumber", 3)
                                .put("counters", counters).put("recoveryRequired", incomingId != installationId)
                        }
                        request[1] == "/materials/catalogue" -> if (invalidCatalog) JSONObject() else JSONObject()
                            .put("updatedAt", "2026-10-27T10:00:00Z").put("materials", JSONArray().put(JSONObject()
                                .put("id", "1").put("name", "PA 6").put("type", "PA").put("group", "Polimery").put("suppliers", JSONArray().put("Dostawca"))))
                        request[1] == "/materials/label-rules" -> labelRules
                        request[0] == "GET" && request[1].contains("/documents") -> {
                            val path = request[1].substringBefore('?').split('/')
                            if (path.last() == "documents") {
                                val entries = (documents.map { (id, value) -> "PZ" to value.getJSONObject("document") } +
                                    rwDocuments.map { (_, value) -> "RW" to value.getJSONObject("document") })
                                    .sortedBy { it.first + ":" + it.second.getString("id") }
                                val after = java.net.URI(request[1]).rawQuery.split('&').firstOrNull { it.startsWith("after=") }
                                    ?.substringAfter('=')?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
                                val remaining = entries.filter { it.first + ":" + it.second.getString("id") > after }
                                val page = remaining.take(1)
                                JSONObject().put("documents", JSONArray().apply { page.forEach { (module, doc) ->
                                    put(JSONObject().put("module", module).put("documentId", doc.getString("id")).put("number", doc.getString("number"))) } })
                                    .put("nextCursor", if (remaining.size > 1) page.last().first + ":" + page.last().second.getString("id") else JSONObject.NULL)
                            } else if (path.last() == "photo") {
                                val stored = photos.getValue(path[path.lastIndex - 1]).getJSONObject("photo")
                                JSONObject().put("photoId", stored.getString("id")).put("sha256", stored.getString("sha256"))
                                    .put("contentBase64", stored.getString("contentBase64")).put("storedAt", "2026-11-03T10:00:00Z")
                            } else {
                                val module = path[path.lastIndex - 1]
                                val id = path.last()
                                if (module == failRestoreModule) status = 503
                                val stored = if (module == "PZ") documents.getValue(id) else rwDocuments.getValue(id)
                                JSONObject().put("module", module).put("document", stored.getJSONObject("document"))
                                    .put("sha256", hashes.getValue(id)).put("storedAt", "2026-10-27T10:00:00Z").apply {
                                        if (module == "PZ") put("pdf", JSONObject(stored.getJSONObject("pdf").toString()).apply {
                                            if (wrongRecoveryPdf) put("sha256", "0".repeat(64))
                                        })
                                    }
                            }
                        }
                        request[0] == "PUT" && request[1].endsWith("/photo") -> {
                            photoUploads++
                            if (failPhoto) status = 503
                            val payload = JSONObject(String(body, Charsets.UTF_8))
                            val photo = payload.getJSONObject("photo")
                            val id = request[1].split('/').let { it[it.lastIndex - 1] }
                            if (id == failPhotoEntryId) status = 503
                            photos[id] = payload
                            JSONObject().put("photoId", photo.getString("id")).put("sha256", photo.getString("sha256"))
                                .put("storedAt", "2026-11-03T10:00:00Z")
                        }
                        request[1].startsWith("/materials/rw/") -> {
                            rwBackups++
                            val payload = JSONObject(String(body, Charsets.UTF_8))
                            val id = payload.getJSONObject("document").getString("id")
                            rwDocuments[id] = payload
                            hashes[id] = Digests.sha256(body)
                            JSONObject().put("documentId", id).put("sha256", Digests.sha256(body)).put("storedAt", "2026-10-27T10:00:00Z")
                        }
                        else -> {
                            backups++
                            lastBackupBody = body
                            val payload = JSONObject(String(body, Charsets.UTF_8))
                            val id = payload.getJSONObject("document").getString("id")
                            check(headers["x-content-sha256"] == Digests.sha256(body))
                            val pdf = payload.getJSONObject("pdf")
                            check(pdf.getString("sha256") == Digests.sha256(Base64.decode(pdf.getString("contentBase64"), Base64.DEFAULT)))
                            documents[id] = payload
                            hashes[id] = Digests.sha256(body)
                            if (failBackup) status = 503
                            if (id == failDocumentId) status = 422
                            JSONObject().put("documentId", id).put("sha256", if (wrongChecksum) "wrong" else Digests.sha256(body)).put("storedAt", "2026-10-27T10:00:00Z")
                        }
                    }
                    val bytes = response.toString().toByteArray(Charsets.UTF_8)
                    client.getOutputStream().apply {
                        write(("HTTP/1.1 " + status + " Result\r\nContent-Type: application/json\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                        write(bytes); flush()
                    }
                }
            } catch (error: SocketException) { if (!socket.isClosed) throw error }
        }
        override fun close() { socket.close(); worker.join(2000) }
    }
}
