# BLE Chinese API Release Records

This file records local build artifacts, test status, and delivery notes for
debug APK iterations. Keep it updated before sharing an APK or pushing a
validated milestone.

## Issue #1 reliable transport layer

- Branch: `issue-1-reliable-transport`
- Goal: move the validated v0.5.7 discovery, identity, direction arbitration,
  notification subscription, and message deduplication behavior into the
  reusable library surface.
- Planned additions: write queue, `onCharacteristicWrite` serialization,
  timeout/error feedback, peer aging, and updated usage notes.
- Status: implementation planning started.

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
