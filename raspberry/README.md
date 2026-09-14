# Sensor Collector

젯슨 나노 / 라즈베리 파이용 온습도·조도·토양수분 수집기입니다.

## 센서 구성

| 센서 | 모델 | 인터페이스 |
|------|------|------------|
| 조도 | BH1750 (GY-302) | I2C |
| 온습도 | DHT22/DHT11 또는 AHT20 (제품명 확인 후 `config/*.yaml`에서 선택) | GPIO 또는 I2C |
| 토양수분 | 아날로그 모듈 | **ADS1115 ADC** 경유 (Pi/Jetson 공통) |

> 라즈베리 파이와 젯슨 나노에는 아날로그 입력(ADC)이 없습니다. 토양수분 센서 AO 핀은 ADS1115 같은 I2C ADC에 연결해야 합니다.

## 토양수분 → Spring Boot

Pi/Jetson은 **측정값만** 서버로 보냅니다. 필요 급수량 계산·펌프 제어는 Spring / 급수대 쪽 책임입니다.

```bash
# 1회 전송 테스트
python cli/plant.py push-soil

# 수집 루프에서 주기 전송 (backend.enabled: true)
python main.py --once
```

POST 페이로드 예시 (`backend.soil_path`):

```json
{
  "deviceId": "pi-01",
  "timestamp": "2026-07-21T10:30:00+09:00",
  "soilRaw": 14200,
  "soilMoisturePct": 48.3
}
```

`config/*.yaml`의 `backend:` 또는 `.env`의 `SPRING_BASE_URL`로 주소를 바꿉니다.

## 빠른 시작 (Windows — mock)

현재 PC에서는 하드웨어 없이 파이프라인을 검증할 수 있습니다.

```bash
cd C:\Users\SSAFY\Desktop\S15P11E104
python -m venv .venv
# Git Bash:
source .venv/Scripts/activate
# PowerShell:
# .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py --once
```

기본 `config/default.yaml`의 `platform: mock` 이 가상 센서값을 `data/readings.csv`에 저장합니다.

## 대화형 파이프라인 (로컬 판단 + LLM 말투)

```
[센서] → [수집/CSV] → [로컬 규칙 상태 판단] → [이벤트 로그]
                              ↓
                    [템플릿 또는 LLM 문장] → [TTS/콘솔]
                              ↓
                    [자유 대화 + tool use]
```

**수정 포인트 (원안 대비)**
- 판단은 항상 로컬 (`src/status`). LLM은 말투/대화만.
- API 키 없으면 템플릿 폴백으로 PC에서도 바로 동작.
- 음성은 `src/voice` 어댑터(현재 콘솔). Whisper/ElevenLabs는 나중에 교체.
- 성장 사진(`src/vision`)은 자리만 둠 — 텍스트 LLM과 분리.

```bash
# 상태 브리핑 (1인칭 멘트)
python cli/plant.py brief

# 외출 이벤트 리포트
python cli/plant.py report

# 자유 대화 (exit 로 종료)
python cli/plant.py chat

# 수집+상태/이벤트만 보기
python cli/plant.py tick
```

LLM을 쓰려면 프로젝트 루트 `.env`에 **SSAFY GMS 키**를 넣습니다:

```env
OPENAI_API_KEY=여기에_GMS_키
OPENAI_BASE_URL=https://gms.ssafy.io/gmsapi/api.openai.com/v1
OPENAI_MODEL=gpt-4.1-nano
OPENAI_MAX_TOKENS=200
```

```bash
python cli/plant.py llm-status
python cli/plant.py brief
```

변수 이름이 `OPENAI_*`인 이유는 GMS가 OpenAI 호환 API이기 때문입니다. OpenAI 공식 키가 아닙니다.

| 폴더 | 역할 |
|------|------|
| `src/status` | 임계값 기반 정상/이상 라벨 |
| `src/events` | 상태 변화 JSONL 로그 |
| `src/llm` | OpenAI 호환 API + tool use + 템플릿 |
| `src/dialogue` | brief / report / chat 오케스트레이션 |
| `src/voice` | STT/TTS 어댑터 |
| `src/vision` | 카메라 캡처 (OV5647) + `data/camera` 저장 |

### 카메라 (SunFounder Rev1.3 / OV5647)

저장 위치는 **하나**: `data/camera/`
- 이미지: `data/camera/frame_YYYYMMDD_HHMMSS.jpg`
- 메타: `data/camera/index.jsonl`

```bash
# PC 또는 Pi 어디서든 (프로젝트 루트에서)
python -m cli.capture_photo
```

- **Pi에서 실행**: Pi의 `data/camera/`에 바로 저장
- **PC에서 실행**: Pi에서 촬영 후 PC `data/camera/`로 자동 동기화
- mock만: `python -m cli.capture_photo --mock`

> Pi에서 collector 서비스(`sensor-collector`)가 돌고 있으면 카메라를 이미 잡고 있어
> 직접 촬영이 실패할 수 있습니다. 서버 촬영 명령(MQTT) 경로를 쓰거나 서비스를 잠시 내리세요.

## PC ↔ 라즈베리 파이 (WiFi / SSH)

