package com.teja.pocs.idempotency;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Step 6: the payment service as a real HTTP server on http://localhost:8080 */
public class PaymentHttpServer {

    static final PaymentService service = new PaymentService();
    static final AtomicBoolean firstRequest = new AtomicBoolean(true);

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/payments", PaymentHttpServer::handlePayment);
        server.start();
        System.out.println("Payment server listening on http://localhost:8080/payments  (Ctrl+C to stop)");
    }

    // POST /payments?customerId=cust-42&amountCents=4999   with header  Idempotency-Key: <uuid>
    static void handlePayment(HttpExchange ex) throws java.io.IOException {
        try {
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            if (key == null || key.isBlank()) { reply(ex, 400, "Missing Idempotency-Key header"); return; }

            String customerId = queryParam(ex, "customerId");
            int amountCents = Integer.parseInt(queryParam(ex, "amountCents"));
            System.out.println("[server] request: key=" + key + " customer=" + customerId + " amount=" + amountCents);

            String paymentId = service.payIdempotent(key, customerId, amountCents);   // Step 5 logic, unchanged

            if (firstRequest.getAndSet(false)) {
                // Simulate a network glitch: the payment is COMMITTED, but the reply is slow,
                // so the client gives up (times out) before it arrives.
                System.out.println("[server] payment committed... simulating a slow network (5s) before replying");
                Thread.sleep(5000);
            }
            reply(ex, 200, paymentId);
        } catch (Exception e) {
            reply(ex, 500, "Error: " + e.getMessage());
        }
    }

    static String queryParam(HttpExchange ex, String name) {
        for (String pair : ex.getRequestURI().getQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals(name)) return kv[1];
        }
        throw new IllegalArgumentException("Missing query param: " + name);
    }

    static void reply(HttpExchange ex, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}