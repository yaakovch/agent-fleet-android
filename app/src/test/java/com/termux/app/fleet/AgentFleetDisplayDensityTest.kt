package com.termux.app.fleet

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AgentFleetDisplayDensityTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun setUp() {
        context.getSharedPreferences(AgentFleetDisplayDensityStore.PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        AgentFleetDisplayDensityStore.resetForTest()
    }

    @After
    fun tearDown() = setUp()

    @Test
    fun defaultsMatchApprovedCompactDirection() {
        val value = AgentFleetDisplayDensityStore.load(context)
        assertEquals(15, value.nativeBodySp)
        assertEquals(12, value.nativeMetadataSp)
        assertEquals(14, value.nativeCodeSp)
        assertEquals(17, value.nativeHeadingSp)
        assertEquals(15, value.drawerTitleSp)
        assertEquals(12, value.drawerMetadataSp)
        assertEquals(46, value.drawerRowHeightDp)
        assertEquals(30, value.terminalShortcutHeightDp)
    }

    @Test
    fun persistedValuesAreBoundedAndShortcutHeightUsesTwoDpSteps() {
        AgentFleetDisplayDensityStore.save(context, AgentFleetDisplayDensity(2, 99, 35))
        val value = AgentFleetDisplayDensityStore.load(context)
        assertEquals(13, value.nativeBodySp)
        assertEquals(18, value.drawerTitleSp)
        assertEquals(34, value.terminalShortcutHeightDp)
        assertEquals(52, value.drawerRowHeightDp)
        assertEquals(value, AgentFleetDisplayDensityStore.observe(context).value)
    }
}
