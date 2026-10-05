package com.teja.pocs.idempotency;

   import java.sql.Connection;
   import java.sql.PreparedStatement;
   import java.sql.ResultSet;
   import java.sql.SQLIntegrityConstraintViolationException;
   import java.util.UUID;

/** The server side: takes a payment request and charges the customer. */
public class PaymentService {

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
     */
    public String payIdempotent(String idempotencyKey, String customerId, int amountCents) throws Exception {
        try (Connection conn = Db.connect()) {
            conn.setAutoCommit(false);                          // start a transaction
            try {
                // 1. Claim the key. If it already exists, MySQL throws (Step 3's lesson).
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO idempotency_keys (idempotency_key, request_hash, status, expires_at) "
                      + "VALUES (?, '-', 'IN_PROGRESS', NOW(3) + INTERVAL 24 HOUR)")) {
                    ps.setString(1, idempotencyKey);
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
                String saved = findSavedPayment(conn, idempotencyKey);
                System.out.println("  [server] key " + idempotencyKey + " seen before -> returning saved " + saved + ", NO new charge");
                return saved;
            } catch (Exception e) {
                conn.rollback();                                // any other failure: undo everything
                throw e;
            }
        }
    }

    private String findSavedPayment(Connection conn, String idempotencyKey) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT payment_id FROM idempotency_keys WHERE idempotency_key = ?")) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}