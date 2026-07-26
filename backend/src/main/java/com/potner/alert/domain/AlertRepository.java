package com.potner.alert.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, String> {

    Optional<Alert> findByPlantIdAndMetricTypeAndResolvedAtIsNull(
            String plantId,
            AlertMetricType metricType
    );
}
