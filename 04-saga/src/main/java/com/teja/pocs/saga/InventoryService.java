package com.teja.pocs.saga;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;

public class InventoryService {

     static final String DB = "inventory_db";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8083"));   // Docker (Goal 7) can change it
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/inventory/", InventoryService::handleGetStock);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        System.out.println("InventoryService on http://localhost:" + port + "   db=" + DB + "  (Ctrl+C to stop)");
    }

    /** GET /inventory/{sku}  ->  200 {"sku":"sku-1","available":10} | 404 unknown sku | 500 error */
    static void handleGetStock(HttpExchange ex) throws IOException {
        String sku = ex.getRequestURI().getPath().substring("/inventory/".length());   // "/inventory/sku-1" -> "sku-1"
        try (Connection c = Db.connect(DB);                                            // closed automatically
             PreparedStatement ps = c.prepareStatement("SELECT available FROM stock WHERE sku = ?")) {
            ps.setString(1, sku);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Http.reply(ex, 200, "{\"sku\":\"" + sku + "\",\"available\":" + rs.getInt("available") + "}");
                } else {
                    Http.reply(ex, 404, "{\"error\":\"unknown sku: " + sku + "\"}");
                }
            }
        } catch (Exception e) {
            Http.reply(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    public static void handleReserve(HttpExchange ex) {

    }
    
    public static void reserve(String orderId, String sku, int qty) {

    }
    
}
