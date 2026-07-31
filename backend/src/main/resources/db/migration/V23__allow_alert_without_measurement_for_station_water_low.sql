-- 스테이션 물 부족을 alert 에 기록한다. 지금까지는 푸시만 나가고 알림 목록에 남지 않아,
-- 푸시를 놓친 사용자가 물 부족을 알 방법이 없었다.
--
-- 물 부족 보고는 불리언이라(WaterLowMessage 주석 참고) 측정값·기준이 없다. 없는 값을
-- 0/1/1 같은 합성값으로 채우면 앱 알림 상세에 "측정값 0, 기준 1~1" 이 그대로 노출되므로,
-- 세 컬럼을 NULL 허용으로 바꾸고 물 부족 알림은 비워 둔다.
--
-- 센서 알림까지 덩달아 값 없이 저장되는 것은 ck_alert_measurement_presence 가 막는다.
-- 앱은 지금까지 세 값이 항상 있다고 전제해도 됐는데, 이 제약 덕에 그 전제가
-- "STATION_WATER_LOW 가 아니면" 으로만 좁아진다.

ALTER TABLE `alert`
    MODIFY COLUMN `measured_value` DECIMAL(14,4) NULL
        COMMENT '판정 근거가 된 최근 측정값의 중앙값. 측정값이 없는 지표(물 부족)는 NULL',
    MODIFY COLUMN `threshold_min`  DECIMAL(14,4) NULL
        COMMENT '판정 당시 적용 기준 하한. 측정값이 없는 지표(물 부족)는 NULL',
    MODIFY COLUMN `threshold_max`  DECIMAL(14,4) NULL
        COMMENT '판정 당시 적용 기준 상한. 측정값이 없는 지표(물 부족)는 NULL';

ALTER TABLE `alert`
    DROP CHECK `ck_alert_metric_type`;

ALTER TABLE `alert`
    ADD CONSTRAINT `ck_alert_metric_type`
        CHECK (`metric_type` IN (
            'TEMPERATURE', 'HUMIDITY', 'SOIL_MOISTURE', 'DAILY_LIGHT', 'PHOTOPERIOD',
            'STATION_WATER_LOW')),
    ADD CONSTRAINT `ck_alert_measurement_presence`
        CHECK (`metric_type` = 'STATION_WATER_LOW'
            OR (`measured_value` IS NOT NULL
                AND `threshold_min` IS NOT NULL
                AND `threshold_max` IS NOT NULL));
