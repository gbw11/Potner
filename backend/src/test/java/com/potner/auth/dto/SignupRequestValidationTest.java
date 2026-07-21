package com.potner.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SignupRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsValidSignupRequest() {
        SignupRequest request = new SignupRequest("member@example.com", "password1", "potner");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsInvalidEmail() {
        SignupRequest request = new SignupRequest("not-an-email", "password1", "potner");

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("email"));
    }

    @Test
    void rejectsWeakPassword() {
        SignupRequest request = new SignupRequest("member@example.com", "onlyletters", "potner");

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("password"));
    }
}
