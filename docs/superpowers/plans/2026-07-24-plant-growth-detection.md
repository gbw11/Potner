# 식물 성장 단계 객체 탐지 노트북 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Roboflow 데이터셋을 누수 없이 정제하고 Ultralytics YOLO로 학습·평가·추론하는 순차 실행형 한국어 JupyterLab 노트북과 의존성 파일을 만든다.

**Architecture:** 하나의 노트북을 설정, 다운로드, 검사, 그룹화, 재분할, 통계·시각화, 학습, 평가, 최종 검증 순서의 독립 코드 셀로 구성한다. 원본은 읽기만 하고, 파일명 시퀀스와 확정 pHash 관계의 연결 컴포넌트를 원자 단위로 정제 데이터셋에 복사한다.

**Tech Stack:** Python 3.10+, JupyterLab, Roboflow SDK, Ultralytics YOLO, PyTorch, pandas, NumPy, Pillow, ImageHash, matplotlib, seaborn, PyYAML, tqdm, scikit-learn

## Global Constraints

- 모든 설명과 코드 주석은 한국어로 작성한다.
- API 키는 `ROBOFLOW_API_KEY` 환경변수로만 읽는다.
- 대상 클래스는 `germination`, `vegetative`, `flowering`이며 새 ID는 0, 1, 2이다.
- 원본 다운로드 디렉터리를 수정하지 않고 결과는 `cleaned_dataset/`에 생성한다.
- pHash 거리 5 이하는 재분할 전에 병합하고 6~10은 보고만 한다.
- 같은 시퀀스 또는 확정 중복 컴포넌트는 하나의 split에만 둔다.
- test split은 최종 평가에만 사용한다.
- seed, split 비율, 정규식, pHash 임계값과 학습 하이퍼파라미터는 상단 설정 셀에 둔다.

---

### Task 1: 정적 검증 기준과 의존성

**Files:**
- Create: `requirements.txt`
- Create: `tests/test_notebook_static.py`

**Interfaces:**
- Consumes: 설계 명세의 파일명과 필수 워크플로
- Produces: 노트북 JSON·코드 문법·필수 기능을 검사하는 pytest 테스트

- [ ] **Step 1: 실패하는 정적 테스트 작성**

```python
import ast
import json
from pathlib import Path

NOTEBOOK = Path("plant_growth_detection.ipynb")

def test_notebook_is_valid_and_complete():
    assert NOTEBOOK.exists()
    notebook = json.loads(NOTEBOOK.read_text(encoding="utf-8"))
    sources = ["".join(cell["source"]) for cell in notebook["cells"]]
    code = "\n".join(
        source for cell, source in zip(notebook["cells"], sources)
        if cell["cell_type"] == "code"
    )
    for source in (
        source for cell, source in zip(notebook["cells"], sources)
        if cell["cell_type"] == "code"
    ):
        ast.parse(source)
    for required in [
        "ROBOFLOW_API_KEY", "cleaned_dataset", "leakage_report.csv",
        "PHASH_DUPLICATE_MAX", "PHASH_REVIEW_MAX", "SEQUENCE_PATTERNS",
        "best.pt", "model.train", "model.val",
    ]:
        assert required in code
```

- [ ] **Step 2: 테스트가 노트북 부재로 실패하는지 확인**

Run: `pytest -q tests/test_notebook_static.py`
Expected: FAIL because `plant_growth_detection.ipynb` does not exist.

- [ ] **Step 3: 런타임 의존성 명시**

```text
jupyterlab>=4.2,<5
roboflow>=1.1.40,<2
ultralytics>=8.3,<9
torch>=2.2
torchvision>=0.17
pandas>=2.1,<3
numpy>=1.26,<3
Pillow>=10.2,<12
ImageHash>=4.3,<5
matplotlib>=3.8,<4
seaborn>=0.13,<1
PyYAML>=6.0,<7
tqdm>=4.66,<5
scikit-learn>=1.4,<2
```

### Task 2: 순차 실행 노트북 구현

**Files:**
- Create: `plant_growth_detection.ipynb`

**Interfaces:**
- Consumes: `ROBOFLOW_API_KEY`, Roboflow workspace/project/version, 상단 설정값
- Produces: `cleaned_dataset/data.yaml`, `leakage_report.csv`, 통계 CSV, 학습 run, `best.pt`

