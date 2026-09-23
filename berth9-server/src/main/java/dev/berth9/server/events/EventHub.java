package dev.berth9.server.events;

import dev.berth9.engine.config.Json;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Pushes pipeline events to the live console over Server-Sent Events and keeps the most recent ones,
 * so a console that connects late (or a replay recording) sees what just happened.
 */
@Component
public class EventHub {

    private static final Logger log = LoggerFactory.getLogger(EventHub.class);
    private static final int KEEP = 1000;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();
    private final AtomicLong sequence = new AtomicLong();

    public SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }

    public void publish(String type, Map<String, Object> data) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("seq", sequence.incrementAndGet());
        event.put("type", type);
        event.put("at", Instant.now().toString());
        event.put("data", data);
        synchronized (recent) {
            recent.addLast(event);
            while (recent.size() > KEEP) {
                recent.removeFirst();
            }
        }
        String json = Json.write(event);
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("pipeline").data(json));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
        log.debug("event {} {}", type, data);
    }

    public List<Map<String, Object>> recent(long afterSeq) {
        synchronized (recent) {
            return new ArrayList<>(recent.stream().filter(e -> ((Long) e.get("seq")) > afterSeq).toList());
        }
    }

    /** Keeps idle connections open through proxies. */
    @Scheduled(fixedDelay = 15000)
    void heartbeat() {
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (IOException | IllegalStateException e) {
                emitters.remove(emitter);
            }
        }
    }

    public int subscribers() {
        return emitters.size();
    }
}
