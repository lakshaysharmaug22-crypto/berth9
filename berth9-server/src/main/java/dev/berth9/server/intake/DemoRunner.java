package dev.berth9.server.intake;

import dev.berth9.server.config.Berth9Properties;
import dev.berth9.server.events.EventHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Run demo" in the console: drops the bundled partner files into the inbox one by one, exactly as an
 * SFTP upload would, so the real intake route picks them up. Nothing is faked downstream of the inbox.
 */
@Component
public class DemoRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoRunner.class);

    private final Berth9Properties props;
    private final EventHub events;
    private final AtomicBoolean running = new AtomicBoolean();

    public DemoRunner(Berth9Properties props, EventHub events) {
        this.props = props;
        this.events = events;
    }

    public boolean start(long gapMillis) throws IOException {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath:demo/*/*");
        List<Resource> files = new ArrayList<>();
        for (Resource r : resources) {
            String name = r.getFilename();
            if (name != null && !name.isBlank() && !name.endsWith("/") && r.isReadable()) {
                files.add(r); // jar scans also return directory entries; skip them
            }
        }
        files.sort(Comparator.comparing(r -> String.valueOf(r.getFilename())));
        events.publish("demo.started", Map.of("files", files.size()));
        Thread.ofVirtual().name("demo-runner").start(() -> {
            try {
                for (Resource r : files) {
                    String partner = partnerOf(r);
                    Path dir = Path.of(props.intake().inboxDir(), partner);
                    Files.createDirectories(dir);
                    String stamp = String.valueOf(System.currentTimeMillis() % 100000);
                    Path tmp = dir.resolve("." + r.getFilename() + ".part");
                    try (InputStream in = r.getInputStream()) {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.move(tmp, dir.resolve(stamp + "_" + r.getFilename()), StandardCopyOption.ATOMIC_MOVE);
                    Thread.sleep(gapMillis);
                }
            } catch (IOException | InterruptedException e) {
                log.warn("demo run stopped: {}", e.getMessage());
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    private static String partnerOf(Resource r) throws IOException {
        String uri = r.getURI().toString().replace('\\', '/');
        String[] parts = uri.split("/");
        return parts[parts.length - 2];
    }
}
