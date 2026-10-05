package com.teja.pocs.idempotency;

import java.sql.Connection;
import java.sql.PreparedStatement;
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
}