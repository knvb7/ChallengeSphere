package com.ironside.challengesphere.config;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
interface PostgresConnectionFactory {
    Connection connect(String username, String password) throws SQLException;
}
