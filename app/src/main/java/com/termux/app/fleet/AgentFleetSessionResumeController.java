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
        void startAttachment(FleetSession session);
        boolean selectAttachment(String sessionId);
    }

    public interface Scheduler {
        void postDelayed(Runnable runnable, long delayMillis);
        void cancelAll();
    }

    public interface Listener {
        void onSelected(String sessionId);
        void onError(String message);
    }

    private static final int MAX_POLL_ATTEMPTS = 60;
    private static final int MAX_VISIBLE_RESTARTS = 5;
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
        if (foreground && validSessionId(sessionId) && sessionId.equals(targetSessionId)) return;
        foreground = true;
        generation++;
        scheduler.cancelAll();
        targetSessionId = validSessionId(sessionId) ? sessionId : null;
        startingSessionId = null;
        visibleRestartCount = 0;
        if (targetSessionId != null) drive(generation, 0);
    }

    /** Retry a transport that failed while the managed session is still the visible target. */
    public void onAttachmentEnded(@Nullable String sessionId) {
        if (closed || !foreground || !validSessionId(sessionId) || !sessionId.equals(targetSessionId)) return;
        generation++;
        scheduler.cancelAll();
        startingSessionId = null;
        if (visibleRestartCount >= MAX_VISIBLE_RESTARTS) {
            listener.onError("The connection keeps ending. Switch away and reopen this session to retry.");
            return;
        }
        long expectedGeneration = generation;
        long delay = VISIBLE_RESTART_DELAYS[visibleRestartCount++];
        scheduler.postDelayed(() -> drive(expectedGeneration, 0), delay);
    }

    public void onBackground() {
        foreground = false;
        targetSessionId = null;
        startingSessionId = null;
        visibleRestartCount = 0;
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

        if (!sessionId.equals(startingSessionId)) {
            FleetSession session = sessionLookup.find(sessionId);
            if (session == null) {
                listener.onError("The remembered session is unavailable. Open it again from Sessions.");
                return;
            }
            startingSessionId = sessionId;
            try {
                attachmentHost.startAttachment(session);
            } catch (RuntimeException error) {
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
            listener.onError("The session could not reconnect. Open it again from Sessions.");
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
}
