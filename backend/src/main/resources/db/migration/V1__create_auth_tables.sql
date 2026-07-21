CREATE TABLE IF NOT EXISTS `app_user` (
    `user_id`        CHAR(36)     NOT NULL COMMENT '사용자 UUID',
    `email`          VARCHAR(255) NOT NULL COMMENT '로그인 이메일',
    `nickname`       VARCHAR(50)  NOT NULL COMMENT '서비스 닉네임',
    `password_hash`  VARCHAR(255) NULL COMMENT '비밀번호 해시. 소셜 전용 계정은 NULL 가능',
    `account_status` VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, SUSPENDED, WITHDRAWN',
    `created_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `withdrawn_at`   DATETIME     NULL COMMENT '회원 탈퇴 일시',

    CONSTRAINT `pk_app_user` PRIMARY KEY (`user_id`),
    CONSTRAINT `uq_app_user_email` UNIQUE (`email`),
    CONSTRAINT `ck_app_user_account_status`
        CHECK (`account_status` IN ('ACTIVE', 'SUSPENDED', 'WITHDRAWN'))
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='사용자';

CREATE TABLE IF NOT EXISTS `refresh_token` (
    `refresh_token_id` CHAR(36)     NOT NULL,
    `user_id`          CHAR(36)     NOT NULL,
    `token_hash`       VARCHAR(255) NOT NULL COMMENT 'Refresh Token 원문이 아닌 해시',
    `device_id`        VARCHAR(100) NULL,
    `expires_at`       DATETIME     NOT NULL,
    `revoked_at`       DATETIME     NULL,
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT `pk_refresh_token` PRIMARY KEY (`refresh_token_id`),
    CONSTRAINT `uq_refresh_token_hash` UNIQUE (`token_hash`),
    CONSTRAINT `fk_refresh_token_user`
        FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE CASCADE,
    INDEX `idx_refresh_token_user_expiry` (`user_id`, `expires_at`)
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='자체 JWT Refresh Token';
