﻿﻿# Sensor Collector

??�� ?�노 / ?�즈베리 ?�이???�습?�·조?�·토?�수�??�집기입?�다.

## ?�서 구성

| ?�서 | 모델 | ?�터?�이??|
|------|------|------------|
| 조도 | BH1750 (GY-302) | I2C |
| ?�습??| DHT22/DHT11 ?�는 AHT20 (?�품�??�인 ??`config.yaml`?�서 ?�택) | GPIO ?�는 I2C |
| ?�양?�분 | ?�날로그 모듈 | **ADS1115 ADC** 경유 (Pi/Jetson 공통) |

> ?�즈베리 ?�이?� ??�� ?�노?�는 ?�날로그 ?�력(ADC)???�습?�다. ?�양?�분 ?�서 AO ?�?� ADS1115 같�? I2C ADC???�결?�야 ?�니??

## ?�양?�분 ??Spring Boot

Pi/Jetson?� **측정값만** ?�버�?보냅?�다. ?�요 급수??계산·?�프 ?�어??Spring / 급수?� �?책임?�니??

```bash
# 1???�송 ?�스??
python -m cli.plant push-soil

# ?�집 루프?�서 주기 ?�송 (backend.enabled: true)
python main.py --once
```

POST ?�이로드 ?�시 (`backend.soil_path`):

```json
{
  "deviceId": "pi-01",
  "timestamp": "2026-07-21T10:30:00+09:00",
  "soilRaw": 14200,
  "soilMoisturePct": 48.3
}
```

`config.yaml`??`backend:` ?�는 `.env`??`SPRING_BASE_URL` / `DEVICE_ID`�?주소�?바꿉?�다.

## 빠른 ?�작 (Windows ??mock)

?�재 PC?�서???�드?�어 ?�이 ?�이?�라?�을 검증할 ???�습?�다.

```bash
cd C:\Users\SSAFY\Projects\sensor-collector
python -m venv .venv
# Git Bash:
source .venv/Scripts/activate
# PowerShell:
# .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python main.py --once
```

기본 `config.yaml`??`platform: mock` ??가???�서값을 `data/readings.csv`???�?�합?�다.

## ?�?�형 ?�이?�라??(로컬 ?�단 + LLM 말투)

```
[?�서] ??[?�집/CSV] ??[로컬 규칙 ?�태 ?�단] ??[?�벤??로그]
                              ??
                    [?�플�??�는 LLM 문장] ??[TTS/콘솔]
                              ??
                    [?�유 ?�??+ tool use]
```

**?�정 ?�인??(?�안 ?��?**
- ?�단?� ??�� 로컬 (`src/status`). LLM?� 말투/?�?�만.
- API ???�으�??�플�??�백?�로 PC?�서??바로 ?�작.
- ?�성?� `src/voice` ?�댑???�재 콘솔). Whisper/ElevenLabs???�중??교체.
- ?�장 ?�진(`src/vision`)?� ?�리�??????�스??LLM�?분리.

```bash
# ?�태 브리??(1?�칭 멘트)
python -m cli.plant brief

# ?�출 ?�벤??리포??
python -m cli.plant report

# ?�유 ?�??(exit �?종료)
python -m cli.plant chat

# ?�집+?�태/?�벤?�만 보기
python -m cli.plant tick
```

LLM???�려�??�로?�트 루트 `.env`??**SSAFY GMS ??*�??�습?�다:

```env
OPENAI_API_KEY=?�기??GMS_??
OPENAI_BASE_URL=https://gms.ssafy.io/gmsapi/api.openai.com/v1
OPENAI_MODEL=gpt-4.1-nano
OPENAI_MAX_TOKENS=200
```

```bash
python -m cli.plant llm-status
python -m cli.plant brief
```

변???�름??`OPENAI_*`???�유??GMS가 OpenAI ?�환 API?�기 ?�문?�니?? OpenAI 공식 ?��? ?�닙?�다.

