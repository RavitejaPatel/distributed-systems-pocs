package com.teja.pocs.saga;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Opens a connection to ONE service's own database. Host/user/password come from env vars. */
public class Db {

    static final String HOST = env("DB_HOST", "localhost");          // Goal 7 (Docker): "mysql"
    static final String USER = env("DB_USER", "poc_user");
    static final String PASS = env("DB_PASSWORD", "");

    static {
        try { Class.forName("com.mysql.cj.jdbc.Driver"); }           // same fix as POC 01 (HttpServer threads)
        catch (ClassNotFoundException e) { throw new RuntimeException(e); }
    }

    /** e.g. Db.connect("orders_db") -> only the Order Service ever passes "orders_db". */
    public static Connection connect(String database) throws SQLException {
        return DriverManager.getConnection("jdbc:mysql://" + HOST + ":3306/" + database, USER, PASS);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}