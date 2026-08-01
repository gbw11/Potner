-- 자동 명령이 어느 체인에 속하는지 남긴다.
--
-- 지금까지 자동 체인은 급수 하나뿐이라 "AUTO 이동(스테이션) OK → 급수" 로 판단해도 됐다.
-- 촬영과 말리기가 같은 스테이션 이동을 쓰기 시작하면 그 판단이 무너진다 — 촬영하러 도착한
-- 로봇에 급수 명령이 나간다.
--
-- 체인 상태를 따로 저장하지 않는다는 방침은 그대로다. 다음 단계는 여전히 회신된 명령의
-- 속성만으로 정해지며, 그 속성에 "무엇 때문에 갔는가" 가 하나 늘었을 뿐이다.
ALTER TABLE `device_command`
    ADD COLUMN `purpose` VARCHAR(20) NULL
        COMMENT '자동 명령의 이유. WATERING/CAPTURE/DRYING/RELOCATION. 사용자 명령은 NULL'
        AFTER `initiator`,
    ADD CONSTRAINT `ck_device_command_purpose`
        CHECK (`purpose` IS NULL
            OR `purpose` IN ('WATERING', 'CAPTURE', 'DRYING', 'RELOCATION')),
    -- 사용자 명령에 체인 목적이 붙으면 오케스트레이터가 사용자 조작을 자기 체인으로 착각한다.
    ADD CONSTRAINT `ck_device_command_purpose_requires_auto`
        CHECK (`purpose` IS NULL OR `initiator` = 'AUTO');
