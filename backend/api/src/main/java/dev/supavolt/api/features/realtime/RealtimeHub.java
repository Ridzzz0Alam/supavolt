package dev.supavolt.api.features.realtime;

import dev.supavolt.api.features.auth.ProjectKey;
import dev.supavolt.api.features.projects.ProjectResolver;
import dev.supavolt.api.infrastructure.tenancy.SqlIdentifier;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The realtime endpoint at /realtime. It speaks the SignalR JSON hub protocol over WebSockets —
 * handshake, invocations, completions and pings — so the dashboard's {@code @microsoft/signalr}
 * client and SDKs written against the .NET hub keep working unchanged. Hub methods are
 * {@code Subscribe(table)} and {@code Unsubscribe(table)}; events arrive as {@code event}.
 *
 * <p>Single instance only: groups live in memory. Scaling out needs a broker (see README).
 */
@Component
public class RealtimeHub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);

    /** SignalR's record separator: every protocol message ends with it. */
    static final char RS = '\u001e';

    private static final int INVOCATION = 1;
    private static final int COMPLETION = 3;
    private static final int PING = 6;
    private static final int CLOSE = 7;

    private final ProjectResolver projects;
    private final JsonMapper json;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, Set<Client>> groups = new ConcurrentHashMap<>();

    public RealtimeHub(ProjectResolver projects, JsonMapper json) {
        this.projects = projects;
        this.json = json;
    }

    public static String projectGroup(UUID projectId) {
        return "project:" + projectId;
    }

    public static String tableGroup(UUID projectId, String table) {
        return "project:" + projectId + ":table:" + table;
    }

    private static final class Client {
        final WebSocketSession session;
        final ProjectKey key;
        final StringBuilder buffer = new StringBuilder();
        final Set<String> groups = ConcurrentHashMap.newKeySet();
        volatile boolean handshaken;

        Client(WebSocketSession session, ProjectKey key) {
            this.session = session;
            this.key = key;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        var key = session.getPrincipal() instanceof Authentication auth && auth.getPrincipal() instanceof ProjectKey k ? k : null;

        // Same rule as the data API: a key issued before the last rotation is dead.
        if (key == null || !projects.isCurrentKey(key.projectId(), key.keyVersion())) {
            log.info("Realtime connection rejected: stale or missing key");
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        // Sends come from the notification listener and the ping timer at once; serialise them.
        var safe = new ConcurrentWebSocketSessionDecorator(session, 10_000, 512 * 1024);
        var client = new Client(safe, key);
        clients.put(session.getId(), client);
        join(client, projectGroup(key.projectId()));
        log.debug("Realtime client {} joined project {}", session.getId(), key.projectId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        var client = clients.get(session.getId());
        if (client == null) return;

        // Frames can carry several records, or part of one.
        client.buffer.append(message.getPayload());
        int end;
        while ((end = client.buffer.indexOf(String.valueOf(RS))) >= 0) {
            var record = client.buffer.substring(0, end);
            client.buffer.delete(0, end + 1);
            handleRecord(client, record);
        }
    }

    private void handleRecord(Client client, String record) throws IOException {
        JsonNode node;
        try {
            node = json.readTree(record);
        } catch (RuntimeException e) {
            client.session.close(CloseStatus.BAD_DATA);
            return;
        }

        if (!client.handshaken) {
            if (!"json".equals(node.path("protocol").asString(""))) {
                send(client, Map.of("error", "Only the json protocol is supported"));
                client.session.close(CloseStatus.NOT_ACCEPTABLE);
                return;
            }
            client.handshaken = true;
            send(client, Map.of());
            return;
        }

        switch (node.path("type").asInt()) {
            case INVOCATION -> invoke(client, node);
            case CLOSE -> client.session.close(CloseStatus.NORMAL);
            default -> {
                // Pings and anything else need no answer.
            }
        }
    }

    /**
     * Subscribe joins a table group, so a key only receives events for tables it explicitly
     * subscribed to. Invalid table names are ignored, as the .NET hub did.
     */
    private void invoke(Client client, JsonNode node) throws IOException {
        var target = node.path("target").asString("").toLowerCase(Locale.ROOT);
        var table = SqlIdentifier.tryCreate(node.path("arguments").path(0).asString("").strip()).orElse(null);
        String error = null;

        switch (target) {
            case "subscribe" -> {
                if (table != null) join(client, tableGroup(client.key.projectId(), table.value()));
            }
            case "unsubscribe" -> {
                if (table != null) leave(client, tableGroup(client.key.projectId(), table.value()));
            }
            default -> error = "Unknown hub method '" + node.path("target").asString("") + "'";
        }

        var invocationId = node.path("invocationId").asString(null);
        if (invocationId != null) {
            var completion = new LinkedHashMap<String, Object>();
            completion.put("type", COMPLETION);
            completion.put("invocationId", invocationId);
            if (error != null) completion.put("error", error);
            send(client, completion);
        }
    }

    /** Fan an event out to everyone subscribed to the table. Serialised once, sent many times. */
    public void publish(String group, Object event) {
        var members = groups.get(group);
        if (members == null || members.isEmpty()) return;

        var frame = new TextMessage(json.writeValueAsString(
                Map.of("type", INVOCATION, "target", "event", "arguments", new Object[] {event})) + RS);

        for (var client : members) {
            if (!client.handshaken) continue;
            try {
                client.session.sendMessage(frame);
            } catch (IOException | RuntimeException e) {
                log.debug("Realtime send to {} failed", client.session.getId(), e);
            }
        }
    }

    /** SignalR clients drop the connection after 30 seconds of silence; ping every 15. */
    @Scheduled(fixedRate = 15_000)
    void keepAlive() {
        var ping = new TextMessage("{\"type\":" + PING + "}" + RS);
        for (var client : clients.values()) {
            if (!client.handshaken) continue;
            try {
                client.session.sendMessage(ping);
            } catch (IOException | RuntimeException e) {
                log.debug("Realtime ping to {} failed", client.session.getId(), e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        var client = clients.remove(session.getId());
        if (client == null) return;

        for (var group : client.groups) leave(client, group);
    }

    private void join(Client client, String group) {
        groups.computeIfAbsent(group, g -> ConcurrentHashMap.newKeySet()).add(client);
        client.groups.add(group);
    }

    private void leave(Client client, String group) {
        client.groups.remove(group);
        groups.computeIfPresent(group, (g, members) -> {
            members.remove(client);
            return members.isEmpty() ? null : members;
        });
    }

    private void send(Client client, Object message) throws IOException {
        client.session.sendMessage(new TextMessage(json.writeValueAsString(message) + RS));
    }
}
