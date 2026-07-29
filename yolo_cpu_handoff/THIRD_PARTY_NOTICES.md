# 제3자 구성요소 메타데이터 및 보류 게이트

이 문서는 2026-07-29 작업공간의 **로컬 설치 메타데이터**(Python package
metadata)를
기준으로 작성한 기술 인벤토리입니다. 전체 라이선스 본문이나 SBOM을
대체하지 않으며, 법률 자문이 아닙니다. 어떤 사용 또는 배포도
승인되었다는 의미가 아닙니다.

## 핵심 런타임 메타데이터

| 패키지 | 확인 버전 | 로컬 metadata의 license 표기 |
|---|---:|---|
| FastAPI | 0.115.14 | MIT classifier |
| python-multipart | 0.0.32 | Apache-2.0 |
| Pydantic | 2.12.5 | MIT |
| Typer | 0.25.1 | MIT |
| Uvicorn | 0.51.0 | BSD-3-Clause |
| Pillow | 11.3.0 | MIT-CMU |
| NumPy | 2.3.5 | BSD classifier |
| OpenCV Python | 5.0.0.93 | Apache 2.0 |
| PyTorch | 2.5.1 (CPU target) | BSD-3-Clause |
| TorchVision | 0.20.1 (CPU target) | BSD |
| Ultralytics | 8.4.104 | **AGPL-3.0**, AGPLv3+ classifier |
| ultralytics-thop | 2.0.20 | **AGPL-3.0**, AGPLv3+ classifier |

정확한 배포 closure는 `constraints-linux-cpu.txt`에 기록했습니다. 각 wheel이나
source distribution에 동봉된 원문, 저작권 고지, notice 파일이 최종 판단의
기준이며 이 요약 표와 다를 경우 원문을 우선합니다.

## 필수 disposition

Ultralytics 8.4.104의 로컬 metadata는 `AGPL-3.0`을, 프로젝트 classifier는
GNU Affero General Public License v3 or later를 표시합니다. 조직이 보유한
별도 commercial 라이선스가 있는지, AGPL 의무를 어떤 방식으로 이행할지,
수정 소스와 네트워크 상호작용에 적용되는 의무가 무엇인지를 담당자가
문서로 disposition해야 합니다.

다음 행위 전에는 이 disposition과 `MODEL_CARD.md`의 모델/데이터 권리 승인이
모두 완료되어야 합니다.

- 패키지, 컨테이너 이미지 또는 checkpoint의 외부 배포
- 조직 경계를 넘는 네트워크 배포 또는 서비스 운영
- 고객/제3자에게 제공하는 제품에 포함

이 패키지는 현재 내부 기술 인수인계용이며 법무 또는 권리 소유자의 clearance를
주장하지 않습니다. 프로젝트 자체의 `LICENSE`는 소유권과 허가 근거가 확인되지
않았으므로 임의로 만들지 않았습니다.
