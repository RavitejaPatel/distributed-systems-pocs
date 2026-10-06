package com.teja.pocs.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLIntegrityConstraintViolationException;
import java.util.HexFormat;
import java.util.UUID;

/** The server side: takes a payment request and charges the customer. */
public class PaymentService {

    /** Step 7a: thrown when a key is reused for a DIFFERENT request (the server turns it into HTTP 409). */
    public static class KeyReusedException extends RuntimeException {
        public KeyReusedException(String key) {
            super("Idempotency-Key " + key + " was already used for a different request");
        }
    }

    /** Step 7a: fingerprint of a request = SHA-256 of "customerId|amountCents" (64 hex characters). */
    static String requestHash(String customerId, int amountCents) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((customerId + "|" + amountCents).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    /** Step 4: NAIVE version. Every call = a new charge, even if it's a retry. */
    public String payNaive(String customerId, int amountCents) throws Exception {
        String paymentId = "pay-" + UUID.randomUUID().toString().substring(0, 8);
        try (Connection conn = Db.connect();
             PreparedStatement ps = conn.prepareStatement(
                 "INSERT INTO payments (payment_id, customer_id, amount_cents, status) VALUES (?, ?, ?, 'CHARGED')")) {
            ps.setString(1, paymentId);
            ps.setString(2, customerId);
            ps.setInt(3, amountCents);
            ps.executeUpdate();                       // money moved
        }
        return paymentId;
    }

    /**
     * Step 5: IDEMPOTENT version. Claim the key, charge, save the result: all in ONE transaction.
     * A retry with the same key gets the saved payment back. No second charge.
     * Step 7a: the same key with a DIFFERENT request is refused (KeyReusedException -> HTTP 409).
     */
    public String payIdempotent(String idempotencyKey, String customerId, int amountCents) throws Exception {
        String hash = requestHash(customerId, amountCents);    // 7a: fingerprint of THIS request
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);                          // start a transaction
            try {
                // 1. Claim the key AND store this request's fingerprint next to it (7a).
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO idempotency_keys (idempotency_key, request_hash, status, expires_at) "
                      + "VALUES (?, ?, 'IN_PROGRESS', NOW(3) + INTERVAL 24 HOUR)")) {
                    ps.setString(1, idempotencyKey);
                    ps.setString(2, hash);
                    ps.executeUpdate();
                }

                // 2. New key -> charge the customer (same insert as payNaive)
                String paymentId = "pay-" + UUID.randomUUID().toString().substring(0, 8);
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO payments (payment_id, customer_id, amount_cents, status) VALUES (?, ?, ?, 'CHARGED')")) {
                    ps.setString(1, paymentId);
                    ps.setString(2, customerId);
                    ps.setInt(3, amountCents);
                    ps.executeUpdate();
                }

                // 3. Save the result next to the key, so retries can get it back
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE idempotency_keys SET status = 'COMPLETED', payment_id = ? WHERE idempotency_key = ?")) {
                    ps.setString(1, paymentId);
                    ps.setString(2, idempotencyKey);
                    ps.executeUpdate();
                }

                conn.commit();                                  // key + payment + result saved together
                System.out.println("  [server] new key " + idempotencyKey + " -> charged " + paymentId);
                return paymentId;

            } catch (SQLIntegrityConstraintViolationException duplicateKey) {
                conn.rollback();                                // nothing from this attempt is kept
                String[] saved = findSaved(conn, idempotencyKey);   // [0] = payment_id, [1] = request_hash

                // 7a: same key but a DIFFERENT request? Refuse instead of returning the old payment.
                if (!saved[1].equals(hash)) {
                    System.out.println("  [server] key " + idempotencyKey + " REUSED for a different request -> 409, NO charge");
                    throw new KeyReusedException(idempotencyKey);
                }

                System.out.println("  [server] key " + idempotencyKey + " seen before -> returning saved " + saved[0] + ", NO new charge");
                return saved[0];
            } catch (Exception e) {
                conn.rollback();                                // any other failure: undo everything
                throw e;
            }
        }
    }

    /** Returns { payment_id, request_hash } saved for this key. */
    private String[] findSaved(Connection conn, String idempotencyKey) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT payment_id, request_hash FROM idempotency_keys WHERE idempotency_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new String[] { rs.getString(1), rs.getString(2) };
            }
        }
    }
}
