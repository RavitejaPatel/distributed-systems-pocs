package com.teja.pocs.idempotency;

import java.util.UUID;

/** Step 5: same lost response + retry as Step 4, but with an idempotency key. */
public class IdempotentRetryDemo {

    public static void main(String[] args) throws Exception {
        PaymentService server = new PaymentService();
        String customer = "cust-44";

        // The CLIENT creates the key ONCE per purchase, before the first attempt
        String key = UUID.randomUUID().toString();
        System.out.println("Client: new purchase, key = " + key);

        String first = server.payIdempotent(key, customer, 4999);
        System.out.println("Attempt 1: got " + first + " ... but the response was LOST (timeout)");

        String retry = server.payIdempotent(key, customer, 4999);   // SAME key
        System.out.println("Attempt 2: retry with the same key -> got " + retry);

        System.out.println("Same payment both times? " + first.equals(retry));
        System.out.println("Payments in MySQL for " + customer + ": " + NaiveRetryDemo.countPayments(customer) + "  <- charged ONCE");
    }
}