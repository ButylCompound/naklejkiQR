package pl.almara.inwentaryzacja

import org.junit.Assert.*
import org.junit.Test

class CatalogScanTest {
    private fun catalog(vararg names: String) = MaterialCatalog("2026-11-06T10:00:00Z", names.mapIndexed { index, name ->
        CatalogMaterial(index.toString(), name, "", "", emptyList())
    })

    @Test fun manufacturerDescriptionMatchesTheCatalogueGradeAndKeepsItsNameAndId() {
        val catalogue = catalog("PIB32", "PA 6", "IIR-1953")
        assertEquals(catalogue.materials[0], catalogue.matchScanned("POLIIZOBUTEN PIB32"))
        assertEquals(catalogue.materials[0], catalogue.matchScanned("poliizobuten (pib32)"))
        assertEquals(catalogue.materials[1], catalogue.matchScanned("POLIAMID PA 6"))
        assertEquals(catalogue.materials[1], catalogue.matchScanned("pa6"))
        assertEquals(catalogue.materials[2], catalogue.matchScanned("BUTYL IIR-1953"))
    }

    @Test fun matchesDoNotConsumePartsOfLongerNamesOrDifferentGrades() {
        val catalogue = catalog("PIB32", "PA 6", "IIR-1953")
        for (name in listOf("POLIIZOBUTEN PIB320", "XPIB32", "POLIAMID PA66", "BUTYL IIR-1954", "Unknown"))
            assertNull(name, catalogue.matchScanned(name))
        assertNull(catalog().matchScanned("POLIIZOBUTEN PIB32"))
    }

    @Test fun ambiguousNamesNeedAnExplicitCatalogueIdAndExactFullNamesTakePriority() {
        val catalogue = catalog("PIB32", "PIB33", "Poliizobuten PIB32", "Duplicate", "Duplicate")
        assertNull(catalogue.matchScanned("PIB32 / PIB33"))
        assertNull(catalogue.matchScanned("Duplicate"))
        assertEquals(catalogue.materials[4], catalogue.matchScanned("Duplicate", "4"))
        assertEquals(catalogue.materials[2], catalogue.matchScanned("POLIIZOBUTEN PIB32"))
        assertEquals(catalogue.materials[0], catalogue.matchScanned("PIB32", "missing-id"))
    }
}