- [ ] **Step 1: 설명·설정·환경·다운로드 셀 작성**

설정 셀에 `RAW_DATASET_DIR`, `CLEAN_DATASET_DIR`, `SPLIT_RATIOS`, `SEED`, `SEQUENCE_PATTERNS`, `PHASH_DUPLICATE_MAX`, `PHASH_REVIEW_MAX`, YOLO 하이퍼파라미터를 정의한다. 비율 합계와 임계값을 즉시 검증하고 CUDA 또는 CPU 장치를 출력한다. API 키는 존재 여부만 검사한다.

- [ ] **Step 2: 데이터 발견과 파일명 패턴 분석 셀 작성**

YAML의 상대·절대 split 경로를 해석하고 이미지/라벨 쌍을 수집한다. 파일명 표본, 토큰 빈도, 생성된 시퀀스 키, 대형 그룹을 출력하는 `make_sequence_key()`를 제공한다.

- [ ] **Step 3: 무결성·클래스 재매핑 셀 작성**

이미지 열기, 라벨 형식·범위, 클래스 ID를 검사하고 대상 클래스 박스만 0–2로 바꾼다. 누락 라벨, 고아 라벨, 손상 이미지, 잘못된 라벨, 대상 박스 없음은 제외 보고서 레코드로 만든다.

- [ ] **Step 4: pHash 및 컴포넌트 셀 작성**

유효 이미지의 pHash를 계산하고 BK-tree를 이용해 설정된 최대 거리 이내 후보만 찾는다. 거리 5 이하 간선과 동일 시퀀스 간선은 union-find로 병합하고, 거리 6–10은 `review_required` 레코드만 만든다. 컴포넌트 크기와 최소·최대 pHash 거리, 간선 수, 근거를 집계한다.

- [ ] **Step 5: 그룹 재분할과 정제 데이터 생성 셀 작성**

컴포넌트별 이미지 수와 클래스 존재 벡터를 기준으로 고정 seed 탐욕 배치한다. 대상 디렉터리 충돌을 방지하는 고유 파일명을 만들고 이미지 복사와 라벨 쓰기, `data.yaml` 생성을 수행한다.

- [ ] **Step 6: 보고서·통계·시각화 셀 작성**

모든 감사 레코드를 `leakage_report.csv`로 저장한다. 정제 전후 split별 이미지 수, 클래스별 이미지 수와 박스 수, 분할 비율을 CSV로 저장하고 출력한다. 정제 라벨 샘플을 박스로 표시한다.

- [ ] **Step 7: 학습·val·test·추론 셀 작성**

`model.train(data=clean_yaml, ...)`에는 test를 전달하지 않는다. val 결과의 클래스별 P/R/mAP를 데이터프레임으로 출력하고 혼동행렬 파일을 표시한다. 복사된 `best.pt` 모델로 test를 최종 평가하고 test 표본 예측 이미지를 표시한다.

- [ ] **Step 8: 최종 불변 조건·산출물 검증 셀 작성**

메타데이터 기준 시퀀스와 컴포넌트 split 유일성, 이미지·라벨 일대일 대응, YAML·보고서·통계·`best.pt` 존재를 assert하고 생성 구조를 출력한다.

### Task 3: 검증과 인계

**Files:**
- Verify: `plant_growth_detection.ipynb`
- Verify: `requirements.txt`
- Verify: `tests/test_notebook_static.py`

**Interfaces:**
- Consumes: Tasks 1–2 산출물
- Produces: 정적 검증 결과와 사용자 실행 안내

- [ ] **Step 1: 정적 테스트 실행**

Run: `pytest -q tests/test_notebook_static.py`
Expected: `1 passed`.

- [ ] **Step 2: nbformat 검증 및 모든 코드 셀 컴파일**

Run: `python -m pytest -q`
Expected: all tests pass.

- [ ] **Step 3: 요구사항 추적 점검**

Run: `rg -n "ROBOFLOW_API_KEY|PHASH_DUPLICATE_MAX|review_required|leakage_report.csv|best.pt|model.train|model.val" plant_growth_detection.ipynb`
Expected: every required workflow marker appears.

- [ ] **Step 4: 산출물 확인**

Run: `ls -l plant_growth_detection.ipynb requirements.txt`
Expected: both files exist and are non-empty.
