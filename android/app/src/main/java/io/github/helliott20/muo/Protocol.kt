package io.github.helliott20.muo

/**
 * KEF MUO SPP settings protocol, reverse-engineered from the official app (v2.1.9).
 * See docs/PROTOCOL.md. Every frame is 20 bytes:
 *
 *   55 12 <cmd> <pkg> <14 payload bytes> <sum(bytes 1..17) & 0xFF> 0D
 */
object Protocol {
    const val FRAME_LEN = 20
    const val PAYLOAD_LEN = 14
    const val HEAD = 0x55
    const val LEN_BYTE = 0x12
    const val END = 0x0D

    const val CMD_ACK_FROM_PHONE = 0xFE
    const val CMD_SET_SETTING = 0xFD
    const val CMD_GET_SETTING = 0xFC
    const val REC_ACK = 0x01
    const val REC_SETTING = 0x02

    const val RESULT_OK = 1
    const val RESULT_ERROR = 2

    const val MAX_NAME = 2 * PAYLOAD_LEN

    val EQ_NAMES = linkedMapOf(1 to "Home", 2 to "Outdoor", 3 to "Rock")
    val MODE_NAMES = linkedMapOf(1 to "Single", 2 to "Party", 4 to "Stereo")

    private val BATTERY_TABLE = intArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 3, 4, 6, 7, 9, 10, 12, 13, 14, 16,
        17, 18, 19, 20, 21, 23, 24, 26, 27, 29, 30, 32, 33, 35, 36, 38, 39, 40, 42, 44, 45,
        47, 48, 50, 51, 53, 54, 56, 57, 59, 60, 62, 63, 65, 66, 68, 69, 70, 72, 74, 75, 77,
        78, 80, 81, 83, 84, 86, 87, 89, 90, 92, 93, 94, 95, 96, 97, 98, 99, 100, 100, 100,
        100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100, 100,
    )

    fun batteryPercent(raw: Int): Int = if (raw in BATTERY_TABLE.indices) BATTERY_TABLE[raw] else 0

    fun checksum(f: ByteArray): Int {
        var s = 0
        for (i in 1..17) s += f[i].toInt() and 0xFF
        return s and 0xFF
    }

    fun buildFrame(cmd: Int, pkg: Int, payload: ByteArray? = null): ByteArray {
        val f = ByteArray(FRAME_LEN) { 0xFF.toByte() }
        f[0] = HEAD.toByte(); f[1] = LEN_BYTE.toByte(); f[2] = cmd.toByte(); f[3] = pkg.toByte()
        if (payload != null) {
            require(payload.size <= PAYLOAD_LEN) { "payload too long" }
            payload.copyInto(f, 4)
        }
        f[18] = checksum(f).toByte(); f[19] = END.toByte()
        return f
    }

    fun buildAck(recCmd: Int, pkg: Int, result: Int = RESULT_OK): ByteArray {
        val f = ByteArray(FRAME_LEN) { 0xFF.toByte() }
        f[0] = HEAD.toByte(); f[1] = LEN_BYTE.toByte(); f[2] = CMD_ACK_FROM_PHONE.toByte()
        f[3] = recCmd.toByte(); f[4] = pkg.toByte(); f[5] = result.toByte()
        f[18] = checksum(f).toByte(); f[19] = END.toByte()
        return f
    }

    /** Throws IllegalArgumentException for names the speaker can't store. */
    fun namePackets(name: String): Pair<ByteArray, ByteArray> {
        require(name.isNotEmpty() && name.length <= MAX_NAME) { "Name must be 1-$MAX_NAME characters" }
        require(name.all { it.code in 0x20..0x7E }) { "Name must use plain ASCII characters" }
        val raw = name.toByteArray(Charsets.US_ASCII)
        val p1 = ByteArray(PAYLOAD_LEN); val p2 = ByteArray(PAYLOAD_LEN)
        raw.copyInto(p1, 0, 0, minOf(raw.size, PAYLOAD_LEN))
        if (raw.size > PAYLOAD_LEN) raw.copyInto(p2, 0, PAYLOAD_LEN, raw.size)
        return p1 to p2
    }

    fun settingsPacket(eq: Int, mode: Int, recLight: Int = 0, shutdown: Int = 0): ByteArray {
        val p = ByteArray(PAYLOAD_LEN) { 0xFF.toByte() }
        p[0] = eq.toByte(); p[1] = mode.toByte(); p[2] = recLight.toByte(); p[3] = shutdown.toByte()
        return p
    }

    /** kind = REC_ACK (cmd/pkg/1-byte result) or REC_SETTING (pkg + 14-byte payload). */
    class Frame(val kind: Int, val cmd: Int, val pkg: Int, val data: ByteArray)

    fun parseFrame(f: ByteArray): Frame? {
        if (f.size != FRAME_LEN || u(f[0]) != HEAD || u(f[1]) != LEN_BYTE || u(f[19]) != END) return null
        if (checksum(f) != u(f[18])) return null
        return if (u(f[2]) == REC_ACK) Frame(REC_ACK, u(f[3]), u(f[4]), byteArrayOf(f[5]))
        else Frame(u(f[2]), u(f[2]), u(f[3]), f.copyOfRange(4, 18))
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    /** Splits a byte stream into frames, re-synchronising on noise. */
    class FrameReader {
        private val buf = ArrayList<Byte>()

        fun feed(data: ByteArray, len: Int = data.size): List<Frame> {
            for (i in 0 until len) buf.add(data[i])
            val out = ArrayList<Frame>()
            while (true) {
                val i = findHeader()
                if (i < 0) {
                    val keepHead = buf.isNotEmpty() && u(buf.last()) == HEAD
                    buf.clear(); if (keepHead) buf.add(HEAD.toByte())
                    return out
                }
                repeat(i) { buf.removeAt(0) }
                if (buf.size < FRAME_LEN) return out
                val frame = parseFrame(ByteArray(FRAME_LEN) { buf[it] })
                if (frame != null) {
                    out.add(frame); repeat(FRAME_LEN) { buf.removeAt(0) }
                } else {
                    buf.removeAt(0)
                }
            }
        }

        private fun findHeader(): Int {
            for (i in 0 until buf.size - 1) if (u(buf[i]) == HEAD && u(buf[i + 1]) == LEN_BYTE) return i
            return -1
        }
    }

    data class Settings(
        val name: String,
        val version: String,
        val versionNum: Int,
        val batteryRaw: Int,
        val eq: Int,
        val mode: Int,
        val recLight: Int,
        val btVersion: String,
    ) {
        val batteryPercent get() = batteryPercent(batteryRaw)

        companion object {
            fun fromPackets(p1: ByteArray, p2: ByteArray, p3: ByteArray) = Settings(
                name = (cstr(p1) + cstr(p2)).trim(),
                version = cstr(p3.copyOfRange(0, 4)),
                versionNum = u(p3[4]),
                batteryRaw = u(p3[5]),
                eq = u(p3[6]),
                mode = u(p3[7]),
                recLight = u(p3[8]),
                btVersion = cstr(p3.copyOfRange(9, 14)),
            )

            private fun cstr(b: ByteArray): String {
                val end = b.indexOfFirst { it == 0.toByte() || it == 0xFF.toByte() }.let { if (it < 0) b.size else it }
                return String(b, 0, end, Charsets.UTF_8)
            }
        }
    }
}
