package com.teja.pocs.saga;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Tiny HTTP helpers shared by the 3 services: read a query param, send a JSON reply. */
public class Http {
    /** "/inventory/reserve?orderId=o-1&sku=sku-1&qty=2", name "sku" -> "sku-1". Missing -> exception (=> 400). */
    static String query(HttpExchange ex, String name) {
        String q = ex.getRequestURI().getQuery();                 // "orderId=o-1&sku=sku-1&qty=2" or null
        if (q != null) {
            for (String pair : q.split("&")) {                    // ["orderId=o-1", "sku=sku-1", "qty=2"]
                String[] kv = pair.split("=", 2);                 // ["sku", "sku-1"]
                if (kv[0].equals(name) && kv.length == 2) return kv[1];
            }
        }
        throw new IllegalArgumentException("Missing query param: " + name);
    }

    /** Writes status + JSON body back to the caller. */
    static void reply(HttpExchange ex, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}