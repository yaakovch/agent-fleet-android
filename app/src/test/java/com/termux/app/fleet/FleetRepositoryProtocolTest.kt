package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FleetRepositoryProtocolTest {
    private fun fixture(): JSONObject {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("repository_page_v1.json"))
            .bufferedReader().use { it.readText() }
        return JSONObject(text)
    }

    @Test
    fun parsesSharedRepositoryPageFixture() {
        val page = FleetRepositoryProtocol.parsePage(fixture())
        assertEquals("docs", page.relativePath)
        assertEquals("docs/guide.pdf", page.entries[1].relativePath)
        assertEquals(1200L, page.entries[1].size)
    }

    @Test
    fun rejectsKindSizeMismatchAndUnknownFields() {
        val mismatch = fixture().also { it.getJSONArray("entries").getJSONObject(1).put("size", JSONObject.NULL) }
        assertThrows(IllegalArgumentException::class.java) { FleetRepositoryProtocol.parsePage(mismatch) }
        val unknown = fixture().also { it.put("prompt", "secret") }
        assertThrows(IllegalArgumentException::class.java) { FleetRepositoryProtocol.parsePage(unknown) }
    }

    @Test
    fun rejectsTraversal() {
        val traversal = fixture().also { it.put("relativePath", "../secret") }
        assertThrows(IllegalArgumentException::class.java) { FleetRepositoryProtocol.parsePage(traversal) }
    }

    @Test
    fun cancellationIsImmediatelyVisibleToTheUi() {
        val cancellation = FleetDownloadCancellation()
        assertFalse(cancellation.isCancelledForUi())
        cancellation.cancel()
        assertTrue(cancellation.isCancelledForUi())
    }
}
