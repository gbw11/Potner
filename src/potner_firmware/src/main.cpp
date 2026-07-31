/*
 * Potner 저수준 모터 컨트롤러 (ESP32 DevKit V1 / DevKitC V4 공용)
 *
 * 젯슨이 못 하는 일만 여기서 합니다.
 *   - 엔코더 쿼드러처 디코딩: 초당 약 2,930카운트 x 2. 리눅스 파이썬으로는
 *     카운트를 흘립니다. ESP32의 PCNT는 하드웨어라 CPU 개입 없이 셉니다.
 *   - 바퀴 속도 PID: 주기가 흔들리면 안 되는 제어 루프.
 *   - 20kHz PWM: 가청 대역 밖이라 모터가 조용합니다. PCA9685는 1.5kHz가
 *     한계라 낑낑 소리가 납니다.
 *
 * 젯슨과는 USB 시리얼 한 줄로만 대화합니다. 프로토콜 정의는
 * src/potner_base/potner_base/serial_protocol.py 와 반드시 일치해야 합니다.
 */

#include <Arduino.h>
#include <ESP32Encoder.h>

// ===== 핀 배치 (V1 30핀 · V4 38핀 모두 존재하는 핀만 사용) ================
// GPIO 34~39는 입력 전용이라 엔코더에 딱 맞습니다. 출력 가능한 핀을 아낍니다.
// GPIO 0, 2, 12, 15는 부팅 스트래핑 핀이라 절대 쓰지 마세요.
// ★ V4 실크스크린에는 36·39 가 SENSOR_VP·SENSOR_VN 으로 인쇄돼 있습니다.
//   배선할 때 GPIO 번호가 안 보인다고 다른 핀에 꽂지 마세요.
constexpr int PIN_L_RPWM = 25;
constexpr int PIN_L_LPWM = 26;
constexpr int PIN_R_RPWM = 32;
constexpr int PIN_R_LPWM = 33;

constexpr int PIN_L_ENC_A = 34;
constexpr int PIN_L_ENC_B = 35;
constexpr int PIN_R_ENC_A = 36;
constexpr int PIN_R_ENC_B = 39;

// ===== 하드웨어 상수 =======================================================
// ★ 이 셋은 potner_base.kinematics.DriveConfig 와 같아야 합니다.
//   펌웨어는 카운트를 m/s 로 바꿔 PID를 돌리고, 젯슨은 같은 카운트로
//   오도메트리를 만듭니다. 값이 어긋나면 "명령한 속도"와 "실제 속도"와
//   "지도상 이동거리"가 셋 다 달라져서 원인을 찾기 어렵습니다.
//   tests/test_firmware_constants.py 가 대조합니다.
constexpr float COUNTS_PER_REV   = 1440.0f;  // FIT0403 출력축 (확정값)
constexpr float WHEEL_DIAMETER_M = 0.075f;   // 확정 — 75mm 구동 바퀴
constexpr float MAX_WHEEL_MPS    = 0.25f;

// 브링업용 개루프 듀티 상한. PID를 거치지 않는 경로라 낮게 묶어둡니다.
constexpr float MAX_OPEN_DUTY = 0.5f;

constexpr int PWM_FREQ_HZ   = 20000;  // 가청 대역 밖
constexpr int PWM_RESOLUTION = 10;    // 0~1023
constexpr int PWM_MAX = (1 << PWM_RESOLUTION) - 1;

constexpr int CH_L_R = 0;  // LEDC 채널
constexpr int CH_L_L = 1;
constexpr int CH_R_R = 2;
constexpr int CH_R_L = 3;

// ===== 제어 주기 ===========================================================
constexpr uint32_t CONTROL_PERIOD_MS  = 10;   // 100Hz PID
constexpr uint32_t FEEDBACK_PERIOD_MS = 33;   // 약 30Hz 로 젯슨에 보고
constexpr uint32_t WATCHDOG_TIMEOUT_MS = 500; // 젯슨이 조용하면 멈춤

// ===== PID 게인 ============================================================
// TODO: 실기에서 튜닝. 바퀴가 떨리면 kp를 낮추고, 목표 속도에 못 미치면
//       ki를 조금씩 올리세요. kd는 대개 0으로 두어도 됩니다.
constexpr float KP = 800.0f;
constexpr float KI = 1200.0f;
constexpr float KD = 0.0f;

ESP32Encoder encLeft;
ESP32Encoder encRight;

