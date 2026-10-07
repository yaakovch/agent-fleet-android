package com.termux.app.fleet;

import androidx.annotation.Nullable;

/**
 * Restores a managed terminal attachment when its local transport died while
 * the session activity was in the background. The controller is deliberately
 * lifecycle-only: the activity supplies the service and scheduling adapters so
 * the reconnect decision remains deterministic in unit tests.
 */
public final class AgentFleetSessionResumeController {

    public interface SessionLookup {
        @Nullable FleetSession find(String sessionId);
    }

    public interface AttachmentHost {
        boolean hasRunningAttachment(String sessionId);
        void startAttachment(FleetSession session) throws Exception;
        boolean selectAttachment(String sessionId);
        default boolean isOffline(String sessionId) { return false; }
        @Nullable default String recoveryCode(String sessionId) { return null; }
        default String recoveryMessage(String code) { return code; }
    }

    public interface Scheduler {
        void postDelayed(Runnable runnable, long delayMillis);
        void cancelAll();
    }

    public interface Listener {
        void onSelected(String sessionId);
        void onError(String message);
        default void onRecovering(String sessionId) {}
        default void onUnavailable(String sessionId, String message, boolean retryable) { onError(message); }
    }

    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final long STABLE_ATTACHMENT_MILLIS = 30_000L;
    private static final long[] VISIBLE_RESTART_DELAYS = {1_000L, 2_000L, 5_000L, 10_000L, 30_000L};

    private final SessionLookup sessionLookup;
    private final AttachmentHost attachmentHost;
    private final Scheduler scheduler;
    private final Listener listener;

    private boolean foreground;
    private boolean closed;
    private long generation;
    private String targetSessionId;
    private String startingSessionId;
    private int visibleRestartCount;
    private boolean waitingForAvailability;
    private boolean retryScheduled;
    private String blockedCode;
    private boolean forceProbe;

    public AgentFleetSessionResumeController(
        SessionLookup sessionLookup,
        AttachmentHost attachmentHost,
        Scheduler scheduler,
        Listener listener
    ) {
        this.sessionLookup = sessionLookup;
        this.attachmentHost = attachmentHost;
        this.scheduler = scheduler;
        this.listener = listener;
    }

    /** Re-select the running attachment or recreate it once if it has ended. */
    public void onForeground(@Nullable String sessionId) {
        if (closed) return;
        if (foreground && validSessionId(sessionId) && sessionId.equals(targetSessionId)) {
            // A notification can arrive after an exit callback was lost. Preserve
            // in-flight work, but recheck an attachment that is no longer running.
            if (startingSessionId != null || retryScheduled || waitingForAvailability || blockedCode != null ||
                attachmentHost.hasRunningAttachment(sessionId)) return;
            generation++;
            scheduler.cancelAll();
            drive(generation, 0);
            return;
        }
        foreground = true;
        generation++;
        scheduler.cancelAll();
        targetSessionId = validSessionId(sessionId) ? sessionId : null;
        startingSessionId = null;
        visibleRestartCount = 0;
        waitingForAvailability = false;
        retryScheduled = false;
        blockedCode = null;
        if (targetSessionId != null) drive(generation, 0);
    }

    /** Retry a transport that failed while the managed session is still the visible target. */
    public void onAttachmentEnded(@Nullable String sessionId) {
        if (closed || !foreground || !validSessionId(sessionId) || !sessionId.equals(targetSessionId)) return;
        generation++;
        scheduler.cancelAll();
        startingSessionId = null;
        listener.onRecovering(sessionId);
        long expectedGeneration = generation;
        long delay = VISIBLE_RESTART_DELAYS[Math.min(visibleRestartCount, VISIBLE_RESTART_DELAYS.length - 1)];
        visibleRestartCount = Math.min(visibleRestartCount + 1, VISIBLE_RESTART_DELAYS.length);
        retryScheduled = true;
        scheduler.postDelayed(() -> {
            if (expectedGeneration != generation || closed || !foreground) return;
            retryScheduled = false;
            drive(expectedGeneration, 0);
        }, delay);
    }

