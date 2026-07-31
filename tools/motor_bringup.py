#!/usr/bin/env python3
"""모터 배선을 처음 맞출 때 쓰는 벤치 도구.

ROS 2 를 띄우지 않고 ESP32 에 직접 명령을 보냅니다. 배선이 틀렸을 때
Nav2·도킹·임무까지 함께 의심하지 않아도 되도록, 확인 범위를 모터와
엔코더로만 좁힙니다.

    # 왼쪽만 낮은 듀티로 2초 (개루프 — 엔코더와 무관)
    python3 tools/motor_bringup.py --left 0.15 --seconds 2

    # 엔코더만 확인. 모터를 돌리지 않고 손으로 축을 돌려보세요
    python3 tools/motor_bringup.py --duty 0 --seconds 20

    # 배선이 맞은 뒤 폐루프 확인 (m/s)
    python3 tools/motor_bringup.py --velocity --left 0.1 --seconds 3

★ 바퀴를 반드시 공중에 띄우고 하세요. 방향이 반대로 배선돼 있으면 로봇이
  책상에서 떨어집니다.

★ 워치독이 500ms 라 이 스크립트가 계속 명령을 보내는 동안만 돕니다.
  Ctrl-C 로 끊거나 USB 를 빼면 즉시 멈춥니다. 그게 정상 동작입니다.
"""

import argparse
import sys
import time
from pathlib import Path

# 설치본이 있으면 그것을 쓰고, 없으면 소스에서 직접 읽습니다.
# 젯슨에서 colcon 빌드 전에도 쓸 수 있어야 합니다.
try:
    from potner_base.serial_protocol import (
        decode_feedback,
        encode_duty,
        encode_stop,
        encode_velocity,
    )
except ImportError:
    sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src" / "potner_base"))
    from potner_base.serial_protocol import (  # noqa: E402
        decode_feedback,
        encode_duty,
        encode_stop,
        encode_velocity,
    )

try:
    import serial
except ImportError:
    sys.exit("pyserial 이 없습니다: pip3 install pyserial")

# 워치독(500ms)보다 넉넉히 짧아야 합니다. 한 번 늦어도 멈추지 않게 5배로 둡니다.
SEND_PERIOD_S = 0.1


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", default="/dev/ttyUSB0")
    parser.add_argument("--baud", type=int, default=115200)
    parser.add_argument(
        "--duty",
        type=float,
        default=None,
        help="좌우 같은 값으로 보냅니다. --left/--right 보다 먼저 적용됩니다",
    )
    parser.add_argument("--left", type=float, default=None)
    parser.add_argument("--right", type=float, default=None)
    parser.add_argument("--seconds", type=float, default=2.0)
    parser.add_argument(
        "--velocity",
        action="store_true",
        help="폐루프(m/s)로 보냅니다. 엔코더가 확인된 뒤에만 쓰세요",
    )
    args = parser.parse_args()

    if args.duty is not None:
        args.left = args.duty if args.left is None else args.left
        args.right = args.duty if args.right is None else args.right
    args.left = 0.0 if args.left is None else args.left
    args.right = 0.0 if args.right is None else args.right
    return args


def main():
    args = parse_args()
    encode = encode_velocity if args.velocity else encode_duty
    unit = "m/s" if args.velocity else "듀티"

    print(f"포트 {args.port} @ {args.baud}")
    print(f"{'폐루프' if args.velocity else '개루프'}  왼쪽 {args.left} / 오른쪽 {args.right} ({unit})")
    print(f"{args.seconds}초 동안 {SEND_PERIOD_S * 1000:.0f}ms 마다 보냅니다. Ctrl-C 로 중단.\n")

    with serial.Serial(args.port, args.baud, timeout=0.1) as port:
        # ESP32 는 시리얼 열림(DTR)에 리셋됩니다. 부팅을 기다리지 않으면
        # 첫 명령이 부트로더 로그에 섞여 버려집니다.
        time.sleep(1.0)
        port.reset_input_buffer()

        first = None
        last = None
        deadline = time.monotonic() + args.seconds
        next_send = 0.0

        try:
            while time.monotonic() < deadline:
                now = time.monotonic()
                if now >= next_send:
                    port.write(encode(args.left, args.right).encode("ascii"))
                    next_send = now + SEND_PERIOD_S

                line = port.readline().decode("ascii", errors="ignore").strip()
                if not line:
                    continue
                try:
                    feedback = decode_feedback(line)
                except Exception:
                    # 부트 로그나 깨진 프레임. 조용히 넘깁니다.
                    continue

                if first is None:
                    first = feedback
                last = feedback
                print(
                    f"\r왼쪽 {feedback.left_counts:>10}  "
                    f"오른쪽 {feedback.right_counts:>10}",
                    end="",
                    flush=True,
                )
        except KeyboardInterrupt:
            print("\n중단됨")
        finally:
            # 워치독이 알아서 멈추지만, 명시적으로 보내는 게 확실합니다.
            port.write(encode_stop().encode("ascii"))
            port.flush()

        print()
        report(first, last)


def report(first, last):
    """무엇이 확인됐고 무엇이 안 됐는지 말해줍니다."""
    if first is None:
        print("✗ 피드백을 한 줄도 못 받았습니다.")
        print("  - 포트가 맞습니까? ls /dev/ttyUSB* /dev/ttyACM*")
        print("  - 펌웨어가 올라갔습니까? pio run --target upload")
        print("  - 다른 프로그램이 포트를 쥐고 있지 않습니까? (base_driver, pio monitor)")
        return

    left_delta = last.left_counts - first.left_counts
    right_delta = last.right_counts - first.right_counts

    print(f"엔코더 변화량   왼쪽 {left_delta:+}  오른쪽 {right_delta:+}")

    if left_delta == 0 and right_delta == 0:
        print("\n△ 통신은 되지만 카운트가 변하지 않았습니다.")
        print("  바퀴가 돌았습니까?")
        print("   - 돌았다  → 엔코더 배선 문제입니다 (A/B, 전원, 레벨)")
        print("   - 안 돌았다 → 모터 쪽입니다 (R_EN/L_EN, B+/B-, 팩 전압)")
        print("  손으로 축을 돌려 카운트가 변하면 엔코더는 정상입니다.")
        return

    for name, delta in (("왼쪽", left_delta), ("오른쪽", right_delta)):
        if delta == 0:
            print(f"△ {name} 엔코더만 변화가 없습니다. 그쪽 배선만 보세요.")

    print("\n✓ 통신·엔코더 확인됨.")
    print("  부호가 예상과 반대면 그 바퀴의 엔코더 A/B 두 선을 서로 바꾸세요.")
    print("  (모터 선을 바꾸면 회전 방향까지 같이 바뀌어 더 헷갈립니다)")


if __name__ == "__main__":
    main()
