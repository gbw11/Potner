-- 배수트레이 비움 알림을 추가한다.
--
-- 수위 센서를 두지 않는다. 급수할 때마다 실제 배출량이 device_command.dispensed_ml 에 남으므로
-- 누적 급수량으로 트레이가 찰 시점을 추정할 수 있다. 하드웨어가 늘지 않는 것이 이 방식의
-- 핵심 이점이다.
--
-- 판정 기준은 고정 ml 이 아니라 식물별 권장 급수량의 배수다. 트레이는 총 배수량으로 차므로
-- 회당 급수량 차이는 무관하지만, 트레이 용량은 화분 크기에 따라 다르다.
-- recommended_watering_ml 이 화분 크기의 대리 지표라서 그 값에 배수를 곱한다.
--
-- STATION_WATER_LOW 와 달리 측정값·기준이 실제로 있다(누적 ml vs 임계 ml). 그래서
-- ck_alert_measurement_presence 를 고치지 않는다 — 이 지표는 세 값을 모두 채운다.

ALTER TABLE `alert`
    DROP CHECK `ck_alert_metric_type`;

ALTER TABLE `alert`
    ADD CONSTRAINT `ck_alert_metric_type`
        CHECK (`metric_type` IN (
            'TEMPERATURE', 'HUMIDITY', 'SOIL_MOISTURE', 'DAILY_LIGHT', 'PHOTOPERIOD',
            'STATION_WATER_LOW', 'DRAINAGE_TRAY'));
