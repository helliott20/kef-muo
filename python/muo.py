#!/usr/bin/env python3
"""Control a KEF MUO Bluetooth speaker over its SPP (serial) settings channel.

Protocol reverse-engineered from the official "KEF MUO" Android app v2.1.9
(see docs/PROTOCOL.md). Pair the speaker with your computer first, then:

    Linux:          python3 muo.py --addr 40:EF:4C:xx:xx:xx info
    Windows/macOS:  python3 muo.py --port COM5 info        (needs `pip install pyserial`)
                    python3 muo.py --port /dev/tty.KEFMUO-SerialPort info
"""

from __future__ import annotations

import argparse
import socket
import sys
import time
from dataclasses import dataclass

FRAME_LEN = 20
HEAD = 0x55
LEN_BYTE = 0x12  # 18
END = 0x0D
PAYLOAD_LEN = 14

# Command bytes (frame[2])
CMD_ACK_FROM_PHONE = 0xFE
CMD_SET_SETTING = 0xFD
CMD_GET_SETTING = 0xFC
REC_ACK = 0x01  # speaker acknowledging one of our commands
REC_SETTING = 0x02  # speaker sending a settings packet

RESULT_OK = 1
RESULT_ERROR = 2

EQ_NAMES = {1: "home", 2: "outdoor", 3: "rock"}
MODE_NAMES = {1: "single", 2: "party", 3: "right", 4: "stereo"}

SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"
KEF_OUI = "40:EF:4C"

# The speaker reports a raw battery reading 0..100 which the app mapped to a percentage.
BATTERY_TABLE = [
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 4, 6, 7, 9, 10, 12, 13, 14, 16,
    17, 18, 19, 20, 21, 23, 24, 26, 27, 29, 30, 32, 33, 35, 36, 38, 39, 40, 42, 44, 45,
    47, 48, 50, 51, 53, 54, 56, 57, 59, 60, 62, 63, 65, 66, 68, 69, 70, 72, 74, 75, 77,
    78, 80, 81, 83, 84, 86, 87, 89, 90, 92, 93, 94, 95, 96, 97, 98, 99, 100, 100, 100,
    100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100,
]


class MuoError(Exception):
    pass


def checksum(frame: bytes | bytearray) -> int:
    """8-bit sum of bytes 1..17 (length byte through end of payload)."""
    return sum(frame[1:18]) & 0xFF


def build_frame(cmd: int, pkg: int, payload: bytes | None = None, fill: int = 0xFF) -> bytes:
    f = bytearray([fill] * FRAME_LEN)
    f[0] = HEAD
    f[1] = LEN_BYTE
    f[2] = cmd & 0xFF
    f[3] = pkg & 0xFF
    if payload:
        if len(payload) > PAYLOAD_LEN:
            raise ValueError("payload too long")
        f[4:4 + len(payload)] = payload
    f[18] = checksum(f)
    f[19] = END
    return bytes(f)


def build_ack(rec_cmd: int, pkg: int, result: int = RESULT_OK) -> bytes:
    """Phone -> speaker acknowledgement of a settings packet."""
    f = bytearray([0xFF] * FRAME_LEN)
    f[0], f[1], f[2], f[3], f[4], f[5] = HEAD, LEN_BYTE, CMD_ACK_FROM_PHONE, rec_cmd, pkg, result
    f[18] = checksum(f)
    f[19] = END
    return bytes(f)


@dataclass
class Frame:
    kind: int  # REC_ACK or REC_SETTING (or anything else the speaker sends)
    cmd: int  # for ACK: the command being acked; else == kind
    pkg: int
    data: bytes  # ACK: 1 byte result; else 14 byte payload


def parse_frame(f: bytes) -> Frame:
    if len(f) != FRAME_LEN or f[0] != HEAD or f[1] != LEN_BYTE or f[19] != END:
        raise MuoError(f"malformed frame: {f.hex(' ')}")
    if checksum(f) != f[18]:
        raise MuoError(f"bad checksum: {f.hex(' ')}")
    if f[2] == REC_ACK:
        return Frame(REC_ACK, f[3], f[4], bytes([f[5]]))
    return Frame(f[2], f[2], f[3], bytes(f[4:18]))


