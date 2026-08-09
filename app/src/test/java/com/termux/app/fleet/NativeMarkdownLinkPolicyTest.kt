package com.termux.app.fleet

import android.app.Activity
import android.content.Intent
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
class NativeMarkdownLinkPolicyTest {
    @Test
    fun webLinkUsesTheValidatedExternalIntent() {
        val activity = activity()
        clickLink(activity, "https://example.com/docs")
        val started = shadowOf(activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("https://example.com/docs", started.dataString)
    }

    @Test
    fun customLinkRequiresAnExplicitConfirmationSurface() {
        val activity = activity()
        clickLink(activity, "web+fleet://status/healthy")
        assertNull(shadowOf(activity).nextStartedActivity)
        assertTrue(requireNotNull(ShadowDialog.getLatestDialog()).isShowing)
    }

    @Test
    fun privilegedMarkdownSchemesNeverReachAndroidIntentRouting() {
        listOf("file:///private/example", "javascript:example", "intent:example", "wtmux://pair").forEach { url ->
            val activity = activity()
            val view = renderLink(activity, url)
            val text = view.text as Spanned
            assertTrue(url, text.getSpans(0, text.length, ClickableSpan::class.java).isEmpty())
            assertEquals("Open", text.toString())
            assertNull(url, shadowOf(activity).nextStartedActivity)
        }
    }

    @Test
    fun renderedAccessibleTextKeepsTheLabelButNotTheRawDestination() {
        val activity = activity()
        val view = TextView(activity)
        nativeMarkdownMarkwon(activity).setMarkdown(view, "Read [Fleet docs](https://example.com/private?q=1)")
        assertEquals("Read Fleet docs", view.text.toString())
        assertSame(nativeMarkdownMarkwon(activity), nativeMarkdownMarkwon(activity))
    }

    private fun clickLink(activity: Activity, url: String) {
        val view = renderLink(activity, url)
        val text = view.text as Spanned
        val span = text.getSpans(0, text.length, ClickableSpan::class.java).singleOrNull()
        assertNotNull("Expected a clickable Markdown link for $url", span)
        requireNotNull(span).onClick(view)
    }

    private fun renderLink(activity: Activity, url: String): TextView = TextView(activity).also { view ->
        nativeMarkdownMarkwon(activity).setMarkdown(view, "[Open]($url)")
    }

    private fun activity(): Activity {
        val controller = Robolectric.buildActivity(Activity::class.java)
        return controller.get().also { it.setTheme(androidx.appcompat.R.style.Theme_AppCompat) }
            .also { controller.setup() }
    }
}
