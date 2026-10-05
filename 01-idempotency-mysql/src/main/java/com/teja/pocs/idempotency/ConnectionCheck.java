package com.teja.pocs.idempotency;

import java.sql.Connection;
import java.sql.ResultSet;

/** Step 2: prove Java can talk to MySQL. */
public class ConnectionCheck {

    public static void main(String[] args) throws Exception {
        try (Connection conn = Db.connect();
            ResultSet rs = conn.createStatement().executeQuery("SELECT VERSION(), DATABASE()")) {
            rs.next();
            System.out.println("Connected OK");
            System.out.println("  MySQL version : " + rs.getString(1));
            System.out.println("  Database      : " + rs.getString(2));
        }
    }
}