struct Wheel {
  float target_mps = 0.0f;
  float measured_mps = 0.0f;
  float integral = 0.0f;
  float prev_error = 0.0f;
  int64_t prev_count = 0;
};

Wheel wheelL;
Wheel wheelR;

// 개루프는 브링업 전용입니다.
//
// 폐루프에서 엔코더가 안 읽히면 오차가 줄지 않아 적분항이 상한까지 차고,
// 출력이 곧 100% 듀티가 됩니다. 즉 **엔코더 배선을 틀리면 바퀴가 전속으로
// 돕니다.** 배선을 처음 맞출 때 이건 위험하므로, 엔코더와 무관하게 정해진
// 듀티만 내보내는 경로를 따로 둡니다.
enum ControlMode { MODE_CLOSED_LOOP, MODE_OPEN_LOOP };

ControlMode mode = MODE_CLOSED_LOOP;
float openDutyL = 0.0f;
float openDutyR = 0.0f;

uint32_t lastControlMs = 0;
uint32_t lastFeedbackMs = 0;
uint32_t lastCommandMs = 0;
String rxBuffer;

// --------------------------------------------------------------------------
uint8_t xorChecksum(const String &payload) {
  uint8_t value = 0;
  for (size_t i = 0; i < payload.length(); ++i) value ^= (uint8_t)payload[i];
  return value;
}

void applyPwm(int chForward, int chReverse, float duty) {
  duty = constrain(duty, -1.0f, 1.0f);
  int magnitude = (int)(fabs(duty) * PWM_MAX);

  if (duty > 0.001f) {
    ledcWrite(chForward, magnitude);
    ledcWrite(chReverse, 0);
  } else if (duty < -0.001f) {
    ledcWrite(chForward, 0);
    ledcWrite(chReverse, magnitude);
  } else {
    ledcWrite(chForward, 0);
    ledcWrite(chReverse, 0);
  }
}

float countsToMeters(int64_t counts) {
  return (counts / COUNTS_PER_REV) * PI * WHEEL_DIAMETER_M;
}

/* 엔코더로 실제 속도를 갱신합니다. 개루프에서도 호출합니다 — 바퀴가 도는데
 * 카운트가 안 변하면 엔코더 배선 문제라는 걸 피드백으로 볼 수 있어야 합니다. */
void updateMeasurement(Wheel &wheel, int64_t count, float dt) {
  float travelled = countsToMeters(count - wheel.prev_count);
  wheel.prev_count = count;
  wheel.measured_mps = travelled / dt;
}

float pidOutput(Wheel &wheel, float dt) {
  float error = wheel.target_mps - wheel.measured_mps;
  wheel.integral += error * dt;

  // 적분 와인드업 방지. 바퀴가 걸려 못 움직일 때 적분항이 무한히 커져서
  // 장애물이 치워지는 순간 로봇이 튀어나가는 걸 막습니다.
  wheel.integral = constrain(wheel.integral, -1.0f, 1.0f);

  float derivative = (error - wheel.prev_error) / dt;
  wheel.prev_error = error;

  float output = KP * error + KI * wheel.integral + KD * derivative;
  return constrain(output / 1000.0f, -1.0f, 1.0f);
}

void stopAll() {
  wheelL.target_mps = 0.0f;
  wheelR.target_mps = 0.0f;
  wheelL.integral = 0.0f;
  wheelR.integral = 0.0f;
  openDutyL = 0.0f;
  openDutyR = 0.0f;
  // 개루프로 시험하다 손을 놓으면 폐루프로 돌아옵니다. 시험 모드가 남아
  // 있는 채로 주행에 들어가면 PID 없이 굴러가게 됩니다.
  mode = MODE_CLOSED_LOOP;
  applyPwm(CH_L_R, CH_L_L, 0.0f);
  applyPwm(CH_R_R, CH_R_L, 0.0f);
}

