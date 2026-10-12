package com.teja.pocs.breaker;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.time.Duration;

public class CatalogApi {

    private static final String STORE_URL = "http://localhost:8091";
    private static final int MAX_RETRIES = 3;                       // used in Step 2
    private static final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))                      // give up connecting after 2s
        .build();

    /** Arrow 1 (in): a user calls GET /catalog/{id}. Arrow 4 (out): we answer the user. */
    private static void handle(HttpExchange ex) throws IOException {
        String id = ex.getRequestURI().getPath().substring("/catalog/".length());   // "/catalog/sku-1" -> "sku-1"
        try {
            HttpResponse<String> res = getFromStore(id);             // arrows 2 + 3: ask the store, get its answer
            reply(ex, res.statusCode(), res.body());                 // pass the store's answer straight to the user
        } catch (Exception e) {
            reply(ex, 503, "{\"error\":\"store unavailable\"}");     // store down / not reachable
        }
    }

    /** Arrow 2 (out): we call the store GET /products/{id}. Arrow 3 (back): the store's 200 / 429 / 404. */
    private static HttpResponse<String> getFromStore(String id) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(STORE_URL + "/products/" + id))
            .timeout(Duration.ofSeconds(2))                          // BEFORE build(): max 2s for the whole call
            .GET()
            .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /** Helper: writes status + JSON body back to whoever called us. */
    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8092), 0);
        server.createContext("/catalog/", CatalogApi::handle);
        server.start();
        System.out.println("Catalog API listening on http://localhost:8092/catalog/{id}  (Ctrl+C to stop)");
    }
}