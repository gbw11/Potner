-- 로봇 LLM 대화 저장소다.
--
-- 이 테이블이 없으면 세 가지가 안 된다.
--   1. 백업 — 지금 이력은 젯슨 로컬 JSON(conversation_history.json) 하나뿐이라 기기를 다시
--      이미징하면 사라진다. 되살릴 방법이 없다.
--   2. 앱 조회 — 대화가 서버를 거치지 않으므로 앱이 볼 곳이 없다.
--   3. 기기 교체 — 로봇을 바꾸면 그 식물과 나눈 대화가 함께 사라진다.
--
-- 발화를 한 행씩 담는다. 세션당 JSON 한 덩어리로 담으면 앱이 최근 것만 꺼내 볼 수 없고
-- (전체를 읽어야 한다), 컬럼 하나가 끝없이 커진다. plant_diary 가 텍스트를 행으로 담는 것과
-- 같은 방침이다.
CREATE TABLE IF NOT EXISTS `robot_conversation` (
    `conversation_id` CHAR(36)     NOT NULL,

    -- 장치 토큰에서 서버가 정한다. 요청이 지정할 수 없다 — 토큰 하나로 남의 대화를 읽거나
    -- 덮어쓸 수 있으면 안 된다. device_sensor 최신값 조회와 같은 방침이다.
    `robot_id`        CHAR(36)     NOT NULL,

    -- 갈래를 만든 시점의 활성 배정에서 채운다. 앱이 식물 기준으로 조회하므로 조인 없이
    -- 끝내려고 비정규화한다(device_command.device_uid 와 같은 이유).
    --
    -- 배정이 바뀌어도 갱신하지 않는다. 지난 대화는 그때 그 식물과 나눈 것이므로 나중 배정으로
    -- 옮기면 사실이 바뀐다.
    `plant_id`        CHAR(36)     NULL,

    -- 젯슨 config 의 conversation.session_id 다. 한 기기 안에서 갈래를 나누는 이름이며
    -- 소유권 근거가 아니다. 폰 음성과 모니터 타이핑이 같은 값을 쓰면 한 갈래로 이어진다.
    `session_key`     VARCHAR(100) NOT NULL,

    `started_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT `pk_robot_conversation` PRIMARY KEY (`conversation_id`),

    -- 한 로봇의 한 session_key 는 하나의 갈래다. 재접속마다 새로 만들면 이력이 끊긴다.
    -- 첫 발화에서 갈래를 찾을 때도 이 인덱스를 탄다.
    CONSTRAINT `uq_robot_conversation_robot_session` UNIQUE (`robot_id`, `session_key`),

    -- 앱의 식물별 목록 조회용. 최근 대화가 먼저 나와야 하므로 updated_at 을 뒤에 둔다.
    INDEX `idx_robot_conversation_plant` (`plant_id`, `updated_at`),

    -- 식물을 지워도 대화는 남긴다. 사용자가 식물을 삭제한 것이 "로봇과 나눈 말을 지운다" 는
    -- 뜻은 아니고, 지우려면 대화 삭제라는 별도 행동이어야 한다.
    CONSTRAINT `fk_robot_conversation_plant`
        FOREIGN KEY (`plant_id`) REFERENCES `plant` (`plant_id`) ON DELETE SET NULL
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='로봇 LLM 대화 갈래';


-- 대화의 한 발화다. role·content 는 젯슨이 LLM 에 넘기는 형식 그대로라 변환이 필요 없다.
CREATE TABLE IF NOT EXISTS `robot_conversation_message` (
    `message_id`      CHAR(36)    NOT NULL,
    `conversation_id` CHAR(36)    NOT NULL,

    -- 갈래 안에서 0 부터 늘어나는 순서다. created_at 정렬로는 같은 초에 들어온 user/assistant
    -- 짝의 순서가 보장되지 않는다.
    --
    -- 앱 스크롤백의 커서로도 쓴다. page/size(alert 방식)를 쓰면 스크롤하는 동안 새 발화가
    -- 들어와 항목이 밀려 같은 발화가 두 번 보이거나 빠진다.
    `seq`             INT         NOT NULL,

    `role`            VARCHAR(16) NOT NULL COMMENT 'USER, ASSISTANT',

    -- 음성 질문은 300자 상한이지만 답변과 나중에 붙을 tool 결과를 담을 여유를 둔다.
    `content`         TEXT        NOT NULL,

    `created_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT `pk_robot_conversation_message` PRIMARY KEY (`message_id`),

    -- 순서 조회와 재전송 중복 방지를 한 인덱스로 겸한다. 네트워크가 끊겨 같은 턴을 다시
    -- 보내면 이 제약이 막는다. 별도 인덱스를 두지 않는다.
    CONSTRAINT `uq_robot_conversation_message_seq` UNIQUE (`conversation_id`, `seq`),

    -- 갈래를 지우면 발화도 함께 사라진다. 발화만 남으면 아무 의미가 없다.
    CONSTRAINT `fk_robot_conversation_message_conversation`
        FOREIGN KEY (`conversation_id`) REFERENCES `robot_conversation` (`conversation_id`)
        ON DELETE CASCADE
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_0900_ai_ci
  COMMENT='로봇 LLM 대화 발화';
