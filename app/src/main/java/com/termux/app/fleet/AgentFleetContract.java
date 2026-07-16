package com.termux.app.fleet;

public final class AgentFleetContract {
    public static final String EXTRA_SESSION_NAME = "com.termux.agent_fleet.SESSION_NAME";
    public static final String EXTRA_COMPOSE_INPUT = "com.termux.agent_fleet.COMPOSE_INPUT";
    public static final String EXTRA_HOST_ID = "com.termux.agent_fleet.HOST_ID";
    public static final String EXTRA_PROJECT = "com.termux.agent_fleet.PROJECT";
    public static final String EXTRA_INTERNAL_SESSION = "com.termux.agent_fleet.INTERNAL_SESSION";
    public static final String EXTRA_WORKSPACE_SESSION_ID = "com.termux.agent_fleet.WORKSPACE_SESSION_ID";
    public static final String EXTRA_SHARED_IMAGES = "com.termux.agent_fleet.SHARED_IMAGES";
    public static final String EXTRA_NATIVE_SESSION = "com.termux.agent_fleet.NATIVE_SESSION";
    public static final String EXTRA_LOCAL_SESSION = "com.termux.agent_fleet.LOCAL_SESSION";
    public static final String EXTRA_INITIAL_SURFACE = "com.termux.agent_fleet.INITIAL_SURFACE";
    public static final String EXTRA_FOCUS_SESSION_ID = "com.termux.agent_fleet.FOCUS_SESSION_ID";
    public static final String SURFACE_NATIVE = "native";
    public static final String SURFACE_TERMINAL = "terminal";
    public static final String WORKSPACE_SESSION_PREFIX = "agent-fleet-workspace:";

    private AgentFleetContract() {}
}
