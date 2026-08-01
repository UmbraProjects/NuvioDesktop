package com.nuvio.app.features.tracking

import com.nuvio.app.features.library.LibrarySourceMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibrarySourceTest {
    @Test
    fun `a disconnected provider falls back to the local library`() {
        assertEquals(
            LibrarySourceMode.LOCAL,
            resolveLibrarySource(LibrarySourceMode.TRAKT) { false },
        )
        assertEquals(
            LibrarySourceMode.TRAKT,
            resolveLibrarySource(LibrarySourceMode.TRAKT) { it == TrackingProviderId.TRAKT },
        )
        // A connected sibling does not stand in for the selected provider.
        assertEquals(
            LibrarySourceMode.LOCAL,
            resolveLibrarySource(LibrarySourceMode.SIMKL) { it == TrackingProviderId.TRAKT },
        )
    }

    @Test
    fun `the SIMKL boolean wins the migration, as it did at runtime`() {
        assertEquals(
            LibrarySourceMode.SIMKL,
            migratedLibrarySource(simklWasLibrarySource = true, storedMode = LibrarySourceMode.TRAKT),
        )
        assertEquals(
            LibrarySourceMode.TRAKT,
            migratedLibrarySource(simklWasLibrarySource = false, storedMode = LibrarySourceMode.TRAKT),
        )
    }

    @Test
    fun `a stored SIMKL mode that could never take effect does not resurrect on migration`() {
        // Nothing could set LibrarySourceMode.SIMKL — the enum case was unreachable from the UI —
        // so honouring one found on disk would enable a source the user never actually chose.
        assertEquals(
            LibrarySourceMode.LOCAL,
            migratedLibrarySource(simklWasLibrarySource = false, storedMode = LibrarySourceMode.SIMKL),
        )
    }

    @Test
    fun `storage ids round-trip and unknown values do not resolve`() {
        LibrarySourceMode.entries.forEach { mode ->
            assertEquals(mode, librarySourceFromStorage(librarySourceStorageId(mode)))
        }
        assertEquals(LibrarySourceMode.MDBLIST, librarySourceFromStorage("mdblist"))
        assertNull(librarySourceFromStorage(null))
    }

    @Test
    fun `local needs no provider`() {
        assertNull(LibrarySourceMode.LOCAL.trackingProvider)
        assertEquals(LibrarySourceMode.LOCAL, resolveLibrarySource(LibrarySourceMode.LOCAL) { false })
    }

    @Test
    fun `MDBList library selection requires the MDBList connection`() {
        assertEquals(TrackingProviderId.MDBLIST, LibrarySourceMode.MDBLIST.trackingProvider)
        assertEquals(
            LibrarySourceMode.MDBLIST,
            resolveLibrarySource(LibrarySourceMode.MDBLIST) { it == TrackingProviderId.MDBLIST },
        )
    }
}
