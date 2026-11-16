package com.panel.platform.database;

public final class DatabaseNameGuard {

    public static final String ALLOWED_DATABASE = "panel";

    private DatabaseNameGuard() {
    }

    public static String requireAllowed(String databaseName) {
        if (!ALLOWED_DATABASE.equals(databaseName)) {
            throw new IllegalArgumentException("Only the exact panel database may be reset");
        }
        return databaseName;
    }
}
