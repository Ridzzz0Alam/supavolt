package dev.supavolt.api.integration;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A minimal browser: a cookie jar, JSON bodies, and no redirect following. */
public class ApiClient {

    static final JsonMapper JSON = JsonMapper.builder().build();

    public record Response(int status, String body, HttpResponse<String> raw) {

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String header(String name) {
            return raw.headers().firstValue(name).orElse(null);
        }
    }

    private final String baseUrl;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;

    public String email = "";
    public String orgSlug = "";

    public ApiClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.http = HttpClient.newBuilder().cookieHandler(cookies).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public String cookie(String name) {
        return cookies.getCookieStore().get(URI.create(baseUrl)).stream()
                .filter(c -> c.getName().equals(name))
                .map(HttpCookie::getValue)
                .findFirst().orElse(null);
    }

    public HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + "/api/" + path));
    }

    public Response get(String path) {
        return send(request(path).GET());
    }

    public Response post(String path, Object body) {
        return send(withBody(request(path), "POST", body));
    }

    public Response patch(String path, Object body) {
        return send(withBody(request(path), "PATCH", body));
    }

    public Response delete(String path) {
        return send(request(path).DELETE());
    }

    public static HttpRequest.Builder withBody(HttpRequest.Builder builder, String method, Object body) {
        var publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        return builder.header("Content-Type", "application/json").method(method, publisher);
    }

    public Response send(HttpRequest.Builder builder) {
        try {
            var response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body(), response);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
