package dev.berth9.server.store;

/** A trading partner as stored in the {@code partner} table. Secrets are never serialized to the console. */
public record Partner(String id, String name, String country, String city, String role, String channel, String format,
                      String document, String schema, String currency, String gstin, String ediId, String locale,
                      String expectedBy, String apiKey) {
}
