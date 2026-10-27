package io.github.helliott20.muo

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView

/** Lists paired speakers. Pairing itself is done in Android's Bluetooth settings, as with any speaker. */
class MainActivity : Activity() {
    private lateinit var list: LinearLayout
    private var askedPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "KEF MUO"
        val root = column()
        list = column(0)
        root.add(
            text("Pair your MUO in Android's Bluetooth settings first, then pick it below."),
            button("Stereo pairing guide (two MUOs)") { startActivity(Intent(this, StereoGuideActivity::class.java)) },
            button("Open Bluetooth settings") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            heading("Paired speakers"),
            list,
        )
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        if (hasConnectPermission()) refresh()
        else if (!askedPermission) {
            askedPermission = true
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (hasConnectPermission()) refresh()
        else showMessage("Bluetooth permission is needed to talk to the speaker.")
    }

    private fun hasConnectPermission() = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun showMessage(s: String) {
        list.removeAllViews()
        list.add(text(s))
    }

    @SuppressLint("MissingPermission")
    private fun refresh() {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null) return showMessage("This device has no Bluetooth.")
        if (!adapter.isEnabled) return showMessage("Bluetooth is off. Turn it on and come back.")
        val devices = adapter.bondedDevices.sortedByDescending { looksLikeMuo(it) }
        list.removeAllViews()
        if (devices.isEmpty()) return showMessage("No paired devices yet.")
        for (d in devices) {
            val label = (d.name ?: "Unknown") + if (looksLikeMuo(d)) "  (MUO)" else ""
            list.add(button("$label\n${d.address}") {
                startActivity(Intent(this, SpeakerActivity::class.java).putExtra(SpeakerActivity.EXTRA_ADDRESS, d.address))
            })
        }
    }

    companion object {
        /** KEF's MUO units use the 40:EF:4C address prefix; names default to "KEF MUO". */
        @SuppressLint("MissingPermission")
        fun looksLikeMuo(d: BluetoothDevice) =
            d.address.uppercase().startsWith("40:EF:4C") || (d.name ?: "").contains("MUO", ignoreCase = true)
    }
}
