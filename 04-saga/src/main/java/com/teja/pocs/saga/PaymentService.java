package com.teja.pocs.saga;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.concurrent.Executors;

/**
 * Payment microservice. Owns ONLY payments_db.
 *   POST /payments/charge?orderId=o-1&amountCents=2499  -> 200 CHARGED | 402 DECLINED (amount > $1000)
 *   POST /payments/refund?orderId=o-1                   -> 200 REFUNDED | 404 nothing to refund   (the saga COMPENSATION)
 *   GET  /payments/o-1                                  -> the payment row
 * Both charge and refund are IDEMPOTENT per orderId, so the orchestrator can safely retry them.
 */
public class PaymentService {

    static final String DB = "payments_db";
    static final int DECLINE_ABOVE_CENTS = 100_000;   // simulated card limit: more than $1000 is declined

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8082"));
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/payments/charge", PaymentService::handleCharge);
        server.createContext("/payments/refund", PaymentService::handleRefund);
        server.createContext("/payments/", PaymentService::handleGetPayment);
        server.setExecutor(Executors.newFixedThreadPool(10));
        server.start();
        System.out.println("PaymentService on http://localhost:" + port + "   db=" + DB + "  (Ctrl+C to stop)");
    }

    // ---------- Step 1: read ----------

    /** GET /payments/{orderId} -> 200 {"orderId":"o-1","amountCents":2499,"status":"CHARGED"} | 404 | 500 */
    static void handleGetPayment(HttpExchange ex) throws IOException {
        String orderId = ex.getRequestURI().getPath().substring("/payments/".length());
        try (Connection c = Db.connect(DB);
             PreparedStatement ps = c.prepareStatement("SELECT amount_cents, status FROM payments WHERE order_id = ?")) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    Http.reply(ex, 200, "{\"orderId\":\"" + orderId + "\",\"amountCents\":" + rs.getInt("amount_cents")
                            + ",\"status\":\"" + rs.getString("status") + "\"}");
                } else {
                    Http.reply(ex, 404, "{\"error\":\"no payment for order " + orderId + "\"}");
                }
            }
        } catch (Exception e) {
            Http.reply(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    // ---------- Step 2: charge ----------

    /** POST /payments/charge?orderId=..&amountCents=..  -> 200 CHARGED | 402 DECLINED | 400 | 500 */
    static void handleCharge(HttpExchange ex) throws IOException {
        try {
            String orderId  = Http.query(ex, "orderId");
            int amountCents = Integer.parseInt(Http.query(ex, "amountCents"));

            String status = charge(orderId, amountCents);                    // "CHARGED" or "DECLINED"

            int code = status.equals("CHARGED") ? 200 : 402;                 // 402 = business "no" (like Inventory's 409)
            Http.reply(ex, code, "{\"orderId\":\"" + orderId + "\",\"status\":\"" + status + "\"}");
        } catch (IllegalArgumentException e) {
            Http.reply(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            Http.reply(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Decide (card limit), then record the result. order_id is the PRIMARY KEY:
     * a retry for the same order hits a duplicate key -> we return the SAVED result, no second charge.
     */
    static String charge(String orderId, int amountCents) throws Exception {
        String decision = amountCents > DECLINE_ABOVE_CENTS ? "DECLINED" : "CHARGED";
        try (Connection c = Db.connect(DB)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO payments (order_id, amount_cents, status) VALUES (?, ?, ?)")) {
                ps.setString(1, orderId);
                ps.setInt(2, amountCents);
                ps.setString(3, decision);
                ps.executeUpdate();                                         // ONE statement = atomic, no transaction needed
                System.out.println("  [payment] " + orderId + ": " + decision + " " + amountCents + " cents");
                return decision;
            } catch (SQLIntegrityConstraintViolationException duplicate) {   // retry for the same order
                String saved = savedStatus(c, orderId);
                System.out.println("  [payment] " + orderId + ": already processed (retry) -> " + saved + ", no new charge");
                return saved.equals("DECLINED") ? "DECLINED" : "CHARGED";  // REFUNDED still means "it was charged"
            }
        }
    }

    // ---------- Step 3: refund (the saga COMPENSATION) ----------

    /** POST /payments/refund?orderId=..  -> 200 REFUNDED | 404 nothing to refund | 400 | 500 */
    static void handleRefund(HttpExchange ex) throws IOException {
        try {
            String orderId = Http.query(ex, "orderId");
            if (refund(orderId)) Http.reply(ex, 200, "{\"orderId\":\"" + orderId + "\",\"status\":\"REFUNDED\"}");
            else                 Http.reply(ex, 404, "{\"orderId\":\"" + orderId + "\",\"error\":\"nothing to refund\"}");
        } catch (IllegalArgumentException e) {
            Http.reply(ex, 400, "{\"error\":\"" + e.getMessage() + "\"}");
        } catch (Exception e) {
            Http.reply(ex, 500, "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    /**
     * Only a CHARGED payment can be refunded, and only once.
     * Returns true = refunded now OR already refunded earlier (idempotent), false = nothing to refund.
     */
    static boolean refund(String orderId) throws Exception {
        try (Connection c = Db.connect(DB)) {
            int updated;
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE payments SET status = 'REFUNDED' WHERE order_id = ? AND status = 'CHARGED'")) {
                ps.setString(1, orderId);
                updated = ps.executeUpdate();                               // 1 = refunded now, 0 = see why below
            }
            if (updated == 1) {
                System.out.println("  [payment] " + orderId + ": REFUNDED");
                return true;
            }
            String saved = savedStatus(c, orderId);                         // null / DECLINED / REFUNDED
            if ("REFUNDED".equals(saved)) {
                System.out.println("  [payment] " + orderId + ": already refunded (retry) -> no change");
                return true;
            }
            System.out.println("  [payment] " + orderId + ": nothing to refund (status=" + saved + ")");
            return false;
        }
    }

    /** Helper: the saved status for this order, or null if there is no payment. */
    static String savedStatus(Connection c, String orderId) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT status FROM payments WHERE order_id = ?")) {
            ps.setString(1, orderId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("status") : null;
            }
        }
    }
}