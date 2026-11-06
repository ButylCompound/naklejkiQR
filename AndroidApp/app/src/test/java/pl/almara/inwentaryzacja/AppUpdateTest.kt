package pl.almara.inwentaryzacja

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppUpdateTest {
    private fun metadata() = JSONObject().put("versionCode", 12).put("versionName", "1.11")
        .put("sizeBytes", 34567890).put("sha256", "a".repeat(64))

    @Test fun metadataRoundTripsAndUsesAnImmutablePathOnTheConfiguredServer() {
        val update = AppUpdate.parse(metadata())
        assertEquals(update, AppUpdate.parse(update.json()))
        assertEquals("/app-updates/12/" + "a".repeat(64) + ".apk", update.path)
    }

    @Test fun rejectsFractionalStringOrOutOfRangeVersionCodes() {
        for (value in listOf(0, -1, 1.5, "12", 2147483648L))
            assertThrows(Exception::class.java) { AppUpdate.parse(metadata().put("versionCode", value)) }
    }

    @Test fun rejectsInvalidSizesHashesAndEmptyNames() {
        for (value in listOf(0, -1, 1.5, "200"))
            assertThrows(Exception::class.java) { AppUpdate.parse(metadata().put("sizeBytes", value)) }
        for (value in listOf("", "g".repeat(64), "a".repeat(63), "../example.apk"))
            assertThrows(Exception::class.java) { AppUpdate.parse(metadata().put("sha256", value)) }
        assertThrows(Exception::class.java) { AppUpdate.parse(metadata().put("versionName", "  ")) }
    }
}
