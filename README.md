# Plant Growth Stage Detection

  Ultralytics YOLO 기반 식물 성장 단계 객체 탐지 프로젝트입니다.

  ## Classes

  - germination
  - vegetative
  - flowering

  ## Dataset

  Roboflow Universe:

  https://universe.roboflow.com/general-plant-growth-stage-detection-model/general-plant-growth-stage-model/dataset/2

  ## Setup

  ```bash
  python -m pip install -r requirements.txt
  cp .env.example .env

  .env에 ROBOFLOW_API_KEY를 입력한 후 JupyterLab에서
  plant_growth_detection.ipynb를 실행합니다.

  ## Model weights

  가중치는 Git 저장소에 포함하지 않습니다. 별도의 Google Drive에서
  다운로드해 사용합니다.

  ## GPU

  기본 GPU 인덱스는 4입니다.
