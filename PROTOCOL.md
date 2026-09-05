# FitPro1 Protocol Specification

**FitPro1** is the application protocol spoken between a smart bike's console
(its built-in host) and the machine's onboard electronics (the "brainboard").
This document is a complete specification of that protocol.

It is written in vendor-neutral terms on purpose: it names the constants the
protocol uses and describes what each is used for, but it does not identify the
manufacturer, product, or any first-party software. The project's description of
how these details were obtained, and its position on them, is set out in the
[README](./README.md). This document describes the protocol only.

The protocol is unauthenticated and unencrypted. It has a **transport-independent
core** (frames, commands, and the data fields — §3–§8) plus two interchangeable
**transports** that differ only in how they pack frames onto the link (USB — §2.1,
and Bluetooth LE — §2.2). On the console this project targets the **USB**
transport is the one in use; the brainboard there carries no BLE radio, so the BLE
transport is documented for completeness and for other units.

---

## 1. Overview

- The brainboard is a small embedded microcontroller that owns the machine's
  actuator state (motor / resistance / incline) and its sensors. The console is
  the host that reads that state and issues control commands.
- FitPro1 is a simple request/response protocol: the host sends one **frame**
and the board answers with one **frame**. There is no connection setup beyond
the link itself.
- Two transports carry the same core:
  - **USB** — the board enumerates as a two-endpoint device (vendor `0x213c`,
    product `0x0002`) and frames move as 64-byte bulk packets. This is the
    transport used on the target console.
  - **Bluetooth LE (BLE)** — the board exposes a private GATT service and frames
    move as wrapped, chunked GATT writes/notifications. Used on other units.
- All multi-byte integers on the wire are **little-endian** unless stated.
  All lengths are in bytes.

---

## 2. Transports

### 2.1 USB (the transport used on the target console)

**Identity.** The board enumerates over USB with vendor ID `0x213c` and product
ID `0x0002`. A host selects it on that pair.

**Connection.** The host opens the device, claims **interface 0** (forcing the
claim past any other driver), and uses its two endpoints: one **IN** endpoint
(board → host, the "read" path) and one **OUT** endpoint (host → board, the
"write" path). The endpoint descriptors mark these as 64-byte HID-interrupt
endpoints, but both are driven as **bulk** transfers.

**Write.** One request frame is written as a **single bulk OUT** transfer
(50 ms timeout). There is no framing wrapper on this transport: the raw frame
of §3 goes out as-is.

**Read.** One response is a **single 64-byte bulk IN** transfer (50 ms timeout).
When the board has nothing to send it emits a **0xFF-prefilled keep-alive /
empty packet**; the host retries the read (up to 5 times) while the first byte
of the packet is `0xFF`, and treats a first byte of `0xFF` as "no frame."

**Response framing (verified against the live device).** The response packet
carries the FitPro1 frame **starting at byte 0**, **padded with `0xFF` out to
64 bytes**. Unlike the BLE transport — where a response is preceded by the
4-byte wrapper and split into group packets (§2.2) — there is **no wrapper or
manager header** on the USB transport. The frame is self-delimiting:

```
[0]      = dev
[1]      = len          (total frame length, including the trailing checksum)
[2]      = cmd
[3 .. len-2] = content
[len-1]  = checksum
```

The host therefore extracts a response by taking the first `len` bytes of the
packet and dropping the `0xFF` padding.

**Over-length frames.** A frame's declared `len` may exceed 64. The periodic
telemetry read (§7) declares **~90 bytes**; the board delivers only the **first
64 bytes** in a single packet and does not send the remainder. The protocol
layer tolerates this: when the declared `len` is larger than the number of bytes
actually received, the host accepts the frame if its shape is sane (a non-zero
`dev`, a matching `cmd`, a plausible size, and a status that is not a
keep-alive marker) and decodes the fields that fit within the bytes delivered.
The trailing checksum cannot be verified in this case (it lies in the undelivered
tail) and is not required.

**Flush.** Writing a full 64-byte frame of `0xFF` makes the board answer with a
64-byte all-`0xFF` packet (byte 3 treated as a wildcard). The host uses this to
clear the board's input buffer when a (re)connection is established, and runs it
immediately after the link is up, before the first real command. A flush that
does not confirm is logged but is not fatal — the session setup proceeds either
way.

