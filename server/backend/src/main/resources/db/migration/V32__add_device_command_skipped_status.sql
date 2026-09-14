-- 장치 명령 상태에 SKIPPED 를 추가한다.
--
-- 라즈베리의 급수 명령 처리기(src/mqtt/water_command.py)는 과급수 가드에 걸리면 펌프를 돌리지
-- 않고 status=SKIPPED 로 회신한다. 1회 상한·급수 간격·24시간 예산 중 하나에 걸린 경우이며
-- dispensedMl 은 0 이다.
--
-- 서버가 이 값을 몰라 회신이 통째로 버려지고 있었다. DeviceCommandStatus.fromDeviceReport 가
-- 빈 값을 돌려주면 결과 반영이 조기 종료되어 명령이 ISSUED 로 남고, 체인을 잇는 이벤트도
-- 나가지 않는다. 급수 회차가 스테이션에서 멈추고 로봇은 120초 뒤 타임아웃까지 거기 서 있었다.
--
-- 과급수 가드는 오류 경로가 아니라 정상 기능이라 ERROR 로 옮겨 적을 수 없다. ERROR 는 체인을
-- 끊는 상태라서 같은 결과가 된다.
--
-- 기존 행에는 영향이 없다. 새 값을 허용 목록에 더하기만 하므로 되돌릴 때도 SKIPPED 행이
-- 없으면 그대로 되돌려진다.

-- CHECK 는 교체가 없어서 지우고 다시 만든다.
ALTER TABLE `device_command`
    DROP CHECK `ck_device_command_status`;

ALTER TABLE `device_command`
    ADD CONSTRAINT `ck_device_command_status`
        CHECK (`status` IN ('ISSUED', 'OK', 'ERROR', 'BUSY', 'SKIPPED', 'TIMED_OUT'));
