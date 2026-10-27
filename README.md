# kef-muo

A replacement control app for the **KEF MUO** Bluetooth speaker, plus notes on how the original
app talked to it.

## Stereo pairing two MUOs: no app needed

The stereo/party pairing in the old KEF app was **only a guide**. It sent nothing to the speakers;
their firmware does all of it. So you can pair two MUOs right now:

1. Turn on speaker A and connect it to your phone as usual.
2. On speaker A, hold **POWER + PAIRING** together for 2 seconds. It searches for a partner and becomes **Left**.
3. Turn on speaker B. Make sure it is **not** connected to any phone. Hold **POWER + PAIRING** for 2 seconds.
   It searches for A and becomes **Right**.
4. Within 10–15 seconds they link.

* Stand both **upright** for **stereo** (L/R), or lay them **flat** for **party** (same mix on both).
  Or press POWER + PAIRING once on either speaker to toggle.
* Hold POWER + PAIRING on either speaker for 2 seconds to go back to a single speaker.

The new app has the same guide under **Stereo pairing guide**.

## Android app: `dist/muo-control-1.0.apk`

Sideload it: copy it to your phone, open it, and allow "install unknown apps" when asked.
Android 6 or later.

* Lists your paired speakers (MUOs first).
* Shows name, battery %, firmware and Bluetooth firmware versions, EQ and mode.
* Rename the speaker.
* Power the speaker off.
* **Experimental:** EQ preset (Home / Outdoor / Rock) and Single / Party / Stereo mode.
  These are in the speaker protocol, but KEF hid them in its app, so your firmware may ignore them.
* The stereo pairing guide.

Pair the MUO in Android's Bluetooth settings first. The app then opens a serial (SPP) control
channel alongside the normal audio connection.

Build it yourself:

```sh
cd android
./gradlew testDebugUnitTest assembleRelease   # needs the Android SDK (ANDROID_HOME)
# -> app/build/outputs/apk/release/app-release.apk
```

## Desktop tool: `python/muo.py`

The same protocol in one Python file, for a computer that has Bluetooth.

```sh
# Linux (BlueZ), after pairing:
python3 python/muo.py --addr 40:EF:4C:XX:XX:XX info
python3 python/muo.py --addr 40:EF:4C:XX:XX:XX rename "Left MUO"
# Windows / macOS: pairing creates a serial port; pip install pyserial, then
python  python/muo.py --port COM5 info
```

Commands: `info`, `rename NAME`, `eq home|outdoor|rock`, `mode single|party|stereo`, `off`.
Add `-v` to print the raw frames. Tests: `cd python && python3 -m unittest -v`.

## Firmware

**There is no firmware update to recover, and I don't recommend trying to write one.**

* The old app never had a firmware updater. It only *displayed* the version numbers.
  KEF never shipped a public MUO firmware image or a USB update tool.
* The USB port on the MUO is for charging. No documented USB data or update mode exists.
* Writing new firmware would mean opening the speaker, identifying the Bluetooth chip,
  dumping its flash over a debug interface, and changing code with no source and no
  vendor tools. A mistake bricks the speaker, and it would gain nothing you can't already do,
  because stereo pairing is already built into the current firmware (see above).

If you want to look anyway, the safe first step is read-only. Note the firmware and Bluetooth
versions the app shows for **both** speakers. If they differ, that is worth knowing:
mismatched firmware can stop two units pairing with each other.

## Protocol

See [docs/PROTOCOL.md](docs/PROTOCOL.md).