**Failure handling.** The host serializes every exchange on a single
request/response channel (the periodic read pump and any control write never
interleave). Consecutive failed reads are counted; a run of them is treated as
loss of the link and triggers re-attachment.

### 2.2 BLE (alternate transport; not populated on the target console)

The board's private GATT namespace is built on the 128-bit base
`1412-efde-1523-785feabcd123` (the `1412` middle group marks the FitPro
service; `1212` marks the firmware-update service).

| Service | UUID | Purpose |
|---|---|---|
| FitPro data (primary) | `00001533-1412-efde-1523-785feabcd123` | the FitPro1 protocol channel |
| Firmware update (DFU) | `00001530-1212-efde-1523-785feabcd123` | firmware update |
| Accelerometer | `000043dd-100a-f596-ea4f-4c04fd0eae68` | accelerometer stream |
| Chest-strap HR receiver | `0000c167-3a98-1aab-9c40-a068750df195` | wireless chest-strap heart-rate receive |

FitPro data service characteristics:

| Characteristic | UUID | Direction |
|---|---|---|
| Device Rx | `00001534-1412-efde-1523-785feabcd123` | host → board (GATT write) |
| Device Tx | `00001535-1412-efde-1523-785feabcd123` | board → host (GATT notify; subscribe via the `0x2902` CCC) |

The board also exposes standard Bluetooth services (battery `0x180F`, heart rate
`0x180D`, cycling speed & cadence `0x1816`, cycling power `0x1818`, device
information `0x180A`, and the usual generic access services). **All FitPro1
telemetry and control flows over `0x1534`/`0x1535`, not over the standard
services**; those are informational only.

**Layering.** Each command is one frame. Over BLE the frame is wrapped and may be
split across several GATT writes:

```
GATT writes to Device Rx, one ATT write per "packet":
  [ 02 04 02 L ]              4-byte wrapper: 02 | 04 | 02 | L   (L = frame length)
  [ FE 02 L' C ]             group-manager init packet:
                               FE (0xFE) = "start of group" marker
                               02        = fixed tag
                               L'        = total wrapped length (4 + frame length)
                               C         = number of data packets + 1
  [ 00 18 d1..d18 ] ...      20-byte data packets: [seq][count][≤18 payload bytes]
  [ FF nn <tail> ]           final data packet has seq = 0xFF, count = remaining (≤18)
```

- The wrapped blob (`02 04 02 L` + frame) is chunked into 18-byte payload
  packets; the 18-byte size is chosen so a wrapped command fits the **default
  23-byte ATT MTU** — no MTU negotiation is performed.
- Frames are capped at 64 bytes, so a command is at most a handful of GATT
  writes (a frame ≤ 18 wrapped bytes is exactly two: the init packet plus one
  data packet).
- **Responses are mirrored.** The board's notifications on Device Tx arrive in
  the same packet scheme; a host detects "group complete" when the last packet
  of a burst starts with `0xFF`. The concatenated notification payload begins
  with the same `02 04 02 L` wrapper, which the host strips before parsing the
  frame.

**Discovery.** The board advertises the FitPro service (matched by its 16-bit
form `0x1533`) together with manufacturer-specific data carrying a **two-byte
pair key** — an application-level device-identity token. A host deduplicates and
re-selects a previously paired console by that pair key. (This discovery surface
is BLE-only; the target console does not use it.)

### 2.3 The transport-independent core

Sections 3–8 describe the protocol core: the command frame, the command set, the
payload layouts, the session lifecycle, and the data fields. This core is identical
on both transports; only the packing described in §2 differs.

---

## 3. Command frame

Every request and every response is one frame:

```
[ dev ][ len ][ cmd ][ content ... ][ checksum ]        len = 4 + len(content)
```

- `dev` — target selector (a byte; see the table below).
- `len` — total frame length including the header and the trailing checksum.
  Requests are capped at **64**; a *response* may declare a larger value (the
  periodic read declares ~90 — see §2.1 on how an over-length response is
  delivered).
- `cmd` — command id (a byte; §4).
- `checksum` — `(Σ frame[0 .. len-2]) & 0xFF`: the low byte of the plain additive
  sum of every byte preceding the checksum.

**Device ids** (`dev` byte):

