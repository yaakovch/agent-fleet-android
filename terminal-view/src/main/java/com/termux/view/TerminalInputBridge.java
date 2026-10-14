package com.termux.view;

/** Optional application input policy. Classic Termux clients keep their existing behavior. */
public interface TerminalInputBridge {
    boolean isInputAllowed();
    default boolean isScrollNavigationAllowed() { return isInputAllowed(); }
    boolean isCharBasedInput();
    default boolean isSystemImeAllowed() { return isInputAllowed(); }
    default boolean isAppKeyboardAllowed() { return false; }
    default void onTerminalTargetChanged(Object view, Object session) {}
    default boolean dismissAppKeyboard() { return false; }
    long getInputGeneration();
    int consumeModifiers();
    void beforeInput();
}
