package com.potner.plant.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potner.plant.domain.GrowthProfileValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateGrowthProfileRequestTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void distinguishesMissingFieldsFromExplicitNull() throws Exception {
        UpdateGrowthProfileRequest request = objectMapper.readValue(
                """
                        {"temperatureMinC":22.5,"recommendedWateringMl":null}
                        """,
                UpdateGrowthProfileRequest.class);

        GrowthProfileValues merged = request.merge(values());

        assertThat(merged.temperatureMinC()).isEqualByComparingTo("22.5");
        assertThat(merged.temperatureMaxC()).isEqualByComparingTo("29");
        assertThat(merged.recommendedWateringMl()).isNull();
        assertThat(request.isEmpty()).isFalse();
    }

    private GrowthProfileValues values() {
        return new GrowthProfileValues(
                decimal("40"), decimal("55"), decimal("21"), decimal("29"),
                decimal("60"), decimal("80"), null, null, decimal("10000"),
                decimal("15"), null, null, decimal("150000"),
                decimal("1"), decimal("40"), decimal("100")
        );
    }

    private BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }
}
