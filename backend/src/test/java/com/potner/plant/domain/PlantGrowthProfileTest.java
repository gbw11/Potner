package com.potner.plant.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlantGrowthProfileTest {

    @Test
    void copiesRequirementValuesIncludingNullsAndResetsCustomization() {
        Plant plant = mock(Plant.class);
        when(plant.getId()).thenReturn("plant-id");
        SpeciesGrowthRequirement requirement = requirement("requirement-1", "21.00", null);
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 22, 1, 0);

        PlantGrowthProfile profile = PlantGrowthProfile.create(plant, requirement, createdAt);

        assertThat(profile.getPlantId()).isEqualTo("plant-id");
        assertThat(profile.getSourceRequirement()).isSameAs(requirement);
        assertThat(profile.getSourceRevision()).isEqualTo(2);
        assertThat(profile.getTemperatureMinC()).isEqualByComparingTo("21.00");
        assertThat(profile.getRecommendedWateringMl()).isNull();
        assertThat(profile.isCustomized()).isFalse();

        GrowthProfileValues customized = new GrowthProfileValues(
                new BigDecimal("40"), new BigDecimal("55"),
                new BigDecimal("22"), new BigDecimal("29"),
                new BigDecimal("60"), new BigDecimal("80"),
                null, null, new BigDecimal("10000"),
                new BigDecimal("15"),
                null, null, new BigDecimal("150000"),
                new BigDecimal("1"), new BigDecimal("40"), new BigDecimal("120")
        );
        LocalDateTime customizedAt = createdAt.plusHours(1);
        profile.customize(customized, customizedAt);

        assertThat(profile.getTemperatureMinC()).isEqualByComparingTo("22");
        assertThat(profile.getRecommendedWateringMl()).isEqualByComparingTo("120");
        assertThat(profile.isCustomized()).isTrue();
        assertThat(profile.getCustomizedAt()).isEqualTo(customizedAt);

        SpeciesGrowthRequirement newRequirement = requirement("requirement-2", "16.00", null);
        LocalDateTime resetAt = customizedAt.plusHours(1);
        profile.applyRequirement(newRequirement, resetAt);

        assertThat(profile.getSourceRequirement()).isSameAs(newRequirement);
        assertThat(profile.getTemperatureMinC()).isEqualByComparingTo("16.00");
        assertThat(profile.getRecommendedWateringMl()).isNull();
        assertThat(profile.isCustomized()).isFalse();
        assertThat(profile.getCustomizedAt()).isNull();
        assertThat(profile.getUpdatedAt()).isEqualTo(resetAt);
    }

    private SpeciesGrowthRequirement requirement(String id, String temperatureMin, BigDecimal wateringMl) {
        SpeciesGrowthRequirement requirement = mock(SpeciesGrowthRequirement.class);
        when(requirement.getId()).thenReturn(id);
        when(requirement.getRevision()).thenReturn(2);
        when(requirement.getSoilMoistureMinPct()).thenReturn(new BigDecimal("40"));
        when(requirement.getSoilMoistureMaxPct()).thenReturn(new BigDecimal("55"));
        when(requirement.getTemperatureMinC()).thenReturn(new BigDecimal(temperatureMin));
        when(requirement.getTemperatureMaxC()).thenReturn(new BigDecimal("29"));
        when(requirement.getHumidityMinPct()).thenReturn(new BigDecimal("60"));
        when(requirement.getHumidityMaxPct()).thenReturn(new BigDecimal("80"));
        when(requirement.getIlluminanceMinLux()).thenReturn(null);
        when(requirement.getIlluminanceMaxLux()).thenReturn(null);
        when(requirement.getIlluminanceTargetLux()).thenReturn(new BigDecimal("10000"));
        when(requirement.getPhotoperiodHours()).thenReturn(new BigDecimal("15"));
        when(requirement.getDailyLightMinLuxHour()).thenReturn(null);
        when(requirement.getDailyLightMaxLuxHour()).thenReturn(null);
        when(requirement.getDailyLightTargetLuxHour()).thenReturn(new BigDecimal("150000"));
        when(requirement.getWateringCycleDays()).thenReturn(new BigDecimal("1"));
        when(requirement.getWateringTriggerPct()).thenReturn(new BigDecimal("40"));
        when(requirement.getRecommendedWateringMl()).thenReturn(wateringMl);
        return requirement;
    }
}
