package com.teja.pocs.idempotency;

/** Step 7c: runs the expired-key cleanup once. In production this runs on a schedule (e.g. every hour). */
public class KeyCleanupJob {

    public static void main(String[] args) throws Exception {
        int deleted = new PaymentService().deleteExpiredKeys();
        System.out.println("Cleanup done: deleted " + deleted + " expired idempotency key(s)");
    }
}