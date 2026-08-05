# Potner 자율 SLAM 탐색 설계

## 1. 목표

Jetson Orin Nano에서 ROS 2 Humble을 실행하고, ESP32 DevKit V4의 엔코더
오도메트리와 YDLIDAR X4 Pro의 2D 스캔을 결합해 실내 지도를 생성한다.
로봇은 사용자의 수동 조작 없이 미지 공간의 frontier를 찾아 이동한다.
탐색할 frontier가 더 이상 없으면 로봇을 정지시키고 지도를 자동 저장한다.

## 2. 범위

이번 작업에 포함되는 기능은 다음과 같다.

- OccupancyGrid 기반 frontier 검출과 군집화
- 안전하고 접근 가능한 frontier 목표 선정
- Nav2 `NavigateToPose`를 통한 자율 탐색
- 목표 실패, 시간 초과, 센서 중단에 대한 복구와 안전 정지
- 탐색 완료 판정
- Nav2 map saver를 통한 YAML/PGM 지도 저장
- slam_toolbox pose graph 직렬화
- ROS 비의존 핵심 로직의 단위 테스트
- Jetson 실기 검증 절차 문서화

ESP32의 모터 PID, 엔코더 디코딩, 시리얼 프로토콜은 기존 구현을 유지한다.
SLAM 알고리즘 자체를 새로 구현하거나 3D 지도를 생성하는 작업은 범위에
포함하지 않는다.

## 3. 시스템 아키텍처

데이터와 명령은 다음 순서로 흐른다.

```text
ESP32 엔코더 -> base_driver -> /odom -> slam_toolbox
YDLIDAR X4 Pro -------------> /scan -> slam_toolbox -> /map
                                                    |
                                                    v
                                        potner_exploration
                                                    |
                                                    v
                                           NavigateToPose
                                                    |
                                                    v
Nav2 -> /cmd_vel_nav -> twist_mux -> /cmd_vel -> base_driver -> ESP32
```

역할은 다음과 같이 분리한다.

- ESP32: 엔코더 쿼드러처 디코딩, 바퀴 속도 PID, PWM, 통신 워치독
- `potner_base/base_driver`: `/cmd_vel`을 바퀴 속도로 변환하고 `/odom` 및
  `odom -> base_footprint` TF 발행
- YDLIDAR 드라이버: `laser_frame` 기준 `/scan` 발행
- `slam_toolbox`: `/scan`과 TF를 사용해 `/map` 및 `map -> odom` 생성
- Nav2: frontier 목표까지 전역·지역 경로 계획과 장애물 회피 수행
- `twist_mux`: 안전, 수동, 도킹, Nav2 속도 명령의 우선순위 중재
- `potner_exploration`: frontier 선택, Nav2 목표 관리, 완료 판정, 지도 저장

탐색 노드는 모터를 직접 제어하지 않는다. 이동 목표는 Nav2에 전달하고,
종료 또는 오류 시에만 Nav2 목표를 취소하고 정지 명령을 발행한다.

TF 트리는 `map -> odom -> base_footprint -> base_link -> laser_frame`으로
단일화한다. 현재 `base_driver`가 `odom -> base_link`를 발행하는 부분은
`odom -> base_footprint`로 수정한다. `base_footprint -> base_link`는 기존
URDF의 고정 관절만 발행하게 해 `base_link`가 두 부모를 갖는 충돌을 없앤다.

## 4. 패키지와 컴포넌트

새 Python ROS 2 패키지 `src/potner_exploration`을 추가한다.

### 4.1 `frontier_detector.py`

ROS에 의존하지 않는 순수 파이썬 모듈이다.

- OccupancyGrid의 자유 셀 중 미탐색 셀과 인접한 셀을 frontier로 판정
- 8방향 연결성으로 frontier 셀 군집화
- 셀 수와 실제 길이를 기준으로 작은 군집 제거
- 장애물 및 미확인 공간으로부터 안전거리 확보
- 지도 셀 좌표와 월드 좌표 상호 변환
- frontier 크기, 이동 거리, 실패 이력을 이용한 목표 점수 계산
- 군집 중심에서 가장 가까운 안전한 자유 셀을 실제 목표로 선택

OccupancyGrid 값은 `-1`을 미탐색, `0`을 자유, 설정된 점유 임계값 이상의
값을 장애물로 처리한다. 중간 확률 셀은 목표 후보에서 제외한다.

### 4.2 `exploration_state.py`

ROS에 의존하지 않는 탐색 상태 머신과 시간·재시도 정책을 담는다. 상태는
다음과 같다.

- `WAITING_FOR_SYSTEM`
- `SELECTING_FRONTIER`
- `NAVIGATING`
- `RECOVERY`
- `VERIFYING_COMPLETE`
- `SAVING_MAP`
- `COMPLETED`
- `FAILED`

상태 전이 판단을 ROS 콜백과 분리해 CI에서 결정론적으로 검증한다.

### 4.3 `exploration_node.py`

ROS 2 입출력과 외부 서비스 연동을 담당한다.

