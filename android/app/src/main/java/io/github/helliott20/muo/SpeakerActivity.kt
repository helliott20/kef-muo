package io.github.helliott20.muo

import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import java.util.concurrent.Executors

class SpeakerActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var session: MuoSession? = null
    private var current: Protocol.Settings? = null

    private lateinit var status: android.widget.TextView
    private lateinit var info: android.widget.TextView
    private lateinit var controls: LinearLayout
    private lateinit var eqGroup: RadioGroup
    private lateinit var modeGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MUO settings"
        val root = column()
        status = text("Connecting…")
        info = text("", 18f)
        eqGroup = radioGroup(Protocol.EQ_NAMES)
        modeGroup = radioGroup(Protocol.MODE_NAMES)
        controls = column(0).apply {
            add(
                button("Rename speaker") { promptRename() },
                button("Refresh") { run("Reading settings…") { it.getSettings() } },
                heading("Sound preset (experimental)"),
                text("Hidden in the official app. Your firmware may ignore it.", 13f),
                eqGroup,
                heading("Two-speaker mode (experimental)"),
                text("Only matters once two MUOs are linked (see the stereo guide). " +
                    "The buttons on the speaker, or standing both speakers upright (stereo) or flat (party), " +
                    "are the official way to switch.", 13f),
                modeGroup,
                button("Apply sound / mode") { applyEqMode() },
                heading("Other"),
                button("Stereo pairing guide") { startActivity(Intent(this@SpeakerActivity, StereoGuideActivity::class.java)) },
                button("Power off speaker") { confirmPowerOff() },
            )
            visibility = View.GONE
        }
        root.add(status, info, controls)
        setContentView(ScrollView(this).apply { addView(root) })
        connect()
    }

    private fun radioGroup(options: Map<Int, String>) = RadioGroup(this).apply {
        orientation = RadioGroup.HORIZONTAL
        options.forEach { (id, label) -> addView(RadioButton(this@SpeakerActivity).apply { this.id = id; text = label }) }
    }

    private fun connect() {
        val address = intent.getStringExtra(EXTRA_ADDRESS) ?: return finish()
        val device = getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(address)
        worker.execute {
            try {
                val s = MuoSession.connect(device)
                session = s
                val settings = s.getSettings()
                runOnUiThread { show(settings, "Connected") }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Couldn't connect: ${e.message}\n\nMake sure the speaker is on, paired, and not " +
                        "connected to another phone's KEF app. Then go back and try again."
                }
            }
        }
    }

    private fun show(s: Protocol.Settings, msg: String) {
        current = s
        status.text = msg
        info.text = buildString {
            appendLine(s.name.ifEmpty { "(no name)" })
            appendLine("Battery: ${s.batteryPercent}%")
            appendLine("Firmware: ${s.version} (build ${s.versionNum})   Bluetooth: ${s.btVersion}")
            appendLine("Sound: ${Protocol.EQ_NAMES[s.eq] ?: s.eq}   Mode: ${Protocol.MODE_NAMES[s.mode] ?: s.mode}")
        }
        eqGroup.check(s.eq)
        modeGroup.check(s.mode)
        controls.visibility = View.VISIBLE
    }

    /** Runs a speaker operation in the background, then shows the settings it returns. */
    private fun run(busy: String, op: (MuoSession) -> Protocol.Settings) {
        val s = session ?: return
        status.text = busy
        worker.execute {
            try {
                val result = op(s)
                runOnUiThread { show(result, "Done") }
            } catch (e: Exception) {
                runOnUiThread { status.text = "Failed: ${e.message}" }
            }
        }
    }

    private fun promptRename() {
        val cur = current ?: return
        val input = EditText(this).apply {
            setText(cur.name)
            filters = arrayOf(InputFilter.LengthFilter(Protocol.MAX_NAME))
        }
        AlertDialog.Builder(this)
            .setTitle("Rename speaker")
            .setMessage("Up to ${Protocol.MAX_NAME} plain characters (A-Z, 0-9, spaces, punctuation).")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                try {
                    Protocol.namePackets(name)
                } catch (e: IllegalArgumentException) {
                    status.text = e.message
                    return@setPositiveButton
                }
                run("Renaming…") { it.setSettings(name, cur.eq, cur.mode); it.getSettings() }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun applyEqMode() {
        val cur = current ?: return
        val eq = eqGroup.checkedRadioButtonId.takeIf { it > 0 } ?: cur.eq
        val mode = modeGroup.checkedRadioButtonId.takeIf { it > 0 } ?: cur.mode
        run("Applying…") { it.setSettings(cur.name, eq, mode); it.getSettings() }
    }

    private fun confirmPowerOff() {
        val cur = current ?: return
        AlertDialog.Builder(this)
            .setTitle("Power off the speaker?")
            .setPositiveButton("Power off") { _, _ ->
                worker.execute {
                    try { session?.powerOff(cur) } catch (_: Exception) {}
                    runOnUiThread { finish() }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        val s = session
        worker.execute { s?.close() }
        worker.shutdown()
    }

    companion object {
        const val EXTRA_ADDRESS = "address"
    }
}
