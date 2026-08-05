# Potner 자동 탐색 센서 stale 복구 설계

## 1. 문제와 목표

자동 탐색 중 `/scan`, `/map`, `/odom`, TF 발행이 외부 진단에서는 정상인데 탐색 노드가
`sensor data became stale`로 영구 `FAILED`가 되었다. 현재 탐색 노드는 센서 freshness가
한 번이라도 거짓이면 즉시 목표를 취소하고 종료한다. 또한 큰 지도에서 frontier와 장애물
거리 계산을 타이머 콜백 안에서 반복하므로 같은 실행기의 `/odom` 콜백 처리가 늦어져
스스로 stale 조건을 만들 수 있다.

목표는 일시적인 처리 지연이나 짧은 센서 공백에는 안전하게 정지한 뒤 자동 복구하고,
실제 센서 중단이 계속될 때만 탐색을 실패로 종료하는 것이다. 센서 중단 중에는 어떤 경우에도
주행 명령을 유지하지 않는다.

## 2. 선택한 접근

다음 세 가지를 함께 적용한다.

1. freshness 원인을 `/map`, `/odom`, TF로 분리해 각 데이터 나이와 판정 결과를 제공한다.
2. 일시적인 stale은 안전 정지와 목표 취소 후 복구 대기 상태로 전환하고, 연속 중단 시간이
   제한을 넘을 때만 `FAILED`로 전환한다.
3. frontier 재검증은 새 지도 revision마다 한 번만 수행하고, 목표 안전성 검사는 목표 주변의
   제한된 영역만 확인한다.
4. 전체 frontier 분석은 별도 작업 스레드에서 수행해 ROS 센서 콜백 실행기를 막지 않는다.

timeout만 늘리는 방법은 계산 지연을 숨길 뿐이므로 단독 해결책으로 사용하지 않는다.
멀티스레드 실행기는 공유 상태와 액션 콜백 동기화를 복잡하게 하므로 이번 범위에서 제외한다.

## 3. 상태와 안전 동작

기존 상태 머신에 `WAITING_FOR_SENSORS`를 추가한다.

```text
NAVIGATING/SELECTING_FRONTIER/VERIFYING_COMPLETE
                    |
                    | 센서 stale
                    v
          WAITING_FOR_SENSORS -- 센서 복구 --> SELECTING_FRONTIER
                    |
                    | 연속 stale 제한시간 초과
                    v
                  FAILED
```

`WAITING_FOR_SENSORS` 진입 시 다음 동작을 한 번 요청한다.

- 진행 중인 Nav2 목표와 경로 검사를 취소한다.
- `/cmd_vel_nav`에 0 속도를 여러 번 발행한다.
- 기존 navigation 목표를 폐기한다.
- 지도 저장이나 탐색 완료 판정을 수행하지 않는다.

대기 중에도 정지 명령을 주기적으로 다시 발행한다. `/map`, `/odom`, TF가 모두 정상으로
돌아오면 이전 목표를 이어서 사용하지 않고 `SELECTING_FRONTIER`에서 현재 지도 기준으로 새
목표를 선택한다. stale이 기본 10초 동안 연속되면 `FAILED`가 된다. 실제 모터 정지는 기존
`twist_mux`, base driver 명령 timeout, ESP32 watchdog과 함께 다중으로 보장한다.

## 4. freshness 판정과 진단

ROS 비의존 순수 모델이 다음 입력으로 freshness 결과를 만든다.

- 현재 시각
- 마지막 `/map` 수신 시각과 `map_timeout_s`
- 마지막 `/odom` 수신 시각과 `odom_timeout_s`
- 최신 `map -> base_footprint` TF 시각과 `tf_timeout_s`
- TF lookup 성공 여부

결과에는 전체 정상 여부, 각 데이터의 나이, stale 원인 목록이 포함된다. 미래 시각 허용 범위는
기존과 동일하게 0.5초로 제한한다. 로그는 예를 들어 다음처럼 원인을 직접 보여준다.

```text
sensor stale: odom(age=2.14s limit=2.00s); map(age=1.72s); tf(age=0.04s)
```

