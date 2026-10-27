# KEF MUO Bluetooth control protocol

Reverse-engineered from the official **KEF MUO** Android app, v2.1.9 (`com.kef.KEFMUO`,
built March 2017), mainly the `com.kef.Bluetooth` classes `SPPDataFormat`, `ReceiveDataImpl`,
`KEFBt50Setting` and `BluetoothControl`. Internally KEF called the speaker "BT50" / "GP50".

## What the app actually did

| Feature in the old app        | How it worked                                                                 |
|-------------------------------|-------------------------------------------------------------------------------|
| Stereo / party pairing guide  | **Instructions only.** No data is sent; the speakers' firmware does the pairing. |
| Rename speaker                | SPP "set settings" (below)                                                    |
| Battery, firmware version     | SPP "get settings"                                                            |
| EQ preset, single/party/stereo mode | In the protocol, but the controls were hidden (`visibility="gone"`)     |
| Auto power-off on app exit    | SPP "set settings" with the shutdown flag                                      |
| Music player, DLNA, radio     | Phone-side only (plays audio over normal A2DP)                                |
| Firmware update               | **Not present.** The app has no update or DFU code at all.                   |

## Transport

* Bluetooth Classic **RFCOMM / SPP**, UUID `00001101-0000-1000-8000-00805F9B34FB`.
* The speaker must already be paired. Audio stays on A2DP; SPP runs alongside it.
* The app only treated devices with address prefix `40:EF:4C` (and some hard-coded
  ranges inside it) as MUOs, excluding names containing "Soundbar".

## Frame format (both directions)

Every frame is exactly 20 bytes:

```
offset  0    1    2      3      4 .. 17          18         19
        55   12   <cmd>  <pkg>  <14-byte payload> <checksum> 0D
```

* `checksum = (byte[1] + byte[2] + ... + byte[17]) & 0xFF`
* Unused payload bytes are `FF`, except name packets, which are zero-padded.

### Phone → speaker

| cmd  | meaning        | pkg | payload                                         |
|------|----------------|-----|-------------------------------------------------|
| `FC` | get settings   | 0   | none (all `FF`)                                 |
| `FD` | set settings   | 1   | name bytes 0–13, zero-padded                    |
| `FD` | set settings   | 2   | name bytes 14–27, zero-padded                   |
| `FD` | set settings   | 3   | `[eq, mode, rec_light, shutdown, FF × 10]`      |
| `FE` | ACK            | –   | layout `FE <acked cmd> <pkg> <result>` (see below) |

### Speaker → phone

| byte 2 | meaning          | layout                                                     |
|--------|------------------|------------------------------------------------------------|
| `01`   | ACK of our cmd   | `55 12 01 <cmd> <pkg> <result> FF… <cs> 0D`; result 1 = OK, 2 = error/resend |
| `02`   | settings packet  | `55 12 02 <pkg> <14 bytes> <cs> 0D`                         |

Settings packets:

* pkg 1: name part 1 (ASCII/UTF-8, NUL-terminated)
* pkg 2: name part 2
* pkg 3:

  | byte | field                                              |
  |------|----------------------------------------------------|
  | 0–3  | firmware version string, e.g. `1.05`               |
  | 4    | firmware build number                              |
  | 5    | raw battery 0–100 (mapped to % by a lookup table)  |
  | 6    | EQ: 1 = Home (default), 2 = Outdoor, 3 = Rock      |
  | 7    | mode: 1 = single, 2 = party ("double"), 4 = stereo (3 = "right", defined but unused) |
  | 8    | "rec light": set when the speaker was picked by its button during pairing |
  | 9–13 | Bluetooth firmware version string                  |

## Exchanges

**Read settings**

```
phone   55 12 FC 00 FF…FF 00 0D
speaker 55 12 01 FC 00 01 …                (ACK)
speaker 55 12 02 01 <name part 1> …        → phone ACKs: 55 12 FE 02 01 01 FF… <cs> 0D
speaker 55 12 02 02 <name part 2> …        → phone ACKs  FE 02 02 01
speaker 55 12 02 03 <info>        …        → phone ACKs  FE 02 03 01
```

**Write settings.** The whole block is always written: name, then EQ/mode.

```
phone   FD 01 <name part 1>     speaker ACK 01 FD 01 01
phone   FD 02 <name part 2>     speaker ACK 01 FD 02 01
phone   FD 03 <eq mode 0 0 FF…> speaker ACK 01 FD 03 01
```

**Power off.** Send `FD 03` with byte 3 (shutdown) = 1. The app didn't wait for an ACK.

The app waited 1 s per step and resent on timeout or on an ACK with result 2.

## Verification

`python/test_muo.py` and `android/app/src/test/.../ProtocolTest.kt` contain golden frames.
I generated them by compiling the original app's decompiled `SPPDataFormat` class with a stub
logger and running it, so the new code matches the old app byte for byte. The speaker-side
behaviour in the tests is a simulation based on the app's own debug helper `printSPPCMD()`.
It has not been checked against real hardware yet.
