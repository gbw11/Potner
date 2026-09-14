FROM python:3.11.9-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1

WORKDIR /app

RUN apt-get update && \
    apt-get install --yes --no-install-recommends \
        libgl1 \
        libglib2.0-0 && \
    rm -rf /var/lib/apt/lists/*

RUN groupadd --system app && \
    useradd --system --gid app --home-dir /app app

COPY --chown=app:app constraints-linux-cpu.txt ./

RUN pip install --no-cache-dir \
        --index-url https://download.pytorch.org/whl/cpu \
        --constraint constraints-linux-cpu.txt \
        --no-deps \
        torch==2.5.1+cpu \
        torchvision==0.20.1+cpu

COPY --chown=app:app pyproject.toml ./
COPY --chown=app:app app ./app
RUN pip install --no-cache-dir \
        --constraint constraints-linux-cpu.txt \
        .

RUN python -c "import cv2, torch, ultralytics; assert torch.version.cuda is None; assert not torch.cuda.is_available()"

COPY --chown=app:app models ./models

USER app

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=3)"]

CMD ["uvicorn", "app.api:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "1"]
