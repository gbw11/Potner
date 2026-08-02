package com.potner.arrival.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArrivalPropertiesTest {

    @Test
    void totalTimeoutMustBeLongerThanGreetingWait() {
        assertThatThrownBy(() -> new ArrivalProperties(120, 120, 120, 30, 600, 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("totalTimeoutSeconds");
    }

    @Test
    void commandTimeoutCannotExceedTotalTimeout() {
        assertThatThrownBy(() -> new ArrivalProperties(120, 300, 301, 30, 600, 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commandTimeoutSeconds");
    }

    @Test
    void timeoutScanCannotBeSlowerThanCommandTimeout() {
        assertThatThrownBy(() -> new ArrivalProperties(120, 300, 120, 121, 600, 300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("timeoutCheckIntervalSeconds");
    }
}