// --------------------------------------------------------------------------
void handleLine(const String &line) {
  int star = line.lastIndexOf('*');
  if (star < 0) return;

  String payload = line.substring(0, star);
  uint8_t received = (uint8_t)strtol(line.substring(star + 1).c_str(), nullptr, 16);
  if (received != xorChecksum(payload)) return;  // 깨진 프레임은 조용히 버림

  if (payload.startsWith("S")) {
    stopAll();
    lastCommandMs = millis();
    return;
  }

  // 두 명령 모두 "L,R" 두 값을 받습니다. 파싱은 같고 해석만 다릅니다.
  bool isVelocity = payload.startsWith("V,");
  bool isDuty = payload.startsWith("D,");
  if (!isVelocity && !isDuty) return;

  int firstComma = payload.indexOf(',');
  int secondComma = payload.indexOf(',', firstComma + 1);
  if (secondComma < 0) return;

  float left = payload.substring(firstComma + 1, secondComma).toFloat();
  float right = payload.substring(secondComma + 1).toFloat();

  if (isVelocity) {
    mode = MODE_CLOSED_LOOP;
    wheelL.target_mps = constrain(left, -MAX_WHEEL_MPS, MAX_WHEEL_MPS);
    wheelR.target_mps = constrain(right, -MAX_WHEEL_MPS, MAX_WHEEL_MPS);
  } else {
    // 브링업 전용. 듀티를 그대로 내보내므로 엔코더가 없어도 바퀴가 돕니다.
    mode = MODE_OPEN_LOOP;
    openDutyL = constrain(left, -MAX_OPEN_DUTY, MAX_OPEN_DUTY);
    openDutyR = constrain(right, -MAX_OPEN_DUTY, MAX_OPEN_DUTY);
  }
  lastCommandMs = millis();
}

void readSerial() {
  while (Serial.available()) {
    char c = (char)Serial.read();
    if (c == '\n') {
      handleLine(rxBuffer);
      rxBuffer = "";
    } else if (c != '\r' && rxBuffer.length() < 96) {
      rxBuffer += c;
    }
  }
}

void sendFeedback() {
  // TODO: 범퍼 ToF(VL53L0X)를 달면 실제 거리로 교체. 지금은 최대값 고정.
  int leftBumperMm = 1200;
  int rightBumperMm = 1200;

  String payload = "O," + String((long)encLeft.getCount()) +
                   "," + String((long)encRight.getCount()) +
                   "," + String(leftBumperMm) +
                   "," + String(rightBumperMm) +
                   "," + String(millis());

  char checksum[8];
  snprintf(checksum, sizeof(checksum), "*%02X", xorChecksum(payload));
  Serial.println(payload + checksum);
}

// --------------------------------------------------------------------------
void setup() {
  Serial.begin(115200);

  ledcSetup(CH_L_R, PWM_FREQ_HZ, PWM_RESOLUTION);
  ledcSetup(CH_L_L, PWM_FREQ_HZ, PWM_RESOLUTION);
  ledcSetup(CH_R_R, PWM_FREQ_HZ, PWM_RESOLUTION);
  ledcSetup(CH_R_L, PWM_FREQ_HZ, PWM_RESOLUTION);

  ledcAttachPin(PIN_L_RPWM, CH_L_R);
  ledcAttachPin(PIN_L_LPWM, CH_L_L);
  ledcAttachPin(PIN_R_RPWM, CH_R_R);
  ledcAttachPin(PIN_R_LPWM, CH_R_L);

  // GPIO 34~39에는 내부 풀업이 없습니다. 레벨 시프터 쪽에서 확실히
  // 구동해 주지 않으면 값이 떠서 카운트가 튑니다.
  encLeft.attachFullQuad(PIN_L_ENC_A, PIN_L_ENC_B);
  encRight.attachFullQuad(PIN_R_ENC_A, PIN_R_ENC_B);
  encLeft.clearCount();
  encRight.clearCount();

  stopAll();
  lastCommandMs = millis();
}

void loop() {
  readSerial();

  uint32_t now = millis();

  // 워치독: 젯슨이 죽거나 USB가 빠져도 로봇이 계속 달리면 안 됩니다.
  if (now - lastCommandMs > WATCHDOG_TIMEOUT_MS) {
    stopAll();
  }

  if (now - lastControlMs >= CONTROL_PERIOD_MS) {
    float dt = (now - lastControlMs) / 1000.0f;
    lastControlMs = now;

    updateMeasurement(wheelL, encLeft.getCount(), dt);
    updateMeasurement(wheelR, encRight.getCount(), dt);

    if (mode == MODE_OPEN_LOOP) {
      applyPwm(CH_L_R, CH_L_L, openDutyL);
      applyPwm(CH_R_R, CH_R_L, openDutyR);
    } else {
      applyPwm(CH_L_R, CH_L_L, pidOutput(wheelL, dt));
      applyPwm(CH_R_R, CH_R_L, pidOutput(wheelR, dt));
    }
  }

  if (now - lastFeedbackMs >= FEEDBACK_PERIOD_MS) {
    lastFeedbackMs = now;
    sendFeedback();
  }
}
