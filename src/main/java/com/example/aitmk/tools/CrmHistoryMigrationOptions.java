package com.example.aitmk.tools;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public record CrmHistoryMigrationOptions(
        boolean dryRun,
        String customerPhone,
        int pageSize,
        int startPage,
        int maxPages,
        Instant from,
        Instant to,
        boolean migrateAssignments,
        boolean migrateMessages,
        boolean initializeUnread,
        boolean stopOnError,
        int logEvery,
        boolean replaceServingAssignment
) {
    public static CrmHistoryMigrationOptions parse(String[] args) {
        Map<String, String> values = new HashMap<>();
        if (args != null) {
            for (String arg : args) {
                if (arg == null || arg.isBlank()) continue;
                String text = arg.trim();
                if (text.startsWith("--")) text = text.substring(2);
                int idx = text.indexOf('=');
                if (idx < 0) values.put(text, "true");
                else values.put(text.substring(0, idx), text.substring(idx + 1));
            }
        }
        return new CrmHistoryMigrationOptions(
                bool(values, "dryRun", true),
                blankToNull(values.get("customerPhone")),
                boundedInt(values, "pageSize", 200, 1, 200),
                boundedInt(values, "startPage", 1, 1, Integer.MAX_VALUE),
                Math.max(0, intValue(values, "maxPages", 0)),
                instant(values.get("from")),
                instant(values.get("to")),
                bool(values, "migrateAssignments", true),
                bool(values, "migrateMessages", true),
                bool(values, "initializeUnread", false),
                bool(values, "stopOnError", false),
                Math.max(1, intValue(values, "logEvery", 100)),
                bool(values, "replaceServingAssignment", false)
        );
    }

    private static boolean bool(Map<String, String> values, String key, boolean fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return fallback;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y" -> true;
            case "false", "0", "no", "n" -> false;
            default -> fallback;
        };
    }

    private static int boundedInt(Map<String, String> values, String key, int fallback, int min, int max) {
        return Math.max(min, Math.min(max, intValue(values, key, fallback)));
    }

    private static int intValue(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        if (value == null || value.isBlank()) return fallback;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Instant instant(String value) {
        if (value == null || value.isBlank()) return null;
        String text = value.trim();
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