- `/map`과 `/odom` 구독
- `/odom`은 최신성만 감시하고 지도 좌표의 로봇 자세는
  `map -> base_footprint` TF에서 조회
- Nav2 `NavigateToPose` 액션 서버 준비 확인
- Nav2 `ComputePathToPose` 액션으로 후보의 접근 가능성 사전 확인
- frontier 목표 전송, 피드백 감시, 취소, 결과 처리
- `/map`과 `/odom`의 최신성 감시
- Nav2 목표 취소 후 정지 명령 발행
- `map_saver_server/save_map` 서비스 호출
- `slam_toolbox/serialize_map` 서비스 호출
- 현재 상태, 선택한 목표, 실패 이유를 로그로 남김

### 4.4 launch와 설정

`potner_bringup/launch/exploration.launch.py`를 추가한다. 기존 로봇 본체,
SLAM, 공용 Nav2 navigation 서버, map saver, 탐색 노드를 한 번에 구성한다. RViz
실행 여부는 launch 인자로 선택한다.

기존 `nav2.launch.py`는 저장된 지도와 AMCL을 사용하는 운용 모드이므로
탐색 launch에서 포함하지 않는다. `navigation_core.launch.py`가 planner,
controller, smoother, behavior, BT navigator 서버를 실행한다. 속도 smoother는
최종 `cmd_vel` 우회 경로를 만들 수 있어 실행하지 않는다. 지도와
`map -> odom`은 slam_toolbox가 소유하며 map server와 AMCL은 실행하지 않는다.

Nav2 controller가 발행하는 `cmd_vel`은 `navigation_core.launch.py`에서
`cmd_vel_nav`로 remap한다. 탐색 launch와 저장 지도용 Nav2 launch 양쪽이
같은 공용 launch를 사용해 `twist_mux`를 우회하는 속도 명령이 없게 한다.

탐색 파라미터는 `potner_bringup/config/exploration.yaml`에서 관리한다.
주요 항목은 다음과 같다.

- frontier 최소 셀 수와 최소 실제 길이
- 장애물 안전거리
- 점유 임계값
- frontier 크기 및 이동 거리 점수 가중치
- 목표 도달 허용 거리
- 목표별 제한시간
- 실패 지점 차단 반경과 유지시간
- 완료 확인 대기시간
- 최대 연속 실패 횟수와 전체 탐색 제한시간
- 지도 출력 경로와 파일 이름
- 센서 및 TF 최신성 제한시간

## 5. 탐색 동작

### 5.1 시스템 준비

노드는 `/map`, `/odom`, Nav2 액션 서버가 모두 준비될 때까지
`WAITING_FOR_SYSTEM`에 머문다. 준비되지 않은 상태에서는 이동 목표나 속도
명령을 보내지 않는다.

### 5.2 frontier 선택

`SELECTING_FRONTIER`에서 현재 지도의 frontier를 군집화한다. 장애물
안전거리 안에 있는 목표는 제외하고, 남은 후보에 대해 Nav2
`ComputePathToPose`를 호출한다. 유효한 경로가 없는 후보는 제외한다. 남은
후보는 다음 개념식으로 평가한다.

```text
점수 = 정보 이득 가중치 * frontier 크기
     - 거리 가중치 * 로봇과의 거리
     - 실패 이력 페널티
```

가장 높은 점수의 안전한 셀을 목표로 선택한다. 같은 목표를 계속 왕복하지
않도록 최근 방문·실패 위치에는 일시적인 페널티를 적용한다.

### 5.3 이동과 재계획

`NAVIGATING`에서 Nav2에 목표를 전송한다. 이동 중에도 지도를 갱신한다.
현재 목표의 frontier가 사라지거나 목표가 장애물로 바뀌면 액션을 취소하고
다시 선택한다. 목표에 성공하면 짧게 지도 갱신을 기다린 뒤 다음 frontier를
선택한다.

목표가 거부되거나 실패하거나 제한시간을 초과하면 `RECOVERY`로 이동한다.
실패 지점 주변을 설정 시간 동안 차단하고 다른 frontier를 선택한다. 연속
실패 횟수가 설정 한도를 넘으면 안전하게 정지하고 `FAILED`로 끝낸다.

### 5.4 완료 판정

frontier 후보가 한 번 없다고 즉시 완료하지 않는다.
`VERIFYING_COMPLETE`에서 설정된 시간 동안 새 지도와 frontier를 다시
검사한다. 대기 중 유효한 frontier가 생기면 탐색을 재개한다. 전체 확인 시간
동안 유효 후보가 없을 때만 탐색 완료로 판정한다.

### 5.5 지도 저장

완료 판정 후 다음 순서를 지킨다.

1. 진행 중인 Nav2 목표 취소
2. `/cmd_vel_nav`에 0 속도를 여러 주기 발행
3. `map_saver_server/save_map` 호출로 `<경로>.yaml`과 `<경로>.pgm` 저장
4. `slam_toolbox/serialize_map` 호출로 pose graph 저장
5. 두 저장 결과를 로그로 기록

