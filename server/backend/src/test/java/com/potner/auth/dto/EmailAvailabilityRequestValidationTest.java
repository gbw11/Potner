package com.potner.auth.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailAvailabilityRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsValidEmail() {
        EmailAvailabilityRequest request = new EmailAvailabilityRequest("member@example.com");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsInvalidEmail() {
        EmailAvailabilityRequest request = new EmailAvailabilityRequest("not-an-email");

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("email"));
    }

    @Test
    void rejectsBlankEmail() {
        EmailAvailabilityRequest request = new EmailAvailabilityRequest(" ");

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("email"));
    }
}
