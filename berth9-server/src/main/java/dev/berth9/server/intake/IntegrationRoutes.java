package dev.berth9.server.intake;

import dev.berth9.server.config.Berth9Properties;
import dev.berth9.server.delivery.DeliveryService;
import dev.berth9.server.processing.ProcessingService;
import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

/**
 * Camel routes at the edges of the platform.
 *
 * <ul>
 *   <li><b>partner-inbox</b>: polls the inbox endpoint (a local folder by default, SFTP in docker compose).
 *   Files sit in one sub-folder per partner; the folder name identifies the sender. Successful files move
 *   to {@code .done}, files that blow up move to {@code .error}; partial record failures are not route
 *   failures, they go to the exceptions queue.</li>
 *   <li><b>outbox-relay</b>: every two seconds drains validated records to the ERP (see {@link DeliveryService}).</li>
 * </ul>
 */
@Component
public class IntegrationRoutes extends RouteBuilder {

    private final Berth9Properties props;
    private final ProcessingService processing;
    private final DeliveryService delivery;

    public IntegrationRoutes(Berth9Properties props, ProcessingService processing, DeliveryService delivery) {
        this.props = props;
        this.processing = processing;
        this.delivery = delivery;
    }

    @Override
    public void configure() {
        onException(Exception.class)
                .maximumRedeliveries(2)
                .redeliveryDelay(500)
                .logExhausted(true);

        from(props.intake().inboxUri())
                .routeId("partner-inbox")
                .process(this::receive);

        from("timer:outbox-relay?period=2000&delay=3000")
                .routeId("outbox-relay")
                .process(exchange -> exchange.getMessage().setBody(delivery.deliverBatch()));
    }

    private void receive(Exchange exchange) {
        String relative = exchange.getMessage().getHeader(Exchange.FILE_NAME, String.class);
        String fileName = exchange.getMessage().getHeader(Exchange.FILE_NAME_ONLY, String.class);
        String partnerId = partnerFrom(relative);
        byte[] content = exchange.getMessage().getBody(byte[].class);
        String channel = props.intake().inboxUri().startsWith("sftp") ? "SFTP" : "FOLDER";
        ProcessingService.Result result = processing.process(partnerId, fileName, content, channel);
        exchange.getMessage().setBody(result.status());
    }

    /** {@code KESTREL/KESTREL_810.x12} -> KESTREL. Files dropped at the root have no sender and are rejected. */
    static String partnerFrom(String relativePath) {
        if (relativePath == null) {
            throw new IllegalArgumentException("file has no path");
        }
        String normalized = relativePath.replace('\\', '/');
        int slash = normalized.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("put partner files in a folder named after the partner id: " + relativePath);
        }
        return normalized.substring(0, slash).toUpperCase();
    }
}