| 경로 | ??�� |
|------|------|
| `main.py` | ?�시 ?�서 ?�집 루프 (루트 진입?? |
| `cli/` | 부가 CLI (`plant`, `capture_photo` ?? ??`python -m cli.<name>` |
| `scripts/` | PC?�Pi 배포·SSH ?�틸 (`.ps1`) |
| `config/` | ?�경�??�정 ?�일 (`default.yaml`, `raspberry_pi.yaml`) |
| `src/` | 라이브러리 (센서·상태·LLM·비전) |
| `src/status` | ?�계�?기반 ?�상/?�상 ?�벨 |
| `src/events` | ?�태 변??JSONL 로그 |
| `src/llm` | OpenAI ?�환 API + tool use + ?�플�?|
| `src/dialogue` | brief / report / chat ?��??�트?�이??|
| `src/voice` | STT/TTS ?�댑??|
| `src/vision` | 카메??캡처 (OV5647) + `data/camera` ?�??|
| `config.yaml` | PC/mock 기본 ?�정 |
| `config.raspberry_pi.yaml` | ?�즈베리 ?�이 ?�기�??�정 |

### 카메??(SunFounder Rev1.3 / OV5647)

?�???�치??**?�나**: `data/camera/`
- ?��?지: `data/camera/frame_YYYYMMDD_HHMMSS.jpg`
- 메�?: `data/camera/index.jsonl`

```bash
# PC ?�는 Pi ?�디?�든 (?�로?�트 루트?�서)
python -m cli.capture_photo
```

- **Pi?�서 ?�행**: Pi??`data/camera/`??바로 ?�??
- **PC?�서 ?�행**: Pi?�서 촬영 ??PC `data/camera/`�??�동 ?�기??
- mock�? `python -m cli.capture_photo --mock`

## PC ???�즈베리 ?�이 (WiFi / SSH)

같�? WiFi??PC?� Pi가 ?�어???�니?? ?�재 SSH 별칭:

| ??�� | �?|
|------|-----|
| Host | `raspberrypi` |
| IP | `192.168.30.91` |
| User | `e104` |

### 1) 최초 1????SSH ???�록

PowerShell?�서 (Pi 비�?번호 1???�력). ?�행 ?�책 ?�문??막히�?`-ExecutionPolicy Bypass`�?붙입?�다:

```powershell
cd C:\Users\SSAFY\Projects\sensor-collector
powershell -ExecutionPolicy Bypass -File .\scripts\setup-pi-ssh.ps1
```

?�는 ?�크립트 ?�이 ??�?

```powershell
Get-Content $env:USERPROFILE\.ssh\id_ed25519.pub | ssh raspberrypi "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys && echo KEY_OK"
```

?�공?�면 ?�후 `ssh raspberrypi` 가 비�?번호 ?�이 ?�니??

### 2) 코드 배포

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\deploy-to-pi.ps1 -Install -Once
```

- `-Install`: Pi??venv + ?�키지 ?�치
- `-Once`: 배포 ??`python main.py --once` 1???�행

Pi?�서 ?�제 ?�서???�정:

```bash
ssh raspberrypi
cd ~/sensor-collector
source .venv/bin/activate
cp config.raspberry_pi.yaml config.yaml   # ?�는 --config �?지??
python main.py --once
```

### 3) Cursor?�서 Pi�?직접 ?�집 (권장)

1. Cursor / VS Code?�서 **Remote - SSH** ?�장 ?�치
2. `F1` ??`Remote-SSH: Connect to Host...` ??`raspberrypi` ?�택
3. ?�격 창에??`~/sensor-collector` ?�더 ?�기
4. ?��??�에??바로 ?�집 ?�행

IP가 바뀌면 `C:\Users\SSAFY\.ssh\config` ??`HostName` ???�정?�세??

## 보드?�서 ?�행 (?�즈베리 ?�이 / ??��)

1. `config.yaml` ?�정

```yaml
platform: raspberry_pi   # ?�는 jetson
sensors:
  climate:
    driver: dht22        # ???�면 dht11 ?�는 aht20
    pin: 4
```

2. ?�키지 ?�치

```bash
pip install -r requirements.txt
pip install adafruit-circuitpython-dht Adafruit-Blinka   # DHT ?�용 ??
```

3. I2C ?�성????주소 ?�인

```bash
sudo raspi-config   # Pi
sudo i2cdetect -y 1
```

?�상 주소: BH1750=`0x23`(?�는 `0x5C`), ADS1115=`0x48`, AHT20=`0x38`

4. ?�집 ?�작

```bash
python main.py
# ?�는 1?�만
python main.py --once
```

## 배선 ?�약

### BH1750 (조도)
- VCC ??3.3V
- GND ??GND
- SDA ??SDA
- SCL ??SCL
- ADDR ??비연�?0x23) ?�는 VCC(0x5C)

### DHT11/22 (?�습??
- VCC ??3.3V (?�는 모듈 ?�양??맞게 5V)
- GND ??GND
- DATA ??GPIO4 (BCM, `config.yaml`??`pin`)
- ?�요 ??DATA?�VCC ?�이 4.7kΩ ?�??

### ?�양?�분 + ADS1115
- ?�서 VCC/GND ??보드 ?�원/GND
- ?�서 AO ??ADS1115 A0
- ADS1115 VDD ??3.3V, GND ??GND, SDA/SCL ??I2C

### ?�양 캘리브레?�션
1. 공기 �?건조)?�서 `soil_raw` 기록 ??`dry_raw`
2. 물에 ?�신 ???�는 충분???��? ?? `soil_raw` 기록 ??`wet_raw`
3. `config.yaml`??반영

값이 반�?�??�오�?`dry_raw` / `wet_raw`�??�로 바꿔 보세??

## ??�� 분담 ?�시

- **?�즈베리 ?�이**: ???�크립트�??�서 ?�집 ??CSV ?�는 ?�트?�크 ?�송
- **??�� ?�노**: 같�? 코드�??�집?�거?? ?�이?�서 받�? ?�이?��? 분석/추론

지금�? 로컬 CSV ?�?�입?�다. MQTT/HTTP ?�송???�요?�면 ?�어??붙일 ???�습?�다.

| `src/` | ?�이브러�?(?�서·?�태·LLM·비전) |