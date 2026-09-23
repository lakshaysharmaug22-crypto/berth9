package dev.berth9.engine.read;

import dev.berth9.engine.config.Json;
import dev.berth9.engine.model.SourceRecord;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads JSON documents pushed by partners over the API. Records come from the array named by
 * {@code recordElement} (or the root array); scalar values elsewhere in the document (invoice header,
 * supplier block) are carried onto each record as context. Nested keys are flattened to paths:
 * {@code invoice/number}, {@code supplier/gstin}.
 *
 * <p>API payloads are small, so the document is parsed whole.
 */
public final class JsonRecordReader implements RecordReader {

    private final Iterator<SourceRecord> records;
    private final Set<String> columns = new LinkedHashSet<>();

    public JsonRecordReader(InputStream in, ReaderOptions options) throws IOException {
        this(new String(in.readAllBytes(), options.charsetOrDefault()), options.recordElement());
    }

    public JsonRecordReader(String content, String recordElement) {
        Object root = Json.parse(content.strip().startsWith("﻿") ? content.strip().substring(1) : content);
        Map<String, String> context = new LinkedHashMap<>();
        List<Object> items = new ArrayList<>();
        collect(root, "", recordElement, context, items);
        if (items.isEmpty() && root instanceof List<?> list) {
            items.addAll(list);
        }
        List<SourceRecord> out = new ArrayList<>();
        long n = 0;
        for (Object item : items) {
            Map<String, String> fields = new LinkedHashMap<>(context);
            flatten(item, "", fields);
            columns.addAll(fields.keySet());
            out.add(new SourceRecord(++n, fields));
        }
        this.records = out.iterator();
    }

    private static void collect(Object node, String path, String recordElement, Map<String, String> context, List<Object> items) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                String childPath = path.isEmpty() ? key : path + "/" + key;
                Object value = e.getValue();
                if (key.equals(recordElement) && value instanceof List<?> list && items.isEmpty()) {
                    items.addAll(list);
                } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
                    collect(value, childPath, recordElement, context, items);
                } else {
                    context.put(childPath, scalar(value));
                }
            }
        }
    }

    private static void flatten(Object node, String path, Map<String, String> out) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((k, v) -> flatten(v, path.isEmpty() ? String.valueOf(k) : path + "/" + k, out));
        } else if (node instanceof List<?> list) {
            for (int i = 0; i < list.size(); i++) {
                flatten(list.get(i), path + "[" + (i + 1) + "]", out);
            }
        } else {
            out.put(path.isEmpty() ? "value" : path, scalar(node));
        }
    }

    private static String scalar(Object v) {
        if (v == null) {
            return "";
        }
        return v instanceof BigDecimal b ? b.toPlainString() : v.toString();
    }

    @Override
    public List<String> columns() {
        return List.copyOf(columns);
    }

    @Override
    public boolean hasNext() {
        return records.hasNext();
    }

    @Override
    public SourceRecord next() {
        return records.next();
    }

    @Override
    public void close() {
        // parsed at construction
    }
}
