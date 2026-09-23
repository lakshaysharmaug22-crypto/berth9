package dev.berth9.server.delivery;

import dev.berth9.engine.config.Json;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Stand-in for the buyer's ERP (accounts payable) and OMS APIs. Honours an Idempotency-Key the way a
 * real payment/ERP API would, and misbehaves on demand through {@link ChaosState}.
 */
@RestController
@RequestMapping("/mock/erp")
public class ErpMockController {

    private final ChaosState chaos;
    private final Set<String> seenKeys = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicLong> received = new ConcurrentHashMap<>();

    public ErpMockController(ChaosState chaos) {
        this.chaos = chaos;
    }

    @PostMapping("/{document}")
    public ResponseEntity<Map<String, Object>> receive(@PathVariable String document,
                                                       @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                                       @RequestBody String body) throws InterruptedException {
        if (chaos.latencyMs() > 0) {
            Thread.sleep(chaos.latencyMs());
        }
        if (chaos.erpDown()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "ERP maintenance window"));
        }
        if (ThreadLocalRandom.current().nextInt(100) < chaos.failRatePct()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "upstream timeout"));
        }
        if (key != null && !seenKeys.add(key)) {
            return ResponseEntity.ok(Map.of("status", "already-applied", "idempotencyKey", key));
        }
        int count = ((List<?>) Json.parse(body)).size();
        received.computeIfAbsent(document, d -> new AtomicLong()).addAndGet(count);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("status", "accepted", "records", count));
    }

    @GetMapping("/stats")
    public Map<String, AtomicLong> stats() {
        return received;
    }
}
