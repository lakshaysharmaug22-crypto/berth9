package dev.berth9.server.ai;

import dev.berth9.engine.config.Json;
import dev.berth9.engine.model.TargetField;
import dev.berth9.engine.model.TargetSchema;
import dev.berth9.engine.suggest.ColumnProfile;
import dev.berth9.engine.suggest.Suggestion;
import dev.berth9.server.config.Berth9Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Second pass of mapping suggestions: only fields the deterministic matcher could not settle are sent to
 * a language model, with masked sample values. Speaks the OpenAI-compatible chat API, so it runs against a
 * local Ollama model (data never leaves the building) or any hosted provider by configuration.
 * If the model is disabled, unreachable or answers nonsense, the rules result stands on its own.
 */
@Component
public class LlmMappingAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LlmMappingAdvisor.class);
    private static final Pattern DIGITS = Pattern.compile("\\d");
    private static final Pattern GSTIN = Pattern.compile("^\\d{2}[A-Z]{5}\\d{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");

    private final Berth9Properties.Ai config;
    private final HttpClient http;

    public LlmMappingAdvisor(Berth9Properties props) {
        this.config = props.ai();
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean enabled() {
        return config.enabled() && config.baseUrl() != null && !config.baseUrl().isBlank();
    }

    public String model() {
        return config.model();
    }

    /** Returns suggestions for the unresolved fields; empty when the model is off or fails. */
    public List<Suggestion> advise(TargetSchema schema, List<Suggestion> unresolved, List<ColumnProfile> profiles, Set<String> claimed) {
        if (!enabled() || unresolved.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> columns = new ArrayList<>();
        for (ColumnProfile p : profiles) {
            if (claimed.contains(p.column())) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("column", p.column());
            c.put("samples", p.samples().stream().limit(3).map(LlmMappingAdvisor::mask).toList());
            columns.add(c);
        }
        if (columns.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> fields = new ArrayList<>();
        for (Suggestion s : unresolved) {
            TargetField f = schema.field(s.field()).orElseThrow();
            fields.add(Map.of("field", f.name(), "label", f.label(), "type", f.type().name()));
        }
        String prompt = "Map partner file columns to target fields of a B2B '" + schema.label() + "' document.\n"
                + "Target fields still unmapped: " + Json.write(fields) + "\n"
                + "Unclaimed partner columns with masked sample values: " + Json.write(columns) + "\n"
                + "Reply with JSON only: {\"mappings\":[{\"field\":\"...\",\"column\":\"...\",\"confidence\":0.0-1.0,\"reason\":\"...\"}]}. "
                + "Only map when the column clearly holds that field; omit anything uncertain.";
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", config.model());
            body.put("temperature", 0);
            body.put("messages", List.of(
                    Map.of("role", "system", "content", "You are a data integration analyst. You answer with strict JSON."),
                    Map.of("role", "user", "content", prompt)));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(config.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(Math.max(5, config.timeoutSeconds())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
            if (config.apiKey() != null && !config.apiKey().isBlank()) {
                request.header("Authorization", "Bearer " + config.apiKey());
            }
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("LLM mapping call returned HTTP {}", response.statusCode());
                return List.of();
            }
            return parse(response.body(), schema, columns.stream().map(c -> (String) c.get("column")).toList());
        } catch (Exception e) {
            log.warn("LLM mapping call failed: {}", e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    static List<Suggestion> parse(String responseBody, TargetSchema schema, List<String> allowedColumns) {
        Map<String, Object> response = Json.parseObject(responseBody);
        List<Object> choices = (List<Object>) response.get("choices");
        String content = (String) ((Map<String, Object>) ((Map<String, Object>) choices.get(0)).get("message")).get("content");
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return List.of();
        }
        Map<String, Object> json = Json.parseObject(content.substring(start, end + 1));
        List<Suggestion> out = new ArrayList<>();
        for (Object item : (List<Object>) json.getOrDefault("mappings", List.of())) {
            Map<String, Object> m = (Map<String, Object>) item;
            String field = String.valueOf(m.get("field"));
            String column = String.valueOf(m.get("column"));
            if (schema.field(field).isEmpty() || !allowedColumns.contains(column)) {
                continue; // the model may only pick real fields and real columns
            }
            double confidence = Math.min(0.75, m.get("confidence") instanceof Number n ? n.doubleValue() : 0.5);
            out.add(new Suggestion(field, List.of(column), null, null, null, confidence, Suggestion.band(confidence), "trim",
                    List.of("language model: " + m.getOrDefault("reason", "semantic match")), "llm"));
        }
        return out;
    }

    /** Keeps the shape of a value but not its content: digits become 9, GSTINs are hidden entirely. */
    static String mask(String value) {
        if (value == null) {
            return null;
        }
        if (GSTIN.matcher(value).matches()) {
            return "[GSTIN]";
        }
        return DIGITS.matcher(value).replaceAll("9");
    }
}
