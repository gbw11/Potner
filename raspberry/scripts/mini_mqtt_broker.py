#!/usr/bin/env python3
"""PC 로컬 검증용 최소 MQTT 3.1.1 브로커 (개발 도구 — 운영 코드 아님).

**용도**: 실 브로커(i15e104.p.ssafy.io)에 붙지 않고 PC 에서 명령 왕복
(서버→Pi 급수/촬영 명령 → 실행 → 결과 회신)을 통째로 재현하기 위한 것.
mosquitto 설치 없이 표준 라이브러리만으로 돈다. `docs/loops/LOOP_3.md` 에서 처음 사용.

**주의**: 인증을 전부 통과시키고 ACL 도 없다. 로컬 시뮬레이션 전용이며
운영/테스트 경로에 연결하지 말 것. 자동화된 pytest 픽스처로 만드는 건 아직 안 했다.

지원: CONNECT/CONNACK, SUBSCRIBE/SUBACK, PUBLISH(QoS0·1)/PUBACK, PINGREQ/PINGRESP, DISCONNECT.
구독자에게는 QoS0 으로 내려보낸다(min(pub,sub) 다운그레이드 — 규약상 허용).
오가는 PUBLISH 를 전부 JSONL 로 남겨 왕복을 사후 검증할 수 있게 한다.

    python scripts/mini_mqtt_broker.py 18830 broker_log.jsonl

    # config 에서 mqtt.host: 127.0.0.1, mqtt.port: 18830 으로 돌리고
    # MQTT_PASSWORD 를 아무 값이나 넣은 뒤 main.py 를 연속 루프로 띄우면 된다
    #  (리스너는 --once 가 아닐 때만 생성된다: main.py:54,61)
"""

from __future__ import annotations

import json
import socket
import struct
import sys
import threading
import time

HOST = "127.0.0.1"
PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 18830
LOG_PATH = sys.argv[2] if len(sys.argv) > 2 else "broker_log.jsonl"

_log_lock = threading.Lock()
_clients: list["Client"] = []
_clients_lock = threading.Lock()
_start = time.time()


def log_event(kind: str, **fields) -> None:
    rec = {"t": round(time.time() - _start, 3), "kind": kind, **fields}
    line = json.dumps(rec, ensure_ascii=False)
    with _log_lock:
        with open(LOG_PATH, "a", encoding="utf-8") as fh:
            fh.write(line + "\n")
    print(line, flush=True)


def topic_matches(filt: str, topic: str) -> bool:
    """MQTT 와일드카드 매칭 (+ 한 레벨, # 나머지 전부)."""
    f = filt.split("/")
    t = topic.split("/")
    for i, part in enumerate(f):
        if part == "#":
            return True
        if i >= len(t):
            return False
        if part != "+" and part != t[i]:
            return False
    return len(f) == len(t)


def encode_remaining_length(n: int) -> bytes:
    out = bytearray()
    while True:
        byte = n % 128
        n //= 128
        if n > 0:
            byte |= 0x80
        out.append(byte)
        if n == 0:
            break
    return bytes(out)


def encode_string(s: str) -> bytes:
    b = s.encode("utf-8")
    return struct.pack("!H", len(b)) + b


