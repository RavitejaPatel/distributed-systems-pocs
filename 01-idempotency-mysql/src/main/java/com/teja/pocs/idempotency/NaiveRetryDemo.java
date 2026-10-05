package com.teja.pocs.idempotency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/** Step 4: a lost response + a retry = the customer is charged twice. */
public class NaiveRetryDemo {

    public static void main(String[] args) throws Exception {
        PaymentService server = new PaymentService();
        String customer = "cust-42";

        // Attempt 1: the server charges, but the response never reaches the client
        String paymentId = server.payNaive(customer, 4999);
        System.out.println("Attempt 1: server charged " + paymentId + " ... but the response was LOST (timeout)");

        // The client doesn't know it worked, so it retries
        String retryId = server.payNaive(customer, 4999);
        System.out.println("Attempt 2: retry -> server charged " + retryId);

        System.out.println("Payments in MySQL for " + customer + ": " + countPayments(customer) + "  <- customer charged TWICE");
    }

    static int countPayments(String customerId) throws Exception {
        try (Connection conn = Db.connect();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM payments WHERE customer_id = ?")) {
            ps.setString(1, customerId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }
}