class FrameReader:
    """Re-synchronising byte-stream -> frame splitter."""

    def __init__(self):
        self.buf = bytearray()

    def feed(self, data: bytes) -> list[Frame]:
        self.buf += data
        out = []
        while True:
            i = self._find_header()
            if i < 0:
                # keep a trailing 0x55 in case the 0x12 is in the next chunk
                self.buf = self.buf[-1:] if self.buf[-1:] == bytes([HEAD]) else bytearray()
                return out
            del self.buf[:i]
            if len(self.buf) < FRAME_LEN:
                return out
            candidate = bytes(self.buf[:FRAME_LEN])
            try:
                out.append(parse_frame(candidate))
                del self.buf[:FRAME_LEN]
            except MuoError:
                del self.buf[:1]  # false header, slide forward

    def _find_header(self) -> int:
        for i in range(len(self.buf) - 1):
            if self.buf[i] == HEAD and self.buf[i + 1] == LEN_BYTE:
                return i
        return -1


def _cstr(b: bytes) -> str:
    return b.split(b"\x00", 1)[0].split(b"\xff", 1)[0].decode("utf-8", "replace")


@dataclass
class Settings:
    name: str = ""
    version: str = ""
    version_num: int = 0
    battery_raw: int = 0
    eq: int = 1
    mode: int = 1
    rec_light: int = 0
    bt_version: str = ""

    @property
    def battery_percent(self) -> int:
        return BATTERY_TABLE[self.battery_raw] if 0 <= self.battery_raw <= 100 else 0

    @classmethod
    def from_packets(cls, p1: bytes, p2: bytes, p3: bytes) -> "Settings":
        return cls(
            name=(_cstr(p1) + _cstr(p2)).strip(),
            version=_cstr(p3[0:4]),
            version_num=p3[4],
            battery_raw=p3[5],
            eq=p3[6],
            mode=p3[7],
            rec_light=p3[8],
            bt_version=_cstr(p3[9:14]),
        )

    def __str__(self) -> str:
        return (
            f"Name:        {self.name}\n"
            f"Battery:     {self.battery_percent}%  (raw {self.battery_raw})\n"
            f"Firmware:    {self.version} (build {self.version_num})\n"
            f"BT firmware: {self.bt_version}\n"
            f"EQ:          {EQ_NAMES.get(self.eq, self.eq)}\n"
            f"Mode:        {MODE_NAMES.get(self.mode, self.mode)}"
        )


def name_packets(name: str) -> tuple[bytes, bytes]:
    raw = name.encode("ascii", "strict")
    if not raw or len(raw) > 2 * PAYLOAD_LEN:
        raise ValueError(f"name must be 1..{2 * PAYLOAD_LEN} ASCII characters")
    p1 = raw[:PAYLOAD_LEN].ljust(PAYLOAD_LEN, b"\x00")
    p2 = raw[PAYLOAD_LEN:].ljust(PAYLOAD_LEN, b"\x00")
    return p1, p2


def settings_packet(eq: int, mode: int, rec_light: int = 0, shutdown: int = 0) -> bytes:
    return bytes([eq, mode, rec_light, shutdown]) + b"\xff" * (PAYLOAD_LEN - 4)


class Muo:
    def __init__(self, transport, timeout: float = 1.0, retries: int = 5, verbose: bool = False):
        self.t = transport
        self.timeout = timeout
        self.retries = retries
        self.verbose = verbose
        self.reader = FrameReader()
        self.pending: list[Frame] = []

    def _send(self, frame: bytes):
        if self.verbose:
            print(">>", frame.hex(" "), file=sys.stderr)
        self.t.write(frame)

    def _recv(self, timeout: float) -> Frame | None:
        deadline = time.monotonic() + timeout
        while not self.pending:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                return None
            chunk = self.t.read(remaining)
            if chunk:
                self.pending += self.reader.feed(chunk)
        fr = self.pending.pop(0)
        if self.verbose:
            print("<<", fr, file=sys.stderr)
        return fr

    def get_settings(self) -> Settings:
        packets: dict[int, bytes] = {}
        for _ in range(self.retries):
            self._send(build_frame(CMD_GET_SETTING, 0))
            while True:
                fr = self._recv(self.timeout)
                if fr is None:
                    break  # timed out: resend the request
                if fr.kind == REC_SETTING:
                    packets[fr.pkg] = fr.data
                    self._send(build_ack(REC_SETTING, fr.pkg, RESULT_OK))
                    if fr.pkg == 3 and 1 in packets and 2 in packets:
                        return Settings.from_packets(packets[1], packets[2], packets[3])
                elif fr.kind == REC_ACK and fr.cmd == CMD_GET_SETTING and fr.data[0] == RESULT_ERROR:
                    break
        raise MuoError("speaker did not return its settings")

    def _send_pkg(self, pkg: int, payload: bytes):
        frame = build_frame(CMD_SET_SETTING, pkg, payload)
        for _ in range(self.retries):
            self._send(frame)
            deadline = time.monotonic() + self.timeout
            while (left := deadline - time.monotonic()) > 0:
                fr = self._recv(left)
                if fr is None:
                    break
                if fr.kind == REC_ACK and fr.cmd == CMD_SET_SETTING and fr.pkg == pkg:
                    if fr.data[0] == RESULT_OK:
                        return
                    break  # RESULT_ERROR: resend
        raise MuoError(f"speaker did not acknowledge settings packet {pkg}")

    def set_settings(self, name: str, eq: int, mode: int):
        """Write the full settings block (name + EQ + mode), as the original app did."""
        p1, p2 = name_packets(name)
        self._send_pkg(1, p1)
        self._send_pkg(2, p2)
        self._send_pkg(3, settings_packet(eq, mode))

    def power_off(self, current: Settings | None = None):
        cur = current or self.get_settings()
        frame = build_frame(CMD_SET_SETTING, 3, settings_packet(cur.eq, cur.mode, 0, 1))
        self._send(frame)  # the original app did not wait for an ack here


