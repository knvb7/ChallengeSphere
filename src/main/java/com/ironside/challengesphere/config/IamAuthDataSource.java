package com.ironside.challengesphere.config;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.util.StringUtils;

final class IamAuthDataSource extends AbstractDataSource {

    private final String defaultUsername;
    private final RdsIamAuthTokenProvider tokenProvider;
    private final PostgresConnectionFactory connectionFactory;

    IamAuthDataSource(
            String defaultUsername,
            RdsIamAuthTokenProvider tokenProvider,
            PostgresConnectionFactory connectionFactory
    ) {
        this.defaultUsername = Objects.requireNonNull(defaultUsername, "defaultUsername");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.connectionFactory = Objects.requireNonNull(connectionFactory, "connectionFactory");
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connect(defaultUsername);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return connect(StringUtils.hasText(username) ? username : defaultUsername);
    }

    private Connection connect(String username) throws SQLException {
        return connectionFactory.connect(username, tokenProvider.generateToken(username));
    }
}