두 저장이 모두 성공하면 `COMPLETED`가 된다. 하나라도 실패하면 로봇은
정지 상태를 유지하고 `FAILED`로 전환한다. 기존 지도 파일을 암묵적으로
덮어쓰지 않도록 기본 파일 이름에 실행 시각을 포함한다.

## 6. 안전과 오류 처리

- `/map` 또는 `/odom` 갱신이 설정 시간 이상 끊기면 현재 목표를 취소하고
  정지한다.
- Nav2 액션 서버나 지도 저장 서비스가 준비되지 않으면 이동 또는 저장을
  시작하지 않고 명확한 오류를 기록한다.
- 전체 탐색 제한시간을 넘기면 목표를 취소하고 정지한 뒤 `FAILED`가 된다.
- 동일 영역의 반복 실패는 차단 반경과 만료시간으로 억제한다.
- 종료 신호와 예외 처리 경로에서도 목표 취소와 정지를 시도한다.
- 기존 `safety_node`의 `cmd_vel_safety`는 Nav2의 `cmd_vel_nav`보다 높은
  우선순위를 유지한다.
- ESP32의 500ms 워치독과 base_driver의 명령 제한시간을 그대로 유지한다.

## 7. 기본 주행 정책

첫 실기 검증은 높은 화분을 탑재한 소형 로봇에 맞춰 보수적으로 수행한다.

- 최대 선속도: 약 0.12m/s
- 최대 각속도: 약 0.5rad/s
- Nav2 inflation radius: 로봇 외접 반경보다 충분히 크게 설정
- 좁은 frontier와 작은 노이즈 군집 제외
- 목표별 시간 제한 및 연속 실패 제한 적용

정확한 로봇 반경, 바퀴 간 거리, LiDAR 높이는 조립 실측값을 사용한다.
특히 `wheel_separation`은 회전 오도메트리와 지도 정합에 직접 영향을 주므로
실기 탐색 전에 보정한다.

## 8. 테스트 전략

### 8.1 순수 단위 테스트

- 자유 공간과 미탐색 공간 경계 검출
- 8방향 frontier 군집화
- 최소 군집 크기 필터
- 장애물 안전거리 필터
- 지도 셀과 월드 좌표 변환
- 정보 이득과 거리 비용을 반영한 목표 선택
- 실패 지점 주변 후보 제외와 만료
- 빈 지도, 배열 크기 불일치, 0 이하 해상도 처리
- 각 상태의 정상 전이, 시간 초과, 재시도, 완료 확인
- 완료·실패·종료 상태에서 정지 요청 생성
- 지도 저장 성공과 실패 결과 처리

### 8.2 정적 설정 테스트

- launch에서 참조하는 설정 파일과 패키지 실행 파일 존재 확인
- 탐색 설정의 안전 관련 값이 유효 범위에 있는지 확인
- `twist_mux`에서 `cmd_vel_nav`가 안전 명령보다 낮은 우선순위인지 확인
- 프레임 이름이 URDF, SLAM, Nav2 설정과 일치하는지 확인
- TF의 동적 부모가 `odom -> base_footprint`이고 URDF가
  `base_footprint -> base_link`를 소유하는지 확인
- SLAM 탐색 launch가 AMCL과 map server를 실행하지 않는지 확인
- Nav2 controller의 `cmd_vel`이 실제로 `cmd_vel_nav`에 remap되고 속도
  smoother가 직접 `cmd_vel`을 발행하지 않는지 확인

### 8.3 Jetson 실기 검증

1. 바퀴를 공중에 띄우고 `/scan`, `/odom`, TF 트리를 확인한다.
2. 바닥의 좁고 통제된 구역에서 낮은 속도로 Nav2 단일 목표 이동을 확인한다.
3. 일부만 열린 공간에서 frontier 목표가 생성되는지 확인한다.
4. 목표 경로를 장애물로 막아 다른 frontier로 복구하는지 확인한다.
5. frontier가 없어졌을 때 확인 대기 후 자동 정지하는지 확인한다.
6. YAML, PGM, pose graph 파일이 모두 생성되는지 확인한다.
7. 저장한 지도를 RViz와 Nav2에서 다시 열어 벽과 로봇 위치가 정렬되는지
   확인한다.

## 9. 완료 조건

다음 조건을 모두 만족하면 구현을 완료한 것으로 본다.

- 한 명령으로 로봇, LiDAR, SLAM, Nav2, 탐색 노드를 실행할 수 있다.
- 로봇이 수동 조작 없이 유효한 frontier 목표를 연속 선택한다.
- 도달 불가능한 목표에서 무한 반복하지 않고 다른 목표로 복구한다.
- 지도 또는 오도메트리 중단 시 로봇이 정지한다.
- frontier 부재가 확인 시간 동안 유지되면 탐색을 종료한다.
- 종료 후 YAML/PGM 지도와 slam_toolbox pose graph가 자동 저장된다.
- ROS 비의존 테스트가 개발 PC의 CI에서 통과한다.
- Jetson 실기 절차와 실행·튜닝 방법이 문서에 포함된다.