| value | meaning |
|---|---|
| `0x00` | none |
| `0x01` | multiple devices |
| `0x02` | **main** — used for all bike traffic |
| `0x03` | portal (a secondary module) |
| `0x05` | incline-calibration target (used by the `Calibrate` command) |
| `0x07` | fitness bike |
| `0x08` | spin bike |

### 3.1 Response header and status

A response that a command expects carries the same frame shape. A host validates
it before use: `bytes[0]` is a non-zero device id, `bytes[1]` is a plausible
length, `bytes[2]` equals the requested `cmd`, and — for a *complete* frame —
the trailing checksum matches. (For an over-length frame delivered truncated, the
checksum lives in the missing tail and is not checked; see §2.1.) `bytes[3]` is
the **status**.

Note that the response's `dev` byte is the **board's own device class**, not a
copy of the `dev` the host used in the request: a host that addresses the board
as `0x02` (main) can still receive a response headed by the board's self-identified
class (e.g. `0x08`, spin bike — as in the live capture in §9). A host therefore
validates that `bytes[0]` is non-zero, not that it equals the request's `dev`.

| value | status | meaning |
|---|---|---|
| `0` | DevNotSupported | the addressed device does not support this |
| `1` | CmdNotSupported | the command is not supported by this board |
| `2` | **Done** | the command was accepted / applied |
| `3` | InProgress | the command is underway |
| `4` | Failed | the command failed |
| `5` | TimeLeft | a time-remaining value is being reported |
| `7` | UnknownFailure | an unspecified failure |
| `8` | **NotVerified** | a control write was received but **not applied** |
| `9` | CommFailed | a communications failure |

The statuses that are decision-relevant for a control write are **`Done`** (the
write was applied) and **`NotVerified`** (it was not).

---

## 4. Command reference

| id  | name | request content | response content |
|---|---|---|---|
| `0x01` | PortalDevListen | — (not used) | — |
| `0x02` | **ReadWriteData** | write section + read section (§6) | the read section's values (§6) |
| `0x03` | Test | none (no response) | — |
| `0x04` | Connect | none (no response) | — |
| `0x05` | Disconnect | none (no response) | — |
| `0x06` | Calibrate (incline) | `[type u8]`; sent with `dev = 0x05` | status byte only |
| `0x09` | Update / reset | none (no response) | — |
| `0x38` | EnterBootloader | none (no response) | — |
| `0x70` | SetTestingKey | (test-bench hook) | — |
| `0x71` | SetTestingTach | `[enable u8][rpm u16][interval u16][kph u16]` | status |
| `0x80` | SupportedDevices | none | `[count u8][device ids …]` |
| `0x81` | **DeviceInfo** | none | `[sw u8][hw u16][serial u32][manufacturer u16][sections u8][sections × u8 bitmap]` |
| `0x82` | **SystemInfo** | `[fetchMcuName u8][0x00]` | `[configSize u16][config u8][model u32][partNumber u32][cpuUse u16][numTasks u8][intervalTime u16][cpuFreq u32][pollingFreq u16][isMetric u8][isClub u8][configLibVer u8][lang u8][mcuNameLen u8][mcuName …][consoleNameLen u8][consoleName …]` |
| `0x83` | TaskInfo | — (MCU task-table dump) | — |
| `0x84` | **VersionInfo** | `[fetchMcuName u8][fetchConsoleName u8]` | `[masterLibVersion u8][masterLibBuild u16][bleLibName 17-byte string][configToolVer u8][bleSdkVer u16]` |
| `0x86` | ModeHistory | — | — |
| `0x88` | **SupportedCommands** | none | `[command ids …]` (count = `len − 5`) |
| `0x89` | ReadConfig | — (config dump) | — |
| `0x91` | ProtocolData | raw file push/pull sub-protocol | — |
| `0x92` | SpeedGradeLimit | — | — |
| `0x95` | **SerialNumber** | none | `[len u8][ascii …len]` (an all-`0x00`/`0xFF` string means "no serial") |
| `0xFF` | Raw | passthrough bytes | raw |

Notes on the information commands:

- **DeviceInfo** — `sw`/`hw` are the board's software and hardware version
  bytes; `serial` is the board serial number (u32); `manufacturer` is a u16
  manufacturer id; the trailing `sections` byte followed by that many bitmap
  bytes is the **supported-BitField capability map** — bit `n` of section `i`
  corresponds to field id `8·i + n`. A host uses this map to decide which fields
  it may read from or write to on this particular console.
