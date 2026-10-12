package com.termux.view;

/** Optional application input policy. Classic Termux clients keep their existing behavior. */
public interface TerminalInputBridge {
    boolean isInputAllowed();
    boolean isCharBasedInput();
    long getInputGeneration();
    int consumeModifiers();
    void beforeInput();
}
