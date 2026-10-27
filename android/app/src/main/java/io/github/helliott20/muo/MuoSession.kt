package io.github.helliott20.muo

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import io.github.helliott20.muo.Protocol.Frame

class MuoException(msg: String) : IOException(msg)

/**
 * One SPP connection to a speaker. All methods block, so call them off the main thread.
 * The handshake mirrors the original app: 1 s per attempt, resend on timeout or error ACK.
 */
class MuoSession private constructor(
    private val socket: BluetoothSocket?,
    private val input: InputStream,
    private val output: OutputStream,
    private val timeoutMs: Long = 1000,
    private val retries: Int = 5,
) {
    private val frames = LinkedBlockingQueue<Frame>()
    @Volatile private var closed = false

    private val readerThread = Thread {
        val reader = Protocol.FrameReader()
        val buf = ByteArray(256)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                reader.feed(buf, n).forEach { frames.put(it) }
            }
        } catch (_: IOException) {
        }
    }.apply { isDaemon = true; start() }

    private fun send(f: ByteArray) {
        output.write(f); output.flush()
    }

    private fun recv(ms: Long): Frame? = frames.poll(ms, TimeUnit.MILLISECONDS)

    fun getSettings(): Protocol.Settings {
        val packets = HashMap<Int, ByteArray>()
        repeat(retries) {
            frames.clear()
            send(Protocol.buildFrame(Protocol.CMD_GET_SETTING, 0))
            while (true) {
                val fr = recv(timeoutMs) ?: break
                if (fr.kind == Protocol.REC_SETTING) {
                    packets[fr.pkg] = fr.data
                    send(Protocol.buildAck(Protocol.REC_SETTING, fr.pkg))
                    if (fr.pkg == 3 && packets.containsKey(1) && packets.containsKey(2)) {
                        return Protocol.Settings.fromPackets(packets[1]!!, packets[2]!!, packets[3]!!)
                    }
                } else if (fr.kind == Protocol.REC_ACK && fr.cmd == Protocol.CMD_GET_SETTING &&
                    fr.data[0].toInt() == Protocol.RESULT_ERROR
                ) break
            }
        }
        throw MuoException("Speaker did not send its settings")
    }

    private fun sendPkg(pkg: Int, payload: ByteArray) {
        val frame = Protocol.buildFrame(Protocol.CMD_SET_SETTING, pkg, payload)
        repeat(retries) {
            send(frame)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                val fr = recv(left) ?: break
                if (fr.kind == Protocol.REC_ACK && fr.cmd == Protocol.CMD_SET_SETTING && fr.pkg == pkg) {
                    if (fr.data[0].toInt() == Protocol.RESULT_OK) return
                    break
                }
            }
        }
        throw MuoException("Speaker did not acknowledge setting $pkg")
    }

    /** Writes name + EQ + mode in one go, exactly as the original app did. */
    fun setSettings(name: String, eq: Int, mode: Int) {
        val (p1, p2) = Protocol.namePackets(name)
        sendPkg(1, p1)
        sendPkg(2, p2)
        sendPkg(3, Protocol.settingsPacket(eq, mode))
    }

    fun powerOff(current: Protocol.Settings) {
        send(Protocol.buildFrame(Protocol.CMD_SET_SETTING, 3, Protocol.settingsPacket(current.eq, current.mode, 0, 1)))
    }

    fun close() {
        closed = true
        try { socket?.close() } catch (_: IOException) {}
        try { input.close() } catch (_: IOException) {}
        try { output.close() } catch (_: IOException) {}
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** For tests: talk to an in-memory fake speaker. */
        fun forStreams(input: InputStream, output: OutputStream, timeoutMs: Long = 1000) =
            MuoSession(null, input, output, timeoutMs)

        @SuppressLint("MissingPermission")
        fun connect(device: BluetoothDevice): MuoSession {
            val attempts = listOf<(BluetoothDevice) -> BluetoothSocket>(
                { it.createRfcommSocketToServiceRecord(SPP_UUID) },
                { it.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
                // Some phones fail SDP lookup on old CSR chips; channel 1 is the usual SPP channel.
                { it.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType).invoke(it, 1) as BluetoothSocket },
            )
            var last: Exception? = null
            for (make in attempts) {
                var s: BluetoothSocket? = null
                try {
                    s = make(device)
                    s.connect()
                    return MuoSession(s, s.inputStream, s.outputStream)
                } catch (e: Exception) {
                    last = e
                    try { s?.close() } catch (_: IOException) {}
                }
            }
            throw MuoException("Could not open the speaker's control channel: ${last?.message}")
        }
    }
}
