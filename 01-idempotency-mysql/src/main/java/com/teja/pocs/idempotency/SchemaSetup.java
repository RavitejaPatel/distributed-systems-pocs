package com.teja.pocs.idempotency;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/** Step 3: (re)create the tables from src/main/resources/schema.sql. */
public class SchemaSetup {

    public static void main(String[] args) throws Exception {
        String sql;
        try (InputStream in = SchemaSetup.class.getResourceAsStream("/schema.sql")) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection conn = Db.connect(); Statement st = conn.createStatement()) {
            for (String statement : sql.split(";")) {
                if (!statement.isBlank()) st.execute(statement);
            }
        }
        System.out.println("Schema ready: payments, idempotency_keys");
    }
}