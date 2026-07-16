package com.termux.app.fleet;

public final class AgentFleetContract {
    public static final String EXTRA_SESSION_NAME = "com.yaakovch.fleet.extra.SESSION_NAME";
    public static final String EXTRA_COMPOSE_INPUT = "com.yaakovch.fleet.extra.COMPOSE_INPUT";
    public static final String EXTRA_HOST_ID = "com.yaakovch.fleet.extra.HOST_ID";
    public static final String EXTRA_PROJECT = "com.yaakovch.fleet.extra.PROJECT";
    public static final String EXTRA_INTERNAL_SESSION = "com.yaakovch.fleet.extra.INTERNAL_SESSION";
    public static final String EXTRA_WORKSPACE_SESSION_ID = "com.yaakovch.fleet.extra.WORKSPACE_SESSION_ID";
    public static final String EXTRA_SHARED_IMAGES = "com.yaakovch.fleet.extra.SHARED_IMAGES";
    public static final String EXTRA_NATIVE_SESSION = "com.yaakovch.fleet.extra.NATIVE_SESSION";
    public static final String EXTRA_LOCAL_SESSION = "com.yaakovch.fleet.extra.LOCAL_SESSION";
    public static final String EXTRA_INITIAL_SURFACE = "com.yaakovch.fleet.extra.INITIAL_SURFACE";
    public static final String EXTRA_FOCUS_SESSION_ID = "com.yaakovch.fleet.extra.FOCUS_SESSION_ID";
    public static final String SURFACE_NATIVE = "native";
    public static final String SURFACE_TERMINAL = "terminal";
    public static final String WORKSPACE_SESSION_PREFIX = "agent-fleet-workspace:";

    private AgentFleetContract() {}
}
