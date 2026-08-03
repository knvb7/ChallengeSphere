package com.ironside.challengesphere.api.request;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsernameRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void updateRequestAcceptsOnlyFiveToTwentyCharacterUsernames() {
        UpdateUserRequest request = new UpdateUserRequest();

        request.setUsername("four");
        assertUsernameLengthViolation(validator.validate(request));

        request.setUsername("valid");
        assertTrue(validator.validate(request).isEmpty());

        request.setUsername("12345678901234567890");
        assertTrue(validator.validate(request).isEmpty());

        request.setUsername("123456789012345678901");
        assertUsernameLengthViolation(validator.validate(request));
    }

    @Test
    void registrationRequestAcceptsOnlyFiveToTwentyCharacterUsernames() {
        UserRegistrationRequest request = new UserRegistrationRequest();
        request.setEmailId("person@example.com");
        request.setPassword("StrongPassword123!");

        request.setUsername("four");
        assertUsernameLengthViolation(validator.validate(request));

        request.setUsername("valid_user");
        assertTrue(validator.validate(request).isEmpty());

        request.setUsername("123456789012345678901");
        assertUsernameLengthViolation(validator.validate(request));
    }

    private static void assertUsernameLengthViolation(Set<? extends ConstraintViolation<?>> violations) {
        assertEquals(1, violations.size());
        assertEquals(
                "Username must be between 5 and 20 characters",
                violations.iterator().next().getMessage()
        );
    }
}
