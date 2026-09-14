-- 서버가 장치에 보낸 명령 한 건의 수명이다. 발행(ISSUED)으로 태어나 장치의 회신(OK/ERROR/BUSY)
-- 이나 서버의 타임아웃 판정(TIMED_OUT)으로 끝난다.
--
-- 이 테이블이 없으면 세 가지가 안 된다.
--   1. 결과 대조 — 장치 회신의 requestId 가 어느 명령인지 찾을 곳이 없다.
--   2. 타임아웃 — 장치가 죽으면 회신이 영영 없으므로, 발행 시각을 근거로 서버가 끊어야 한다.
--   3. 급수량 집계 — 일기·상태 리포트의 "그날 급수량" 은 실제 급수(dispensed_ml)의 합이다.
--
-- 급수(WATER)와 촬영(CAPTURE)을 한 테이블에 둔다. 발행 → requestId 반향 → 회신 대조 → 타임아웃
-- 이라는 수명이 같아서, 나누면 같은 흐름을 두 벌 관리하게 된다. 명령별로 다른 것은 페이로드
-- (급수량)뿐이라 NULL 컬럼 하나로 감당된다.
CREATE TABLE IF NOT EXISTS `device_command` (
    -- 서버가 발급하고 장치가 회신에 그대로 되돌리는 값이다. 라즈베리 수신기가 requestId 반향을
    -- 이미 구현했으므로 이 값이 곧 매칭 열쇠다.
    `request_id`        CHAR(36)      NOT NULL,

    `plant_id`          CHAR(36)      NOT NULL,

    -- robot_id 와 device_uid 는 FK 를 걸지 않는다. 로봇 삭제는 이미 sensor_reading 이 RESTRICT
    -- 로 막고 있어 제약을 더해도 보호가 늘지 않고, 삭제 차단 지점만 하나 더 생긴다.
    `robot_id`          CHAR(36)      NOT NULL,

    -- 명령을 받은 장치의 device_uid 다. 회신 토픽의 세그먼트와 대조하는 데 쓰므로 iot_device 의
    -- PK 가 아니라 uid 문자열을 비정규화해 둔다. 조인 없이 대조가 끝난다.
    `device_uid`        VARCHAR(100)  NOT NULL,

    `command_type`      VARCHAR(10)   NOT NULL COMMENT 'WATER, CAPTURE',

    -- ISSUED 로 시작한다. OK/ERROR/BUSY 는 장치 회신의 status 그대로다. BUSY 는 장치가 앞선
    -- 작업 중이라 거부한 것으로, 실패와 구분해야 재시도 판단이 달라진다.
    `status`            VARCHAR(10)   NOT NULL DEFAULT 'ISSUED'
        COMMENT 'ISSUED, OK, ERROR, BUSY, TIMED_OUT',

    `requested_ml`      DECIMAL(10,2) NULL COMMENT 'WATER 만. 서버가 생육 기준에서 정한 급수량',

    -- 실제 급수량. 요청량과 다를 수 있다(펌프 상한 도달 등). 일기의 급수량은 이 값의 합이다.
    `dispensed_ml`      DECIMAL(10,2) NULL,

    `error_message`     VARCHAR(200)  NULL COMMENT '장치가 회신한 실패 사유. 진단용으로만 쓴다',

    -- 회신 메시지의 messageId 다. QoS 1 재전송으로 같은 회신이 두 번 와도 한 번만 반영한다.
    -- UNIQUE 가 아니라 행 안에 저장해 대조한다. 회신은 명령당 하나라 그걸로 충분하다.
    `result_message_id` VARCHAR(100)  NULL,

    `issued_at`         DATETIME      NOT NULL,
    `reported_at`       DATETIME      NULL COMMENT '회신을 반영한 시각. 타임아웃이면 NULL 그대로다',

    `created_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`        DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_device_command` PRIMARY KEY (`request_id`),

    -- 생성 컬럼이 없어 CASCADE 를 쓸 수 있다. 식물이 지워지면 그 명령 이력도 의미가 없다.
    CONSTRAINT `fk_device_command_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE CASCADE,
    CONSTRAINT `ck_device_command_type`
        CHECK (`command_type` IN ('WATER', 'CAPTURE')),
    CONSTRAINT `ck_device_command_status`
        CHECK (`status` IN ('ISSUED', 'OK', 'ERROR', 'BUSY', 'TIMED_OUT')),

    -- 식물별 명령 이력 조회(앱)와 그날 급수량 합계(일기)가 탄다.
    INDEX `idx_device_command_plant_issued` (`plant_id`, `issued_at` DESC),
    -- 타임아웃 스케줄러가 탄다. ISSUED 가 오래 남은 행만 훑는다.
    INDEX `idx_device_command_status_issued` (`status`, `issued_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='서버가 장치에 보낸 명령과 그 결과';
