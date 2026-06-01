# BLE Chinese API Release Records

This file records local build artifacts, test status, and delivery notes for
debug APK iterations. Keep it updated before sharing an APK or pushing a
validated milestone.

## Issue #1 reliable transport layer

- Branch: `issue-1-reliable-transport`
- Goal: move the validated v0.5.7 discovery, identity, direction arbitration,
  notification subscription, and message deduplication behavior into the
  reusable library surface.
- Completed additions: write queue, `onCharacteristicWrite` serialization,
  timeout/error feedback, peer aging, compact transport frame, message id
  deduplication, rotation-state retention, and updated usage notes.
- Status: completed in `v0.6.1-debug`. Maintainer verified three-device
  communication and paid RMB 400 for Issue #1.
- Remaining follow-ups: MTU negotiation or fragmentation for longer messages,
  Samsung keyboard-overlap UI polish, and further library API cleanup before
  integrating into a real business app.

## v0.6.3-debug

- Source status: local diagnostic follow-up after v0.6.2.
- GitHub branch: `issue-1-reliable-transport`
- Build command:

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleDebug :sample_app:assembleDebug
```

- Local APK:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-v0.6.3-debug.apk`
- Local email package:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-v0.6.3-debug.zip`

### Implemented

- Kept the v0.6.2 conservative send-path behavior: notification first, client
  writes in the background after notification succeeds, parallel client writes
  when notification is unavailable, and configurable write timeout.
- Added structured library diagnostics for each business send:
  `[SEND#] start`, `notify`, `write`, `write-bg`, and final `result`.
- Each diagnostic line includes channel counts: peer records, connected peers,
  writable peers, client write connections, subscribed notification peers, and
  a short peer summary.
- Changed the sample app into a minimal diagnostic test surface with visible
  `[APP#]`, `[STATE]`, `[PEERS]`, `[RECV]`, and `[API]` logs.
- Added `短测` and `连发5次` buttons so repeated short sends can be tested
  without relying on the keyboard.
- Increased the visible log buffer to keep a longer continuous history for
  screenshots.

### Test Focus

- Two devices can send short messages in both directions after initial connect.
- After one device stops communication, the other device's next send should
  fail or clean up promptly instead of blocking behind stale channels.
- After restart/reconnect, both devices can send again.
- Repeated stop/start cycles should not make `clients` or `subscribed` grow
  indefinitely, and the final `[SEND#] result` should match the visible app
  result.
- This version is intentionally diagnostic; merge the stable behavior into the
  public Chinese API only after the log evidence is clear.

## v0.6.2-debug

- Source status: local follow-up after BLE_RPS `v1.0.30` validation.
- GitHub branch: `issue-1-reliable-transport`
- Build command:

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleDebug :sample_app:assembleDebug
```

- Local APK:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-v0.6.2-debug.apk`
- Local email package:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-v0.6.2-debug.zip`

### Implemented

- `发送()` now tries GATT server notification before waiting for GATT client
  write channels.
- If notification succeeds, client writes are still attempted in the background
  for cleanup/coverage, but stale write channels no longer block the business
  send result.
- If notification is unavailable, GATT client writes are attempted in parallel
  instead of serially waiting behind the first stale channel.
- GATT client write timeout is now configurable through `写入超时毫秒`, with
  a default of 1.5 seconds instead of the previous fixed 5 seconds.
- If a notification request is rejected by the system, the stale subscribed peer
  is removed and peer state is updated.
- Sample app version text and package metadata were bumped to `v0.6.2-debug`.

### Why

BLE_RPS testing showed that a business app should not be forced to wait for a
stale GATT client write path when a working notification path is already
available. Otherwise one side can receive a game message while the sender still
waits or times out on an old channel, causing asymmetric UI state.

### Test Focus

- Two devices can still exchange short text messages.
- Repeated start/stop/reconnect does not leave a stale writable channel that
  blocks later sends.
- `发送()` returns promptly when a notification channel is available.
- v0.6.1 remains the accepted Issue #1 baseline; v0.6.2 is a conservative
  stability follow-up.

## v0.6.1-debug

- Source commit at delivery time: `27ffa62 Compact BLE transport frame`
- GitHub branch: `issue-1-reliable-transport`
- Build command:

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleDebug :sample_app:assembleDebug
```

- Local APK shared for testing:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-debug-v0.6.1-debug.apk.1`
- Local email package:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-v0.6.1-debug-package.zip`

### Implemented

- Stable application-level peer id, so the sample does not rely only on Android
  randomized Bluetooth addresses.
- Direction arbitration to reduce simultaneous bidirectional connection
  collisions.
- Writable channel registration only after notification subscription succeeds.
- Send queue and `onCharacteristicWrite`-based serialized writes.
- Five-second send timeout and visible failure feedback.
- Sixty-second peer aging.
- Seven-byte transport frame header plus message id deduplication.
- Activity rotation handling so landscape/portrait changes keep logs and state.

### Verified Behavior

- Local A/C two-device Chinese text exchange passed.
- Local A/B/C three-device short-message exchange passed.
- Maintainer reported `v0.6.1-debug` passed three-device testing.
- The accepted test flow no longer exposed raw `BCAPI1...` frames in the UI.
- The accepted test flow did not show self-received duplicate messages.
- The accepted test flow did not show write timeouts.

### Known Limits

- Current transport does not negotiate MTU. It keeps the default BLE ATT MTU,
  so a seven-byte frame header leaves about thirteen bytes of payload, or about
  four Chinese characters per packet.
- Longer messages should be handled later with MTU negotiation or fragmentation.
- The sample app's channel counters are logical BLE communication channels, not
  unique physical device counts. Three devices can legitimately show four
  connected/writable channels.
- This is not Bluetooth Mesh. It does not implement standardized mesh
  provisioning, routing, relaying, or flooding.
- Samsung devices still showed keyboard/button overlap in maintainer testing,
  although landscape rotation now keeps logs.

## v0.5.7-debug

- Source commit: `41cd566 Document validated v0.5.7 sample state`
- GitHub branch: `main`
- Build command:

```powershell
.\gradlew.bat --no-daemon --console=plain :ble_chinese_api:assembleRelease :sample_app:assembleDebug
```

- Local APK:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-v0.5.7-debug.apk`
- Local email package:
  `D:\Work\Aideas\BLE_Chinese_API_Design\sample_app\build\outputs\apk\debug\sample_app-v0.5.7-package.zip`

### Verified Behavior

- Two-device minimal BLE message flow passed on local Android devices.
- Three-device automatic mode passed in local testing.
- Maintainer reported Samsung phone and tablet could receive messages both ways
  after repeated tests.
- Core flow verified: scan, discover, connect, subscribe to notification, write
  message, receive message.

### Notes

- Permissions are important across Android vendors. Test devices should allow
  nearby devices, Bluetooth scan/advertise/connect, and location when the system
  exposes location as part of BLE scanning.
- `已连接` in the sample app means at least one communication channel exists. It
  should not be interpreted as unique physical peer count.
- Landscape rotation and keyboard overlap are known sample-app UI issues in
  v0.5.7. They are not part of the BLE minimal-link validation.

## v0.5.8-debug

- Local status: work in progress, not delivered.
- Current storage: git stash `wip v0.5.8 ui rotation and keyboard fixes`.
- Scope: preserve log text on Activity recreation and improve keyboard resize
  behavior.
- Test status: not yet verified on real devices.