같은 WiFi에 PC와 Pi가 있어야 합니다. 현재 SSH 별칭:

| 항목 | 값 |
|------|-----|
| Host | `raspberrypi` |
| IP | `192.168.30.91` |
| User | `e104` |

### 1) 최초 1회 — SSH 키 등록

PowerShell에서 (Pi 비밀번호 1회 입력). 실행 정책 때문에 막히면 `-ExecutionPolicy Bypass`를 붙입니다:

```powershell
cd C:\Users\SSAFY\Desktop\S15P11E104
powershell -ExecutionPolicy Bypass -File .\scripts\setup-pi-ssh.ps1
```

또는 스크립트 없이 한 줄:

```powershell
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh raspberrypi "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo KEY_OK"
```

성공하면 이후 `ssh raspberrypi` 가 비밀번호 없이 됩니다.

### 2) 코드 배포 (git 기반)

Pi의 `~/S15P11E104`는 이 저장소의 클론이고 같은 브랜치를 체크아웃하고 있어
직접 push가 거부됩니다. 임시 ref를 경유해 fast-forward 합니다:

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy-to-pi.ps1        # 배포만
powershell -ExecutionPolicy Bypass -File .\scripts\deploy-to-pi.ps1 -Once  # 배포 후 1회 수집
```

수동으로 하려면:

```bash
git push ssh://raspberrypi/home/e104/S15P11E104 HEAD:refs/heads/sync-tmp
ssh raspberrypi "cd ~/S15P11E104 && git merge --ff-only sync-tmp && git branch -D sync-tmp"
```

배포 후 **서비스 재시작은 별도**입니다 (sudo 비밀번호 필요 → Pi에서 직접):

```bash
sudo systemctl restart sensor-collector
```

Pi에서 수동 실행 (서비스 대신):

```bash
ssh raspberrypi
cd ~/S15P11E104
./scripts/run_pi_collector.sh    # unset DEVICE_ID + .env 로드 + 실행
# 또는 1회만
unset DEVICE_ID && .venv/bin/python main.py --config config/raspberry_pi.yaml --once
```

### 3) Cursor에서 Pi로 직접 편집

1. Cursor / VS Code에서 **Remote - SSH** 확장 설치
2. `F1` → `Remote-SSH: Connect to Host...` → `raspberrypi` 선택
3. 원격 창에서 `~/S15P11E104` 폴더 열기
4. 터미널에서 바로 수집 실행

단, 코드 수정은 PC에서 커밋 → 배포가 원칙입니다 (Pi 직접 편집은 동기화가 깨집니다).

IP가 바뀌면 `C:\Users\SSAFY\.ssh\config` 의 `HostName` 을 수정하세요.

## 보드에서 실행 (라즈베리 파이 / 젯슨)

1. `config/raspberry_pi.yaml` 수정 (실행 시 `--config config/raspberry_pi.yaml` 지정)

```yaml
platform: raspberry_pi   # 또는 jetson
sensors:
  climate:
    driver: dht22        # 안 되면 dht11 또는 aht20
    pin: 4
```

2. 패키지 설치

```bash
pip install -r requirements.txt
pip install adafruit-circuitpython-dht Adafruit-Blinka   # DHT 사용 시
```

3. I2C 활성화 후 주소 확인

```bash
sudo raspi-config   # Pi
sudo i2cdetect -y 1
```

예상 주소: BH1750=`0x23`(또는 `0x5C`), ADS1115=`0x48`, AHT20=`0x38`

4. 수집 시작

```bash
python main.py
# 또는 1회만
python main.py --once
```

## 배선 요약

### BH1750 (조도)
- VCC → 3.3V
- GND → GND
- SDA → SDA
- SCL → SCL
- ADDR → 비연결(0x23) 또는 VCC(0x5C)

### DHT11/22 (온습도)
- VCC → 3.3V (또는 모듈 사양에 맞게 5V)
- GND → GND
- DATA → GPIO4 (BCM, `config/*.yaml`의 `pin`)
- 필요 시 DATA–VCC 사이 4.7kΩ 풀업

### 토양수분 + ADS1115
- 센서 VCC/GND → 보드 전원/GND
- 센서 AO → ADS1115 A0
- ADS1115 VDD → 3.3V, GND → GND, SDA/SCL → I2C

### 토양 캘리브레이션
1. 공기 중(건조)에서 `soil_raw` 기록 → `dry_raw`
2. 물에 적신 후(또는 충분히 젖은 흙) `soil_raw` 기록 → `wet_raw`
3. `config/*.yaml`에 반영

값이 반대로 나오면 `dry_raw` / `wet_raw`를 서로 바꿔 보세요.

## 역할 분담

- **라즈베리 파이**: 센서 수집 + MQTT 텔레메트리/하트비트 발행, 급수·촬영 명령 수신,
  촬영 사진 서버 업로드 (`mqtt:` / `upload:` config 섹션, `docs/potner-mqtt-pi-handoff.md` 참고)
- **서버(Spring Boot)**: 명령 발행, 사진 보관(하루 1장), 판단·알림

로컬 CSV(`data/readings.csv`)와 이벤트 로그(`data/events.jsonl`)는 MQTT와 별개로 항상 남습니다.
