-- 기기 해제(소유권 이전)를 가능하게 한다.
--
-- device_uid 는 지금까지 전역 UNIQUE 라 한 번 등록된 코드는 영원히 그 계정 것이었다. 기기를
-- 중고로 넘기거나 교체하면 새 주인이 같은 코드로 등록할 방법이 없다. 물리 삭제는 답이 아니다 —
-- sensor_reading 이 RESTRICT 로 참조해 측정값을 한 번이라도 보낸 기기는 지워지지 않고,
-- 지운다 해도 이전 주인의 측정 이력이 함께 사라진다.
--
-- 그래서 소프트 해제다. released_at 을 채우면 파생 키가 NULL 이 되어 UNIQUE 대상에서 빠지고,
-- 같은 코드로 새 행을 등록할 수 있다. 이력은 남고 활성은 코드당 하나다 —
-- plant_device_assignment 의 active_plant_key, alert 의 active_key 와 같은 관용구다.
--
-- 주의: 브로커(mosquitto) 계정은 이 테이블과 무관하게 남는다. 소유권을 넘길 때는 해당 계정
-- 비밀번호 재발급이 함께 필요하다 (DEVICE-MQTT.md 12절).

ALTER TABLE `robot`
    ADD COLUMN `released_at` DATETIME NULL
        COMMENT '해제 시각. NULL 이면 활성. 해제된 행은 이력으로만 남는다',
    ADD COLUMN `active_device_uid` VARCHAR(100) GENERATED ALWAYS AS (
        IF(`released_at` IS NULL, `device_uid`, NULL)
    ) STORED COMMENT '활성 기기 코드 유일성 보장용 파생 키';

ALTER TABLE `robot`
    DROP INDEX `uq_robot_device_uid`;

ALTER TABLE `robot`
    ADD CONSTRAINT `uq_robot_active_device_uid` UNIQUE (`active_device_uid`);

ALTER TABLE `iot_device`
    ADD COLUMN `released_at` DATETIME NULL
        COMMENT '해제 시각. 로봇 해제와 함께 채워진다',
    ADD COLUMN `active_device_uid` VARCHAR(100) GENERATED ALWAYS AS (
        IF(`released_at` IS NULL, `device_uid`, NULL)
    ) STORED COMMENT '활성 기기 코드 유일성 보장용 파생 키';

ALTER TABLE `iot_device`
    DROP INDEX `uq_iot_device_device_uid`;

ALTER TABLE `iot_device`
    ADD CONSTRAINT `uq_iot_device_active_device_uid` UNIQUE (`active_device_uid`);

-- MQTT 귀속 조회(device_uid → 활성 장치)가 UNIQUE 를 잃었으므로 인덱스를 대신 둔다.
-- 해제된 행이 섞여도 released_at IS NULL 조건과 함께 짧게 끝난다.
ALTER TABLE `iot_device`
    ADD INDEX `idx_iot_device_uid_released` (`device_uid`, `released_at`);
