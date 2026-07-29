-- 로봇의 현재 행동 상태다. 젯슨이 potner/device/{uid}/status/state 로 보낸다.
--
-- 이력 테이블을 만들지 않는다. 지금 이 값을 읽는 곳은 표정 판정과 (앞으로) 급수 작업이고
-- 둘 다 현재 상태만 필요하다. 급수 작업은 자기 단계와 시각을 자기 테이블에 남기므로 상태
-- 이력을 뒤지지 않는다. 읽는 사람이 없는 이력은 쌓이기만 한다. 일기가 "오늘 사용자를
-- 반겼다" 같은 문장을 쓰려면 그때 필요한 형태로 추가한다.
--
-- iot_device 가 아니라 robot 에 둔다. 상태를 보내는 것은 젯슨이지만 이동하고 급수를 받는
-- 주체는 로봇이다. battery_percent 가 이미 robot 에 있는 것과 같은 이유다.
ALTER TABLE `robot`
    -- 기본값이 IDLE 이다. 상태를 한 번도 받지 못한 로봇은 대기 중으로 보는 것이 맞고,
    -- NULL 을 허용하면 읽는 쪽마다 NULL 처리를 해야 한다.
    ADD COLUMN `current_state` VARCHAR(20) NOT NULL DEFAULT 'IDLE'
        COMMENT 'IDLE, NAVIGATING, DOCKING, SERVICING, GREETING' AFTER `battery_percent`,

    -- 상태가 바뀐 시각이다. 같은 상태가 계속 오는 동안에는 갱신하지 않는다. 젯슨이 상태를
    -- 주기적으로 반복 발행하므로 매번 갱신하면 '언제부터 이 상태인지'를 알 수 없게 된다.
    ADD COLUMN `state_changed_at` DATETIME NULL
        COMMENT '현재 상태로 바뀐 시각. 상태를 아직 받지 못했으면 NULL' AFTER `current_state`,

    ADD CONSTRAINT `ck_robot_current_state`
        CHECK (`current_state` IN ('IDLE', 'NAVIGATING', 'DOCKING', 'SERVICING', 'GREETING'));
