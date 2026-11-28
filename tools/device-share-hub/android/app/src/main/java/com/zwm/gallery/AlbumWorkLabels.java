package com.zwm.gallery;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AlbumWorkLabels {
    static final String[] FILES = {"metadata.json", "manifest.json", "作品标签.json", "source-provenance.json"};
    private static final String[] KEYS = {
            "generationEngine", "productionChannel", "seasons", "flowType", "contentType", "applicableMonths"};
    private static final String[] TITLES = {"出图引擎", "制作渠道", "季节属性", "流量定位", "内容类型", "适用月份"};

    static final AlbumWorkLabels EMPTY = new AlbumWorkLabels(null);

    private final Map<String, List<String>> groups = new LinkedHashMap<>();
    private final String producedAt;

    AlbumWorkLabels(JSONObject projection) {
        for (String key : KEYS) {
            List<String> values = values(projection == null ? null : projection.opt(key), key);
            groups.put(key, values);
        }
        List<String> dates = values(projection == null ? null : projection.opt("producedAt"), "producedAt");
        producedAt = dates.isEmpty() ? "" : dates.get(0);
    }

    static AlbumWorkLabels read(File folder) {
        JSONObject projection = new JSONObject();
        for (String name : FILES) {
            File file = new File(folder, name);
            if (!file.isFile() || file.length() > 512 * 1024) continue;
            try {
                String raw = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                merge(projection, new JSONObject(raw.replaceFirst("^\uFEFF", "")));
            } catch (Exception ignored) {
                // Legacy or malformed sidecars do not prevent a work from opening.
            }
        }
        return new AlbumWorkLabels(projection);
    }

    static void mergeJson(JSONObject result, String raw) {
        try {
            merge(result, new JSONObject(raw.replaceFirst("^\uFEFF", "")));
        } catch (org.json.JSONException ignored) {
            // Metadata is optional and must never invalidate a work.
        }
    }

    private static void merge(JSONObject result, JSONObject document) throws org.json.JSONException {
        JSONObject canonical = document.optJSONObject("canonical");
        JSONObject production = canonical == null ? null : canonical.optJSONObject("production");
        JSONObject classification = canonical == null ? null : canonical.optJSONObject("classification");
        put(result, "generationEngine", get(production, "engineId"), get(production, "generationEngine"), document.opt("generationEngine"));
        put(result, "productionChannel", get(production, "channelId"), get(production, "productionChannel"), document.opt("productionChannel"));
        put(result, "seasons", get(classification, "seasons"), get(classification, "season"), document.opt("seasons"), document.opt("season"));
        for (String key : new String[]{"flowType", "contentType", "applicableMonths"}) {
            put(result, key, get(classification, key), document.opt(key));
        }
        put(result, "producedAt", get(production, "generatedAt"), get(production, "producedAt"),
                document.opt("producedAt"), document.opt("completedAt"), document.opt("completedAtUtc"));
    }

    private static Object get(JSONObject doc, String key) {
        return doc == null ? null : doc.opt(key);
    }

    private static void put(JSONObject result, String key, Object... alternatives) throws org.json.JSONException {
        if (result.has(key)) return;
        for (Object value : alternatives) {
            if (!values(value, key).isEmpty()) {
                List<String> normalized = values(value, key);
                if ("applicableMonths".equals(key)) {
                    JSONArray months = new JSONArray();
                    for (String label : normalized) months.put(Integer.parseInt(label.replace("月", "")));
                    result.put(key, months);
                } else {
                    result.put(key, "producedAt".equals(key) ? normalized.get(0) : new JSONArray(normalized));
                }
                return;
            }
        }
    }

    private static List<String> values(Object raw, String key) {
        List<String> result = new ArrayList<>();
        JSONArray items = raw instanceof JSONArray ? (JSONArray) raw : new JSONArray().put(raw);
        for (int i = 0; i < items.length(); i++) {
            Object item = items.opt(i);
            if ("applicableMonths".equals(key)) {
                String month = String.valueOf(item).replaceFirst("月$", "");
                if (!month.matches("0?[1-9]|1[0-2]")) continue;
                month = Integer.parseInt(month) + "月";
                if (!result.contains(month)) result.add(month);
                continue;
            }
            if (!(item instanceof String)) continue;
            String value = ((String) item).trim();
            if (value.isEmpty() || value.length() > 80 || value.contains("://") || value.contains("=")
                    || value.contains("\n") || value.contains("\r") || value.startsWith("sk-")
                    || value.toLowerCase(java.util.Locale.ROOT).startsWith("bearer ")) continue;
            if ("producedAt".equals(key) && !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}([T ].*)?")) continue;
            if ("generationEngine".equals(key)) {
                if ("magpie-http".equals(value)) value = "Magpie";
                if ("trueapi-image-direct".equals(value)) value = "True API";
                if ("codex-native-imagegen".equals(value)) value = "Codex 原生";
            }
            if ("productionChannel".equals(key) && "Codex native".equals(value)) value = "Codex";
            if ("flowType".equals(key) && "精准流量团建".equals(value)) value = "精准流量";
            if ("flowType".equals(key) && "泛流量游戏攻略".equals(value)) value = "泛流量";
            if (!result.contains(value)) result.add(value);
        }
        return result;
    }

    static AlbumWorkLabels fromStored(String raw, File directory) {
        if (!raw.isEmpty()) {
            try { return new AlbumWorkLabels(new JSONObject(raw)); }
            catch (org.json.JSONException ignored) { }
        }
        return read(directory);
    }

    List<String> core() {
        List<String> result = new ArrayList<>();
        for (List<String> labels : groups.values()) result.addAll(labels);
        return result;
    }

    String detail(int imageCount) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < KEYS.length; i++) {
            List<String> labels = groups.get(KEYS[i]);
            lines.add(TITLES[i] + "　" + (labels.isEmpty() ? "未分类" : String.join("、", labels)));
        }
        if (!producedAt.isEmpty()) lines.add("制作时间　" + producedAt);
        lines.add("图片数量　" + imageCount);
        return String.join("\n\n", lines);
    }
}
