/*
 * Potner 저수준 모터 컨트롤러 (ESP32 DevKit V1)
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

// ===== 핀 배치 (ESP32 DevKit V1 30핀) =====================================
// GPIO 34~39는 입력 전용이라 엔코더에 딱 맞습니다. 출력 가능한 핀을 아낍니다.
// GPIO 0, 2, 12, 15는 부팅 스트래핑 핀이라 절대 쓰지 마세요.
constexpr int PIN_L_RPWM = 25;
constexpr int PIN_L_LPWM = 26;
constexpr int PIN_R_RPWM = 32;
constexpr int PIN_R_LPWM = 33;

constexpr int PIN_L_ENC_A = 34;
constexpr int PIN_L_ENC_B = 35;
constexpr int PIN_R_ENC_A = 36;
constexpr int PIN_R_ENC_B = 39;

// ===== 하드웨어 상수 =======================================================
// FIT0403은 출력축 1회전당 1440 pulse이고 attachFullQuad()는
// 각 pulse의 네 edge를 모두 세므로 소프트웨어 count는 5760입니다.
constexpr float COUNTS_PER_REV   = 5760.0f;
constexpr float WHEEL_DIAMETER_M = 0.060f;   // 확정 — 60mm 구동 바퀴
constexpr float MAX_WHEEL_MPS    = 0.25f;

// 로봇 전진 방향으로 각 바퀴를 손으로 돌려 측정한 하드웨어 부호입니다.
// 누적 카운트는 장시간 운전해도 정밀도가 떨어지지 않도록 정수로 보정합니다.
constexpr int64_t LEFT_ENCODER_SIGN  = -1;
constexpr int64_t RIGHT_ENCODER_SIGN = +1;

// 정규화한 ROS 바퀴 속도를 실제 BTS7960 출력 방향으로 변환합니다.
constexpr float LEFT_MOTOR_OUTPUT_SIGN  = +1.0f;
constexpr float RIGHT_MOTOR_OUTPUT_SIGN = -1.0f;

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

// 0.03m/s 실기 시험에서 약 4초 뒤 PID duty가 0.16 부근에 도달했을 때
// 바퀴가 움직이기 시작했습니다. 비영점 목표에만 정지 마찰 보상을 더합니다.
constexpr float MIN_DRIVE_DUTY = 0.16f;

ESP32Encoder encLeft;
ESP32Encoder encRight;

struct Wheel {
  float target_mps = 0.0f;
  float previous_target_mps = 0.0f;
  float measured_mps = 0.0f;
  float integral = 0.0f;
  float prev_error = 0.0f;
  int64_t prev_count = 0;
};

Wheel wheelL;
Wheel wheelR;

uint32_t lastControlMs = 0;
uint32_t lastFeedbackMs = 0;
uint32_t lastCommandMs = 0;
String rxBuffer;
bool driveEnabled = false;

// --------------------------------------------------------------------------
uint8_t xorChecksum(const String &payload) {
  uint8_t value = 0;
  for (size_t i = 0; i < payload.length(); ++i) value ^= (uint8_t)payload[i];
  return value;
}

bool parseFiniteFloat(const String &text, float &value) {
  const char *start = text.c_str();
  char *end = nullptr;
  float parsed = strtof(start, &end);

  if (start == end || *end != '\0' || !isfinite(parsed)) return false;
  value = parsed;
  return true;
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

int64_t normalizedLeftCount() {
  return LEFT_ENCODER_SIGN * encLeft.getCount();
}

int64_t normalizedRightCount() {
  return RIGHT_ENCODER_SIGN * encRight.getCount();
}

float minimumDriveFeedforward(float targetMps) {
  if (targetMps > 0.0001f) return MIN_DRIVE_DUTY;
  if (targetMps < -0.0001f) return -MIN_DRIVE_DUTY;
  return 0.0f;
}

float updatePid(Wheel &wheel, int64_t count, float dt) {
  float travelled = countsToMeters(count - wheel.prev_count);
  wheel.prev_count = count;
  wheel.measured_mps = travelled / dt;

  bool directionChanged =
      wheel.target_mps * wheel.previous_target_mps < 0.0f;
  if (directionChanged) {
    wheel.integral = 0.0f;
    wheel.prev_error = 0.0f;
  }
  wheel.previous_target_mps = wheel.target_mps;

  // 한쪽 바퀴만 0인 회전 명령에서도 이전 적분값이 PWM을 만들면 안 됩니다.
  if (fabs(wheel.target_mps) <= 0.0001f) {
    wheel.integral = 0.0f;
    wheel.prev_error = 0.0f;
    return 0.0f;
  }

  float error = wheel.target_mps - wheel.measured_mps;
  wheel.integral += error * dt;

  // 적분 와인드업 방지. 바퀴가 걸려 못 움직일 때 적분항이 무한히 커져서
  // 장애물이 치워지는 순간 로봇이 튀어나가는 걸 막습니다.
  wheel.integral = constrain(wheel.integral, -1.0f, 1.0f);

  float derivative = (error - wheel.prev_error) / dt;
  wheel.prev_error = error;

  float feedback = (KP * error + KI * wheel.integral + KD * derivative) / 1000.0f;
  float output = minimumDriveFeedforward(wheel.target_mps) + feedback;
  return constrain(output, -1.0f, 1.0f);
}

void stopAll() {
  driveEnabled = false;
  wheelL.target_mps = 0.0f;
  wheelR.target_mps = 0.0f;
  wheelL.previous_target_mps = 0.0f;
  wheelR.previous_target_mps = 0.0f;
  wheelL.measured_mps = 0.0f;
  wheelR.measured_mps = 0.0f;
  wheelL.integral = 0.0f;
  wheelR.integral = 0.0f;
  wheelL.prev_error = 0.0f;
  wheelR.prev_error = 0.0f;
  wheelL.prev_count = normalizedLeftCount();
  wheelR.prev_count = normalizedRightCount();
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

  if (payload.startsWith("V,")) {
    int firstComma = payload.indexOf(',');
    int secondComma = payload.indexOf(',', firstComma + 1);
    if (secondComma < 0) return;
    if (payload.indexOf(',', secondComma + 1) >= 0) return;

    String leftText = payload.substring(firstComma + 1, secondComma);
    String rightText = payload.substring(secondComma + 1);
    float left = 0.0f;
    float right = 0.0f;
    if (!parseFiniteFloat(leftText, left)) return;
    if (!parseFiniteFloat(rightText, right)) return;

    // A zero Twist is a safety stop, not a request to actively hold position.
    // Keep PWM off until a later non-zero velocity frame arrives.
    if (fabs(left) < 0.0001f && fabs(right) < 0.0001f) {
      stopAll();
      lastCommandMs = millis();
      return;
    }

    wheelL.target_mps = constrain(left, -MAX_WHEEL_MPS, MAX_WHEEL_MPS);
    wheelR.target_mps = constrain(right, -MAX_WHEEL_MPS, MAX_WHEEL_MPS);
    driveEnabled = true;
    lastCommandMs = millis();
  }
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

  String payload = "O," + String((long)normalizedLeftCount()) +
                   "," + String((long)normalizedRightCount()) +
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

    if (driveEnabled) {
      applyPwm(
          CH_L_R, CH_L_L,
          LEFT_MOTOR_OUTPUT_SIGN * updatePid(
              wheelL, normalizedLeftCount(), dt));
      applyPwm(
          CH_R_R, CH_R_L,
          RIGHT_MOTOR_OUTPUT_SIGN * updatePid(
              wheelR, normalizedRightCount(), dt));
    } else {
      // Do not let the PID re-energize a stopped motor because a wheel moved
      // by hand or coasted after the stop. Keep the next delta bounded too.
      wheelL.prev_count = normalizedLeftCount();
      wheelR.prev_count = normalizedRightCount();
      wheelL.measured_mps = 0.0f;
      wheelR.measured_mps = 0.0f;
      applyPwm(CH_L_R, CH_L_L, 0.0f);
      applyPwm(CH_R_R, CH_R_L, 0.0f);
    }
  }

  if (now - lastFeedbackMs >= FEEDBACK_PERIOD_MS) {
    lastFeedbackMs = now;
    sendFeedback();
  }
}
