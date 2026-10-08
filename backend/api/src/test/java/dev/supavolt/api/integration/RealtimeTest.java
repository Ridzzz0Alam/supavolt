package dev.supavolt.api.integration;

import static dev.supavolt.api.integration.ApiTest.createProject;
import static dev.supavolt.api.integration.ApiTest.createTodos;
import static dev.supavolt.api.integration.ApiTest.withKey;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The realtime endpoint speaks the SignalR JSON protocol, which the dashboard's
 * {@code @microsoft/signalr} client depends on. These drive it at the protocol level.
 */
class RealtimeTest extends IntegrationTest {

    private static final char RS = '\u001e';

    /** Collects SignalR records from a raw WebSocket. */
    private static final class Socket implements WebSocket.Listener {
        final LinkedBlockingQueue<String> records = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder buffer = new StringBuilder();
        WebSocket ws;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buffer.append(data);
            int end;
            while ((end = buffer.indexOf(String.valueOf(RS))) >= 0) {
                records.add(buffer.substring(0, end));
                buffer.delete(0, end + 1);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(-1);
        }

        void send(String record) {
            ws.sendText(record + RS, true).join();
        }

        /** The next record that is not a ping. */
        JsonNode next() throws InterruptedException {
            while (true) {
                var record = records.poll(10, TimeUnit.SECONDS);
                assertThat(record).as("a SignalR message within 10 seconds").isNotNull();
                var node = ApiClient.JSON.readTree(record);
                if (node.path("type").asInt() != 6) return node;
            }
        }
    }

    private Socket connect(String key) {
        var socket = new Socket();
        socket.ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(URI.create("ws://localhost:" + port + "/realtime?id=test&access_token=" + key), socket)
                .join();
        return socket;
    }

    @Test
    void negotiate_requires_a_key_and_offers_websockets() {
        var client = newClient();
        var url = URI.create(baseUrl() + "/realtime/negotiate?negotiateVersion=1");

        var anonymous = client.send(java.net.http.HttpRequest.newBuilder(url).POST(java.net.http.HttpRequest.BodyPublishers.noBody()));
        assertThat(anonymous.status()).isEqualTo(401);

        var user = newUser();
        var p = createProject(user, "Demo");
        var res = client.send(java.net.http.HttpRequest.newBuilder(url)
                .header("Authorization", "Bearer " + p.anonKey())
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody()));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.json().get("negotiateVersion").asInt()).isEqualTo(1);
        assertThat(res.json().get("connectionToken").asString()).isNotBlank();
        assertThat(res.json().get("availableTransports").get(0).get("transport").asString()).isEqualTo("WebSockets");
    }

    @Test
    void subscribed_clients_receive_insert_update_and_delete_events() throws Exception {
        var user = newUser();
        var p = createProject(user, "Demo");
        createTodos(user, p);
        assertThat(user.post("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/realtime/todos/enable", null).status())
                .isEqualTo(200);
        assertThat(user.get("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/realtime").body()).contains("todos");

        var socket = connect(p.anonKey());
        socket.send("{\"protocol\":\"json\",\"version\":1}");
        assertThat(socket.next().size()).isZero(); // the handshake answer is {}

        socket.send("{\"type\":1,\"invocationId\":\"0\",\"target\":\"Subscribe\",\"arguments\":[\"todos\"]}");
        var completion = socket.next();
        assertThat(completion.get("type").asInt()).isEqualTo(3);
        assertThat(completion.get("invocationId").asString()).isEqualTo("0");
        assertThat(completion.has("error")).isFalse();

        var http = newClient();
        var rest = "projects/" + p.slug() + "/rest/todos";
        var id = withKey(http, "POST", rest, p.serviceKey(), Map.of("title", "live")).json().get("id").asLong();
        withKey(http, "PATCH", rest + "/" + id, p.serviceKey(), Map.of("title", "edited"));
        withKey(http, "DELETE", rest + "/" + id, p.serviceKey(), null);

        var insert = socket.next();
        assertThat(insert.get("type").asInt()).isEqualTo(1);
        assertThat(insert.get("target").asString()).isEqualTo("event");
        var event = insert.get("arguments").get(0);
        assertThat(event.get("type").asString()).isEqualTo("insert");
        assertThat(event.get("table").asString()).isEqualTo("todos");
        assertThat(event.get("projectId").asString()).isEqualTo(p.id());
        assertThat(event.get("record").get("title").asString()).isEqualTo("live");
        assertThat(event.get("timestamp").asString()).endsWith("Z");

        var update = socket.next().get("arguments").get(0);
        assertThat(update.get("type").asString()).isEqualTo("update");
        assertThat(update.get("oldRecord").get("title").asString()).isEqualTo("live");
        assertThat(update.get("record").get("title").asString()).isEqualTo("edited");

        var delete = socket.next().get("arguments").get(0);
        assertThat(delete.get("type").asString()).isEqualTo("delete");
        assertThat(delete.has("record")).isFalse();

        socket.ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    @Test
    void unknown_hub_methods_complete_with_an_error() throws Exception {
        var user = newUser();
        var p = createProject(user, "Demo");

        var socket = connect(p.anonKey());
        socket.send("{\"protocol\":\"json\",\"version\":1}");
        socket.next();
        socket.send("{\"type\":1,\"invocationId\":\"7\",\"target\":\"DropEverything\",\"arguments\":[]}");

        var completion = socket.next();
        assertThat(completion.get("invocationId").asString()).isEqualTo("7");
        assertThat(completion.get("error").asString()).contains("DropEverything");
    }

    // Same rule as the data API: a key issued before the last rotation is dead.
    @Test
    void a_rotated_key_is_disconnected() throws Exception {
        var user = newUser();
        var p = createProject(user, "Demo");
        assertThat(user.post("orgs/" + user.orgSlug + "/projects/" + p.slug() + "/keys/rotate", null).status()).isEqualTo(200);

        var socket = connect(p.anonKey());
        assertThat(socket.closed.get(10, TimeUnit.SECONDS)).isEqualTo(1008);
    }
}
