package com.termux.app.fleet

import org.junit.Assert.*
import org.junit.Test

class AgentFleetAttachmentFailureTest {
    @Test fun brokenPipeAndAbortedTransportRemainRetryable() {
        for (output in listOf("client_loop: send disconnect: Broken pipe", "Read from remote host host: Software caused connection abort")) {
            assertNull(AgentFleetAttachmentFailure.permanentCode(255, output))
        }
    }
    @Test fun permanentErrorsAreClassifiedOnlyForFailedLaunches() {
        assertEquals("HOST_KEY_CHANGED", AgentFleetAttachmentFailure.permanentCode(255, "WARNING: REMOTE HOST IDENTIFICATION HAS CHANGED!"))
        assertEquals("SSH_AUTH_REQUIRED", AgentFleetAttachmentFailure.permanentCode(255, "user@host: Permission denied (publickey,password)."))
        assertEquals("SESSION_UNAVAILABLE", AgentFleetAttachmentFailure.permanentCode(1, "wtmux: SESSION_UNAVAILABLE: the exact session no longer exists"))
        assertEquals("SESSION_UNAVAILABLE", AgentFleetAttachmentFailure.permanentCode(1, "[wtmux][error] SESSION_UNAVAILABLE\n"))
        assertNull(AgentFleetAttachmentFailure.permanentCode(1, "The documentation mentions SESSION_UNAVAILABLE without an error prefix."))
        assertNull(AgentFleetAttachmentFailure.permanentCode(0, "Permission denied (publickey)."))
        assertNull(AgentFleetAttachmentFailure.permanentCode(130, "Permission denied (publickey)."))
        assertNull(AgentFleetAttachmentFailure.permanentCode(1, "Permission denied (publickey)."))
    }
}
