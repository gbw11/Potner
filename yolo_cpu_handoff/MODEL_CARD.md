# 모델 카드: plant growth YOLO `best.pt`

## 식별 정보

- 전달 파일: `models/best.pt`
- SHA-256:
  `5ab31994de572c21c6717f6741c23c765039427c6e428fbf44d9084543bc3707`
- 형식: Ultralytics YOLO PyTorch checkpoint
- 추론 장치: CPU 전용
- 기본 입력 크기: `640`
- 기본 confidence threshold: `0.10`
- 클래스: `0 germination`, `1 vegetative`, `2 flowering`

## 의도된 사용과 한계

이 모델은 식물 정지 이미지에서 위 세 성장 단계의 객체 탐지를 보조하기 위한
내부 기술 검증용 모델입니다. JPEG, PNG, WebP 입력만 이 패키지에서 지원합니다.
사람의 안전, 의료, 농약 투입, 수확 자동 결정처럼 오분류가 피해를 만드는
고위험 의사결정의 단독 근거로 사용하면 안 됩니다.

새 카메라, 조명, 품종, 재배 환경, 가림, 극단적인 해상도에서의 일반화 성능은
확인되지 않았습니다. 패키지 인수인계에는 승인된 운영 성능 임계값이나
공정성 평가가 포함되지 않습니다. 실제 환경의 대표 표본으로 별도 검증하고
오탐/미탐을 사람이 검토해야 합니다.

## 작업공간 학습 provenance

현재 작업공간에서 확인한 provenance는 다음과 같습니다.

- 실행 노트북: `plant_growth_detection.ipynb`
- 학습 설정: `runs/plant_growth_yolo26n/args.yaml`
- 학습 run 이름: `plant_growth_yolo26n`
- 초기 모델: `yolo26n.pt` pretrained checkpoint
- 정제 데이터 설정: `cleaned_dataset/data.yaml`
- 학습 파라미터: 최대 50 epochs, patience 15, batch 16, `imgsz=640`,
  seed 42, deterministic mode
- 조기 종료: 42 epochs 완료, 최적 epoch 27
- 기본 YOLO26n validation 전체 지표: Precision `0.702`, Recall `0.636`, mAP50 `0.645`,
  mAP50-95 `0.377`
- 기본 YOLO26n test 전체 지표: Precision `0.700`, Recall `0.484`, mAP50 `0.487`,
  mAP50-95 `0.264`
- 기본 YOLO26n test 클래스별 mAP50-95: germination `0.038`, vegetative `0.500`,
  flowering `0.254`
- 동일 test에서 기존 YOLO11n의 mAP50-95는 `0.288`로, YOLO26n의 `0.264`보다
  높았다. YOLO26n은 경량화 요구에 따라 기본 모델로 선택했으며 이 정확도 차이를
  운영 승인 전에 검토해야 한다.
- 추가 미세조정 설정:
  `test_inputs/runs/plant_growth_yolo26n_flower_ft15/args.yaml`
- 추가 미세조정 run 이름: `plant_growth_yolo26n_flower_ft15`
- 추가 미세조정 초기 모델: 기본 run의 `weights/best.pt`
- 추가 미세조정 데이터 설정:
  `test_inputs/plant_growth_flower_augmented.yaml`
- 추가 미세조정 파라미터: 15 epochs, batch 32, `imgsz=640`, seed 42,
  deterministic mode
- 전달 파일은 추가 미세조정 run의 `weights/last.pt`를 `models/best.pt`로
  복사한 것이며, 두 파일의 바이트 및 SHA-256이 일치합니다.
- 원본 `raw_dataset/data.yaml`은 Roboflow
  `general-plant-growth-stage-model` version 2와 `CC BY 4.0` 문자열을
  기록합니다. 정제 단계는 다섯 원본 클래스 중 위 세 클래스만 재매핑했습니다.

이 정보는 작업공간 파일에서 관찰한 기술 provenance이며, 데이터 또는 모델에
대한 소유권이나 사용 허가를 증명하지 않습니다.

## 권리와 승인 상태

- 데이터셋/모델 재배포 권리: **UNKNOWN — 소유자 승인 대기**
- 원본 데이터셋 라이선스 조건 충족 여부: **UNKNOWN — 소유자 승인 대기**
- pretrained checkpoint 및 학습 결과의 외부 제공 승인: **UNKNOWN — 소유자 승인 대기**

`raw_dataset/data.yaml`의 라이선스 문자열만으로 저작권자, attribution 의무,
원본 이미지별 권리, 모델 checkpoint 재배포 권리를 확정할 수 없습니다.
권리 소유자 또는 조직의 담당자가 출처, attribution, 배포 범위와 모델 사용
조건을 문서로 승인하기 전에는 외부 배포하거나 네트워크 서비스로 전환하지
마십시오.
