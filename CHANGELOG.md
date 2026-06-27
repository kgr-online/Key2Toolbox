# Changelog

All notable changes to Key2 Toolbox are documented here.

## [4.2-beta1] - 2026-06-28

### Added
- **Network tab** — new bottom-bar section grouping all network-adjacent tweaks.
- **CPU Performance Tuning** (`PerformanceController`): tunes the Schedutil
  `up_rate_limit_us` on the LITTLE cluster (policy0) and the CAF input-boost
  frequency/duration via `/sys/devices/system/cpu/cpu_boost/`. Settings apply
  live and persist via `service.d/cpu_performance.sh`; the script waits for
  `init.svc.qcom-post-boot` to finish so it wins the race against the Qualcomm
  post-boot tuner.
- **Global Telemetry Block** (`TelemetryController`): scans every installed app
  for `com.google.firebase.crashlytics.xml` and sets
  `firebase_crashlytics_collection_enabled` to `false`. Runs once at boot
  (after a 15-second delay so app data directories exist) and can also be
  applied live from the screen, which reports how many apps are affected vs
  already blocked.
- **Wearable Power Saver** (`WatchController`, formerly Galaxy Watch module):
  lists all wearables registered in GMS's `connectionconfig.db` (watches,
  trackers, any GMS-paired device) and lets you toggle each into **Dormant**
  mode. Dormant devices have `connectionEnabled = 0` written to the GMS
  SQLite database, stopping GMS from firing Bluetooth reconnect alarms for
  out-of-range devices. GMS is force-stopped to pick up the change immediately.
  A `service.d/wearable_dormant.sh` boot script re-applies dormant state for
  any selected MACs, since GMS can reset the field on a cold boot.
- **Bluetooth Auto-Disable** (`BtIdleController`): installs a watchdog daemon
  (`service.d/bt_idle.sh`) that turns Bluetooth off after a configurable
  timeout (5 / 10 / 15 / 30 / 60 min, default 15) with no device connected.
  Uses five detection strategies against `dumpsys bluetooth_manager`: device
  table status, active A2DP/Headset device, `mIsPlaying` flag, profile
  connection state, and GATT client/server map entries. Connecting any device
  resets the timer. A PID lock prevents stacked instances.

### Changed
- **ZRAM UI defaults** updated to `lz4` compression, 3 GB size, and
  swappiness 40 — values confirmed optimal for this device.
- **Boot script race conditions fixed** for `force_us_wifi.sh` and
  `adb_wireless.sh`: both now wait for `init.svc.qcom-post-boot` to reach
  `stopped` before applying settings, preventing the Qualcomm post-boot script
  from overwriting them.
- **Wearable module generalised**: previously hardcoded for Galaxy Watch; now
  reads all paired GMS wearable devices dynamically from `connectionconfig.db`.

## [4.1-beta4] - 2026-06-22

### Added
- **Bottom navigation** with three sections: **Info / Keyboard / System**.
- **Info** landing screen: device (model, Android, LineageOS, security patch,
  kernel, build), battery (level, status, health, temperature, voltage,
  technology, capacity-health % and charge cycles from sysfs), and live root +
  accessibility-service status.
- **Per-App Keyboard Block**: in selected apps, physical key presses reach the
  app directly instead of the BlackBerry IME, by switching to a bundled
  do-nothing passthrough IME (`Key2PassthroughIme`) while the app is foreground
  and restoring the previous IME on exit.
- **5GHz Hotspot Workaround**: forces the WiFi region to US (live + persisted
  boot script) so 5GHz SoftAP works, since EU regdomains expose no 5GHz AP
  channels on this build.
- **Material You (Monet)** theming that follows the system light/dark setting.

### Changed
- Per-app keyboard block now switches IME instead of toggling
  `show_ime_with_hard_keyboard` (which didn't actually stop the BlackBerry IME
  from intercepting keys).
- Fixed scrolling glitches by letting the `Scaffold` own the system-bar insets
  instead of each screen re-applying them.

### Removed
- **Audio FX** (system-wide EQ / BassBoost / LoudnessEnhancer) and its
  `MODIFY_AUDIO_SETTINGS` permission. The in-app, userspace `AudioEffect`
  approach was always a compromise (the EQ ate headroom and the makeup-gain
  compressor pumped). **NLSound** does the job better by operating at the audio
  HAL level, so the in-app audio mods are dropped in its favour.

### Notes
- WiFi hotspot on this build only starts with **WPA2** (WPA3/SAE fails with
  `UNSUPPORTED_CONFIGURATION`); the empty EU 5GHz AP regdomain and the SAE
  failure are ROM/driver-level and tracked upstream.