- **SystemInfo** — `model` and `partNumber` are u32 identity values. For one
  particular console identity (part `370357` with model `39915`) the console's
  own software **normalizes the part number to `374677`**; a conformant host
  applies the same normalization.
- **VersionInfo** — `masterLibVersion` (MLV) is the console's master-library
  version.
- **SupportedDevices / SupportedCommands** — enumerate, respectively, the device
  ids and the command ids this board implements. A host queries these and only
  issues the information commands (`0x82`, `0x84`, `0x95`) that are listed.

**File transfer.** `ProtocolData` (`0x91`) and `Update` (`0x09`) implement a
file read/write sub-protocol over the same channel (a write-init packet
`[02 01 02 0B fileIdx u8 start u32 len u8 payload …]` and a read-init packet
`[02 02 02 06 fileIdx u8 start u32 len u8]` with 64-byte chunked reads). It is
only needed to flash the board (paired with the `0x1530` DFU GATT service and
`EnterBootloader` `0x38`); it is not part of normal operation.

---

## 5. Session lifecycle

The sequence a conformant host runs once the link is up:

```
link established
  → flush (USB: a 64×0xFF write) — clear the board's input buffer
  → DeviceInfo (0x81)
  → SupportedDevices (0x80)  +  SupportedCommands (0x88)      [one batch]
  → [if listed in SupportedCommands] SystemInfo (0x82)
  → [if listed] VersionInfo (0x84)
  → [if listed] SerialNumber (0x95)
  → startup read:  ReadWriteData (read: startup fields ∩ supported)
  → periodic loop: ReadWriteData (read: periodic fields ∩ supported) on a short interval
```

- The host re-queues the **periodic** read on a short interval (the bridge uses
  **100 ms**). A round that does not complete within ~3 s is counted as a
  communications timeout.
- A **60-second "no data at all" watchdog** tears the link down if nothing is
  received.
- **Disconnect mid-workout** does not reset the machine: the board keeps the
  workout alive across a link drop. A host therefore re-establishes and
  re-syncs rather than tearing the workout down.

---

## 6. ReadWriteData (`0x02`) — the workhorse

All telemetry and all control flow through this single command. Its request
content is a **write section** followed by a **read section**; each section is
`[count u8][count × section-bitmap]`, and the write section additionally carries
the values to write.

```
[ wCount ][ wBitmap§0 ][ wBitmap§1 ] … [ write values, ascending field id ]
[ rCount ][ rBitmap§0 ][ rBitmap§1 ] …
```

- The `§i` bitmap is the OR of `1 << (id % 8)` over every field in that section
  whose `id / 8 == i`. `count = (max id / 8) + 1`. A section with no fields is a
  single `0x00` byte.
- Write values are emitted in **ascending field-id order**, each at its field's
  wire width (§7).
- The **response content is the read section's values only**, in the same
  order and widths as the read bitmaps. A host validates
  `response_len == 5 + Σ(read field sizes)`.

**Worked example** — write Grade = 12.5 % while reading WorkoutMode:

```
frame = 02 0C 02 | 02 02 10 | E2 04 | 02 00 10 | 1C
        dev len cmd  W:cnt=2 §0=02 (Grade) §1=10 (WorkoutMode)
                       value Grade 12.5 → 1250 LE      R:cnt=2 §0=00 §1=10
```

A response to it (e.g. the board now in Idle mode) is
`02 05 02 02 01 0C` (status `Done`, WorkoutMode = 1).

### 6.1 Field sets

Two named field sets drive the lifecycle (§5). Each is intersected with the
board's capability bitmap (from DeviceInfo) at runtime, so a host only reads
fields this board actually exposes.

- **Startup fields** (read once):
  SystemUnits, MaxKph, MinKph, MaxGrade, MinGrade, MaxWeight, IdleTimeout,
  PauseTimeout, CoolDownTimeout, WarmupTimeout, MaxResistanceLevel, Gear,
  WorkoutMode, ActivationLock, IsClubUnit, MotorTotalDistance, TotalTime.