    /** Availability updates cannot supersede an attachment launch already in flight. */
    public void onAvailabilityChanged() {
        if (closed || !foreground || targetSessionId == null || startingSessionId != null) return;
        if (!waitingForAvailability && blockedCode == null) return;
        if (attachmentHost.hasRunningAttachment(targetSessionId)) return;
        if (blockedCode != null && blockedCode.equals(attachmentHost.recoveryCode(targetSessionId))) return;
        if (attachmentHost.isOffline(targetSessionId)) return;
        generation++;
        scheduler.cancelAll();
        retryScheduled = false;
        waitingForAvailability = false;
        blockedCode = null;
        drive(generation, 0);
    }

    public void retry() {
        if (closed || !foreground || targetSessionId == null || startingSessionId != null) return;
        generation++;
        scheduler.cancelAll();
        retryScheduled = false;
        waitingForAvailability = false;
        blockedCode = null;
        visibleRestartCount = 0;
        forceProbe = true;
        drive(generation, 0);
    }

    public void onBackground() {
        foreground = false;
        targetSessionId = null;
        startingSessionId = null;
        visibleRestartCount = 0;
        waitingForAvailability = false;
        retryScheduled = false;
        blockedCode = null;
        forceProbe = false;
        generation++;
        scheduler.cancelAll();
    }

    public void close() {
        closed = true;
        onBackground();
    }

    private void drive(long expectedGeneration, int attempt) {
        if (closed || !foreground || expectedGeneration != generation || targetSessionId == null) return;
        final String sessionId = targetSessionId;

        if (attachmentHost.hasRunningAttachment(sessionId)) {
            startingSessionId = null;
            if (attachmentHost.selectAttachment(sessionId)) {
                listener.onSelected(sessionId);
                scheduleStableReset(expectedGeneration, sessionId);
            }
            else schedule(expectedGeneration, attempt + 1);
            return;
        }

        String recoveryCode = attachmentHost.recoveryCode(sessionId);
        if (recoveryCode != null && !retryableCode(recoveryCode)) {
            blockedCode = recoveryCode;
            listener.onUnavailable(sessionId, attachmentHost.recoveryMessage(recoveryCode), false);
            return;
        }
        if (!forceProbe && attachmentHost.isOffline(sessionId)) {
            waitingForAvailability = true;
            listener.onUnavailable(sessionId, "Host is offline. Reconnecting when it returns.", true);
            return;
        }
        forceProbe = false;

        if (!sessionId.equals(startingSessionId)) {
            FleetSession session = sessionLookup.find(sessionId);
            if (session == null) {
                blockedCode = "SESSION_UNAVAILABLE";
                listener.onUnavailable(sessionId, "This session is unavailable. Open Sessions to choose another.", false);
                return;
            }
            startingSessionId = sessionId;
            listener.onRecovering(sessionId);
            try {
                attachmentHost.startAttachment(session);
            } catch (Exception error) {
                startingSessionId = null;
                listener.onError(error.getMessage() == null ? "The session could not reconnect." : error.getMessage());
                return;
            }
            if (attachmentHost.hasRunningAttachment(sessionId)) {
                drive(expectedGeneration, attempt);
                return;
            }
        }

        if (attempt >= MAX_POLL_ATTEMPTS) {
            startingSessionId = null;
            onAttachmentEnded(sessionId);
            return;
        }
        schedule(expectedGeneration, attempt + 1);
    }

    private void schedule(long expectedGeneration, int attempt) {
        scheduler.postDelayed(
            () -> drive(expectedGeneration, attempt),
            attempt <= 1 ? 40L : 100L
        );
    }

    private void scheduleStableReset(long expectedGeneration, String sessionId) {
        scheduler.postDelayed(() -> {
            if (!closed && foreground && expectedGeneration == generation && sessionId.equals(targetSessionId) &&
                attachmentHost.hasRunningAttachment(sessionId)) visibleRestartCount = 0;
        }, STABLE_ATTACHMENT_MILLIS);
    }

    private static boolean validSessionId(@Nullable String value) {
        return value != null && value.matches("[A-Za-z0-9._: -]{1,180}");
    }

    private static boolean retryableCode(String code) {
        switch (code) {
            case "NETWORK_UNREACHABLE": case "DNS_UNAVAILABLE": case "HOST_RESPONSE_INVALID":
            case "HOST_RUNTIME_UNAVAILABLE": case "ENDPOINT_TRUST_UNAVAILABLE": case "HANDSHAKE_TIMEOUT":
            case "HEARTBEAT_TIMEOUT": case "SNAPSHOT_TIMEOUT": return true;
            default: return false;
        }
    }
}
