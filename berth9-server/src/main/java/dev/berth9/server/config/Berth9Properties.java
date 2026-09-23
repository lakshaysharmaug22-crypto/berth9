package dev.berth9.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * All Berth 9 settings, bound from {@code berth9.*} in application.yml (overridable by env vars,
 * e.g. {@code BERTH9_AI_API_KEY}).
 */
@ConfigurationProperties("berth9")
public record Berth9Properties(Intake intake, Delivery delivery, Security security, Ai ai) {

    /**
     * @param inboxUri  Camel endpoint partners drop files into; one sub-folder per partner id
     * @param inboxDir  local folder behind the default file endpoint (also used by the demo runner)
     * @param outboxDir where acknowledgments (997) and rejection reports for partners are written
     */
    public record Intake(String inboxUri, String inboxDir, String outboxDir) {
    }

    /**
     * @param erpBaseUrl   downstream ERP/OMS API; defaults to the built-in mock
     * @param batchSize    records per delivery call
     * @param maxAttempts  attempts before a record is dead-lettered
     * @param kafkaEnabled publish delivered records as Kafka events
     * @param kafkaTopicPrefix topic = prefix + schema, e.g. berth9.invoice-line
     */
    public record Delivery(String erpBaseUrl, int batchSize, int maxAttempts, boolean kafkaEnabled, String kafkaTopicPrefix) {
    }

    /**
     * @param operatorToken   required on console write APIs when not blank
     * @param maxSkewSeconds  allowed clock skew for signed partner requests
     */
    public record Security(String operatorToken, long maxSkewSeconds) {
    }

    /**
     * OpenAI-compatible chat endpoint used for mapping suggestions the rules could not settle.
     * Works with a local Ollama ({@code http://localhost:11434/v1}) so partner data stays on-prem.
     */
    public record Ai(boolean enabled, String baseUrl, String model, String apiKey, int timeoutSeconds) {
    }
}