- **Periodic fields** (re-read on the short interval): WorkoutMode, Grade,
  CurrentTime, CurrentDistance, CurrentCalories, Resistance, Gear, Rpm,
  LapTime, AverageGrade, Watts, AverageWatts, VerticalMeterNet,
  VerticalMeterGain, Pulse, WarmupTimeout, CoolDownTimeout, Kph, ActualKph,
  StartRequested, FanState, Volume, PausedTime, RunningTime, Strokes,
  StrokesPerMin, FiveHundredSplit, AvgFiveHundredSplit, GoalTime, KeyObject,
  IsReadyToDisconnect.

The periodic read's response declares **~90 bytes** — larger than the 64-byte
USB packet — so over USB it is delivered truncated and decoded best-effort
(§2.1). Over BLE the same frame is chunked into the 18-byte packets of §2.2.

---

## 7. BitField registry and value encodings

Fields are addressed by a small integer **id**. The board's DeviceInfo bitmap
reports which ids it exposes. The registry below lists each id, its name, its
wire encoding, its size in bytes, and whether a host may **write** it (W) or
whether it is read-only (R). (A few ids in the 512-wide space have no converter
and are unused.)

| id | field | wire encoding (write → wire) | size | W/R |
|---|---|---|---|---|
| 0 | Kph | u16 = km/h × 100 (truncated) | 2 | W |
| 1 | Grade | i16 = incline % × 100 (signed; negative = downhill) | 2 | W |
| 2 | Resistance | u16, an abstract 0…full-scale value (see §7.2) | 2 | W |
| 3 | Watts | u16 | 2 | R |
| 4 | CurrentDistance | i32 (metres) | 4 | R |
| 5 | Rpm | u16 | 2 | R |
| 6 | Distance | i32 | 4 | R |
| 7 | KeyObject | `[code u16][rawKey u64][timePressed u16][timeHeld u16]` | 14 | R |
| 8 | FanSpeed | u8 | 1 | W |
| 9 | Volume | u8 | 1 | W |
| 10 | Pulse | `[user u8][avg u8][count u8][source u8]` (source: 0 = BLE, …) | 4 | W |
| 11 | RunningTime | i32 (seconds) | 4 | R |
| 12 | WorkoutMode | u8 (enum, §7.1) | 1 | W |
| 13 | Calories | u32 = kcal × 1e8 / 1024 | 4 | R |
| 14 | AudioSource | `[current u8][src1 u8][src2 u8]` | 3 | W |
| 15 | LapTime | u16 (seconds) | 2 | R |
| 16 | ActualKph | as Kph | 2 | R |
| 17 | ActualIncline | as Grade | 2 | R |
| 18 | ActualResistance | as Resistance | 2 | R |
| 19 | ActualDistance | i32 | 4 | R |
| 20 | CurrentTime | i32 | 4 | R |
| 21 | CurrentCalories | u32 (as Calories) | 4 | R |
| 22 | GoalTime | i32 (seconds) | 4 | W |
| 23 | IntervalKph | `[work u16][recovery u16]` | 4 | R |
| 24 | Age | u8 | 1 | W |
| 25 | Weight | u16 = kg × 100 | 2 | W |
| 26 | Gear | `[0,0,0,0, currentGear u8, gearOption u8, 0, maxGear+1 u8]` | 8 | W |
| 27 | MaxGrade | i16 (× 100) | 2 | R |
| 28 | MinGrade | i16 (× 100) | 2 | R |
| 29 | TransMax | i16 | 2 | W |
| 30 | MaxKph | u16 (× 100) | 2 | R |
| 31 | MinKph | u16 (× 100) | 2 | R |
| 34 | IdleTimeout | u16 (seconds) | 2 | W |
| 35 | PauseTimeout | u16 (seconds) | 2 | W |
| 36 | SystemUnits | bool | 1 | W |
| 37 | Gender | bool | 1 | W |
| 38 | FirstName | 45-byte UTF-8 | 45 | W |
| 39 | LastName | 45-byte UTF-8 | 45 | W |
| 40 | UserName | 45-byte UTF-8 | 45 | W |
| 41 | Height | i16 (cm) | 2 | W |
| 42 | MaxResistanceLevel | u8 (a level *count*) | 1 | R |
| 43 | MaxWeight | u16 = kg × 100 | 2 | R |
| 44 | WarmupDistance | i32 | 4 | W |
| 45 | WarmupTime | u16 | 2 | W |
| 46 | WarmupTimeout | u16 | 2 | W |
| 47 | WarmupCalories | u32 | 4 | W |
| 48 | IntervalGrade | i16 (× 100) | 2 | R |
| 49 | MaxPulse | u8 | 1 | R |
| 51 | WtMaxKph | u16 (× 100) | 2 | W |
| 52 | AverageGrade | i16 (× 100) | 2 | R |
| 53 | WtMaxGrade | i16 (× 100) | 2 | W |
| 54 | AverageWatts | u16 | 2 | R |
| 55 | MaxWatts | u16 | 2 | R |
| 56 | AverageRpm | u16 | 2 | R |
| 57 | MaxRpm | u16 | 2 | R |
| 58 | KphGoal | as Kph | 2 | W |
| 59 | GradeGoal | as Grade | 2 | W |
| 60 | ResistanceGoal | as Resistance | 2 | W |
| 61 | WattGoal | u16 | 2 | W |
| 63 | RpmGoal | u16 | 2 | W |
| 64 | DistanceGoal | i32 | 4 | W |
| 65 | PulseGoal | u8 | 1 | W |
| 66 | StartUpTime | i32 | 4 | R |
| 67 | BeltTotalTime | i32 | 4 | R |
| 68 | BeltTotalMeters | i32 | 4 | R |
| 69 | MotorTotalDistance | i32 | 4 | R |
| 70 | TotalTime | i32 | 4 | R |
| 71 | CoolDownTimeout | u16 | 2 | W |
| 72 | CoolDownTime | u16 | 2 | R |
| 73 | CoolDownDistance | i32 | 4 | R |
| 74 | CoolDownCalories | u32 | 4 | R |
| 75 | VerticalMeterNet | i32 = m × 10000 | 4 | R |
| 76 | VerticalMeterGain | i32 = m × 10000 | 4 | R |
| 77 | Reps | u16 | 2 | R |
| 78 | LeftReps | u16 | 2 | R |
| 79 | RightReps | u16 | 2 | R |
| 80 | RepLength | u16 | 2 | W |
| 81 | RepLeftLength | u16 | 2 | W |
| 82 | RepRightLength | u16 | 2 | W |
| 83 | BurnRate | u16 = kcal/h × 1000 | 2 | W |
| 84 | AvgBurnRate | u16 | 2 | R |
| 85 | MaxBurnRate | u16 | 2 | R |
| 86 | IntervalRpm | i16 (× 100) | 4 | R |
| 87 | IntervalResistance | i16 (× 100) | 4 | R |
| 94 | GoalCalories | u32 (as Calories) | 4 | W |
| 95 | IdleModeLockout | bool | 1 | W |
| 96 | StartRequested | bool | 1 | W |
| 98 | FanState | u8 (0 = off … 4 = auto) | 1 | W |
| 100 | ActivationLock | u8 | 1 | W |
| 103 | PausedTime | i32 | 4 | R |
| 107 | SleepTimerState | bool | 1 | R |
| 108 | RequireStartRequested | bool | 1 | R |
| 109 | Strokes | u16 | 2 | W |
| 110 | StrokesPerMin | u8 | 1 | W |
| 111 | FiveHundredSplit | u16 | 2 | R |
| 112 | AvgFiveHundredSplit | u16 | 2 | R |
| 115 | IsClubUnit | bool | 1 | R |
| 116 | IsReadyToDisconnect | bool | 1 | R |
| 119 | IsConstantWattsMode | bool | 1 | W |

