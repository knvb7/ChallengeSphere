package com.ironside.challengesphere.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IamAuthDataSourceTest {

    @Test
    void generatesFreshTokenForEachConnection() throws SQLException {
        List<String> passwordsUsed = new ArrayList<>();
        IamAuthDataSource dataSource = new IamAuthDataSource(
                "challengesphere",
                username -> "token-for-" + username + "-" + (passwordsUsed.size() + 1),
                (username, password) -> {
                    passwordsUsed.add(password);
                    return connectionProxy();
                }
        );

        dataSource.getConnection();
        dataSource.getConnection();

        assertThat(passwordsUsed).containsExactly(
                "token-for-challengesphere-1",
                "token-for-challengesphere-2"
        );
    }

    @Test
    void ignoresCallerPasswordAndGeneratesTokenForCallerUsername() throws SQLException {
        List<String> usernamesUsed = new ArrayList<>();
        List<String> passwordsUsed = new ArrayList<>();
        IamAuthDataSource dataSource = new IamAuthDataSource(
                "challengesphere",
                username -> "token-for-" + username,
                (username, password) -> {
                    usernamesUsed.add(username);
                    passwordsUsed.add(password);
                    return connectionProxy();
                }
        );

        dataSource.getConnection("override_user", "plain-password");

        assertThat(usernamesUsed).containsExactly("override_user");
        assertThat(passwordsUsed).containsExactly("token-for-override_user");
    }

    private static Connection connectionProxy() {
        return (Connection) Proxy.newProxyInstance(
                IamAuthDataSourceTest.class.getClassLoader(),
                new Class<?>[] { Connection.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "isClosed" -> false;
                    case "isValid" -> true;
                    case "toString" -> "connectionProxy";
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        return null;
    }
}
