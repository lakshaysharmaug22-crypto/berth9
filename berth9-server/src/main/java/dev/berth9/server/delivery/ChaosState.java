package dev.berth9.server.delivery;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fault injection for the built-in ERP mock, driven from the console, so the circuit breaker,
 * retries and dead-lettering can be shown live instead of described.
 */
@Component
public class ChaosState {

    private volatile boolean erpDown;
    private volatile int latencyMs;
    private volatile int failRatePct;

    public boolean erpDown() {
        return erpDown;
    }

    public int latencyMs() {
        return latencyMs;
    }

    public int failRatePct() {
        return failRatePct;
    }

    public void set(Boolean down, Integer latency, Integer failRate) {
        if (down != null) {
            this.erpDown = down;
        }
        if (latency != null) {
            this.latencyMs = Math.max(0, Math.min(latency, 10_000));
        }
        if (failRate != null) {
            this.failRatePct = Math.max(0, Math.min(failRate, 100));
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("erpDown", erpDown);
        m.put("latencyMs", latencyMs);
        m.put("failRatePct", failRatePct);
        return m;
    }
}