### 7.1 WorkoutMode (field 12, u8)

| value | mode | value | mode |
|---|---|---|---|
| 0 | Unknown | 9 | Demo |
| 1 | **Idle** | 10 | WarmUp |
| 2 | **Running** | 11 | CoolDown |
| 3 | **Pause** | 12 | Sleep |
| 4 | Results | 13 | **Resume** |
| 5 | Debug | 14 | Locked |
| 6 | Log | 20 | PauseOverride |
| 7 | Maintenance | 8 | Dmk (safety key out) |

The host drives mode transitions by writing this field: **start** = `2`
(Running), **pause** = `3`, **resume** = `13`, **stop** = `1` (Idle). (On some
consoles the `StartRequested` field (96) is read-only — the board sets it when
its own start control is pressed — and is not written by the host.)

### 7.2 Resistance scaling

Resistance is carried as a **raw u16 on an abstract scale** whose top of range
("full scale") is a per-console constant. The protocol reports only the level
*count* (`MaxResistanceLevel`, field 42); it does **not** report the raw
full-scale value, so a host must supply that per model. For the console with
model `5121` the top-of-range raw value was measured at **5964** (22 user
levels, step 284); every other model uses the protocol default **10000**.

The mapping between a 1-based user level and a raw value is linear across
`0 … fullScale` with span `maxLevel − 1`:

