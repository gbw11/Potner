package com.potner.push.dto;

import com.potner.push.domain.PushPlatform;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RegisterFcmTokenRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsValidRequest() {
        RegisterFcmTokenRequest request =
                new RegisterFcmTokenRequest("fcm-token-value", PushPlatform.ANDROID);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsBlankToken() {
        RegisterFcmTokenRequest request = new RegisterFcmTokenRequest("   ", PushPlatform.ANDROID);

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("token"));
    }

    @Test
    void rejectsMissingPlatform() {
        RegisterFcmTokenRequest request = new RegisterFcmTokenRequest("fcm-token-value", null);

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("platform"));
    }

    @Test
    void rejectsTokenLongerThanColumn() {
        RegisterFcmTokenRequest request =
                new RegisterFcmTokenRequest("t".repeat(513), PushPlatform.ANDROID);

        assertThat(validator.validate(request))
                .anyMatch(violation -> violation.getPropertyPath().toString().equals("token"));
    }
}
