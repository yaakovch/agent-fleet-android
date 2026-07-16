package com.termux.app.fleet;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.shared.logger.Logger;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalViewClient;

public final class WorkspaceTerminalViewClient implements TerminalViewClient {
    @Override public float onScale(float scale) { return Math.max(0.5f, Math.min(2.5f, scale)); }
    @Override public void onSingleTapUp(MotionEvent event) {}
    @Override public boolean shouldBackButtonBeMappedToEscape() { return false; }
    @Override public boolean shouldEnforceCharBasedInput() { return true; }
    @Override public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    @Override public boolean isTerminalViewSelected() { return true; }
    @Override public void copyModeChanged(boolean copyMode) {}
    @Override public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) { return false; }
    @Override public boolean onKeyUp(int keyCode, KeyEvent event) { return false; }
    @Override public boolean onLongPress(MotionEvent event) { return false; }
    @Override public boolean readControlKey() { return false; }
    @Override public boolean readAltKey() { return false; }
    @Override public boolean readShiftKey() { return false; }
    @Override public boolean readFnKey() { return false; }
    @Override public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
        session.writeCodePoint(ctrlDown, codePoint);
        return true;
    }
    @Override public void onEmulatorSet() {}
    @Override public void logError(String tag, String message) { Logger.logError(tag, message); }
    @Override public void logWarn(String tag, String message) { Logger.logWarn(tag, message); }
    @Override public void logInfo(String tag, String message) { Logger.logInfo(tag, message); }
    @Override public void logDebug(String tag, String message) { Logger.logDebug(tag, message); }
    @Override public void logVerbose(String tag, String message) { Logger.logVerbose(tag, message); }
    @Override public void logStackTraceWithMessage(String tag, String message, Exception error) { Logger.logStackTraceWithMessage(tag, message, error); }
    @Override public void logStackTrace(String tag, Exception error) { Logger.logStackTrace(tag, error); }
}