```
raw    = clamp( (level − 1) × fullScale / (maxLevel − 1),  0, fullScale )
level  = clamp( 1 + raw × (maxLevel − 1) / fullScale,      1, maxLevel  )
```

(Grades, by contrast, are signed on the wire — a bike can be set downhill — so a
host sign-extends the read u16 to recover the true value.)

---

## 8. Operational verification

On some software versions the board requires a short **operational
verification** exchange, performed during session setup, before it will accept
control writes. This document does not describe that step.

---

## 9. Verified wire examples

Raw request frames (the transport-independent form of §3; over USB these go out
as a single bulk OUT and the reply arrives as the frame at byte 0 padded with
`0xFF` to 64):

| command | request frame |
|---|---|
| SupportedDevices | `02 04 80 86` |
| SupportedCommands | `02 04 88 8E` |
| DeviceInfo | `02 04 81 87` |
| SystemInfo (fetch MCU name) | `02 06 82 01 00 8B` |
| VersionInfo (fetch console name) | `02 06 84 00 01 8D` |
| SerialNumber | `02 04 95 9B` |
| ReadWriteData — write Grade 12.5 %, read WorkoutMode | `02 0C 02 02 02 10 E2 04 02 00 10 1C` |
| (response to the above, mode = Idle) | `02 05 02 02 01 0C` |

**A verified response (live capture).** The first `DeviceInfo` reply received
from the target console is a 29-byte frame, delivered `0xFF`-padded to 64
bytes:

```
08 1D 81 02 | 53 01 | 03 00 00 00 | 00 FF | 0F | FF FF FF DF FC FF FB BC E7 1F C0 C0 D5 10 98 | 9E
dev len cmd sw | hw | serial | mfr | sections | capability bitmap (15 bytes) | ck
```

Read: `dev = 0x08` (the board's own device class — a spin bike — not an echo
of the request's `0x02`, §3.1); `len = 29`; `sw = 2`; `hw = 0x0153`;
`serial = 3`; `manufacturer = 0xFF00`; `sections = 15` followed by 15
capability-bitmap bytes; checksum `0x9E`.

Over the **BLE** transport each of these frames is wrapped as in §2.2 — e.g. the
DeviceInfo request is sent as the init packet `FE 02 08 02` followed by the data
packet `FF 08 02 04 02 04 02 04 81 87` (the `02 04 02 04` wrapper plus the raw
frame, as one 18-byte-or-less data packet).

---

## 10. FitPro1 vs. FitPro2 (orientation)

**FitPro2** is a separate, newer protocol used on newer consoles; this document
covers **FitPro1** only. On the console targeted here, the (present) USB link —
and the (absent) BLE link — both carry **FitPro1**. For reference, the two
differ as follows:

| | FitPro1 (this document) | FitPro2 |
|---|---|---|
| framing | `[dev][len][cmd][content][ck]`, additive checksum | `[02][dev\|type][len][payload]`, no checksum |
| fields | BitField ids (bits of a 512-wide space), per-section bitmaps | flat u16 feature ids, 32-bit float values |
| subscription | implicit — each `ReadWriteData` lists its read fields | explicit subscribe/unsubscribe (chunks ≤ 8) |
| session setup | DeviceInfo → Supported\* → System/Version/Serial → read | supported-features → extended-info → unsubscribe → subscribe |

---

## 11. Notes

- **The board owns the workout state.** It keeps a workout alive across a host
  link drop; the host re-establishes and re-syncs rather than resetting the
  machine.
- **Telemetry is polled, not pushed.** There are no fire-and-forget
  asynchronous notifications for telemetry: the host re-issues the periodic
  `ReadWriteData` read on a short interval and decodes the reply.
- **No flash by default.** Normal operation never touches the bootloader or
  firmware-update paths (`0x38`, `0x09`, the `0x1530` DFU service).
