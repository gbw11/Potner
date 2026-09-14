-- 명령을 누가 시작했는지 남긴다. 자동 급수(수분 부족 알림 → 이동 → 급수 → 복귀)가 생기면서
-- 서버가 스스로 발행하는 명령이 생겼다.
--
-- 이 컬럼이 없으면 두 가지가 안 된다.
--   1. 체인 연결 — 자동 명령의 회신이 오면 다음 단계를 이어야 하는데, 사용자가 직접 누른
--      명령의 회신에 서버가 멋대로 후속 명령을 붙이면 안 된다.
--   2. 이력 구분 — 사용자가 명령 이력에서 "내가 안 시킨 급수" 를 봤을 때 자동인지 알 수 있어야
--      한다.
ALTER TABLE `device_command`
    ADD COLUMN `initiator` VARCHAR(10) NOT NULL DEFAULT 'USER'
        COMMENT 'USER: 앱에서 사용자가 발행, AUTO: 서버 자동 케어가 발행'
        AFTER `command_type`,
    ADD CONSTRAINT `ck_device_command_initiator`
        CHECK (`initiator` IN ('USER', 'AUTO'));
