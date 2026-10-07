package com.termux.app.fleet;

import androidx.annotation.Nullable;
import java.util.Locale;

/** Classifies only terminal launch failures; it retains no remote output. */
public final class AgentFleetAttachmentFailure {
    private AgentFleetAttachmentFailure() {}

    @Nullable public static String permanentCode(int exitStatus, String output) {
        if (exitStatus == 0 || exitStatus == 130 || output == null) return null;
        String tail = output.substring(Math.max(0, output.length() - 8192)).toLowerCase(Locale.ROOT);
        if (tail.matches("(?s).*\\b(session_unavailable|session_invalid):.*")) return "SESSION_UNAVAILABLE";
        if (exitStatus != 255) return null;
        if (tail.contains("remote host identification has changed") || tail.contains("host key verification failed")) return "HOST_KEY_CHANGED";
        if (tail.contains("permission denied (publickey") || tail.contains("permission denied (password") ||
            tail.contains("no supported authentication methods available")) return "SSH_AUTH_REQUIRED";
        if (tail.contains("bad configuration option") || tail.contains("no such identity:")) return "REGISTRY_INVALID";
        return null;
    }
}
