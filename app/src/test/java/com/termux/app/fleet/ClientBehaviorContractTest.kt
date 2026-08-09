package com.termux.app.fleet

import com.termux.shared.data.ExternalUrlPolicy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClientBehaviorContractTest {
    private val fixture: JSONObject by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("client-behavior-v1.json"))
            .bufferedReader()
            .use { it.readText() }
        JSONObject(text)
    }

    @Test
    fun externalLinkPolicyMatchesCanonicalGolden() {
        val policy = fixture.getJSONObject("externalLinks")
        assertEquals("utf16-code-units", policy.getString("lengthMetric"))
        val cases = policy.getJSONArray("cases")
        repeat(cases.length()) { index ->
            val case = cases.getJSONObject(index)
            val decision = ExternalUrlPolicy.classify(case.getString("value"))
            assertEquals(case.getString("id"), case.getString("decision"), decision.action.name.lowercase())
            assertEquals(case.getString("id"), case.getString("scheme"), decision.scheme)
        }

        val blocked = policy.getJSONArray("blockedSchemes")
        repeat(blocked.length()) { index ->
            val scheme = blocked.getString(index)
            val decision = ExternalUrlPolicy.classify("$scheme:example")
            assertEquals(scheme, ExternalUrlPolicy.Action.BLOCK, decision.action)
            assertEquals(scheme, scheme, decision.scheme)
        }
        val automatic = policy.getJSONArray("automaticSchemes")
        repeat(automatic.length()) { index ->
            val scheme = automatic.getString(index)
            val decision = ExternalUrlPolicy.classify("$scheme://example.com")
            assertEquals(scheme, ExternalUrlPolicy.Action.OPEN, decision.action)
            assertEquals(scheme, scheme, decision.scheme)
        }
        assertEquals("confirm-every-time", policy.getString("otherRegisteredSchemes"))
        val prefix = "https://example.com/"
        val maximum = policy.getInt("maxCharacters")
        assertEquals(
            ExternalUrlPolicy.Action.OPEN,
            ExternalUrlPolicy.classify(prefix + "x".repeat(maximum - prefix.length)).action
        )
        val oversized = prefix + "x".repeat(maximum + 1 - prefix.length)
        assertEquals(ExternalUrlPolicy.Action.BLOCK, ExternalUrlPolicy.classify(oversized).action)
        assertEquals(
            ExternalUrlPolicy.Action.OPEN,
            ExternalUrlPolicy.classify(prefix + "😀".repeat(1_014)).action
        )
        assertEquals(
            ExternalUrlPolicy.Action.BLOCK,
            ExternalUrlPolicy.classify(prefix + "😀".repeat(1_015)).action
        )
    }

    @Test
    fun alertGoldenKeepsAndroidForegroundOnly() {
        val alerts = fixture.getJSONObject("alerts")
        val categories = alerts.getJSONArray("categories")
        assertEquals(listOf(
            Triple("hardLimits", listOf("attention.hard-limit"), "session-or-dashboard"),
            Triple("deliveryFailures", listOf("schedule.failed", "schedule.interrupted"), "session-or-dashboard"),
            Triple("deliverySuccess", listOf("schedule.delivered"), "session-or-dashboard"),
            Triple("hostState", listOf("host.offline", "host.recovered"), "host-or-dashboard"),
            Triple("versionDrift", listOf("host-runtime.mismatch-verified-expected"), "host-or-dashboard"),
            Triple("pairing", listOf("pairing.awaiting-review"), "pairing-review")
        ), (0 until categories.length()).map { index ->
            val category = categories.getJSONObject(index)
            val sources = category.getJSONArray("sources")
            Triple(
                category.getString("id"),
                (0 until sources.length()).map(sources::getString),
                category.getString("target")
            )
        })
        val android = alerts.getJSONObject("platforms").getJSONObject("android")
        assertEquals("foreground-only", android.getString("observation"))
        assertEquals("in-app", android.getString("delivery"))
        assertEquals("explicit-user-action-only", android.getString("notificationPermission"))
    }
}
