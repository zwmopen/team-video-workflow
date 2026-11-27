package com.zwm.livecore;

/** Preferred strategy for explicitly requested AI motion, not a provider capability claim. */
public enum LiveEnginePreference {
    ZHIPU_FIRST("zhipu_first", "智谱优先 · 待接入"),
    LOCAL_ONLY("local_only", "仅本地"),
    FLOW_FIRST("flow_first", "Google Flow · 待接入");

    public enum Route { LOCAL, ZHIPU, FLOW }
    public final String id, title;
    LiveEnginePreference(String id, String title) { this.id = id; this.title = title; }
    public static LiveEnginePreference fromId(String id) {
        for (LiveEnginePreference p : values()) if (p.id.equals(id)) return p;
        return ZHIPU_FIRST;
    }
    public Route resolve(boolean aiRequested, boolean uploadAuthorized, boolean zhipuReady, boolean flowReady) {
        if (!aiRequested || !uploadAuthorized) return Route.LOCAL;
        if (this == ZHIPU_FIRST && zhipuReady) return Route.ZHIPU;
        if (this == FLOW_FIRST && flowReady) return Route.FLOW;
        return Route.LOCAL;
    }
}