복구 대기 중 같은 원인을 매 타이머마다 출력하지 않고 throttle하여 로그 폭주를 막는다.

## 5. frontier 계산 최적화

내용이 달라진 `/map`마다 증가하는 `map_revision`을 둔다. 동일한 지도 재발행은 새 revision으로
세지 않는다. NAVIGATING 상태의 현재 목표 유효성 검사는 동일 revision에서 반복하지 않으며,
새 지도가 들어왔을 때 목표 주변의 자유 셀, 장애물 안전거리, 인접 미지 공간만 다시 확인한다.

전체 frontier 탐색은 단일 작업 스레드에서 실행하고 ROS 타이머는 완료 결과만 poll한다.
장애물 거리장은 `clearance_m` 밖의 불필요한 먼 거리까지 확장하지 않는다. 분석 도중 새 지도가
들어오면 빈 결과는 버리고 최신 revision을 다시 요청한다. 이전 revision에서 찾은 frontier가
있으면 Nav2 경로 검사와 최신 지도 국소 안전성 검사를 모두 통과한 경우에만 목표로 사용한다.
외부에서 사용하는 `find_frontiers()` API는 유지한다.

## 6. 설정

`exploration.yaml`에 다음 값을 추가한다.

```yaml
sensor_recovery_timeout_s: 10.0
sensor_stop_publish_period_s: 0.5
```

기존 `map_timeout_s: 6.0`, `odom_timeout_s: 2.0`, `tf_timeout_s: 1.0`은 실제 센서 품질을
감시하는 값으로 유지한다. 복구 제한시간은 개별 freshness timeout이 지난 뒤부터 연속으로
측정한다.

## 7. 테스트

ROS 비의존 단위 테스트로 다음을 먼저 실패하게 만든 뒤 구현한다.

- 개별 `/map`, `/odom`, TF stale 원인과 나이를 정확히 구분한다.
- 한 번의 stale은 `WAITING_FOR_SENSORS`와 정지·취소 결정을 만든다.
- 제한시간 안에 센서가 복구되면 `SELECTING_FRONTIER`로 돌아간다.
- 제한시간을 넘긴 연속 stale은 `FAILED`가 된다.
- 복구 후 과거 navigation 목표를 재사용하지 않는다.
- 동일 지도 revision에서 navigation 목표 검증을 반복하지 않는다.
- navigation 목표 검증이 전체 지도 분석 대신 국소 검사를 사용한다.
- 전체 frontier 분석 요청이 호출자를 막지 않고 worker 결과에 revision을 보존한다.
- 장애물 거리 계산이 `clearance_m` 밖으로 확장되지 않는다.
- 새 설정값이 양수이고 기존 timeout보다 긴지 정적 설정 테스트로 확인한다.

기존 상태 전이, frontier 검출, 액션 결과, 지도 저장 테스트 전체도 함께 실행해 회귀가 없는지
확인한다.

## 8. Jetson 검증 기준

1. 자동 탐색 중 `/odom` 또는 TF를 2~5초 일시 중단하면 로봇이 즉시 정지한다.
2. 데이터가 10초 안에 복구되면 탐색이 새 frontier 선택부터 자동 재개된다.
3. 10초 넘게 중단하면 정지 상태를 유지한 채 `FAILED`가 된다.
4. 큰 지도로 확장되어도 정상 `/odom` 25Hz 이상에서 false stale이 재현되지 않는다.
5. 로그만으로 어떤 입력이 몇 초 동안 stale이었는지 확인할 수 있다.
6. 정상 탐색 완료 후 YAML, PGM, pose graph 저장 동작은 기존과 동일하다.

## 9. 범위 밖 항목

- ESP32 펌웨어나 USB 드라이버 변경
- Nav2 Behavior Tree 교체
- ROS 2 멀티스레드 실행기로의 전환
- SLAM Toolbox 또는 YDLIDAR 드라이버 수정

실제 USB disconnect가 관측되면 이 복구 로직은 안전 정지까지만 담당하고, 하드웨어 전원과
배선 문제는 별도로 해결한다.
