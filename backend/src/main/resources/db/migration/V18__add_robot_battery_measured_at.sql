-- 배터리를 측정한 시각이다.
--
-- battery_percent 는 V3_5 부터 있었지만 채우는 경로가 없었고 시각 컬럼도 없었다. 값만 있으면
-- 사흘 전 78% 가 현재값처럼 보인다. 로봇이 꺼져 있어도 앱에 78% 가 계속 뜨므로, 값이 언제
-- 것인지 함께 알아야 오래된 값을 가려낼 수 있다.
--
-- 이력 테이블을 만들지 않는다. 화면에 필요한 것은 현재 잔량이고, 방전 곡선 분석은 시연 범위가
-- 아니다. 읽는 사람이 없는 이력은 쌓이기만 한다. robot.current_state 와 같은 판단이다.
ALTER TABLE `robot`
    ADD COLUMN `battery_measured_at` DATETIME NULL
        COMMENT '배터리 측정값을 받은 시각. 한 번도 받지 못했으면 NULL' AFTER `battery_percent`;
