-- 기기별 FCM 등록 토큰이다.
--
-- installation_id 를 PK 로 둔 것이 이 테이블의 핵심이다. 앱은 세션이 만료되어 강제 로그아웃될 때
-- 서버 등록을 지우지 않는다. 이미 죽은 Access Token 으로 DELETE 를 부를 수 없기 때문이며
-- 클라이언트로서는 옳은 선택이다. 그래서 사용자 A 의 행이 남은 채 같은 기기에서 B 가 로그인할 수 있다.
-- (user_id, installation_id) 를 유일 키로 잡으면 두 행이 공존해 A 의 알림이 B 의 기기로 간다.
-- 설치 ID 단독 유일이면 B 의 등록이 A 의 행을 대체하므로 스키마가 이 상황을 막는다.
--
-- 토큰이 아니라 설치 ID 를 키로 쓰는 이유는 FCM 토큰이 회전하기 때문이다. 토큰을 키로 두면
-- 회전할 때마다 새 행이 생기고 옛 행이 고아로 남는다. 설치 ID 는 앱 설치당 안정적이다.
--
-- 생성 컬럼이 없으므로 CASCADE 참조 동작을 쓸 수 있다. alert 나 plant_device_assignment 가
-- RESTRICT 여야 했던 MySQL 제약에 걸리지 않는다.

CREATE TABLE IF NOT EXISTS `fcm_token` (
    `installation_id` VARCHAR(128) NOT NULL
        COMMENT 'Firebase 설치 ID. 앱 설치당 안정적이며 토큰과 달리 회전하지 않는다',
    `user_id`         CHAR(36)     NOT NULL,
    `token`           VARCHAR(512) NOT NULL COMMENT 'FCM 등록 토큰. 회전한다',
    `platform`        VARCHAR(20)  NOT NULL,
    `active`          TINYINT(1)   NOT NULL DEFAULT 1
        COMMENT '무효 토큰 비활성 처리용. 발송 대상 조회가 이 값을 본다',
    `last_seen_at`    DATETIME     NOT NULL COMMENT '앱이 마지막으로 등록을 확인한 시각',
    `created_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_fcm_token` PRIMARY KEY (`installation_id`),
    CONSTRAINT `fk_fcm_token_user`
        FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE CASCADE,
    -- 앱은 android/iOS 가 아니면 등록 자체를 건너뛰므로 그 밖의 값은 도달하지 않는다.
    CONSTRAINT `ck_fcm_token_platform` CHECK (`platform` IN ('ANDROID', 'IOS')),

    -- 발송 대상 조회가 사용자별 활성 토큰을 훑는다.
    INDEX `idx_fcm_token_user_active` (`user_id`, `active`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='기기별 FCM 등록 토큰. 설치 ID 가 PK 라 한 기기가 두 사용자에게 동시에 등록되지 않는다';
