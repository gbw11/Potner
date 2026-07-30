-- 장치 명령에 이동(NAVIGATE)과 송풍(FAN)을 추가한다.
--
-- 모든 동작 명령이 서버를 거치기로 결정되면서 이동 명령이 서버 몫이 됐다. 로봇의 자율 임무
-- (mission_manager 의 임계값 판단)는 꺼지고, 서버가 생육 데이터로 판단해 명령을 내린다.
--
-- 급수·바람·사진은 같은 스팟(WATER_STATION)에서 이루어진다. 펌프·팬·카메라가 모두 스테이션의
-- 라즈베리에 붙어 있으므로 위치 종류를 늘리지 않는다.
--
-- 새 테이블이 아니라 컬럼 추가인 이유: 명령의 수명(발행 → requestId 반향 → 회신 대조 →
-- 타임아웃)이 급수·촬영과 같아서, 나누면 같은 흐름을 두 벌 관리하게 된다. 명령별로 다른 것은
-- 페이로드(목적지·가동 시간)뿐이라 NULL 컬럼으로 감당된다 — requested_ml 과 같은 방침이다.

ALTER TABLE `device_command`
    ADD COLUMN `destination` VARCHAR(20) NULL
        COMMENT 'NAVIGATE 만. robot_location.location_type 의 목적지' AFTER `requested_ml`,
    ADD COLUMN `run_seconds` INT UNSIGNED NULL
        COMMENT 'FAN 만. 1회 가동 시간(초)' AFTER `destination`;

-- CHECK 는 교체가 없어서 지우고 다시 만든다.
ALTER TABLE `device_command`
    DROP CHECK `ck_device_command_type`;

ALTER TABLE `device_command`
    ADD CONSTRAINT `ck_device_command_type`
        CHECK (`command_type` IN ('WATER', 'CAPTURE', 'NAVIGATE', 'FAN')),
    -- robot_location 의 ck_robot_location_type 과 같은 목록이어야 한다. 위치 종류가 늘면
    -- 양쪽을 함께 고친다.
    ADD CONSTRAINT `ck_device_command_destination`
        CHECK (`destination` IS NULL
            OR `destination` IN ('WATER_STATION', 'HOME', 'SUNLIGHT', 'GREETING'));
