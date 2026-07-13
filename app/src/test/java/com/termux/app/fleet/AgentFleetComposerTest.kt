package com.termux.app.fleet

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentFleetComposerTest {
    @Test
    fun contentIsTrimmedAndAttachmentsAreSeparated() {
        assertEquals(
            "continue\n\n.wtmux/images/one.png\n.wtmux/images/two.png",
            buildAgentFleetComposerText(
                "continue  \n",
                listOf(".wtmux/images/one.png", ".wtmux/images/two.png")
            )
        )
    }

    @Test
    fun attachmentsWorkWithoutMessageText() {
        assertEquals(".wtmux/images/one.png", buildAgentFleetComposerText("  ", listOf(".wtmux/images/one.png")))
    }

    @Test
    fun emptyPrimaryActionBecomesEnter() {
        assertEquals("Enter", agentFleetPrimaryActionLabel(false))
        assertEquals("Send", agentFleetPrimaryActionLabel(true))
    }
}
