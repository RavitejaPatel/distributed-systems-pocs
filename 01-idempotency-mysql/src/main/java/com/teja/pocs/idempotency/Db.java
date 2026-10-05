package com.teja.pocs.idempotency;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** One place to open MySQL connections. Settings come from environment variables. */
public class Db {

    static final String URL  = env("DB_URL", "jdbc:mysql://localhost:3306/commerce_training");
    static final String USER = env("DB_USER", "poc_user");
    static final String PASS = env("DB_PASSWORD", "");

    public static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASS);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}