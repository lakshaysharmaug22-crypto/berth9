package dev.berth9.engine.format;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Works out what a partner actually sent, from the first few KB of the file.
 * The file name is only a hint: suppliers regularly send CSV with a .txt extension
 * or EDI with a .dat one, so content wins over extension.
 */
public final class FormatSniffer {

    private static final char[] DELIMITER_CANDIDATES = {',', '|', '\t', ';'};

    private FormatSniffer() {
    }

    public static SourceFormat detect(String fileName, byte[] head) {
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return SourceFormat.XLSX;
        }
        String text = stripBom(new String(head, 0, Math.min(head.length, 8192), StandardCharsets.UTF_8)).stripLeading();
        if (text.startsWith("ISA")) {
            return SourceFormat.X12;
        }
        if (text.startsWith("<")) {
            return SourceFormat.XML;
        }
        if (text.startsWith("{") || text.startsWith("[")) {
            return SourceFormat.JSON;
        }
        String ext = extension(fileName);
        switch (ext) {
            case "xlsx", "xlsm" -> {
                return SourceFormat.XLSX;
            }
            case "edi", "x12", "810", "850" -> {
                return SourceFormat.X12;
            }
            case "xml" -> {
                return SourceFormat.XML;
            }
            case "json" -> {
                return SourceFormat.JSON;
            }
            default -> {
                // fall through to content checks
            }
        }
        if (looksFixedWidth(text)) {
            return SourceFormat.FIXED_WIDTH;
        }
        return text.isBlank() ? SourceFormat.UNKNOWN : SourceFormat.CSV;
    }

    /**
     * Picks the delimiter whose per-line count is most consistent across the sample.
     * Title rows break consistency a little, which is fine: the mode still wins.
     */
    public static char sniffDelimiter(List<String> lines) {
        char best = ',';
        double bestScore = -1;
        for (char candidate : DELIMITER_CANDIDATES) {
            int[] counts = lines.stream().filter(l -> !l.isBlank()).mapToInt(l -> countOutsideQuotes(l, candidate)).toArray();
            if (counts.length == 0) {
                continue;
            }
            int mode = mode(counts);
            if (mode == 0) {
                continue;
            }
            long matching = java.util.Arrays.stream(counts).filter(c -> c == mode).count();
            double score = (matching / (double) counts.length) * 10 + Math.min(mode, 30) / 30.0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        return best;
    }

    static boolean looksFixedWidth(String text) {
        List<String> lines = text.lines().filter(l -> !l.isBlank()).limit(20).toList();
        if (lines.size() < 3) {
            return false;
        }
        for (char c : DELIMITER_CANDIDATES) {
            long withDelimiter = lines.stream().filter(l -> l.indexOf(c) >= 0).count();
            if (withDelimiter > lines.size() / 2) {
                return false;
            }
        }
        long spaced = lines.stream().filter(l -> l.contains("  ") && l.length() >= 30).count();
        return spaced >= lines.size() * 0.8;
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        int count = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                quoted = !quoted;
            } else if (ch == delimiter && !quoted) {
                count++;
            }
        }
        return count;
    }

    private static int mode(int[] values) {
        java.util.Map<Integer, Integer> freq = new java.util.HashMap<>();
        int best = 0;
        int bestFreq = 0;
        for (int v : values) {
            int f = freq.merge(v, 1, Integer::sum);
            if (f > bestFreq || (f == bestFreq && v > best)) {
                bestFreq = f;
                best = v;
            }
        }
        return best;
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }
}
