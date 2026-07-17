package com.termux.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.termux.app.fleet.publishAgentFleetDownload
import com.termux.app.fleet.verifyAgentFleetDownloads
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentFleetDownloadsTest {
    @Test
    fun androidDownloadsProviderPassesTheRealDiagnosticProbe() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        val result = verifyAgentFleetDownloads(context)

        assertEquals("Downloads is writable", result.first)
        assertTrue(result.second.contains("provider"))
    }

    @Test
    fun privateDownloadIsPublishedAndReadableThroughAndroidDownloads() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val body = "agent-fleet-download".toByteArray()
        val source = File(context.cacheDir, "download-publication-fixture.txt").apply { writeBytes(body) }
        val published = publishAgentFleetDownload(context, source, "agent-fleet-download-fixture.txt")
        val uri = Uri.parse(published.location)
        try {
            assertEquals("content", uri.scheme)
            assertEquals(body.size.toLong(), published.size)
            val actual = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            assertArrayEquals(body, actual)
        } finally {
            context.contentResolver.delete(uri, null, null)
            source.delete()
        }
    }
}
