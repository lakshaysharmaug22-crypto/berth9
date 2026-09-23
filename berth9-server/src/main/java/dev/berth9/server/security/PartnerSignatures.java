package dev.berth9.server.security;

import dev.berth9.server.config.Berth9Properties;
import dev.berth9.server.store.Partner;
import dev.berth9.server.store.PartnerStore;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Authenticates partner API pushes. The partner sends its API key, a Unix timestamp and
 * {@code HMAC-SHA256(secret, timestamp + "." + body)} as hex. The timestamp window blocks replays,
 * the HMAC proves the sender holds the secret and that the body was not altered in transit.
 */
@Component
public class PartnerSignatures {

    private final PartnerStore partners;
    private final long maxSkewSeconds;

    public PartnerSignatures(PartnerStore partners, Berth9Properties props) {
        this.partners = partners;
        this.maxSkewSeconds = props.security().maxSkewSeconds();
    }

    public Partner verify(String partnerId, String apiKey, String timestamp, String signature, byte[] body) {
        if (apiKey == null || timestamp == null || signature == null) {
            throw new SecurityException("missing X-Api-Key, X-Timestamp or X-Signature header");
        }
        Partner partner = partners.findByApiKey(apiKey).orElseThrow(() -> new SecurityException("unknown API key"));
        if (!partner.id().equalsIgnoreCase(partnerId)) {
            throw new SecurityException("API key does not belong to partner " + partnerId);
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp.strip());
        } catch (NumberFormatException e) {
            throw new SecurityException("X-Timestamp must be Unix seconds");
        }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > maxSkewSeconds) {
            throw new SecurityException("request timestamp outside the allowed window");
        }
        String secret = partners.secret(partner.id()).orElseThrow(() -> new SecurityException("partner has no API secret"));
        String expected = sign(secret, timestamp.strip(), body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                signature.strip().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
            throw new SecurityException("signature does not match");
        }
        return partner;
    }

    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
