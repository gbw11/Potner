-- 시연용 가상 데이터다. 앱 화면이 비어 보이지 않도록 실제 수집 경로(MQTT·스케줄러·LLM)가
-- 만들어야 하는 데이터를 손으로 채운다.
--
-- 왜 API 가 아니라 SQL 인가: 센서 측정값·알림·일기·일일 광량·명령 이력은 사용자 API 로 만들
-- 수 있는 경로가 없다. 장치가 MQTT 로 보내거나 서버 스케줄러·LLM 이 만드는 데이터다.
-- 식물·개화처럼 API 가 있는 것도 여기서 함께 넣는다. 화면 하나가 여러 표를 동시에 읽으므로
-- 경로를 나누면 "일기는 있는데 그날 센서가 없는" 어긋난 상태가 쉽게 생긴다.
--
-- 실행:
--   docker compose exec -T mysql \
--     mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" < backend/scripts/seed-demo-data.sql
--
-- 여러 번 돌려도 안전하다. 넣는 모든 행의 식별자가 'seed' 로 시작하고, 맨 앞에서 그 행들만
-- 지우고 다시 넣는다. 사용자가 앱에서 직접 만든 데이터는 건드리지 않는다.
--
-- 사진은 이 스크립트가 넣지 않는다. 파일이 디스크에 있어야 하고 서버가 썸네일·재생본을
-- 만들어야 하므로, seed-demo-photos.sh 가 장치 업로드 API 로 넣는다. 그 스크립트가 쓰는
-- 업로드 토큰을 여기서 함께 심는다.

SET @email := 'potner@naver.com';

-- 업로드 토큰은 원문을 저장하지 않고 SHA-256 해시만 둔다(Robot.uploadTokenHash).
-- 시연용이라 원문을 스크립트에 적어 두고 사진 업로드에 그대로 쓴다.
SET @upload_token := 'demo-upload-token-fff';

SET @now := UTC_TIMESTAMP();
-- 서비스 타임존 기준 오늘이다. photo_date·diary_date·bloom_date 가 모두 이 기준이라
-- UTC 날짜를 쓰면 자정 근처에서 하루가 어긋난다.
SET @today := DATE(CONVERT_TZ(@now, '+00:00', '+09:00'));

SET @user_id := (SELECT `user_id` FROM `app_user` WHERE `email` = @email);

-- 사용자를 못 찾으면 아래 문장들이 조용히 0건을 넣고 끝난다. 결과를 먼저 찍어 준다.
SELECT IF(
    @user_id IS NULL,
    CONCAT('중단: 사용자를 찾지 못했습니다 - ', @email),
    CONCAT('대상 사용자: ', @email, ' (', @user_id, ')')
) AS `사용자 확인`;

-- 식물은 이미 등록되어 있다고 보고 그 위에 채운다. 없으면 앱에서 먼저 등록해야 한다.
SET @p1 := (SELECT `plant_id` FROM `plant`
             WHERE `user_id` = @user_id AND `plant_status` = 'ACTIVE'
             ORDER BY `created_at` LIMIT 1);
SET @p2 := (SELECT `plant_id` FROM `plant`
             WHERE `user_id` = @user_id AND `plant_status` = 'ACTIVE'
             ORDER BY `created_at` LIMIT 1 OFFSET 1);

SELECT IF(@p1 IS NULL,
          '중단: 활성 식물이 없습니다 - 앱에서 식물을 먼저 등록해 주세요.',
          CONCAT('대상 식물 수: ',
                 (SELECT COUNT(*) FROM `plant`
                   WHERE `user_id` = @user_id AND `plant_status` = 'ACTIVE'))
) AS `식물 확인`;

-- 데모 로봇·장치 식별자를 고정한다. 고정해야 재실행 때 같은 행을 갱신하고, 정리도
-- 'seed' 접두어 하나로 끝난다.
SET @robot1 := 'seedrob1-0000-0000-0000-000000000001';
SET @robot2 := 'seedrob2-0000-0000-0000-000000000002';
SET @pi_device := 'seeddev1-0000-0000-0000-000000000001';
SET @jetson_device := 'seeddev2-0000-0000-0000-000000000002';

-- ---------------------------------------------------------------------------
-- 1. 이전 시드 정리
--
-- FK 를 거스르지 않는 순서로 지운다. sensor_reading 이 robot·iot_device 를 RESTRICT
-- 로 잡고 있어 측정값을 먼저 지워야 로봇을 지울 수 있다.
-- ---------------------------------------------------------------------------
DELETE FROM `sensor_reading` WHERE `device_message_id` LIKE 'seed-%';
DELETE FROM `device_command` WHERE `request_id` LIKE 'seed%';
DELETE FROM `alert` WHERE `alert_id` LIKE 'seed%';
DELETE FROM `plant_diary` WHERE `diary_id` LIKE 'seed%';
DELETE FROM `plant_bloom` WHERE `bloom_id` LIKE 'seed%';
DELETE FROM `plant_daily_light` WHERE `daily_light_id` LIKE 'seed%';
DELETE FROM `robot_location` WHERE `location_id` LIKE 'seed%';
DELETE FROM `plant_device_assignment` WHERE `assignment_id` LIKE 'seed%';
DELETE FROM `iot_device` WHERE `device_id` LIKE 'seed%';
DELETE FROM `robot` WHERE `robot_id` LIKE 'seed%';

