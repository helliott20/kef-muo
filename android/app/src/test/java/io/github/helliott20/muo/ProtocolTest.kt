package io.github.helliott20.muo

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

/** Golden frames come from running the original app's decompiled SPPDataFormat class. */
class ProtocolTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test fun framesMatchOriginalApp() {
        val (p1, p2) = Protocol.namePackets("KEF MUO Living Room")
        assertArrayEquals(hex("5512fc00ffffffffffffffffffffffffffff000d"), Protocol.buildFrame(Protocol.CMD_GET_SETTING, 0))
        assertArrayEquals(hex("5512fe020101ffffffffffffffffffffffff080d"), Protocol.buildAck(2, 1))
        assertArrayEquals(hex("5512fd014b4546204d554f204c6976696e67800d"), Protocol.buildFrame(Protocol.CMD_SET_SETTING, 1, p1))
        assertArrayEquals(hex("5512fd0220526f6f6d000000000000000000ce0d"), Protocol.buildFrame(Protocol.CMD_SET_SETTING, 2, p2))
        assertArrayEquals(hex("5512fd0302040000ffffffffffffffffffff0e0d"),
            Protocol.buildFrame(Protocol.CMD_SET_SETTING, 3, Protocol.settingsPacket(2, 4)))
        assertArrayEquals(hex("5512fd0301010001ffffffffffffffffffff0b0d"),
            Protocol.buildFrame(Protocol.CMD_SET_SETTING, 3, Protocol.settingsPacket(1, 1, 0, 1)))
    }

    @Test fun parsesSpeakerFrames() {
        val ack = Protocol.parseFrame(hex("551201fd0301ffffffffffffffffffffffff080d"))!!
        assertEquals(listOf(Protocol.REC_ACK, 0xFD, 3, 1), listOf(ack.kind, ack.cmd, ack.pkg, ack.data[0].toInt()))
        val f = Protocol.parseFrame(hex("55120203312e3035073c01040056322e31303a0d"))!!
        val s = Protocol.Settings.fromPackets("MUO".toByteArray() + ByteArray(11), ByteArray(14), f.data)
        assertEquals("MUO", s.name); assertEquals("1.05", s.version); assertEquals("V2.10", s.btVersion)
        assertEquals(66, s.batteryPercent); assertEquals(4, s.mode)
        assertNull(Protocol.parseFrame(hex("55120203312e3035073c01040056322e31303b0d")))
    }

    @Test fun readerResyncsAcrossChunks() {
        val r = Protocol.FrameReader()
        val stream = hex("0055" + "99") + hex("551201fd0301ffffffffffffffffffffffff080d") + hex("55") +
            hex("55120203312e3035073c01040056322e31303a0d")
        val out = stream.toList().chunked(3).flatMap { r.feed(it.toByteArray()) }
        assertEquals(listOf(Protocol.REC_ACK, Protocol.REC_SETTING), out.map { it.kind })
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsLongNames() { Protocol.namePackets("x".repeat(29)) }
    @Test(expected = IllegalArgumentException::class) fun rejectsNonAscii() { Protocol.namePackets("Café") }

    /** Speaker-side state machine running on a thread, connected via pipes. */
    private class FakeSpeaker(private val toPhone: OutputStream) : OutputStream() {
        var name = "KEF MUO"; var eq = 1; var mode = 1; var poweredOff = false
        private val reader = Protocol.FrameReader()
        private val staged = HashMap<Int, ByteArray>()

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
        override fun write(b: ByteArray, off: Int, len: Int) {
            for (fr in reader.feed(b.copyOfRange(off, off + len))) handle(fr)
        }

        private fun mcuAck(cmd: Int, pkg: Int) {
            val f = ByteArray(20) { 0xFF.toByte() }
            byteArrayOf(0x55, 0x12, 0x01, cmd.toByte(), pkg.toByte(), 1).copyInto(f)
            f[18] = Protocol.checksum(f).toByte(); f[19] = 0x0D
            toPhone.write(f); toPhone.flush()
        }

        private fun sendPkg(pkg: Int) {
            val (p1, p2) = Protocol.namePackets(name)
            val p3 = "1.05".toByteArray() + byteArrayOf(7, 70, eq.toByte(), mode.toByte(), 0) + "V2.10".toByteArray()
            toPhone.write(Protocol.buildFrame(0x02, pkg, mapOf(1 to p1, 2 to p2, 3 to p3)[pkg])); toPhone.flush()
        }

        private fun handle(fr: Protocol.Frame) {
            when (fr.kind) {
                Protocol.CMD_GET_SETTING -> { mcuAck(0xFC, 0); sendPkg(1) }
                Protocol.CMD_ACK_FROM_PHONE -> if (fr.pkg == 0x02 && fr.data[0] < 3) sendPkg(fr.data[0] + 1)
                Protocol.CMD_SET_SETTING -> {
                    staged[fr.pkg] = fr.data
                    if (fr.pkg == 3) {
                        if (fr.data[3].toInt() == 1) poweredOff = true
                        else {
                            eq = fr.data[0].toInt(); mode = fr.data[1].toInt()
                            name = (String(staged[1]!!).trimEnd('\u0000') + String(staged[2]!!).trimEnd('\u0000'))
                        }
                    }
                    mcuAck(0xFD, fr.pkg)
                }
            }
        }
    }

    private fun session(): Pair<MuoSession, FakeSpeaker> {
        val phoneIn = PipedInputStream(4096)
        val speakerOut = PipedOutputStream(phoneIn)
        val speaker = FakeSpeaker(speakerOut)
        return MuoSession.forStreams(phoneIn, speaker, timeoutMs = 300) to speaker
    }

    @Test fun readRenameAndPowerOff() {
        val (s, speaker) = session()
        assertEquals("KEF MUO", s.getSettings().name)
        s.setSettings("Left Speaker In The Kitchen", 3, 4)
        val after = s.getSettings()
        assertEquals("Left Speaker In The Kitchen", after.name)
        assertEquals(3, after.eq); assertEquals(4, after.mode); assertEquals(81, after.batteryPercent)
        s.powerOff(after)
        assertEquals(true, speaker.poweredOff); assertEquals(4, speaker.mode)
        s.close()
    }
}
