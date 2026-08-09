package com.termux.app.fleet

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FleetAlertTrackerTest {
    private val fixture: JSONObject by lazy {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("client-behavior-v1.json"))
            .bufferedReader()
            .use { it.readText() }
        JSONObject(text).getJSONObject("alerts")
    }

    @Test
    fun constantsAndCategoriesMatchCanonicalFixture() {
        val categories = fixture.getJSONArray("categories")
        assertEquals(
            (0 until categories.length()).map { categories.getJSONObject(it).getString("id") },
            FleetAlertCategory.entries.map(FleetAlertCategory::canonicalId)
        )
        val pause = fixture.getJSONObject("pause")
        assertEquals(FLEET_ALERT_PAUSE_DURATION_MILLIS / 1_000L, pause.getLong("durationSeconds"))
        assertEquals("consume-without-delivery", pause.getString("initialBaseline"))
        assertEquals("consume-without-delivery", pause.getString("whilePaused"))
        assertEquals("new-transitions-only", pause.getString("onResume"))

        val bounds = fixture.getJSONObject("bounds")
        assertEquals(bounds.getInt("hostStates"), MAX_FLEET_ALERT_HOST_STATES)
        assertEquals(bounds.getInt("scheduleStates"), MAX_FLEET_ALERT_SCHEDULE_STATES)
        assertEquals(bounds.getInt("attentionIds"), MAX_FLEET_ALERT_ATTENTION_IDS)
        assertEquals(bounds.getInt("pairingRequestStates"), MAX_FLEET_ALERT_PAIRING_STATES)
        assertEquals(bounds.getInt("deliveriesPerUpdate"), MAX_FLEET_ALERTS_PER_UPDATE)
        assertEquals(bounds.getInt("concreteDeliveriesBeforeSummary"), MAX_CONCRETE_FLEET_ALERTS_PER_UPDATE)

        val android = fixture.getJSONObject("platforms").getJSONObject("android")
        assertEquals("foreground-only", android.getString("observation"))
        assertEquals("in-app", android.getString("delivery"))
        assertEquals("explicit-user-action-only", android.getString("notificationPermission"))
    }

    @Test
    fun baselineAndPausedTransitionsAreConsumedWithoutReplay() {
        val tracker = FleetAlertTracker()
        val expected = verified("git-expected")
        val initial = snapshot(
            hosts = listOf(host("gaming")),
            schedules = listOf(schedule("schedule"))
        )
        assertEquals(emptyList<FleetAlert>(), tracker.process(initial, FleetAlertSettings(), NOW, expected))

        val pausedSnapshot = initial.copy(
            hosts = listOf(host("gaming", status = "offline", version = "git-behind")),
            schedules = listOf(schedule("schedule", status = "delivered")),
            attention = listOf(attention("limit-paused")),
            pairingRequests = listOf(pairing("pair-paused"))
        )
        val paused = FleetAlertSettings(pauseUntilEpochMillis = NOW + FLEET_ALERT_PAUSE_DURATION_MILLIS)
        assertEquals(emptyList<FleetAlert>(), tracker.process(pausedSnapshot, paused, NOW, expected))
        assertEquals(
            emptyList<FleetAlert>(),
            tracker.process(pausedSnapshot, paused.copy(pauseUntilEpochMillis = null), NOW, expected)
        )

        val resumed = pausedSnapshot.copy(
            hosts = listOf(host("gaming", status = "healthy")),
            attention = pausedSnapshot.attention + attention("limit-new"),
            pairingRequests = pausedSnapshot.pairingRequests + pairing("pair-new")
        )
        val alerts = tracker.process(resumed, FleetAlertSettings(), NOW, expected)
        assertEquals(
            listOf(
                "Codex usage limit detected",
                "gaming recovered",
                "Pairing request from device-pair-new"
            ),
            alerts.map(FleetAlert::title)
        )
    }

    @Test
    fun disabledCategoriesStillConsumeTheirTransitions() {
        val tracker = FleetAlertTracker()
        val expected = verified("git-expected")
        val initial = snapshot(
            hosts = listOf(host("gaming")),
            schedules = listOf(schedule("delivered"), schedule("failed"))
        )
        tracker.process(initial, FleetAlertSettings(), NOW, expected)

        val changed = initial.copy(
            hosts = listOf(host("gaming", status = "offline", version = "git-old")),
            schedules = listOf(
                schedule("delivered", status = "delivered"),
                schedule("failed", status = "failed", detail = "Network timeout")
            ),
            attention = listOf(attention("disabled-limit")),
            pairingRequests = listOf(pairing("disabled-pair"))
        )
        val disabled = FleetAlertSettings(
            hardLimits = false,
            deliveryFailures = false,
            deliverySuccess = false,
            hostState = false,
            versionDrift = false,
            pairing = false
        )
        assertEquals(emptyList<FleetAlert>(), tracker.process(changed, disabled, NOW, expected))
        assertEquals(emptyList<FleetAlert>(), tracker.process(changed, FleetAlertSettings(), NOW, expected))
    }

    @Test
    fun emitsAllCanonicalCategoriesWithExactRouteClasses() {
        val tracker = FleetAlertTracker()
        val expected = verified("git-expected")
        val initial = snapshot(
            hosts = listOf(host("gaming")),
            schedules = listOf(schedule("success"), schedule("failure"))
        )
        tracker.process(initial, FleetAlertSettings(), NOW, expected)

        val alerts = tracker.process(
            initial.copy(
                hosts = listOf(host("gaming", status = "offline", version = "git-old")),
                schedules = listOf(
                    schedule("success", status = "delivered"),
                    schedule("failure", status = "interrupted", detail = "Host unavailable")
                ),
                attention = listOf(attention("hard-limit")),
                pairingRequests = listOf(pairing("pairing"))
            ),
            FleetAlertSettings(),
            NOW,
            expected
        )

        assertEquals(
            listOf(
                FleetAlertCategory.HardLimits,
                FleetAlertCategory.HostState,
                FleetAlertCategory.DeliverySuccess,
                FleetAlertCategory.DeliveryFailures,
                FleetAlertCategory.VersionDrift,
                FleetAlertCategory.Pairing
            ),
            alerts.map(FleetAlert::category)
        )
        assertEquals(
            listOf(
                FleetAlertTarget.Session("session-hard-limit"),
                FleetAlertTarget.Host("gaming"),
                FleetAlertTarget.Session("session-success"),
                FleetAlertTarget.Session("session-failure"),
                FleetAlertTarget.Host("gaming"),
                FleetAlertTarget.PairingReview("pairing")
            ),
            alerts.map(FleetAlert::target)
        )
    }

    @Test
    fun onlyVerifiedExpectedVersionInputCanProduceDriftAndSynchronizedUpgradeDoesNotAlert() {
        val tracker = FleetAlertTracker()
        val initial = snapshot(hosts = listOf(host("gaming")))
        tracker.process(initial, FleetAlertSettings(), NOW, expectedHostRuntimeVersion = null)

        val old = initial.copy(hosts = listOf(host("gaming", version = "git-old")))
        val drift = tracker.process(old, FleetAlertSettings(), NOW, verified("git-expected"))
        assertEquals(listOf("gaming runtime version drift"), drift.map(FleetAlert::title))

        val upgradedTogether = old.copy(hosts = listOf(host("gaming", version = "git-new")))
        assertEquals(
            emptyList<FleetAlert>(),
            tracker.process(upgradedTogether, FleetAlertSettings(), NOW, verified("git-new"))
        )
        assertThrows(IllegalArgumentException::class.java) {
            VerifiedExpectedHostRuntimeVersion.fromVerifiedSource("unverified version with spaces")
        }
    }

    @Test
    fun unknownDeliveryOutcomeCannotEnterAlertCopy() {
        val tracker = FleetAlertTracker()
        val initial = snapshot(schedules = listOf(schedule("sentinel")))
        tracker.process(initial, FleetAlertSettings(), NOW)

        val sentinel = "private_prompt_fragment"
        val alerts = tracker.process(
            initial.copy(
                schedules = listOf(
                    schedule("sentinel", status = "failed").copy(
                        outcomeCode = sentinel,
                        detail = "Arbitrary provider detail must never be displayed"
                    )
                )
            ),
            FleetAlertSettings(),
            NOW
        )
        assertEquals("The scheduled action could not be delivered.", alerts.single().body)
        assertFalse(alerts.single().body.contains(sentinel))
        assertFalse(alerts.single().body.contains("provider detail"))
    }

    @Test
    fun resolvedResourcesArePrunedAndARealRecurrenceAlertsAgain() {
        assertEquals("consume-once", fixture.getJSONObject("transitions").getString("unchangedResource"))
        assertEquals("prune", fixture.getJSONObject("transitions").getString("resolvedResource"))
        assertEquals("deliver-again", fixture.getJSONObject("transitions").getString("sameIdRecurrence"))
        val tracker = FleetAlertTracker()
        val initial = snapshot()
        tracker.process(initial, FleetAlertSettings(), NOW)
        val active = initial.copy(
            attention = listOf(attention("recurring-limit")),
            pairingRequests = listOf(pairing("recurring-pairing"))
        )
        assertEquals(2, tracker.process(active, FleetAlertSettings(), NOW).size)

        assertEquals(emptyList<FleetAlert>(), tracker.process(initial, FleetAlertSettings(), NOW))
        assertEquals(0, tracker.state().recentAttention)
        assertEquals(0, tracker.state().recentPairing)

        assertEquals(
            listOf("Codex usage limit detected", "Pairing request from device-recurring-pairing"),
            tracker.process(active, FleetAlertSettings(), NOW).map(FleetAlert::title)
        )
    }

    @Test
    fun duplicateSourceIdsCannotDuplicateAlertsOrRetainedState() {
        val tracker = FleetAlertTracker()
        val initial = snapshot(
            hosts = listOf(host("gaming")),
            schedules = listOf(schedule("delivery"))
        )
        tracker.process(initial, FleetAlertSettings(), NOW, verified("git-expected"))

        val changedHost = host("gaming", status = "offline", version = "git-old")
        val changedSchedule = schedule("delivery", status = "failed")
        val changedAttention = attention("limit")
        val changedPairing = pairing("pair")
        val alerts = tracker.process(
            snapshot(
                hosts = listOf(changedHost, changedHost),
                schedules = listOf(changedSchedule, changedSchedule),
                attention = listOf(changedAttention, changedAttention),
                pairingRequests = listOf(changedPairing, changedPairing)
            ),
            FleetAlertSettings(),
            NOW,
            verified("git-expected")
        )

        assertEquals(5, alerts.size)
        assertEquals(5, alerts.map { it.category }.toSet().size)
        assertEquals(
            FleetAlertTrackerState(hosts = 1, schedules = 1, versionDrifts = 1, recentAttention = 1, recentPairing = 1),
            tracker.state()
        )
    }

    @Test
    fun exactStateBoundsAndFifteenPlusSummaryCapAreEnforced() {
        val oversized = snapshot(
            hosts = List(MAX_FLEET_ALERT_HOST_STATES + 20) { host("host-$it", version = "git-old") },
            schedules = List(MAX_FLEET_ALERT_SCHEDULE_STATES + 20) { schedule("schedule-$it") },
            attention = List(MAX_FLEET_ALERT_ATTENTION_IDS + 20) { attention("attention-$it") },
            pairingRequests = List(MAX_FLEET_ALERT_PAIRING_STATES + 20) { pairing("pair-$it") }
        )
        val bounded = FleetAlertTracker()
        bounded.process(oversized, FleetAlertSettings(), NOW, verified("git-expected"))
        assertEquals(
            FleetAlertTrackerState(
                hosts = MAX_FLEET_ALERT_HOST_STATES,
                schedules = MAX_FLEET_ALERT_SCHEDULE_STATES,
                versionDrifts = MAX_FLEET_ALERT_HOST_STATES,
                recentAttention = MAX_FLEET_ALERT_ATTENTION_IDS,
                recentPairing = MAX_FLEET_ALERT_PAIRING_STATES
            ),
            bounded.state()
        )

        val burst = FleetAlertTracker()
        val empty = snapshot()
        burst.process(empty, FleetAlertSettings(), NOW)
        val alerts = burst.process(
            empty.copy(attention = List(30) { attention("burst-$it") }),
            FleetAlertSettings(),
            NOW
        )
        assertEquals(MAX_FLEET_ALERTS_PER_UPDATE, alerts.size)
        assertEquals("15 more fleet changes", alerts.last().title)
        assertEquals(FleetAlertTarget.Dashboard, alerts.last().target)
    }

    private fun verified(value: String) = VerifiedExpectedHostRuntimeVersion.fromVerifiedSource(value)

    private fun snapshot(
        hosts: List<FleetHost> = emptyList(),
        schedules: List<FleetSchedule> = emptyList(),
        attention: List<FleetAttention> = emptyList(),
        pairingRequests: List<FleetPairingRequest> = emptyList()
    ) = FleetSnapshot(
        revision = "revision",
        generatedAt = "2026-08-01T00:00:00Z",
        hosts = hosts,
        sessions = emptyList(),
        schedules = schedules,
        attention = attention,
        pairingRequests = pairingRequests
    )

    private fun host(
        id: String,
        status: String = "healthy",
        version: String = "git-expected"
    ) = FleetHost(
        id = id,
        name = id,
        status = status,
        platform = "wsl",
        lastSeenAt = "2026-08-01T00:00:00Z",
        capabilities = emptySet(),
        wtmuxVersion = version
    )

    private fun schedule(
        id: String,
        status: String = "pending",
        detail: String = ""
    ) = FleetSchedule(
        id = id,
        hostId = "gaming",
        sessionId = "session-$id",
        deliverAt = "2026-08-01T01:00:00Z",
        status = status,
        outcomeCode = if (detail.isBlank()) "" else "host_unavailable",
        detail = detail
    )

    private fun attention(id: String) = FleetAttention(
        id = id,
        hostId = "gaming",
        sessionId = "session-$id",
        agent = "codex",
        resetAt = "2026-08-01T02:00:00Z",
        state = "detected",
        kind = "hard-limit",
        title = "Codex usage limit detected",
        detail = "gaming · resets 2026-08-01T02:00:00Z"
    )

    private fun pairing(id: String) = FleetPairingRequest(
        id = id,
        deviceName = "device-$id",
        platform = "Windows",
        peer = "device.example.ts.net",
        requestedAt = "2026-08-01T00:00:00Z",
        expiresAt = "2026-08-01T00:10:00Z",
        status = "awaiting-review"
    )

    private companion object {
        const val NOW = 1_786_000_000_000L
    }
}
