package com.potner.sensor.domain;

import java.math.BigDecimal;

/**
 * 기간별 센서 집계 결과 한 구간이다.
 * {@code bucketIndex}는 서비스 타임존 기준 구간 번호이며 실제 시각 변환은 애플리케이션에서 수행한다.
 */
public interface SensorHistoryBucket {

    Long getBucketIndex();

    BigDecimal getAverageValue();

    BigDecimal getMinimumValue();

    BigDecimal getMaximumValue();

    Long getSampleCount();
}
