package com.teja.pocs.idempotency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLIntegrityConstraintViolationException;

/** Step 3: watch MySQL reject the same idempotency key twice. */
public class UniqueKeyDemo {

    static final String INSERT_KEY =
        "INSERT INTO idempotency_keys (idempotency_key, request_hash, status, expires_at) "
      + "VALUES (?, 'hash-not-used-yet', 'IN_PROGRESS', NOW(3) + INTERVAL 24 HOUR)";

    public static void main(String[] args) throws Exception {
        try (Connection conn = Db.connect()) {
            insertKey(conn, "key-abc-123");   // 1st time: accepted
            insertKey(conn, "key-abc-123");   // 2nd time: rejected by the PRIMARY KEY
            insertKey(conn, "key-xyz-789");   // different key: accepted
        }
    }

    static void insertKey(Connection conn, String key) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement(INSERT_KEY)) {
            ps.setString(1, key);
            ps.executeUpdate();
            System.out.println("INSERT " + key + " -> accepted");
        } catch (SQLIntegrityConstraintViolationException e) {
            System.out.println("INSERT " + key + " -> REJECTED (MySQL error " + e.getErrorCode() + ": duplicate key)");
        }
    }
}