from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

if not __package__:  # `python cli/plant.py` 직접 실행 시 프로젝트 루트를 경로에 추가
    sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from src.config import load_config
from src.dialogue.service import PlantService
from src.llm.client import create_llm_client, describe_llm
from src.vision import build_camera, build_camera_store


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Plant dialogue: local judgment + LLM speech + optional chat"
    )
    parser.add_argument("--config", default="config/default.yaml")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("brief", help="현재 상태 브리핑 (규칙 판단 + LLM/템플릿)")
    report = sub.add_parser("report", help="외출 중 이벤트 리포트")
    report.add_argument("--limit", type=int, default=40)
    sub.add_parser("chat", help="자유 대화 (tool use / 오프라인 폴백)")
    sub.add_parser("tick", help="1회 수집 + 상태/이벤트만 출력")
    sub.add_parser(
        "push-soil",
        help="토양수분 측정값을 Spring Boot로 POST (급수량 계산은 서버)",
    )
    sub.add_parser("llm-status", help="API 키/.env 연동 상태 확인")
    sub.add_parser("capture", help="카메라 1장 촬영 → data/camera + index.jsonl")
    cam_list = sub.add_parser("camera-list", help="저장된 카메라 프레임 목록")
    cam_list.add_argument("--limit", type=int, default=10)

    return parser.parse_args()


def main() -> int:
    args = parse_args()
    config_path = Path(args.config)
    if not config_path.exists():
        print(f"Config not found: {config_path}", file=sys.stderr)
        return 1

    config = load_config(config_path)

    if args.command == "llm-status":
        client = create_llm_client(config)
        print(describe_llm(client, config))
        env_path = Path.cwd() / ".env"
        print(f".env exists: {env_path.is_file()} ({env_path})")
        if not client.available():
            print(
                f"configured model={client.model} max_tokens={client.max_tokens}\n"
                f"configured base_url={client.base_url}"
            )
        return 0 if client.available() else 2

    if args.command == "camera-list":
        store = build_camera_store(config)
        for item in store.recent(limit=args.limit):
            print(json.dumps(item, ensure_ascii=False))
        return 0

    if args.command == "capture":
        camera = build_camera(config)
        if camera is None:
            print("camera.enabled=false in config", file=sys.stderr)
            return 1
        store = build_camera_store(config)
        try:
            result = store.capture(camera)
        finally:
            camera.close()
        print(json.dumps(result.to_dict(), ensure_ascii=False, indent=2))
        return 0 if result.ok else 1

    if args.command == "push-soil":
        service = PlantService(config)
        try:
            result = service.push_soil(refresh=True)
            print(json.dumps(result, ensure_ascii=False, indent=2))
        finally:
            service.close()
        return 0 if result.get("ok") or not result.get("enabled") else 1

    service = PlantService(config)
    print(describe_llm(service.llm, config))
    try:
        if args.command == "brief":
            service.brief()
        elif args.command == "report":
            service.report(limit=args.limit)
        elif args.command == "chat":
            service.chat_loop()
        elif args.command == "tick":
            reading, status, events = service.tick()
            print(reading)
            print(status.to_prompt_dict())
            if events:
                print("events:", events)
        else:
            return 1
    finally:
        service.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
