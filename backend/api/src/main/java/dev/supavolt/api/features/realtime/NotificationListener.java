package dev.supavolt.api.features.realtime;

import dev.supavolt.api.infrastructure.tenancy.SchemaNames;
import dev.supavolt.api.infrastructure.tenancy.TenantConnections;
import dev.supavolt.contracts.Contracts.RealtimeEvent;
import dev.supavolt.contracts.Contracts.RealtimeEventType;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.Map;
import java.util.UUID;
import org.postgresql.PGConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Holds one direct Postgres connection for the whole instance, parked on LISTEN, and fans
 * notifications out through the realtime hub. The original opened a connection per channel, per
 * project and table.
 */
@Component
public class NotificationListener implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(NotificationListener.class);

    /** What to_char(now(), 'YYYY-MM-DD"T"HH24:MI:SS.MSOF') produces: an offset of "+00" or "+05:30". */
    private static final DateTimeFormatter PG_TIMESTAMP = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .appendPattern("[XXX][X]")
            .toFormatter();

    private static final TypeReference<Map<String, Object>> ROW = new TypeReference<>() { };

    private final TenantConnections connections;
    private final RealtimeHub hub;
    // Floats as BigDecimal, so numeric columns reach clients exactly as Postgres sent them.
    private final JsonMapper json = JsonMapper.builder().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();

    private volatile boolean running;
    private Thread thread;

    public NotificationListener(TenantConnections connections, RealtimeHub hub) {
        this.connections = connections;
        this.hub = hub;
    }

    /** Started once the app is ready, after the trigger function exists. */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (running) return;
        running = true;
        thread = new Thread(this::run, "realtime-listener");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void start() {
        // Deferred to onReady.
    }

    @Override
    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void run() {
        var backoff = Duration.ofSeconds(1);

        while (running) {
            try (var conn = connections.openListen()) {
                try (var statement = conn.createStatement()) {
                    statement.execute("LISTEN " + SchemaNames.REALTIME_CHANNEL);
                }

                log.info("Listening on channel {}", SchemaNames.REALTIME_CHANNEL);
                backoff = Duration.ofSeconds(1);

                var pg = conn.unwrap(PGConnection.class);
                while (running) {
                    var notifications = pg.getNotifications(10_000);
                    if (notifications == null) continue;
                    for (var n : notifications) dispatch(n.getParameter());
                }
            } catch (Exception e) {
                if (!running) return;

                log.error("Realtime listener dropped; reconnecting in {}", backoff, e);
                try {
                    Thread.sleep(backoff.toMillis());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
                backoff = Duration.ofSeconds(Math.min(backoff.toSeconds() * 2, 30));
            }
        }
    }

    void dispatch(String payload) {
        try {
            var node = json.readTree(payload);
            var projectId = UUID.fromString(node.path("projectId").asString());
            var table = node.path("table").asString();

            var event = new RealtimeEvent(
                    switch (node.path("type").asString()) {
                        case "INSERT" -> RealtimeEventType.INSERT;
                        case "UPDATE" -> RealtimeEventType.UPDATE;
                        default -> RealtimeEventType.DELETE;
                    },
                    table,
                    row(node.path("record")),
                    row(node.path("oldRecord")),
                    projectId,
                    timestamp(node.path("timestamp").asString(null)));

            hub.publish(RealtimeHub.tableGroup(projectId, table), event);
        } catch (RuntimeException e) {
            log.warn("Malformed realtime payload discarded", e);
        }
    }

    private Map<String, Object> row(JsonNode node) {
        return node == null || node.isNull() || node.isMissingNode() ? null : json.convertValue(node, ROW);
    }

    /** In UTC: the trigger formats in the writing session's time zone, which varies by client. */
    private static OffsetDateTime timestamp(String value) {
        try {
            return value == null
                    ? OffsetDateTime.now(ZoneOffset.UTC)
                    : OffsetDateTime.parse(value, PG_TIMESTAMP).withOffsetSameInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
