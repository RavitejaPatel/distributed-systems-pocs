package com.teja.pocs.idempotency;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

/** Step 6: a real client that times out, waits, and retries with the SAME key. */
public class PaymentHttpClient {

    public static void main(String[] args) throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // Created ONCE per purchase, before the first attempt. Every retry reuses it.
        String idempotencyKey = UUID.randomUUID().toString();
        System.out.println("[client] new purchase, Idempotency-Key = " + idempotencyKey);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:8080/payments?customerId=cust-42&amountCents=4999"))
            .header("Idempotency-Key", idempotencyKey)
            .timeout(Duration.ofSeconds(2))                 // give up after 2s
            .POST(HttpRequest.BodyPublishers.noBody())
            .build();

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                System.out.println("[client] attempt " + attempt + " ...");
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                System.out.println("[client] attempt " + attempt + " -> HTTP " + response.statusCode() + ", payment = " + response.body());
                return;
            } catch (HttpTimeoutException e) {
                System.out.println("[client] attempt " + attempt + " TIMED OUT. Was I charged? Unknown. Waiting 6s, then retrying with the SAME key...");
                Thread.sleep(6000);
            }
        }
        System.out.println("[client] gave up after 3 attempts");
    }
}