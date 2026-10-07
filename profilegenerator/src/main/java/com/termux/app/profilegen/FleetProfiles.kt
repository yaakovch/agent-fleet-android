package com.termux.app.profilegen

import android.content.ComponentName
import android.content.Intent
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val PACKAGE = "com.yaakovch.fleet"
private fun fixtureIntent() = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PACKAGE, "com.termux.app.fleet.profile.FleetProfileFixtureActivity"))
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
private fun notificationIntent() = Intent(Intent.ACTION_MAIN).setComponent(ComponentName(PACKAGE, "com.termux.app.TermuxActivity"))
    .putExtra("com.yaakovch.fleet.extra.NOTIFICATION_ENTRY", true)

@RunWith(AndroidJUnit4::class)
class FleetBaselineProfileGenerator {
    @get:Rule val profiles = BaselineProfileRule()
    @Test fun launcher() = profiles.collect(PACKAGE, includeInStartupProfile = true) { pressHome(); startActivityAndWait() }
    @Test fun notificationEntry() = profiles.collect(PACKAGE, includeInStartupProfile = true) { pressHome(); startActivityAndWait(notificationIntent()) }
    @Test fun switchingScrollingAndPreview() = profiles.collect(PACKAGE) {
        pressHome()
        startActivityAndWait(fixtureIntent())
        device.waitForIdle()
        assertTrue("Synthetic Native screen did not become visible", device.wait(Until.hasObject(By.text("Terminal fixture")), 30000))
        device.findObject(By.text("Terminal fixture")).click()
        device.waitForIdle()
        device.findObject(By.text("Native fixture")).click()
        device.waitForIdle()
        repeat(5) { device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 3 / 4, 12) }
        device.findObject(By.text("Open file preview")).click()
        assertTrue(device.wait(Until.hasObject(By.textContains("Verified current host file")), 10000))
        device.pressBack()
    }
}

/** Local startup and frame timing. Network/provider latency is deliberately absent. */
@RunWith(AndroidJUnit4::class)
class FleetProfileBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private fun startup(compilation: CompilationMode, notification: Boolean, mode: StartupMode = StartupMode.COLD) = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(StartupTimingMetric()), compilationMode = compilation,
        startupMode = mode, iterations = 20,
        setupBlock = { pressHome() }
    ) { if (notification) startActivityAndWait(notificationIntent()) else startActivityAndWait() }
    @Test fun launcherWithoutProfiles() = startup(CompilationMode.None(), false)
    @Test fun launcherWithProfiles() = startup(CompilationMode.Partial(BaselineProfileMode.Require), false)
    @Test fun notificationWithoutProfiles() = startup(CompilationMode.None(), true)
    @Test fun notificationWithProfiles() = startup(CompilationMode.Partial(BaselineProfileMode.Require), true)
    @Test fun warmLauncherWithoutProfiles() = startup(CompilationMode.None(), false, StartupMode.WARM)
    @Test fun warmLauncherWithProfiles() = startup(CompilationMode.Partial(BaselineProfileMode.Require), false, StartupMode.WARM)
    @Test fun warmNotificationWithoutProfiles() = startup(CompilationMode.None(), true, StartupMode.WARM)
    @Test fun warmNotificationWithProfiles() = startup(CompilationMode.Partial(BaselineProfileMode.Require), true, StartupMode.WARM)
    @Test fun framesWithoutProfiles() = frames(CompilationMode.None())
    @Test fun framesWithProfiles() = frames(CompilationMode.Partial(BaselineProfileMode.Require))
    private fun frames(compilation: CompilationMode) = benchmark.measureRepeated(
        PACKAGE, listOf(FrameTimingMetric()), compilationMode = compilation, iterations = 20,
        setupBlock = { startActivityAndWait(fixtureIntent()) }
    ) { repeat(5) { device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 3 / 4, 12) } }
}

/** Compare distinct APKs with and without Fleet rules; both keep library profiles. */
@RunWith(AndroidJUnit4::class)
class FleetReleaseProfileBenchmark {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private fun startup(notification: Boolean, mode: StartupMode) = benchmark.measureRepeated(
        packageName = PACKAGE, metrics = listOf(StartupTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        startupMode = mode, iterations = 20, setupBlock = { pressHome() }
    ) {
        try { if (notification) startActivityAndWait(notificationIntent()) else startActivityAndWait() }
        catch (error: Throwable) {
            val context = InstrumentationRegistry.getInstrumentation().context
            val directory = File(context.getExternalFilesDir(null), "profile-failure").apply { mkdirs() }
            runCatching {
                File(directory, "startup-logcat.txt").writeText(device.executeShellCommand(
                    "logcat -d -t 20000 ActivityTaskManager:I ActivityManager:I AndroidRuntime:E ProfileInstaller:I '*:S'"))
                File(directory, "startup-processes.txt").writeText(device.executeShellCommand("ps -A"))
                device.takeScreenshot(File(directory, "startup-failure.png"))
            }
            throw error
        }
    }
    @Test fun coldLauncher() = startup(false, StartupMode.COLD)
    @Test fun coldNotification() = startup(true, StartupMode.COLD)
    @Test fun warmLauncher() = startup(false, StartupMode.WARM)
    @Test fun warmNotification() = startup(true, StartupMode.WARM)
    @Test fun scrollingFrames() = benchmark.measureRepeated(
        PACKAGE, listOf(FrameTimingMetric()), compilationMode = CompilationMode.Partial(BaselineProfileMode.Require), iterations = 20,
        setupBlock = { startActivityAndWait(fixtureIntent()) }
    ) { repeat(5) { device.swipe(device.displayWidth / 2, device.displayHeight / 3, device.displayWidth / 2, device.displayHeight * 3 / 4, 12) } }
}