class SocketTransport:
    """Linux BlueZ native RFCOMM socket."""

    def __init__(self, addr: str, channel: int):
        if not hasattr(socket, "AF_BLUETOOTH"):
            raise MuoError("this Python has no AF_BLUETOOTH support; use --port instead")
        self.s = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
        self.s.connect((addr, channel))

    def write(self, data: bytes):
        self.s.sendall(data)

    def read(self, timeout: float) -> bytes:
        self.s.settimeout(timeout)
        try:
            return self.s.recv(256)
        except socket.timeout:
            return b""

    def close(self):
        self.s.close()


class SerialTransport:
    """A paired speaker's virtual COM port (Windows) or /dev/tty.* / /dev/rfcomm* device."""

    def __init__(self, port: str):
        try:
            import serial  # type: ignore
        except ImportError:
            raise MuoError("pip install pyserial  (needed for --port)")
        self.s = serial.Serial(port, 115200, timeout=0.1)

    def write(self, data: bytes):
        self.s.write(data)
        self.s.flush()

    def read(self, timeout: float) -> bytes:
        self.s.timeout = timeout
        return self.s.read(256)

    def close(self):
        self.s.close()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--addr", help="speaker Bluetooth address (Linux)")
    g.add_argument("--port", help="serial port of the paired speaker (COMx, /dev/tty.*, /dev/rfcommN)")
    ap.add_argument("--channel", type=int, default=1, help="RFCOMM channel (default 1)")
    ap.add_argument("-v", "--verbose", action="store_true", help="print raw frames")
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("info", help="show name, battery, firmware, EQ and mode")
    p = sub.add_parser("rename", help="rename the speaker (max 28 ASCII chars)")
    p.add_argument("name")
    p = sub.add_parser("eq", help="(experimental) set EQ preset")
    p.add_argument("preset", choices=list(EQ_NAMES.values()))
    p = sub.add_parser("mode", help="(experimental) set single/party/stereo mode")
    p.add_argument("mode", choices=["single", "party", "stereo"])
    sub.add_parser("off", help="power the speaker off")
    a = ap.parse_args(argv)

    t = SocketTransport(a.addr, a.channel) if a.addr else SerialTransport(a.port)
    try:
        m = Muo(t, verbose=a.verbose)
        cur = m.get_settings()
        if a.cmd == "info":
            print(cur)
        elif a.cmd == "rename":
            m.set_settings(a.name, cur.eq, cur.mode)
            print(f"Renamed to {a.name!r}. Re-pair or toggle Bluetooth to see the new name.")
        elif a.cmd == "eq":
            eq = {v: k for k, v in EQ_NAMES.items()}[a.preset]
            m.set_settings(cur.name, eq, cur.mode)
            print(m.get_settings())
        elif a.cmd == "mode":
            mode = {v: k for k, v in MODE_NAMES.items()}[a.mode]
            m.set_settings(cur.name, cur.eq, mode)
            print(m.get_settings())
        elif a.cmd == "off":
            m.power_off(cur)
            print("Power-off sent.")
    finally:
        t.close()


if __name__ == "__main__":
    try:
        main()
    except MuoError as e:
        sys.exit(f"error: {e}")
