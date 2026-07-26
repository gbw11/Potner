package com.potner.sensor.domain;

import java.time.Duration;

public enum SensorHistoryInterval {

    HOUR(Duration.ofHours(1)),
    DAY(Duration.ofDays(1));

    private final Duration bucketSize;

    SensorHistoryInterval(Duration bucketSize) {
        this.bucketSize = bucketSize;
    }

    public long bucketSeconds() {
        return bucketSize.toSeconds();
    }
}
