"""Tests for muo.py. Golden frames were produced by running the original app's
decompiled SPPDataFormat class, so these pin byte-for-byte compatibility."""

import unittest

import muo

GOLDEN = {
    "get": "5512fc00ffffffffffffffffffffffffffff000d",
    "ack2_1": "5512fe020101ffffffffffffffffffffffff080d",
    "ack2_3": "5512fe020301ffffffffffffffffffffffff0a0d",
    "set1": "5512fd014b4546204d554f204c6976696e67800d",
    "set2": "5512fd0220526f6f6d000000000000000000ce0d",
    "set3": "5512fd0302040000ffffffffffffffffffff0e0d",
    "off": "5512fd0301010001ffffffffffffffffffff0b0d",
    "mcuack_fd3": "551201fd0301ffffffffffffffffffffffff080d",
    "spk3": "55120203312e3035073c01040056322e31303a0d",
}


def g(k):
    return bytes.fromhex(GOLDEN[k])


class FrameTests(unittest.TestCase):
    def test_matches_original_app(self):
        p1, p2 = muo.name_packets("KEF MUO Living Room")
        self.assertEqual(muo.build_frame(muo.CMD_GET_SETTING, 0), g("get"))
        self.assertEqual(muo.build_ack(2, 1), g("ack2_1"))
        self.assertEqual(muo.build_ack(2, 3), g("ack2_3"))
        self.assertEqual(muo.build_frame(muo.CMD_SET_SETTING, 1, p1), g("set1"))
        self.assertEqual(muo.build_frame(muo.CMD_SET_SETTING, 2, p2), g("set2"))
        self.assertEqual(muo.build_frame(muo.CMD_SET_SETTING, 3, muo.settings_packet(2, 4)), g("set3"))
        self.assertEqual(muo.build_frame(muo.CMD_SET_SETTING, 3, muo.settings_packet(1, 1, 0, 1)), g("off"))

    def test_parse_speaker_frames(self):
        ack = muo.parse_frame(g("mcuack_fd3"))
        self.assertEqual((ack.kind, ack.cmd, ack.pkg, ack.data), (muo.REC_ACK, 0xFD, 3, b"\x01"))
        s = muo.parse_frame(g("spk3"))
        self.assertEqual((s.kind, s.pkg), (muo.REC_SETTING, 3))
        st = muo.Settings.from_packets(b"MUO\0" + b"\0" * 10, b"\0" * 14, s.data)
        self.assertEqual((st.name, st.version, st.version_num, st.eq, st.mode, st.bt_version),
                         ("MUO", "1.05", 7, 1, 4, "V2.10"))
        self.assertEqual(st.battery_raw, 60)
        self.assertEqual(st.battery_percent, 66)

    def test_bad_checksum_rejected(self):
        f = bytearray(g("spk3"))
        f[18] ^= 1
        with self.assertRaises(muo.MuoError):
            muo.parse_frame(bytes(f))

    def test_reader_resyncs_through_noise_and_splits(self):
        r = muo.FrameReader()
        stream = b"\x00\x55\x99" + g("mcuack_fd3") + b"\x55" + g("spk3")
        out = []
        for i in range(0, len(stream), 3):
            out += r.feed(stream[i:i + 3])
        self.assertEqual([f.kind for f in out], [muo.REC_ACK, muo.REC_SETTING])

    def test_name_limits(self):
        with self.assertRaises(ValueError):
            muo.name_packets("x" * 29)
        with self.assertRaises(ValueError):
            muo.name_packets("")
        with self.assertRaises(UnicodeEncodeError):
            muo.name_packets("Café")


class FakeSpeaker:
    """Speaker-side state machine, modelled on the app's own printSPPCMD() notes."""

    def __init__(self, drop_first=0):
        self.name = "KEF MUO"
        self.eq, self.mode, self.raw_batt = 1, 1, 70
        self.powered = True
        self.out = bytearray()
        self.reader = muo.FrameReader()
        self.drop = drop_first
        self.staged = {}

    def _mcu_ack(self, cmd, pkg, res=1):
        f = bytearray(b"\xff" * 20)
        f[0:6] = bytes([0x55, 0x12, 0x01, cmd, pkg, res])
        f[18] = muo.checksum(f)
        f[19] = 0x0D
        self.out += f

    def _send_pkg(self, pkg):
        p1, p2 = muo.name_packets(self.name)
        p3 = b"1.05" + bytes([7, self.raw_batt, self.eq, self.mode, 0]) + b"V2.10"
        self.out += muo.build_frame(0x02, pkg, {1: p1, 2: p2, 3: p3}[pkg])

    def write(self, data):
        for fr in self.reader.feed(data):
            if self.drop:
                self.drop -= 1
                continue
            if fr.kind == muo.CMD_GET_SETTING:
                self._mcu_ack(0xFC, 0)
                self._send_pkg(1)
            elif fr.kind == muo.CMD_ACK_FROM_PHONE:
                # phone ACK layout is FE <acked cmd> <pkg> <result>
                acked_cmd, pkg = fr.pkg, fr.data[0]
                if acked_cmd == 0x02 and pkg < 3:
                    self._send_pkg(pkg + 1)
            elif fr.kind == muo.CMD_SET_SETTING:
                self.staged[fr.pkg] = fr.data
                if fr.pkg == 3:
                    d = fr.data
                    if d[3] == 1:
                        self.powered = False
                    else:
                        self.eq, self.mode = d[0], d[1]
                        if 1 in self.staged and 2 in self.staged:
                            self.name = (muo._cstr(self.staged[1]) + muo._cstr(self.staged[2]))
                self._mcu_ack(0xFD, fr.pkg)

    def read(self, timeout):
        data, self.out = bytes(self.out), bytearray()
        return data

    def close(self):
        pass


class SessionTests(unittest.TestCase):
    def test_get_settings(self):
        sp = FakeSpeaker()
        s = muo.Muo(sp, timeout=0.05).get_settings()
        self.assertEqual(s.name, "KEF MUO")
        self.assertEqual(s.battery_percent, 81)

    def test_get_settings_retries_after_drop(self):
        sp = FakeSpeaker(drop_first=1)
        s = muo.Muo(sp, timeout=0.05).get_settings()
        self.assertEqual(s.version, "1.05")

    def test_rename_eq_mode_roundtrip(self):
        sp = FakeSpeaker()
        m = muo.Muo(sp, timeout=0.05)
        m.set_settings("Left Speaker In The Kitchen", 3, 4)
        s = m.get_settings()
        self.assertEqual((s.name, s.eq, s.mode), ("Left Speaker In The Kitchen", 3, 4))

    def test_power_off_keeps_eq_mode(self):
        sp = FakeSpeaker()
        sp.eq, sp.mode = 2, 4
        muo.Muo(sp, timeout=0.05).power_off()
        self.assertFalse(sp.powered)
        self.assertEqual((sp.eq, sp.mode), (2, 4))


if __name__ == "__main__":
    unittest.main()
