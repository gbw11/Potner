CREATE TABLE IF NOT EXISTS `user_notification_setting` (
    `user_id`            CHAR(36)   NOT NULL,
    `all_enabled`        TINYINT(1) NOT NULL DEFAULT 1
        COMMENT '마스터 스위치. 끄면 세부 설정과 무관하게 발송하지 않는다',
    `push_enabled`       TINYINT(1) NOT NULL DEFAULT 1 COMMENT '기기 팝업 알림',
    `plant_care_enabled` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '센서 이상 및 케어 알림',
    `marketing_enabled`  TINYINT(1) NOT NULL DEFAULT 0
        COMMENT '이벤트 및 공지사항. 회원가입의 선택 동의 항목이라 기본값이 꺼짐',
    `created_at`         DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_user_notification_setting` PRIMARY KEY (`user_id`),
    CONSTRAINT `fk_user_notification_setting_user`
        FOREIGN KEY (`user_id`) REFERENCES `app_user` (`user_id`) ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='사용자 알림 수신 설정. 행이 없으면 전부 기본값으로 취급하므로 기존 사용자 backfill이 필요하지 않다';