-- ---------------------------------------------------------------------------
-- 2. 로봇과 하위 장치
--
-- 두 대를 만든다. 한 대는 온라인·배터리·행동 상태까지 채워 장치 관리 화면이 살아 보이게
-- 하고, 다른 한 대는 오프라인·배터리 부족으로 두어 상태 배지가 구별돼 보이게 한다.
--
-- 상수만 넣는 SELECT 에도 FROM 이 필요하다. MySQL 은 FROM 없는 SELECT 에 WHERE 를
-- 허용하지 않으므로 더미 테이블 (SELECT 1) 을 끼운다.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `robot` (
    `robot_id`, `user_id`, `device_uid`, `upload_token_hash`, `name`,
    `connection_status`, `battery_percent`, `battery_measured_at`, `last_seen_at`,
    `firmware_version`, `current_state`, `state_changed_at`, `created_at`, `updated_at`
)
SELECT @robot1, @user_id, 'demo-robot-01', SHA2(@upload_token, 256), '포트니',
       'ONLINE', 82, DATE_SUB(@now, INTERVAL 4 MINUTE), DATE_SUB(@now, INTERVAL 1 MINUTE),
       '1.4.2', 'SERVICING', DATE_SUB(@now, INTERVAL 3 MINUTE),
       DATE_SUB(@now, INTERVAL 40 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL
UNION ALL
SELECT @robot2, @user_id, 'demo-robot-02', NULL, '새싹이',
       'OFFLINE', 12, DATE_SUB(@now, INTERVAL 2 DAY), DATE_SUB(@now, INTERVAL 2 DAY),
       '1.3.9', 'IDLE', DATE_SUB(@now, INTERVAL 2 DAY),
       DATE_SUB(@now, INTERVAL 12 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL;

-- 측정값의 출처다. 이 행이 없으면 sensor_reading 을 넣을 수 없다.
INSERT IGNORE INTO `iot_device` (
    `device_id`, `robot_id`, `device_uid`, `device_type`,
    `connection_status`, `last_seen_at`, `created_at`, `updated_at`
)
SELECT @pi_device, @robot1, 'demo-station-pi-01', 'RASPBERRY_PI',
       'ONLINE', DATE_SUB(@now, INTERVAL 1 MINUTE), DATE_SUB(@now, INTERVAL 40 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL
UNION ALL
SELECT @jetson_device, @robot1, 'demo-jetson-01', 'JETSON_ORIN',
       'ONLINE', DATE_SUB(@now, INTERVAL 2 MINUTE), DATE_SUB(@now, INTERVAL 40 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL;

-- 첫 식물에 로봇을 배정한다. 배정이 없으면 사진 업로드가 어느 식물인지 못 찾고,
-- 장치 관리 화면도 "아직 배정되지 않음" 으로 남는다.
-- 이미 다른 로봇이 배정돼 있으면 건드리지 않는다(그쪽이 실제 장치일 수 있다).
INSERT IGNORE INTO `plant_device_assignment` (
    `assignment_id`, `plant_id`, `robot_id`, `assigned_at`, `unassigned_at`
)
SELECT 'seedasgn-0000-0000-0000-000000000001', @p1, @robot1,
       DATE_SUB(@now, INTERVAL 30 DAY), NULL
FROM (SELECT 1) AS `g`
WHERE @p1 IS NOT NULL
  AND NOT EXISTS (
      SELECT 1
      FROM (SELECT `plant_id`, `unassigned_at` FROM `plant_device_assignment`) AS `a`
      WHERE `a`.`plant_id` = @p1 AND `a`.`unassigned_at` IS NULL
  );

-- ---------------------------------------------------------------------------
-- 3. 로봇 위치
--
-- 급수 스테이션에는 코드가 필수고 나머지에는 없어야 한다(ck_robot_location_station_code).
-- 물 부족을 켜 두어 위치 설정 화면의 배지가 보이게 한다.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `robot_location` (
    `location_id`, `robot_id`, `location_type`, `station_code`,
    `pose_x`, `pose_y`, `pose_yaw`, `water_low`, `water_low_at`, `created_at`, `updated_at`
)
SELECT 'seedloca-0000-0000-0000-000000000001', @robot1, 'WATER_STATION', 'demo-station-01',
       1.250, -0.480, 1.5708, 1, DATE_SUB(@now, INTERVAL 3 HOUR),
       DATE_SUB(@now, INTERVAL 30 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL
UNION ALL
SELECT 'seedloca-0000-0000-0000-000000000002', @robot1, 'HOME', NULL,
       0.000, 0.000, 0.0000, 0, NULL, DATE_SUB(@now, INTERVAL 30 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL
UNION ALL
SELECT 'seedloca-0000-0000-0000-000000000003', @robot1, 'SUNLIGHT', NULL,
       2.840, 1.120, -0.7854, 0, NULL, DATE_SUB(@now, INTERVAL 25 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL
UNION ALL
-- 좌표를 비워 둔다. 앱의 "좌표 입력 전이에요" 경고가 어떻게 보이는지 함께 확인할 수 있다.
SELECT 'seedloca-0000-0000-0000-000000000004', @robot1, 'GREETING', NULL,
       NULL, NULL, NULL, 0, NULL, DATE_SUB(@now, INTERVAL 5 DAY), @now
FROM (SELECT 1) AS `g` WHERE @user_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 4. 센서 측정값 — 30일치, 한 시간 간격
--
-- 환경 대시보드의 24시간(HOUR)·7일·30일(DAY) 그래프가 모두 이 표에서 나온다. 홈의 현재
-- 환경도 최신 한 건씩을 읽으므로 슬롯 0 을 지금 시각에 맞춰 STALE 로 빠지지 않게 한다.
--
-- 값은 SIN 으로 흔들어 그래프가 직선이 되지 않게 한다. 습도는 최근 8시간만 높게 두어
-- 아래에서 넣는 '습도 높음' 활성 알림과 앞뒤가 맞게 한다.
-- ---------------------------------------------------------------------------
SET SESSION cte_max_recursion_depth = 1000;

INSERT IGNORE INTO `sensor_reading` (
    `plant_id`, `robot_id`, `source_device_id`, `device_message_id`,
    `sensor_type`, `measured_value`, `unit`, `quality`, `measured_at`, `received_at`
)
WITH RECURSIVE `slot` AS (
    SELECT 0 AS `n`
    UNION ALL
    SELECT `n` + 1 FROM `slot` WHERE `n` < 719
),
`target` AS (
    SELECT `plant_id`, ROW_NUMBER() OVER (ORDER BY `created_at`) AS `idx`
    FROM `plant`
    WHERE `user_id` = @user_id AND `plant_status` = 'ACTIVE'
),
`point` AS (
    SELECT
        `t`.`plant_id`,
        `t`.`idx`,
        `s`.`n`,
        DATE_SUB(@now, INTERVAL `s`.`n` HOUR) AS `at`,
        HOUR(CONVERT_TZ(DATE_SUB(@now, INTERVAL `s`.`n` HOUR), '+00:00', '+09:00')) AS `kst_hour`,
        -- 값의 범위는 종 기준에서 끌어낸다. 숫자를 박아 두면 종을 바꿨을 때 측정값이 밴드
        -- 밖에 눌러앉는다. 그래프에서는 선이 적정 범위 띠 위로 통째로 떠 있는데 현재 상태는
        -- '정상' 이라고 뜨는 모순으로 보인다 — 판정은 최신값 하나만 보기 때문이다.
        `g`.`soil_moisture_min_pct` AS `soil_min`,
        `g`.`soil_moisture_max_pct` AS `soil_max`,
        `g`.`humidity_min_pct` AS `humi_min`,
        `g`.`humidity_max_pct` AS `humi_max`,
        `g`.`daily_light_target_lux_hour` AS `light_target`
    FROM `slot` AS `s`
    CROSS JOIN `target` AS `t`
    JOIN `plant_growth_profile` AS `g` ON `g`.`plant_id` = `t`.`plant_id`
)
SELECT `plant_id`, @robot1, @pi_device,
       CONCAT('seed-', `idx`, '-soil-', `n`),
       'SOIL_MOISTURE',
       -- 급수 뒤 마르는 흐름을 톱니처럼 만든다. 밴드 한가운데를 중심으로 폭의 35% 만큼
       -- 오르내려 항상 기준 안에 머문다. 주기는 10시간쯤이라 24시간 그래프에 두 번 보인다.
       ROUND((`soil_min` + `soil_max`) / 2
             + (`soil_max` - `soil_min`) * 0.35 * SIN(`n` / 1.6 + `idx`), 2),
       'PERCENT', 'GOOD', `at`, `at`
FROM `point`
UNION ALL
SELECT `plant_id`, @robot1, @pi_device,
       CONCAT('seed-', `idx`, '-temp-', `n`),
       'TEMPERATURE',
       -- 낮에 오르고 새벽에 내려간다. 19.8~26.2도.
       ROUND(23 + 3.2 * SIN((`kst_hour` - 4) * PI() / 12) + 0.4 * SIN(`n` / 17), 2),
       'CELSIUS', 'GOOD', `at`, `at`
FROM `point`
UNION ALL
SELECT `plant_id`, @robot1, @pi_device,
       CONCAT('seed-', `idx`, '-humi-', `n`),
       'HUMIDITY',
       CASE
           -- 이틀 전 여덟 시간만 높게 띄운다. 아래 '습도 높음'(해소됨) 알림의 근거값이라
           -- 시각이 맞아야 한다. 최근 8시간에 두면 그래프는 계속 높은데 알림은 없는
           -- 상태가 되어 화면끼리 어긋난다.
           WHEN `n` BETWEEN 43 AND 51 THEN ROUND(86 + 2 * SIN(`n`), 2)
           ELSE ROUND((`humi_min` + `humi_max`) / 2
                      + (`humi_max` - `humi_min`) * 0.35 * SIN(`n` / 7 + `idx`), 2)
       END,
       'PERCENT', 'GOOD', `at`, `at`
FROM `point`
UNION ALL
SELECT `plant_id`, @robot1, @pi_device,
       CONCAT('seed-', `idx`, '-lux-', `n`),
       'ILLUMINANCE',
       -- 밤에는 0 이다. 조도는 순간값으로 판정하지 않으므로 0 이 정상이고, 하루 누적
       -- 광량이 아래 plant_daily_light 와 앞뒤가 맞아야 한다.
       --
       -- 정점을 상수로 박으면 종을 바꿨을 때 하루 누적이 종 기준과 어긋난다. 그러면 마감된
       -- 날은 저장된 목표치로 판정되어 멀쩡해 보이는데 오늘만 종 기준과 비교되어 진행률이
       -- 한 자리로 떨어진다. 그래서 종 기준에서 거꾸로 정점을 구한다.
       --
       -- 6~19시 사인 곡선의 적분은 정점 * 13 * 2/PI 이고, 여기에 아래 흔들림 계수의 평균
       -- (0.75 + 0.25 * 2/PI) 이 곱해진다. 둘을 합치면 약 7.52 다.
       CASE
           WHEN `kst_hour` BETWEEN 6 AND 19
               THEN ROUND(GREATEST(0, (`light_target` / 7.52)
                                      * SIN((`kst_hour` - 6) * PI() / 13))
                          * (0.75 + 0.25 * ABS(SIN(`n` / 53))), 2)
           ELSE 0
       END,
       'LUX', 'GOOD', `at`, `at`
FROM `point`
-- 오늘은 아래에서 훨씬 촘촘하게 따로 넣는다. 여기서도 넣으면 정시마다 표본이 겹친다.
WHERE DATE(CONVERT_TZ(`at`, '+00:00', '+09:00')) < @today;

-- ---------------------------------------------------------------------------
-- 4-1. 오늘의 조도 — 장치 주기에 가깝게
--
-- 마감된 날의 광량 판정은 아래 plant_daily_light 에 미리 계산해 넣지만, 오늘 값만은 서버가
-- 이 표를 직접 적분해서 만든다. 그래서 오늘은 표본 간격이 결과를 그대로 좌우한다.
--
-- 서버는 표본 하나가 최대 60초까지만 대표한다고 본다(potner.daily-light.max-gap-seconds).
-- 실기기가 10초마다 보내는 것을 전제로 잡은 값이다. 시간별로 하나만 넣으면 한 시간 중
-- 1분만 인정되어 누적 광량이 60분의 1로 깎이고, 홈과 환경 화면의 오늘 진행률이 늘 한 자리
-- 퍼센트로 뜬다. 값이 틀린 것이 아니라 표본이 성긴 탓이라 원인을 찾기가 어렵다.
--
-- 하루치를 1분 간격으로 채운다. 조도 한 종만 1440건이라 부담이 크지 않고, 이 표만
-- 촘촘하면 오늘 진행률이 실제 장치를 붙였을 때와 같은 값이 된다.
-- ---------------------------------------------------------------------------
SET SESSION cte_max_recursion_depth = 2000;

INSERT IGNORE INTO `sensor_reading` (
    `plant_id`, `robot_id`, `source_device_id`, `device_message_id`,
    `sensor_type`, `measured_value`, `unit`, `quality`, `measured_at`, `received_at`
)
WITH RECURSIVE `minute` AS (
    SELECT 0 AS `m`
    UNION ALL
    SELECT `m` + 1 FROM `minute` WHERE `m` < 1439
),
`target` AS (
    SELECT `plant_id`, ROW_NUMBER() OVER (ORDER BY `created_at`) AS `idx`
    FROM `plant`
    WHERE `user_id` = @user_id AND `plant_status` = 'ACTIVE'
),
`point` AS (
    SELECT
        `t`.`plant_id`,
        `t`.`idx`,
        `mi`.`m`,
        -- KST 자정에서 m 분 뒤를 UTC 로 되돌린다.
        CONVERT_TZ(TIMESTAMP(@today) + INTERVAL `mi`.`m` MINUTE, '+09:00', '+00:00') AS `at`,
        `mi`.`m` / 60.0 AS `kst_hour`
    FROM `minute` AS `mi`
    CROSS JOIN `target` AS `t`
)
SELECT `plant_id`, @robot1, @pi_device,
       CONCAT('seed-', `idx`, '-luxmin-', `m`),
       'ILLUMINANCE',
       CASE
           WHEN `kst_hour` BETWEEN 6 AND 19
               THEN ROUND(GREATEST(0, (
                              SELECT `g`.`daily_light_target_lux_hour` / 7.52
                              FROM `plant_growth_profile` AS `g`
                              WHERE `g`.`plant_id` = `point`.`plant_id`
                          ) * SIN((`kst_hour` - 6) * PI() / 13))
                          * (0.75 + 0.25 * ABS(SIN(`m` / 311))), 2)
           ELSE 0
       END,
       'LUX', 'GOOD', `at`, `at`
FROM `point`
-- 아직 오지 않은 시각은 넣지 않는다. 미래 측정값은 실제로 존재할 수 없다.
WHERE `at` <= @now;

-- ---------------------------------------------------------------------------
-- 5. 일일 광량 판정 — 어제부터 29일치
--
-- 오늘은 넣지 않는다. 진행 중인 오늘 값은 서버가 위 측정값에서 실시간으로 계산해
-- 진행률로 보여 주고, 이 표는 마감된 날의 판정만 담는다.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `plant_daily_light` (
    `daily_light_id`, `plant_id`, `light_date`,
    `accumulated_lux_hour`, `light_hours`, `coverage_pct`, `sample_count`,
    `target_lux_hour`, `target_photoperiod_hours`,
    `threshold_min_lux_hour`, `threshold_max_lux_hour`,
    `light_status`, `photoperiod_status`, `computed_at`, `created_at`, `updated_at`
)
WITH RECURSIVE `day` AS (
    SELECT 1 AS `n`
    UNION ALL
    SELECT `n` + 1 FROM `day` WHERE `n` < 29
),
`target` AS (
    SELECT `plant_id`, ROW_NUMBER() OVER (ORDER BY `created_at`) AS `idx`
    FROM `plant`
    WHERE `user_id` = @user_id AND `plant_status` = 'ACTIVE'
),
`calc` AS (
    SELECT
        `t`.`plant_id`,
        `t`.`idx`,
        `d`.`n`,
        DATE_SUB(@today, INTERVAL `d`.`n` DAY) AS `light_date`,
        `g`.`daily_light_target_lux_hour` AS `target_luxh`,
        `g`.`daily_light_min_lux_hour` AS `min_luxh`,
        `g`.`daily_light_max_lux_hour` AS `max_luxh`,
        `g`.`photoperiod_hours` AS `target_hours`,
        -- 목표의 ±32% 로 흔든다. 밴드가 70~130% 라 부족·적정·초과가 모두 나온다.
        ROUND(`g`.`daily_light_target_lux_hour`
              * (1 + 0.32 * SIN(`d`.`n` * 0.8 + `t`.`idx`)), 2) AS `accumulated`,
        -- 일조 시간은 목표 아래로만 내린다. 흐린 날은 짧아지지만 하루가 주는 해보다 길어질
        -- 수는 없다. 위로도 흔들면 17시간처럼 있을 수 없는 값이 나온다.
        ROUND(`g`.`photoperiod_hours`
              - `g`.`photoperiod_hours` * 0.28 * ABS(SIN(`d`.`n` * 0.7 + `t`.`idx`)), 2) AS `hours`
    FROM `day` AS `d`
    CROSS JOIN `target` AS `t`
    JOIN `plant_growth_profile` AS `g` ON `g`.`plant_id` = `t`.`plant_id`
)
SELECT
    CONCAT('seedlite-0000-0000-0000-', LPAD(`idx` * 1000 + `n`, 12, '0')),
    `plant_id`, `light_date`,
    `accumulated`, `hours`,
    ROUND(94 + 6 * ABS(SIN(`n`)), 2),
    220 + MOD(`n` * 7, 60),
    `target_luxh`, `target_hours`,
    -- 허용 범위는 종 기준을 그대로 쓴다. V7 마이그레이션이 목표의 70~130% 로 채웠다.
    `min_luxh`, `max_luxh`,
    CASE
        WHEN `accumulated` < `min_luxh` THEN 'LOW'
        WHEN `accumulated` > `max_luxh` THEN 'HIGH'
        ELSE 'NORMAL'
    END,
    CASE
        WHEN `hours` < `target_hours` * 0.8 THEN 'LOW'
        WHEN `hours` > `target_hours` * 1.2 THEN 'HIGH'
        ELSE 'NORMAL'
    END,
    -- 자정 직후 배치가 계산한다.
    CONVERT_TZ(TIMESTAMP(DATE_ADD(`light_date`, INTERVAL 1 DAY), '00:10:00'), '+09:00', '+00:00'),
    @now, @now
FROM `calc`;

-- ---------------------------------------------------------------------------
-- 6. 알림
--
-- 알림 화면이 세 섹션(이상 알림 / 개화 알림 / 알림 이력)이므로 활성 하나와 해소된 여러
-- 건을 섞는다. 활성은 식물·지표 조합당 하나만 존재할 수 있다(uq_alert_active).
-- 읽음·안 읽음을 섞어 굵게 표시와 미읽음 배지를 함께 확인한다.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `alert` (
    `alert_id`, `user_id`, `plant_id`, `metric_type`, `deviation`,
    `measured_value`, `threshold_min`, `threshold_max`,
    `occurred_at`, `resolved_at`, `read_at`, `created_at`, `updated_at`
)
-- 활성 + 안 읽음. 홈의 미읽음 배지와 이상 알림 카드가 여기서 나온다.
--
-- 활성으로 두는 지표를 고를 때 주의가 필요하다. 온도·습도·토양수분은 측정값이 들어올 때마다
-- 다시 판정되므로, 장치가 정상값을 보내는 동안에는 넣자마자 해소되어 이상 알림 칸이 빈다.
-- 광량과 일조는 자정 배치가, 물 부족과 배수트레이는 장치 보고가 판정하므로 측정값이 정상
-- 범위로 흘러도 그대로 남는다. 그래서 활성 세 건은 이 지표들로 잡는다.
-- 물 부족 보고는 불리언이라 넣을 수치가 없다. ck_alert_measurement_presence 가 이 지표에만
-- NULL 을 허용하고 나머지는 measured_value 와 임계값 세 개를 모두 요구한다.
SELECT 'seedale1-0000-0000-0000-000000000001', @user_id, @p1, 'STATION_WATER_LOW', 'LOW',
       NULL, NULL, NULL,
       DATE_SUB(@now, INTERVAL 1 DAY), NULL, NULL, DATE_SUB(@now, INTERVAL 1 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
-- 광량·일조의 임계값은 종 기준을 그대로 읽는다. 상수로 박으면 위 plant_daily_light 와
-- 어긋나 같은 날을 두고 화면마다 다른 기준이 보인다.
SELECT 'seedale2-0000-0000-0000-000000000002', @user_id, @p1, 'DAILY_LIGHT', 'LOW',
       ROUND(`g`.`daily_light_min_lux_hour` * 0.9, 4),
       `g`.`daily_light_min_lux_hour`, `g`.`daily_light_max_lux_hour`,
       DATE_SUB(@now, INTERVAL 3 DAY), NULL, NULL, DATE_SUB(@now, INTERVAL 3 DAY), @now
FROM `plant_growth_profile` AS `g` WHERE `g`.`plant_id` = @p1
UNION ALL
SELECT 'seedale3-0000-0000-0000-000000000003', @user_id, @p1, 'PHOTOPERIOD', 'LOW',
       ROUND(`g`.`photoperiod_hours` * 0.7, 4),
       ROUND(`g`.`photoperiod_hours` * 0.8, 4), ROUND(`g`.`photoperiod_hours` * 1.2, 4),
       DATE_SUB(@now, INTERVAL 3 DAY), NULL, NULL, DATE_SUB(@now, INTERVAL 3 DAY), @now
FROM `plant_growth_profile` AS `g` WHERE `g`.`plant_id` = @p1
UNION ALL
-- 해소된 건들. 알림 이력 섹션을 채운다. 읽음·안 읽음을 섞어 굵게 표시를 확인한다.
SELECT 'seedale4-0000-0000-0000-000000000004', @user_id, @p1, 'HUMIDITY', 'HIGH',
       87.4000, 50.0000, 70.0000,
       DATE_SUB(@now, INTERVAL 2 DAY), DATE_SUB(@now, INTERVAL 43 HOUR),
       NULL, DATE_SUB(@now, INTERVAL 2 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedale5-0000-0000-0000-000000000005', @user_id, @p1, 'TEMPERATURE', 'LOW',
       10.2000, 18.0000, 27.0000,
       DATE_SUB(@now, INTERVAL 4 DAY), DATE_SUB(@now, INTERVAL 93 HOUR),
       DATE_SUB(@now, INTERVAL 3 DAY), DATE_SUB(@now, INTERVAL 4 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedale6-0000-0000-0000-000000000006', @user_id, @p1, 'SOIL_MOISTURE', 'LOW',
       28.6000, 38.0000, 60.0000,
       DATE_SUB(@now, INTERVAL 8 DAY), DATE_SUB(@now, INTERVAL 188 HOUR),
       DATE_SUB(@now, INTERVAL 7 DAY), DATE_SUB(@now, INTERVAL 8 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
-- 두 번째 식물이 있으면 알림도 섞어 둔다. 문구에 식물 이름이 들어가는 것을 확인할 수 있다.
SELECT 'seedale7-0000-0000-0000-000000000007', @user_id, @p2, 'SOIL_MOISTURE', 'LOW',
       24.1000, 38.0000, 60.0000,
       DATE_SUB(@now, INTERVAL 30 MINUTE), NULL, NULL, DATE_SUB(@now, INTERVAL 30 MINUTE), @now
FROM (SELECT 1) AS `g` WHERE @p2 IS NOT NULL
UNION ALL
SELECT 'seedale8-0000-0000-0000-000000000008', @user_id, @p2, 'TEMPERATURE', 'HIGH',
       31.5000, 18.0000, 27.0000,
       DATE_SUB(@now, INTERVAL 4 DAY), DATE_SUB(@now, INTERVAL 93 HOUR),
       DATE_SUB(@now, INTERVAL 3 DAY), DATE_SUB(@now, INTERVAL 4 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p2 IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 7. 성장 일기 — 21일치
--
-- 원래는 LLM 이 매일 쓴다. 달력에 점이 촘촘히 찍히도록 21일을 채우고 본문은 다섯 종류를
-- 돌려 쓴다. 하루 한 건이다(uq_plant_diary_plant_date).
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `plant_diary` (
    `diary_id`, `plant_id`, `diary_date`, `title`, `content`, `created_at`, `updated_at`
)
WITH RECURSIVE `day` AS (
    SELECT 0 AS `n`
    UNION ALL
    SELECT `n` + 1 FROM `day` WHERE `n` < 20
),
`target` AS (
    SELECT `plant_id`, ROW_NUMBER() OVER (ORDER BY `created_at`) AS `idx`
    FROM `plant`
    WHERE `user_id` = @user_id AND `plant_status` = 'ACTIVE'
)
SELECT
    CONCAT('seeddiar-0000-0000-0000-', LPAD(`t`.`idx` * 1000 + `d`.`n`, 12, '0')),
    `t`.`plant_id`,
    DATE_SUB(@today, INTERVAL `d`.`n` DAY),
    ELT(MOD(`d`.`n`, 5) + 1,
        '햇살이 좋았던 날',
        '조금 목말랐던 하루',
        '새 잎이 돋았어요',
        '바람이 선선했어요',
        '물을 듬뿍 마셨어요'),
    ELT(MOD(`d`.`n`, 5) + 1,
        '아침부터 창가에 햇살이 가득했다. 잎을 활짝 펴고 온몸으로 빛을 받았다. 이런 날은 하루가 길게 느껴져서 좋다. 흙도 아직 촉촉해서 더 바랄 게 없었다.',
        '흙이 조금 마른 것 같아 하루 종일 목이 말랐다. 그래도 오후에 스테이션에서 물을 받아 마시니 금방 기운이 돌아왔다. 잎끝이 다시 팽팽해지는 게 느껴진다.',
        '줄기 끝에서 아주 작은 잎이 하나 돋아난 걸 발견했다. 아직 손톱보다 작지만 며칠 지나면 제 모양을 갖출 것 같다. 자라는 건 늘 조금씩이라 눈치채기 어렵다.',
        '바람이 선선하게 지나가는 날이었다. 잎이 살랑이면서 먼지가 털려 나가는 기분이 들었다. 온도도 습도도 편안한 범위에 머물러 하루가 잔잔했다.',
        '물을 듬뿍 마신 날이다. 뿌리 끝까지 시원하게 스며드는 느낌이 좋았다. 며칠은 든든하게 버틸 수 있을 것 같다. 내일도 이만큼만 좋으면 좋겠다.'),
    CONVERT_TZ(TIMESTAMP(DATE_SUB(@today, INTERVAL `d`.`n` DAY), '22:30:00'), '+09:00', '+00:00'),
    @now
FROM `day` AS `d`
CROSS JOIN `target` AS `t`;

-- ---------------------------------------------------------------------------
-- 8. 개화 기록
--
-- 하나는 안 읽음으로 둔다. 알림 화면의 개화 알림 섹션에서 굵게 보인다.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `plant_bloom` (
    `bloom_id`, `user_id`, `plant_id`, `bloom_date`, `note`, `source`,
    `read_at`, `created_at`, `updated_at`
)
SELECT 'seedbloo-0000-0000-0000-000000000001', @user_id, @p1,
       DATE_SUB(@today, INTERVAL 1 DAY), '드디어 첫 꽃망울이 열렸어요!', 'USER',
       NULL, DATE_SUB(@now, INTERVAL 1 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedbloo-0000-0000-0000-000000000002', @user_id, @p1,
       DATE_SUB(@today, INTERVAL 9 DAY), '두 번째 꽃도 피었어요', 'USER',
       DATE_SUB(@now, INTERVAL 8 DAY), DATE_SUB(@now, INTERVAL 9 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedbloo-0000-0000-0000-000000000003', @user_id, @p1,
       DATE_SUB(@today, INTERVAL 20 DAY), NULL, 'USER',
       DATE_SUB(@now, INTERVAL 19 DAY), DATE_SUB(@now, INTERVAL 20 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedbloo-0000-0000-0000-000000000004', @user_id, @p2,
       DATE_SUB(@today, INTERVAL 4 DAY), '작은 봉오리를 봤어요', 'USER',
       NULL, DATE_SUB(@now, INTERVAL 4 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p2 IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 9. 장치 명령 이력
--
-- 급수량(dispensed_ml)은 상태 리포트와 일기의 "그날 급수량" 근거다. 상태를 골고루 섞어
-- 성공·실패·거부·타임아웃이 화면에 어떻게 보이는지 함께 확인한다.
-- ---------------------------------------------------------------------------
INSERT IGNORE INTO `device_command` (
    `request_id`, `plant_id`, `robot_id`, `device_uid`, `command_type`, `status`,
    `requested_ml`, `destination`, `run_seconds`, `dispensed_ml`,
    `error_message`, `result_message_id`, `issued_at`, `reported_at`, `created_at`, `updated_at`
)
SELECT 'seedcmd1-0000-0000-0000-000000000001', @p1, @robot1, 'demo-station-pi-01', 'WATER', 'OK',
       300.00, NULL, NULL, 288.50, NULL, 'seed-result-1',
       DATE_SUB(@now, INTERVAL 5 HOUR), DATE_SUB(@now, INTERVAL 299 MINUTE),
       DATE_SUB(@now, INTERVAL 5 HOUR), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedcmd2-0000-0000-0000-000000000002', @p1, @robot1, 'demo-station-pi-01', 'CAPTURE', 'OK',
       NULL, NULL, NULL, NULL, NULL, 'seed-result-2',
       DATE_SUB(@now, INTERVAL 9 HOUR), DATE_SUB(@now, INTERVAL 539 MINUTE),
       DATE_SUB(@now, INTERVAL 9 HOUR), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedcmd3-0000-0000-0000-000000000003', @p1, @robot1, 'demo-station-pi-01', 'FAN', 'OK',
       NULL, NULL, 120, NULL, NULL, 'seed-result-3',
       DATE_SUB(@now, INTERVAL 1 DAY), DATE_SUB(@now, INTERVAL 1439 MINUTE),
       DATE_SUB(@now, INTERVAL 1 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedcmd4-0000-0000-0000-000000000004', @p1, @robot1, 'demo-jetson-01', 'NAVIGATE', 'OK',
       NULL, 'SUNLIGHT', NULL, NULL, NULL, 'seed-result-4',
       DATE_SUB(@now, INTERVAL 27 HOUR), DATE_SUB(@now, INTERVAL 1619 MINUTE),
       DATE_SUB(@now, INTERVAL 27 HOUR), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedcmd5-0000-0000-0000-000000000005', @p1, @robot1, 'demo-station-pi-01', 'WATER', 'OK',
       250.00, NULL, NULL, 250.00, NULL, 'seed-result-5',
       DATE_SUB(@now, INTERVAL 3 DAY), DATE_SUB(@now, INTERVAL 4319 MINUTE),
       DATE_SUB(@now, INTERVAL 3 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedcmd6-0000-0000-0000-000000000006', @p1, @robot1, 'demo-station-pi-01', 'WATER', 'ERROR',
       300.00, NULL, NULL, 40.00, '펌프가 물을 끌어올리지 못했습니다.', 'seed-result-6',
       DATE_SUB(@now, INTERVAL 4 DAY), DATE_SUB(@now, INTERVAL 5759 MINUTE),
       DATE_SUB(@now, INTERVAL 4 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
SELECT 'seedcmd7-0000-0000-0000-000000000007', @p1, @robot1, 'demo-station-pi-01', 'CAPTURE', 'BUSY',
       NULL, NULL, NULL, NULL, NULL, 'seed-result-7',
       DATE_SUB(@now, INTERVAL 6 DAY), DATE_SUB(@now, INTERVAL 8639 MINUTE),
       DATE_SUB(@now, INTERVAL 6 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL
UNION ALL
-- 타임아웃은 회신이 없었다는 뜻이라 reported_at 이 NULL 이다.
SELECT 'seedcmd8-0000-0000-0000-000000000008', @p1, @robot1, 'demo-station-pi-01', 'CAPTURE', 'TIMED_OUT',
       NULL, NULL, NULL, NULL, NULL, NULL,
       DATE_SUB(@now, INTERVAL 7 DAY), NULL, DATE_SUB(@now, INTERVAL 7 DAY), @now
FROM (SELECT 1) AS `g` WHERE @p1 IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 10. 대표 사진
--
-- seed-demo-photos.sh 를 먼저 돌렸다면 그 사진 중 가장 최근 것을 대표로 지정한다.
-- 사진이 없으면 아무 일도 하지 않는다.
-- ---------------------------------------------------------------------------
UPDATE `plant` AS `p`
JOIN (
    SELECT `plant_id`, MAX(`photo_date`) AS `latest`
    FROM `plant_photo`
    WHERE `source` = 'DEVICE'
    GROUP BY `plant_id`
) AS `newest` ON `newest`.`plant_id` = `p`.`plant_id`
JOIN `plant_photo` AS `photo`
  ON `photo`.`plant_id` = `newest`.`plant_id`
 AND `photo`.`photo_date` = `newest`.`latest`
 AND `photo`.`source` = 'DEVICE'
SET `p`.`representative_photo_id` = `photo`.`photo_id`
WHERE `p`.`user_id` = @user_id
  AND `p`.`plant_status` = 'ACTIVE'
  AND `p`.`representative_photo_id` IS NULL;

-- ---------------------------------------------------------------------------
-- 결과 요약
-- ---------------------------------------------------------------------------
SELECT '센서 측정값' AS `표`, COUNT(*) AS `건수` FROM `sensor_reading` WHERE `device_message_id` LIKE 'seed-%'
UNION ALL SELECT '일일 광량', COUNT(*) FROM `plant_daily_light` WHERE `daily_light_id` LIKE 'seed%'
UNION ALL SELECT '알림', COUNT(*) FROM `alert` WHERE `alert_id` LIKE 'seed%'
UNION ALL SELECT '성장 일기', COUNT(*) FROM `plant_diary` WHERE `diary_id` LIKE 'seed%'
UNION ALL SELECT '개화 기록', COUNT(*) FROM `plant_bloom` WHERE `bloom_id` LIKE 'seed%'
UNION ALL SELECT '장치 명령', COUNT(*) FROM `device_command` WHERE `request_id` LIKE 'seed%'
UNION ALL SELECT '로봇', COUNT(*) FROM `robot` WHERE `robot_id` LIKE 'seed%'
UNION ALL SELECT '로봇 위치', COUNT(*) FROM `robot_location` WHERE `location_id` LIKE 'seed%'
UNION ALL SELECT '장치 사진(스크립트로 업로드)', COUNT(*) FROM `plant_photo` WHERE `source` = 'DEVICE';
