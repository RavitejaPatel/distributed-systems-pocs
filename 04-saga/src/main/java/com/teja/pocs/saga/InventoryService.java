package com.teja.pocs.saga;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.Executors;
import java.sql.SQLIntegrityConstraintViolationException;

public class InventoryService {

    static final String DB = "inventory_db";

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8083"));   // Docker (Goal 7) can change it
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/inventory/", InventoryService::handleGetStock);
        server.createContext("/inventory/reserve", InventoryService::handleReserve);
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
    
        /**
     * POST /inventory/reserve?orderId=o-1&sku=sku-1&qty=2
     *   200 {"orderId":"o-1","status":"RESERVED"}
     *   409 {"orderId":"o-2","error":"OUT_OF_STOCK"}
     *   400 missing/bad param, 500 DB error
     */
    static void handleReserve(HttpExchange ex) throws IOException {
        try {
            String orderId = Http.query(ex, "orderId");                     // throws if missing -> 400
            String sku     = Http.query(ex, "sku");
            int qty        = Integer.parseInt(Http.query(ex, "qty"));       // "abc" -> NumberFormatException -> 400

            boolean reserved = reserve(orderId, sku, qty);                  // business logic (SQL) lives in reserve()

            if (reserved) Http.reply(ex, 200, "{\"orderId\":\"" + orderId + "\",\"status\":\"RESERVED\"}");
            else          Http.reply(ex, 409, "{\"orderId\":\"" + orderId + "\",\"error\":\"OUT_OF_STOCK\"}");

        } catch (IllegalArgumentException e) {                              // caller's mistake (incl. NumberFormatException)
            Http.reply(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {                                             // our problem (DB down, SQL error)
            Http.reply(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }
    
    /**
     * ONE local transaction in inventory_db:
     *   1. take stock ONLY if enough is available (atomic conditional UPDATE -> no overselling)
     *   2. record the reservation; order_id is the PRIMARY KEY -> a retry for the same order is detected
     * Returns true = reserved (now, or already earlier for this order), false = out of stock.
     * DB errors are thrown (-> 500), never turned into "false".
     */
    static boolean reserve(String orderId, String sku, int qty) throws Exception {
        try (Connection c = Db.connect(DB)) {
            c.setAutoCommit(false);                                   // start a transaction
            try {
                // 1. take stock (only if enough)
                int updated;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE stock SET available = available - ? WHERE sku = ? AND available >= ?")) {
                    ps.setInt(1, qty);
                    ps.setString(2, sku);
                    ps.setInt(3, qty);
                    updated = ps.executeUpdate();                     // 1 = taken, 0 = not enough / unknown sku
                }
                if (updated == 0) {
                    c.rollback();
                    System.out.println("  [inventory] " + orderId + ": OUT OF STOCK for " + qty + " x " + sku);
                    return false;
                }

                // 2. record the reservation (fails with duplicate key if this order was already reserved)
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO reservations (order_id, sku, qty, status) VALUES (?, ?, ?, 'RESERVED')")) {
                    ps.setString(1, orderId);
                    ps.setString(2, sku);
                    ps.setInt(3, qty);
                    ps.executeUpdate();
                }

                c.commit();                                           // stock change + reservation saved TOGETHER
                System.out.println("  [inventory] " + orderId + ": RESERVED " + qty + " x " + sku);
                return true;

            } catch (SQLIntegrityConstraintViolationException duplicate) {
                c.rollback();                                         // RETRY: undo step 1 of THIS attempt
                System.out.println("  [inventory] " + orderId + ": already reserved (retry) -> no change");
                return true;                                          // same answer as the first time
            } catch (Exception e) {
                c.rollback();                                         // any other error: undo everything
                throw e;                                              // -> handleReserve -> 500
            }
        }
    }
    
}