class Client(threading.Thread):
    def __init__(self, conn: socket.socket, addr) -> None:
        super().__init__(daemon=True)
        self.conn = conn
        self.addr = addr
        self.client_id = "?"
        self.subs: list[str] = []
        self.alive = True
        self._send_lock = threading.Lock()

    # --- 수신 ---

    def recv_exact(self, n: int) -> bytes:
        buf = b""
        while len(buf) < n:
            chunk = self.conn.recv(n - len(buf))
            if not chunk:
                raise ConnectionError("peer closed")
            buf += chunk
        return buf

    def recv_remaining_length(self) -> int:
        multiplier = 1
        value = 0
        while True:
            b = self.recv_exact(1)[0]
            value += (b & 0x7F) * multiplier
            if not (b & 0x80):
                return value
            multiplier *= 128

    def send_raw(self, data: bytes) -> None:
        with self._send_lock:
            try:
                self.conn.sendall(data)
            except OSError:
                self.alive = False

    # --- 처리 ---

    def run(self) -> None:
        try:
            while self.alive:
                header = self.recv_exact(1)[0]
                ptype = header >> 4
                flags = header & 0x0F
                length = self.recv_remaining_length()
                body = self.recv_exact(length) if length else b""
                self.dispatch(ptype, flags, body)
        except (ConnectionError, OSError):
            pass
        finally:
            self.alive = False
            with _clients_lock:
                if self in _clients:
                    _clients.remove(self)
            log_event("DISCONNECT", client_id=self.client_id)
            try:
                self.conn.close()
            except OSError:
                pass

    def dispatch(self, ptype: int, flags: int, body: bytes) -> None:
        if ptype == 1:  # CONNECT
            self.handle_connect(body)
        elif ptype == 3:  # PUBLISH
            self.handle_publish(flags, body)
        elif ptype == 8:  # SUBSCRIBE
            self.handle_subscribe(body)
        elif ptype == 10:  # UNSUBSCRIBE
            pid = struct.unpack("!H", body[:2])[0]
            self.send_raw(bytes([0xB0, 0x02]) + struct.pack("!H", pid))
        elif ptype == 12:  # PINGREQ
            self.send_raw(bytes([0xD0, 0x00]))
        elif ptype == 14:  # DISCONNECT
            self.alive = False

    def handle_connect(self, body: bytes) -> None:
        pos = 0
        plen = struct.unpack("!H", body[pos:pos + 2])[0]
        pos += 2 + plen          # protocol name
        pos += 1                 # protocol level
        connect_flags = body[pos]
        pos += 1
        pos += 2                 # keepalive
        cid_len = struct.unpack("!H", body[pos:pos + 2])[0]
        pos += 2
        self.client_id = body[pos:pos + cid_len].decode("utf-8", "replace") or "(empty)"
        pos += cid_len
        username = None
        if connect_flags & 0x04:  # will
            for _ in range(2):
                ln = struct.unpack("!H", body[pos:pos + 2])[0]
                pos += 2 + ln
        if connect_flags & 0x80:  # username
            ln = struct.unpack("!H", body[pos:pos + 2])[0]
            pos += 2
            username = body[pos:pos + ln].decode("utf-8", "replace")
            pos += ln
        log_event("CONNECT", client_id=self.client_id, username=username)
        # CONNACK: session present 0, return code 0 (accepted) — 인증은 전부 통과시킨다
        self.send_raw(bytes([0x20, 0x02, 0x00, 0x00]))

    def handle_subscribe(self, body: bytes) -> None:
        pid = struct.unpack("!H", body[:2])[0]
        pos = 2
        granted = []
        while pos < len(body):
            ln = struct.unpack("!H", body[pos:pos + 2])[0]
            pos += 2
            filt = body[pos:pos + ln].decode("utf-8", "replace")
            pos += ln
            qos = body[pos]
            pos += 1
            self.subs.append(filt)
            granted.append(min(qos, 1))
            log_event("SUBSCRIBE", client_id=self.client_id, topic=filt, qos=qos)
        payload = bytes(granted)
        self.send_raw(bytes([0x90]) + encode_remaining_length(2 + len(payload))
                      + struct.pack("!H", pid) + payload)

    def handle_publish(self, flags: int, body: bytes) -> None:
        qos = (flags >> 1) & 0x03
        retain = flags & 0x01
        tlen = struct.unpack("!H", body[:2])[0]
        topic = body[2:2 + tlen].decode("utf-8", "replace")
        pos = 2 + tlen
        pid = None
        if qos > 0:
            pid = struct.unpack("!H", body[pos:pos + 2])[0]
            pos += 2
        payload = body[pos:].decode("utf-8", "replace")
        log_event(
            "PUBLISH",
            client_id=self.client_id,
            topic=topic,
            qos=qos,
            retain=bool(retain),
            payload=payload,
        )
        if qos == 1 and pid is not None:
            self.send_raw(bytes([0x40, 0x02]) + struct.pack("!H", pid))
        self.route(topic, payload)

    def route(self, topic: str, payload: str) -> None:
        data = encode_string(topic) + payload.encode("utf-8")
        packet = bytes([0x30]) + encode_remaining_length(len(data)) + data  # QoS0 하향
        with _clients_lock:
            targets = [
                c for c in _clients
                if c.alive and any(topic_matches(f, topic) for f in c.subs)
            ]
        for c in targets:
            log_event("DELIVER", to=c.client_id, topic=topic)
            c.send_raw(packet)


def main() -> None:
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((HOST, PORT))
    srv.listen(16)
    log_event("LISTEN", host=HOST, port=PORT)
    while True:
        conn, addr = srv.accept()
        c = Client(conn, addr)
        with _clients_lock:
            _clients.append(c)
        c.start()


if __name__ == "__main__":
    main()
