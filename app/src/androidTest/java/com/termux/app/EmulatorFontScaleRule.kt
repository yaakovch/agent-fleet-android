package com.termux.app

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Change real window density before Compose launches, and always restore it. */
internal class EmulatorFontScaleRule(private val method: String, private val scale: Float) : TestRule {
    override fun apply(base: Statement, description: Description): Statement {
        if (description.methodName != method) return base
        return object : Statement() {
            override fun evaluate() {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(command)
                ).bufferedReader().use { it.readText().trim() }
                check(Build.VERSION.SDK_INT == 36 && "x86_64" in Build.SUPPORTED_ABIS &&
                    shell("getprop ro.kernel.qemu") == "1") { "Font mutation requires the protected API36 emulator" }
                val original = shell("settings get system font_scale")
                val prior = original.toFloatOrNull() ?: 1f
                check(prior.isFinite() && prior > 0f)
                fun waitForFont(expected: Float) {
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (SystemClock.elapsedRealtime() < deadline) {
                        instrumentation.waitForIdleSync()
                        if (kotlin.math.abs(instrumentation.targetContext.resources.configuration.fontScale - expected) < 0.01f) return
                        SystemClock.sleep(50)
                    }
                    error("System font scale did not reach $expected")
                }
                try {
                    shell("settings put system font_scale $scale")
                    waitForFont(scale)
                    base.evaluate()
                } finally {
                    shell(if (original == "null") "settings delete system font_scale" else "settings put system font_scale $prior")
                    waitForFont(prior)
                    File(instrumentation.targetContext.externalMediaDirs.first(), "questions-system-font-restored.json").apply {
                        parentFile?.mkdirs()
                        writeText(org.json.JSONObject().put("protectedEmulator", true).put("requested", scale)
                            .put("original", original).put("restored", instrumentation.targetContext.resources.configuration.fontScale).toString())
                    }
                }
            }
        }
    }
}
