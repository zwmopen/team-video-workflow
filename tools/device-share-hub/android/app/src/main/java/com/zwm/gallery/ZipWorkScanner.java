package com.zwm.gallery;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ZipWorkScanner {
    private ZipWorkScanner() {
    }

    static List<WorkPlan> scan(InputStream input) throws Exception {
        Map<String, List<String>> byDirectory = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = validate(entry.getName());
                if (!entry.isDirectory()) {
                    int slash = path.lastIndexOf('/');
                    String directory = slash < 0 ? "" : path.substring(0, slash);
                    byDirectory.computeIfAbsent(directory, ignored -> new ArrayList<>()).add(path);
                }
                zip.closeEntry();
            }
        }

        ArrayList<WorkPlan> result = new ArrayList<>();
        for (Map.Entry<String, List<String>> directory : byDirectory.entrySet()) {
            ArrayList<String> images = new ArrayList<>();
            ArrayList<String> textNames = new ArrayList<>();
            Map<String, String> textPaths = new LinkedHashMap<>();
            for (String path : directory.getValue()) {
                String name = baseName(path);
                if (WorkRules.isSupportedImage(name)) images.add(path);
                if (name.toLowerCase(Locale.ROOT).endsWith(".txt")) {
                    textNames.add(name);
                    textPaths.put(name, path);
                }
            }
            String captionName = WorkRules.chooseCaption(textNames);
            if (images.isEmpty() || captionName.isEmpty()) continue;
            images.sort((left, right) -> WorkRules.compareNatural(baseName(left), baseName(right)));
            result.add(new WorkPlan(baseName(directory.getKey()), directory.getKey(), textPaths.get(captionName), images, textNames.size()));
        }
        result.sort((left, right) -> WorkRules.compareNatural(left.directory, right.directory));
        return result;
    }

    private static String validate(String raw) {
        String path = raw.replace('\\', '/');
        if (path.isEmpty() || path.startsWith("/") || path.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("ZIP 包含非法路径：" + raw);
        }
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) throw new IllegalArgumentException("ZIP 包含越界路径：" + raw);
        }
        return path;
    }

    private static String baseName(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    static final class WorkPlan {
        final String name;
        final String directory;
        final String captionEntry;
        final List<String> imageEntries;
        final int captionFileCount;

        WorkPlan(String name, String directory, String captionEntry, List<String> imageEntries, int captionFileCount) {
            this.name = name;
            this.directory = directory;
            this.captionEntry = captionEntry;
            this.imageEntries = imageEntries;
            this.captionFileCount = captionFileCount;
        }
    }
}
