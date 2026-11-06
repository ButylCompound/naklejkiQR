package pl.almara.inwentaryzacja

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.io.IOException
import java.util.TimeZone

class PzTest {
    @Test fun uniqueCatalogueNamesResolveAfterEditsButAmbiguousOrManualNamesDoNot() {
        val catalogue = MaterialCatalog("2026-11-05T10:00:00Z", listOf(
            CatalogMaterial("1", "PA 6", "", "", emptyList()),
            CatalogMaterial("2", "PA 66", "", "", emptyList()),
            CatalogMaterial("3", "Duplicate", "A", "", emptyList()),
            CatalogMaterial("4", "Duplicate", "B", "", emptyList())))
        assertEquals("1", catalogue.resolve("  pa   6 ")?.id)
        assertNull(catalogue.resolve("PA 6 edited", "1"))
        assertEquals("1", catalogue.resolve("PA 6", "1")?.id)
        assertEquals("2", catalogue.resolve("PA 66", "1")?.id)
        assertNull(catalogue.resolve("Duplicate"))
        assertEquals("4", catalogue.resolve("Duplicate", "4")?.id)
        assertNull(catalogue.resolve("Custom material"))
    }

    @Test fun datesUseThePhoneTimeZoneAndRespectDaylightSavingAndDateChanges() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
            assertEquals("2026-10-30 10:18:09", PzSummary.date("2026-10-30T09:18:09.717Z"))
            assertEquals("2026-10-30 10:18:09", PzSummary.date("2026-10-30T10:18:09.717+01:00"))
            assertEquals("2026-07-01 11:18:09", PzSummary.date("2026-07-01T09:18:09Z"))
            assertEquals("2026-01-01 00:30:00", PzSummary.date("2025-12-31T23:30:00Z"))
        } finally {
            TimeZone.setDefault(original)
        }
    }

    @Test fun failedPrimaryFallsBackAndWorkingRouteIsReusedUntilTheNextSync() {
        val attempts = mutableListOf<String>()
        val routes = ServerRoutes(listOf("http://local", "http://tailscale"))
        assertEquals("http://tailscale", routes.call { _, url ->
            attempts.add(url)
            if (url == "http://local") throw IOException("Unavailable")
            url
        })
        assertEquals("http://tailscale", routes.call { _, url -> attempts.add(url); url })
        assertEquals(listOf("http://local", "http://tailscale", "http://tailscale"), attempts)
        assertEquals("http://local", ServerRoutes(listOf("http://local", "http://tailscale")).call { _, url -> url })
    }

    @Test fun timeoutsAndTemporaryServerFailuresCanUseTheOtherAddress() {
        for (status in listOf(408, 500, 503)) {
            assertEquals("http://tailscale", ServerRoutes(listOf("http://local", "http://tailscale")).call { _, url ->
                if (url == "http://local") throw ServerApiException(status)
                url
            })
        }
    }

    @Test fun accessDenialsConflictsAndRateLimitsDoNotSwitchAddresses() {
        for (status in listOf(301, 400, 401, 403, 409, 422, 429)) {
            val attempts = mutableListOf<String>()
            val error = assertThrows(ServerApiException::class.java) {
                ServerRoutes(listOf("http://local", "http://tailscale")).call { _, url ->
                    attempts.add(url)
                    throw ServerApiException(status)
                }
            }
            assertEquals(status, error.status)
            assertEquals(listOf("http://local"), attempts)
        }
    }

    @Test fun invalidResponsesAndRejectedDeviceNumbersDoNotSwitchAddresses() {
        val attempts = mutableListOf<String>()
        assertThrows(IllegalArgumentException::class.java) {
            ServerRoutes(listOf("http://local", "http://tailscale")).call { _, url ->
                attempts.add(url)
                throw IllegalArgumentException("Phone number changed")
            }
        }
        assertEquals(listOf("http://local"), attempts)
    }

    private fun entry(id: String = "one", name: String = "PA 6", lot: String? = "0001", unit: String = "kg", quantity: String = "0.1") =
        PzEntry(id, null, name, lot, if (lot == null) "Label missing" else "", quantity.toBigDecimal(), unit,
            PzCondition.OK, "", "2026-10-27T10:00:00+01:00")

    @Test fun physicalEntriesStayDistinctWhileSummaryGroupsOnlyMatchingLots() {
        val entries = listOf(entry(), entry("two"), entry("three", lot = "0002"), entry("four", name = "PA 66"), entry("five", unit = "szt."))
        val groups = PzSummary.groups(entries)
        assertEquals(5, entries.size)
        assertEquals(listOf(2, 1, 1, 1), groups.map { it.entries.size })
        assertEquals(BigDecimal("0.2"), groups[0].quantity)
    }

    @Test fun unknownLotsNeverMergeEvenForTheSameMaterial() {
        assertEquals(2, PzSummary.groups(listOf(entry(lot = null), entry("two", lot = null))).size)
    }

    @Test fun leadingZerosAndLetterCaseInLotsAreNotChanged() {
        val lots = listOf("01", "1", "AB", "ab")
        assertEquals(4, PzSummary.groups(lots.map { entry(it, lot = it) }).size)
    }

    @Test fun catalogueIdentityGroupsRenamedMaterialButDoesNotMergeManualNames() {
        val first = entry().copy(materialId = "1")
        val second = entry("two", name = "New catalogue spelling").copy(materialId = "1")
        val manual = entry("three", name = "Other material")
        assertEquals(listOf(2, 1), PzSummary.groups(listOf(first, second, manual)).map { it.entries.size })
    }

    @Test fun decimalTotalsAreExactAndUnitsRemainSeparate() {
        val entries = listOf(entry(), entry("two", quantity = "0.2"), entry("three", unit = "szt.", quantity = "20"))
        assertEquals("0.3 kg  •  20 szt.", PzSummary.totals(entries))
        assertEquals("0.000001", PzSummary.number(BigDecimal("0.000001")))
    }

    @Test fun positiveDecimalInputAcceptsCommaAndNeverRoundsToZero() {
        assertEquals(BigDecimal("0.5"), PzSummary.parseQuantity(",5"))
        assertEquals(BigDecimal("12"), PzSummary.parseQuantity("12."))
        for (value in listOf("", "0", "-1", "NaN", "Infinity", "1,2,3")) assertNull(value, PzSummary.parseQuantity(value))
    }

    @Test fun damageUnknownLotAndShippingCommentsAreIndependent() {
        val damaged = entry(lot = null).copy(condition = PzCondition.DAMAGED)
        assertEquals(PzProblem.DAMAGE_COMMENT, damaged.problem())
        val repaired = damaged.copy(damageComment = "Torn bag")
        assertNull(repaired.problem())
        val doc = PzDocument("doc", "2026-10-27T10:00:00+01:00", supplier = "Supplier", receiver = "Receiver",
            items = listOf(repaired), deliveryMatches = true)
        assertNull(doc.problem())
        assertEquals(PzProblem.DELIVERY_COMMENT, doc.copy(deliveryMatches = false).problem())
        assertNull(doc.copy(deliveryMatches = false, deliveryComment = "Quantity differs").problem())
        assertEquals(PzProblem.UNKNOWN_LOT_COMMENT, repaired.copy(unknownLotComment = "").problem())
    }

    @Test fun approvalRequiresAnExplicitDeliveryMatchAnswer() {
        val doc = PzDocument("doc", "2026-10-27T10:00:00+01:00", supplier = "Supplier", receiver = "Receiver", items = listOf(entry()))
        assertEquals(PzProblem.DELIVERY_MATCH, doc.problem())
        assertEquals(PzProblem.EMPTY, doc.copy(items = emptyList()).problem())
    }

    @Test fun documentJsonRetainsEveryPhysicalEntryAndItsNotes() {
        val doc = PzDocument("doc", "2026-10-27T10:00:00+01:00", supplier = "Łódź", receiver = "Jan",
            invoiceNumber = "0001/A", items = listOf(entry(), entry("two", lot = null)), deliveryMatches = false,
            deliveryComment = "Opis\nDruga linia")
        assertEquals(doc, PzJson.document(JSONObject(PzJson.document(doc).toString())))
    }

    @Test fun catalogueParsesTextChoicesAndTrimsSupplierWhitespace() {
        val value = JSONObject("""{"updatedAt":"2026-10-27T10:00:00Z","materials":[{"id":"1","name":"PA 6","type":" PA ","group":" Polimery ","suppliers":["\tSupplier ","Supplier",""]}]}""")
        val catalog = MaterialCatalog.parse(value)
        assertEquals(listOf("Supplier"), catalog.suppliers)
        assertEquals("PA", catalog.materials[0].type)
        assertEquals(catalog, MaterialCatalog.parse(catalog.json()))
    }

    @Test fun invalidAndPartialCatalogueResponsesAreRejected() {
        val valid = JSONObject("""{"updatedAt":"2026-10-27T10:00:00Z","materials":[{"id":"1","name":"PA","type":"","group":"","suppliers":[]}]}""")
        val duplicate = JSONObject(valid.toString()).apply { getJSONArray("materials").put(getJSONArray("materials").getJSONObject(0)) }
        for (value in listOf(JSONObject("{}"), duplicate, JSONObject(valid.toString()).put("updatedAt", "bad"))) {
            assertTrue(runCatching { MaterialCatalog.parse(value) }.isFailure)
        }
    }

    @Test fun serverAddressesCannotEmbedPasswordsOrQueryParameters() {
        assertEquals("https://server.example", ServerAddress.normalize(" https://server.example/ "))
        assertEquals("http://100.64.0.1:8080", ServerAddress.normalize("http://100.64.0.1:8080"))
        for (value in listOf("", "file:///tmp/catalog", "https://user:password@host", "https://host/?key=secret", "https://host/#fragment", "https://host/api", "https://host/materials/catalogue"))
            assertTrue(value, runCatching { ServerAddress.normalize(value) }.isFailure)
    }

    @Test fun serverRootsNormalizeExistingMaterialsAddressesWithoutDuplicatingThePrefix() {
        for (suffix in listOf("", "/", "/materials", "/materials/")) {
            assertEquals("http://192.168.1.154:3000", ServerAddress.normalize("http://192.168.1.154:3000" + suffix))
            assertEquals("http://100.108.237.106:3000", ServerAddress.normalize("http://100.108.237.106:3000" + suffix))
        }
        assertEquals("http://[::1]:3000", ServerAddress.normalize("http://[::1]:3000/"))
    }
}
