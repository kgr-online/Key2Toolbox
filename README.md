# Key2 Toolbox

A root app for the BlackBerry Key2 (FolkPatch/APatch, LineageOS 22.2, 4.19
kernel) that bundles a set of previously-separate tweaks into one UI,
organised into five bottom-bar sections:

**Info** - device status landing page: build (model, Android, LineageOS,
security patch, kernel) and battery (level, health, temperature, voltage,
technology, capacity-health % and charge cycles from sysfs, with a link into
per-app battery usage). Root/accessibility-service status now lives in
Settings → Quick Access instead of duplicating it here.

**Keyboard**
- **Remap Key → Ctrl** (`stmpe.kl`, Currency or Convenience key)
- **Adaptive keyboard backlight** daemon
- **Keyboard Nav Lock** - stops accidental Back/Home/Recents while typing
- **Lockscreen PIN on Keyboard** - type your PIN on the physical keyboard
- **Per-App Keyboard Block** - in chosen apps, route physical keys straight
  to the app (for games) by switching to a passthrough IME
- **Calculator Keys** - route physical digit/operator keys to a foreground
  AOSP/Google Calculator
- **IME Suggestions** - Ctrl+W/E/R picks suggestion 1/2/3 from the keyboard's
  candidate strip (needs a key remapped to Ctrl first)

**System**
- **ZRAM** compression algorithm + size (Off / 2GB / 3GB / 4GB), VM swappiness
- **Persistent wireless ADB** on a user-chosen static port
- **Double-Tap to Wake** (DT2W)
- **CPU Performance Tuning** - Schedutil `up_rate_limit_us` + CAF input-boost
  frequency and duration
- **Play Store Tagger** - retag (or untag) installed apps as Play
  Store-installed, so apps that check the install source stop complaining
- **BBProdFix Settings** - companion page for the `bb-prodfix` Magisk
  module's `system.prop`/`service.sh` tweaks
- **LED Notify Colors** - per-app notification LED colors written straight
  to the LED hardware, bypassing LineageOS's own (inaccurate on this device)
  notification light color picker
- **Extra Dim** - dims below the system's standard minimum brightness, with
  an optional daily on/off schedule
- **Call Shortcuts** - mute, speaker, and dialpad digits from the physical
  keyboard during a Google Phone call (mute/speaker work standalone; fast
  digit entry also wants Auto-Focus enabled for the dialer)
- **Auto-Focus** - focus and type into the first text field automatically in
  chosen apps, on the first printable key press

**Network**
- **5GHz Hotspot Workaround** - force the WiFi region to US so 5GHz SoftAP
  works (EU regdomains expose no 5GHz AP channels on this build)
- **Bluetooth Auto-Disable** - watchdog daemon that turns Bluetooth off after a
  configurable idle timeout (no device connected), preventing `hal_bluetooth_lock`
  from blocking deep sleep overnight
- **Wearable Power Saver** - put any GMS-paired wearable into Dormant mode so
  out-of-range devices don't trigger constant Bluetooth reconnect alarms
- **Global Telemetry Block** - disable Firebase Crashlytics collection across
  all installed apps at boot

**Settings**
- **Updates** - checks the GitHub releases feed for a newer build and can
  download/install the APK directly
- **Quick Access** - shortcuts into relevant system settings screens
  (accessibility, notification access, etc.)
- **Contributors** - pulls the repo's contributor list from the GitHub API
- **About** - current version and project links

The UI follows Material You (Monet), in light or dark to match the system.
Most modules are stateless: they fire root commands on demand and persist by
installing a script to `/data/adb/service.d/`. A few (Nav Lock, PIN, Per-App
Keyboard Block - the last two ported from
[nozerorma/key2-tweaks](https://github.com/nozerorma/key2-tweaks)) instead
depend on a long-lived `Key2AccessibilityService` that watches IME/window
state and intercepts physical key events, since none of that is observable
from a one-shot root command. Their settings live in a `key2tweaks`
SharedPreferences file rather than going through `AssetInstaller`. LED
Notify Colors follows a similar but separate pattern: it depends on a
`NotificationListenerService` rather than the accessibility service, with
its own `led_notify` SharedPreferences file.

The accessibility-service modules only work once **Key2 Toolbox** is enabled
under Settings → Accessibility - each of their screens shows a banner with a
direct link there if it isn't. Reinstalling the app resets this, so it needs
re-enabling after every fresh install. LED Notify Colors has the equivalent
requirement for Settings → Notifications → Notification access, with its own
banner.

## Signing with your existing keystore

`app/build.gradle.kts` has a commented-out `signingConfigs { create("release") {...} }`
block referencing `kgr_signing.keystore` / alias `kgr`. Uncomment it, point
`storeFile` at your keystore path, and wire `signingConfig = signingConfigs.getByName("release")`
into the `release` build type. Consider passing the password via
`gradle.properties` (gitignored) or an env var rather than committing it in
the build script.

## How each module works

### Key Remap (`KeyRemapController`)
Remaps a chosen physical key to Ctrl, so it works as a real modifier for the
Calculator Keys and IME Suggestions shortcuts. Choice of source key:
**Currency** (`stmpe.kl` scancode 5, normally keycode `4`) or **Convenience**
(scancode 110, normally `FUNCTION`) - the only two spare/remappable keys on
this keyboard's layout (replaces the old single-key-only `CtrlKeyController`).
- **Live apply**: `setenforce 0` → `nsenter -t 1 -m -- mount -o rw,remount /vendor`
  → whitespace-tolerant `sed -E` swap of the chosen scancode's line to
  `CTRL_LEFT` → `setenforce 1`. Before applying, both known source keys are
  first reverted back to their original keycode (a no-op for whichever one
  isn't currently remapped), so switching sources never leaves a stale
  double-remap.
- **Persist**: installs `assets/key_remap_template.sh` (with `__SCANCODE__`/
  `__ORIGINAL_KEYCODE__` substituted for the selected source) to
  `/data/adb/service.d/key_remap.sh`.
- **Live reload**: editing `stmpe.kl` alone doesn't take effect until reboot -
  confirmed on-device that `InputReader` keeps using whichever keylayout it
  parsed when the input device was first opened. Unbinding/rebinding the
  `stmpe-keypad` i2c driver (the trick q25toolbox's `Q25_keyboard` driver
  needs) is a no-op here too, since this driver doesn't tear down
  `/dev/input/eventN` on unbind. What does work: writing `remove` then `add`
  to the keypad's own `/sys/class/input/eventN/uevent` file - the same
  device-hotplug event `InputReader` reacts to for a real unplug/replug.
  Confirmed live: the physical key reports the stale keycode before this and
  the remapped one immediately after, with no reboot.

### ZRAM (`ZramController`)
- **Compression algorithm**: read dynamically from
  `/sys/block/zram0/comp_algorithm`, so the screen only offers algorithms
  this kernel actually supports (confirmed: `lzo`, `lz4`, `zstd`, `deflate`).
- **Size**: Off / 2GB / 3GB / 4GB.
- **Persist**: installs `assets/zram_template.sh` (with `__ALGO__` and
  `__SIZE_MB__` substituted) to `/data/adb/service.d/zram_size.sh`. Selecting
  "Off" removes the script.
- **Live apply** (behind a confirmation dialog): `swapoff` → reset → set
  `comp_algorithm` → set `disksize` → `mkswap` → `swapon`. This briefly
  disables swap and can cause background apps to be killed - the dialog
  warns about this, default is reboot-to-apply.

### Keyboard backlight (`KbdLightController`)
- This script is a persistent loop, not a one-shot config, so "enabled" =
  install `assets/kbd_light.sh` to `service.d` for next boot, **and**
  optionally launch it right now (`nohup sh ... &`) / kill it
  (`pkill -f kbd_light.sh`) so you don't need to reboot to test.

### Wireless ADB (`WirelessAdbController`)
- User enters a port; **persist** installs `assets/adb_wireless_template.sh`
  (with `__PORT__` substituted) to `/data/adb/service.d/adb_wireless.sh`,
  which sets `adb_wifi_enabled` and pins `persist.adb.tcp.port` /
  `service.adb.tcp.port` at boot.
- **Live apply** sets the same properties immediately.
- The screen also shows the device's current WLAN IP (via `ip route get`,
  checked against a `wlan*`-named interface so it doesn't report a cellular
  IP when WiFi is down) and the live port, so you can confirm the
  `adb connect <ip>:<port>` target at a glance.
- If you previously had a separate static-port script, remove it once this
  module's persistence is confirmed working, so two boot scripts aren't
  racing to set the same property.

### Double-Tap to Wake (`Dt2wController`)
- Toggles the `wake_gesture` sysfs node on the main touchscreen
  (`synaptics_dsx_2.7`, I2C 4-0070).
- Must be applied while the screen is **on** - the driver only picks up the
  gesture-mode setting as part of its normal suspend sequence, so enabling
  it with the screen already off won't take effect until the next time the
  screen turns on and back off.
- **Persist**: installs `assets/dt2w.sh` to `/data/adb/service.d/`, which
  sleeps briefly after boot (screen is assumed on) then re-applies the write,
  since the value doesn't survive reboot on its own.
- If the live state doesn't actually change after toggling, this may be the
  unresolved driver/HAL-level issue from earlier debugging (sysfs write
  appears to succeed but the gesture doesn't engage) rather than a bug in
  the app itself.

### Keyboard Nav Lock (`Key2AccessibilityService`)
Ported from nozerorma/key2-tweaks. Stops accidental Back / Home / Recents
presses while the on-screen keyboard (IME) is visible. Three independent
toggles, all stored as booleans in the `key2tweaks` SharedPreferences file:
- **Keyboard Nav Lock** (`nav_lock_enabled`): master switch for the
  disable-while-typing behavior.
- **Double-tap Back** (`nav_gesture_mode`, no root): keeps the buttons live;
  the service gates `KEYCODE_BACK` in `onKeyEvent` so a single tap is
  swallowed and only a double-tap (within 300ms, and only if the tap was
  shorter than a 350ms long-press) fires it. Home/Recents can't be gated
  this way - Android's window policy acts on them regardless of what an
  accessibility service consumes.
- **Disable nav buttons ALWAYS** (`nav_always_off`, root): permanently cuts
  all three capacitive buttons via the sysfs node
  `/sys/class/input/eventN/device/0dbutton` (`1`=on, `0`=off), resolved by
  device name (`synaptics_dsx_2`) each time rather than a fixed event
  number, so it survives reboots even if the event index shifts. The root
  write goes through `RootShell` (a `for` loop over `/sys/class/input/event*`
  in one shell invocation, matching the FolkPatch one-command-per-`-c` quirk
  already documented for the other modules).

The service recomputes the desired button state (`reconcileNav()`) whenever
an accessibility event fires or any of the three prefs change, and always
re-enables the buttons in `onUnbind`/`onDestroy` so a crash or disable never
leaves them stuck off.

### Lockscreen PIN on Keyboard (`Key2AccessibilityService`)
Ported from nozerorma/key2-tweaks. No root needed. While the keyguard is
locked, maps physical key presses to taps on SystemUI's PIN pad via
`AccessibilityNodeInfo`, so the PIN can be entered on the hardware keyboard
instead of the touchscreen. Digits map phone-dialpad style onto QWERTY:
`W E R` = `1 2 3`, `S D F` = `4 5 6`, `Z X C` = `7 8 9`, `Q` = `0` (number
row and numpad keys also work directly). Enter/D-pad-center confirms,
Delete/Backspace deletes. Button lookup tries known SystemUI view IDs first
(`key0`-`key9`, `delete_button`, `key_enter`, etc.) and falls back to a
recursive node search by visible text or content description if those IDs
don't match on this build.

### Per-App Keyboard Block (`Key2AccessibilityService` + `Key2PassthroughIme`)
In a chosen set of apps, physical key presses are routed straight to the app
instead of going through the keyboard. On the Key2 the BlackBerry IME
intercepts and translates hardware keys (it even ignores the system keymap -
`stmpe.kcm` maps the currency key to `$`, but the IME still emits `4`), which
interferes with games. The service tracks the foreground app
(`foregroundAppPackage()` from the active application window) and, when a
selected package is in front, switches the default IME to a bundled
do-nothing input method (`Key2PassthroughIme`, which inflates no view and
consumes no keys) via root `ime enable`/`ime set`, saving the previous IME to
restore on the way out. The picked packages are a `StringSet` in the
`key2tweaks` prefs; the app list uses a `<queries>` launcher intent so it can
enumerate launchable apps on Android 11+.

### Calculator Keys (`CalculatorInputFix`)
Routes physical digit/operator keys to a foreground AOSP/Google Calculator's
buttons via the accessibility tree, since those apps don't otherwise accept
raw hardware key input for most operators. Digits reuse the same
Q/W/E/R/S/D/F/Z/X/C mapping already established for the lockscreen PIN
keyboard; new mappings added for this feature: O/I/A/G = + − × ÷, M = decimal
point, U = percent, B = factorial, T/Y = parentheses, Sym/Alt = toggle
scientific mode. Digits are inserted directly into the formula view's text
(`ACTION_SET_TEXT`); everything else clicks the matching button by resource id
(`com.android.calculator2:id/...`), with a text-label fallback if the id
lookup misses. No root required.

### IME Suggestions (`Key2AccessibilityService`)
Ctrl+W/E/R picks suggestion 1/2/3 from the physical keyboard's candidate
strip (confirmed on the BlackBerry Keyboard, where the strip is a row of
clickable `TextView`s next to an unrelated toggle button, filtered out by
class name). Only consumes the key press if a suggestion was actually found
and clicked, so Ctrl+W/E/R still behave normally elsewhere (e.g. closing a
browser tab) when nothing is showing. Needs a key remapped to Ctrl (see Key
Remap) to be reachable at all. No root required.

### Call Shortcuts (`Key2AccessibilityService`)
On the Google Phone call screen: M mutes/unmutes, the Currency key (or Ctrl,
if remapped) toggles the speaker, and Q/W/E/R/S/D/F/Z/X/C dial digits into the
keypad (auto-opening it on the first digit if it isn't already open, polling
briefly for the open animation to finish before injecting). Gated on the
in-call screen having its full 3-toggle set (keypad/mute/speaker) so it can't
misfire on the pre-call dial-a-number screen, which shares the same foreground
package. Digit injection goes through root `input keyevent`; everything else
is a direct accessibility-tree click.

### Auto-Focus (`AutoFocusController`)
In chosen apps, focuses and types into the first text field as soon as you
press a printable key with nothing already focused - handy for search/entry
fields that otherwise need a tap first.
- **Finding the field**: walks the window's node tree for the first
  `EditText`/`AutoCompleteTextView`; if a `WebView` is present (a browser),
  the search is scoped to inside it, so a page with no `<input>` falls
  through to nothing rather than the browser chrome's own address bar.
- **Focus + type**: fires both `ACTION_FOCUS` and `ACTION_CLICK` (some search
  boxes, e.g. Google Maps' omnibox, only activate via click, opening a full
  overlay), then waits for real input focus to land - woken instantly by a
  `CountDownLatch` counted down from `onAccessibilityEvent` the moment the
  target field is actually focused (a 1s timeout is just the safety net for
  apps where that never cleanly fires), rather than a fixed poll delay.
  Types via `ACTION_SET_TEXT` (not synthetic `input keyevent`, which was
  found unreliable this soon after a focus transition) - in the Google
  Dialer's own number field specifically, the physical letter key's
  phone-keypad digit is inserted instead of the raw letter.
- Gated on a live "is anything already focused?" check rather than a
  per-app-session flag, so it naturally re-arms whenever focus is actually
  lost (e.g. tapping back), without needing an app change to reset it.

### 5GHz Hotspot Workaround (`WifiRegdomainController`)
On this build every EU WiFi regdomain exposes **zero** 5GHz SoftAP channels
(`SupportedChannelListIn5g[]`), so 5GHz hotspot is greyed out / fails with
`NO_CHANNEL`; only the US regdomain has them. This forces the WiFi country
code to US via `cmd wifi force-country-code enabled US`, applied live and
persisted with `assets/force_us_wifi.sh` (which re-applies it after each boot
once the WiFi service is up, since the override resets on reboot). Trade-off,
surfaced on the screen: it also applies to WiFi as a client (you lose 2.4GHz
ch 12-13 and EU-only 5GHz channels) and enables the upper US channels
(149-165) that aren't EU-licensed. Also note SoftAP only starts with **WPA2**
on this build - WPA3/SAE fails with `UNSUPPORTED_CONFIGURATION`.

### CPU Performance Tuning (`PerformanceController`)
Tunes two Qualcomm-specific knobs that the stock Qualcomm post-boot script sets
but leaves at relatively conservative values:
- **`up_rate_limit_us`** (Schedutil LITTLE cluster, policy0): how quickly the
  governor can raise the CPU frequency. Default 500 µs; tuned value 2000 µs to
  reduce unnecessary ramp-ups and save power at light loads.
- **CAF input-boost frequency / duration**: the frequency cores are temporarily
  boosted to on a touch/input event, and for how long. Tuned defaults reduce
  the boost frequency (1401600 → 1113600 kHz on LITTLE cores) and shorten the
  duration (40 → 20 ms) so the boost is present but more conservative.
- **Persist**: installs `assets/performance_template.sh` (with substituted
  values) to `/data/adb/service.d/cpu_performance.sh`. The boot script waits
  for `init.svc.qcom-post-boot` to reach `stopped` before writing sysfs, so
  it always wins the race against the Qualcomm tuner.
- **Live apply**: writes the same sysfs nodes immediately without a reboot.

### Play Store Tagger (`PlayStoreTaggerManager`)
Retags (or untags) already-installed apps as Play Store-installed, for apps
that check their own install source and refuse to run/update otherwise.
Extracted from a standalone app of the same name
([kgr17/PlayStoreTagger](https://github.com/kgr17/PlayStoreTagger)), with
changes mirrored back manually between the two.
- **Tag / Untag mode**: switching to Untag flips the default filter from
  "Non-Play" to "All" so already-tagged apps are visible to reverse.
- Resolves each app's APK path(s) via `pm path`; a single APK goes through
  a plain `pm install -i com.android.vending --dont-kill -r`, while
  split/multi-APK installs go through the full session flow
  (`pm install-create` → `pm install-write` per split, sized via `stat`, →
  `pm install-commit`, with `pm install-abandon` on any failed write).
  Untagging re-runs the same flow with no `-i` flag.
- Filter chips: **Non-Play** (default) / **All**, plus a **System** toggle
  to include system packages. Search box, per-app checkboxes, running app
  count, and a scrollable log panel that streams each `pm` command's output
  live during a batch operation.

### BBProdFix Settings (`K2PFController`)
Companion page for the `bb-prodfix` Magisk module (gated behind detection at
`/data/adb/modules/bb-prodfix/` - the screen shows "Checking for bb-prodfix
module…" then either the toggles or a not-installed message). Reads/writes
`system.prop` and `service.sh` inside that module's directory directly, no
separate persistence layer needed since the module's own boot scripts apply
them:
- **BlackBerry device identity** - a `ro.product.*` block across
  `system`/`system_ext`/`odm`/`vendor`/`vendor_dlkm` partitions.
- **Bluetooth A2DP offload** - also applied live via `setprop` immediately,
  since those are `persist.*`/mutable props.
- **Higher volume steps**, **SurfaceFlinger triple buffering**, **background
  app limit** - each a small tagged prop block, added/removed by marker
  comment so re-toggling doesn't duplicate lines.
- **Swappiness** - not a boolean like the others but a numeric value patched
  into the `sysctl -w vm.swappiness=` line in `service.sh` (applied live too);
  the screen also reads `ZramController.isPersisted()` to flag if Key2
  Toolbox's own ZRAM module is already managing swappiness, so the two don't
  fight over the same setting.
- All `system.prop`/`service.sh` rewrites go through `printf '%s' > file`
  with `'` escaped as `'\''`, to avoid shell-quoting breakage on the prop
  values. `ro.*` prop changes need a reboot to take effect; everything else
  in this screen is live.

### LED Notify Colors (`LedNotifyManager` + `LedNotifyListenerService`)
Per-app notification LED colors, written directly to the LED class sysfs
nodes via root - bypassing LineageOS's own per-app notification light color
picker, whose color quantization doesn't match this device's actual LED
hardware (arbitrary RGB gets snapped to the nearest color in a lookup table
that's wrong for this kernel). Apps that set their own light color via
`NotificationChannel.setLightColor()` (e.g. WhatsApp) skip that broken layer
and come out correct, which is the same path this module takes.
- **Detection** (`LedNotifyManager.detectMode`): probes for either a
  "separate" RGB layout (`/sys/class/leds/red|green|blue/brightness`) or a
  combined "multicolor" layout (`/sys/class/leds/rgb/multi_intensity`),
  caching whichever is found; if neither exists, the screen shows a warning
  instead of silently no-opping every write.
- **Blinking** uses the kernel's `timer` trigger (`delay_on`/`delay_off`)
  rather than a software loop, so the pattern survives the app process being
  killed by doze/battery optimization. Write order matters: the color has to
  be set *before* switching `trigger` to `timer`, since the trigger snapshots
  whatever brightness is currently set as its "on" level at the moment of
  activation - doing it in the other order silently produces a solid color
  instead of a blink.
- **`LedNotifyListenerService`** (a `NotificationListenerService`, requires
  Settings → Notifications → Notification access) recomputes the LED state
  from scratch on every notification post/remove rather than tracking deltas,
  so it self-corrects if an event is ever missed.
- **Multiple active notifications**: "Show only the most recent" (default)
  lets whichever managed notification posted last own the LED outright;
  "Cycle through colors" walks through every distinct active color in turn
  (2s each) via a dedicated coroutine loop, since sysfs timer triggers can't
  natively chain more than one color.
- **Screen-on suppression**: off by default (the LED only fires while the
  screen is off), overridable per the "Flash while screen is on" toggle,
  tracked via a `BroadcastReceiver` on `ACTION_SCREEN_ON`/`ACTION_SCREEN_OFF`
  registered in `onListenerConnected`.
- Per-app colors and all of the above settings live in their own
  `led_notify` SharedPreferences file, keyed by `color_<packageName>`.
- For any app managed here, its own in-app LED color setting (and/or
  LineageOS's per-app override) should be left unset - otherwise both the
  system's lights service and this module end up racing to write the same
  physical LED.

### Extra Dim (`ExtraDimController`)
Dims the screen below the system's standard minimum via
`Settings.Secure.reduce_bright_colors_activated`/`reduce_bright_colors_level`,
with a manual toggle + intensity slider and an optional daily on/off schedule.
- **Schedule** ("Auto Night Dim"): a `service.d/extra_dim_schedule.sh` watchdog
  daemon (from `assets/extra_dim_schedule_template.sh`, with `__START_MINUTES__`/
  `__END_MINUTES__` substituted) polls every 30s and only writes the setting on
  an actual on/off transition, so a manual toggle in between isn't immediately
  overwritten. Minutes-since-midnight, so any time (e.g. 00:35) is supported.
- **Detached daemon launch**: started via
  `nohup setsid sh ... </dev/null >/dev/null 2>&1 &` rather than a bare
  `nohup ... &`, since the latter didn't reliably survive the invoking root
  shell session being recycled.
- **Lock file** (`/data/adb/.extra_dim_schedule.lock`): a PID lock that also
  verifies `/proc/$PID/cmdline` still names this script before treating it as
  held, rather than a plain `kill -0 $PID` check that can false-positive on an
  unrelated process reusing the same PID.
- **Self-heal**: the screen's own state-loading effect relaunches the daemon
  if the schedule is enabled but not currently running, instead of just
  reporting "not running" passively - catches the case where it died mid-session
  for any reason.

### Bluetooth Auto-Disable (`BtIdleController`)
A root watchdog daemon (`service.d/bt_idle.sh`) that checks once per minute
whether Bluetooth has any device actively connected and turns it off after a
configurable number of idle minutes (5 / 10 / 15 / 30 / 60, default 15). This
prevents a bonded-but-out-of-range radio from holding `hal_bluetooth_lock` and
blocking deep sleep overnight.

Connection detection uses five strategies against `dumpsys bluetooth_manager`:
1. Device table: MAC address line present without `NotConnected`.
2. Active audio device: `mActiveDevice` set to a MAC in A2DP/Headset profile.
3. Active playback: `mIsPlaying: true`.
4. Profile state machines: any profile at `STATE_CONNECTED` / state 2.
5. GATT maps: `GattClientMap` or `GattServerMap` has Entries > 0.

Any match resets the idle counter. A PID lock file (`/data/adb/.bt_idle.lock`)
ensures only one daemon instance runs at a time.

### Wearable Power Saver (`WatchController`)
Reads all wearables paired through GMS from
`/data/data/com.google.android.gms/databases/connectionconfig.db` and lists
them by name and MAC. Toggling a device **Dormant** sets `connectionEnabled = 0`
in that SQLite table and force-stops GMS so it picks up the change immediately.
On the next connection attempt the device is simply ignored by GMS.

A `service.d/wearable_dormant.sh` boot script re-applies `connectionEnabled = 0`
for all selected MACs at boot (retrying up to 30 × 2 s until the data partition
is decrypted and the DB is accessible), because GMS can reset the field during
a cold boot before our script runs.

### Global Telemetry Block (`TelemetryController`)
Scans `/data/data/*/com.google.firebase.crashlytics.xml` across all installed
apps and sets `firebase_crashlytics_collection_enabled` to `false`, using
`nsenter` to reach the real data partition from a root shell. If the key is
already present it does an in-place `sed` rewrite; if absent it injects a
`<boolean>` element before `</map>`. The screen shows how many apps have the
Crashlytics XML (affected) vs how many are already blocked.
- **Watchdog, not one-shot**: apps rewrite their own Crashlytics XML at
  runtime (re-enabling collection on app start), so a single boot-time pass
  gets silently undone. `service.d/block_telemetry.sh` re-scans every 30
  minutes instead (ported from q25toolbox, along with the fixes below).
- **Detached launch + hardened lock**: same two fixes as Extra Dim's daemon -
  launched via `nohup setsid sh ... </dev/null` (a bare `nohup ... &` didn't
  reliably survive the invoking root shell session recycling), and the PID
  lock also checks `/proc/$PID/cmdline` rather than a bare `kill -0`, so a
  reused PID can't be mistaken for the watchdog still running.
- **Self-heal**: the screen's own state-loading effect relaunches the
  watchdog if it's enabled but not running, instead of leaving telemetry
  silently unblocked until the next reboot.

### Battery Usage (`BatteryUsageController`, opened from the Info screen's Battery card)
Per-app estimated battery use since the last reset, read from the same
`dumpsys batterystats --checkin` power model as Android's own Battery usage
screen (machine-parseable, unlike the human-readable dump) - not through
Settings' own UI, which never populates on this device (it requires a
`BATTERY_STATUS_FULL` transition the charging driver never reports).
- **Auto-reset**: a substitute for the missing full-charge signal - a
  `BroadcastReceiver` on `ACTION_BATTERY_CHANGED` resets stats once charging
  crosses a configurable threshold (default 100%), armed once per plug-in
  session so it doesn't refire on every subsequent broadcast at/above the
  threshold.
- Pie chart + per-app list (color-coded, matched between the two), with a
  system-app filter and manual "block all now" reset.

## ⚠ Known risk: writing to `/data/adb/service.d/` from the app

In a previous session, **every attempt to write to `/data/adb/service.d/`
from a root shell post-boot failed with "Permission denied"** - including
`su -c` over ADB using `>`, `dd`, etc. The only thing that worked was an
`install -m 755` from an **interactive Termux `su` session**. The likely
cause is a filesystem-encryption-context mismatch between the shell session
that originally created files there (the initial `adb shell` session at
setup time) and any shell spawned afterwards.

This app's root shell (via `libsu`) is yet another shell context, spawned at
app-runtime, so it **may hit the same wall**. Each module's screen shows a
"Persisted: Yes/No" status that's read back from disk after every write, so
you'll see immediately if a persist operation silently failed.

**If persistence writes fail from the app:**
1. The app's live-apply / enable-now actions still work (they don't touch
   `service.d`), so the toggles remain useful for testing.
2. For persistence, fall back to the Termux method: copy the relevant script
   from `app/src/main/assets/` (or pull it from the app's
   `filesDir` - the app writes a staging copy there before attempting the
   `install`), then from Termux:
   ```
   su
   install -m 755 /path/to/script.sh /data/adb/service.d/script.sh
   ```
3. If you find a write path that *does* work from an app-spawned root shell,
   it's worth updating `AssetInstaller.installFromAsset` here so the app can
   self-persist reliably - that's the main open question for this build.

## Extending

- For stateless root-command modules, add a `core`-style controller in
  `modules/`, following the pattern of `KeyRemapController` /
  `ZramController` / `KbdLightController` / `WirelessAdbController` /
  `Dt2wController` (persist via `AssetInstaller`, live-apply via
  `RootShell.run`).
- For features that need to observe ongoing state (window/IME visibility,
  key events) rather than just fire a command, that observation has to
  happen inside `Key2AccessibilityService` - root has no API for "tell me
  when X happens," only for executing commands. Add the logic there, store
  settings as SharedPreferences booleans/ints in the `key2tweaks` prefs file,
  and write the corresponding screen to read/write those same keys directly
  rather than going through `AssetInstaller`.
- Same idea applies to anything that needs to observe notifications
  specifically (see `LedNotifyListenerService`): use a
  `NotificationListenerService` rather than the accessibility service, with
  its own dedicated SharedPreferences file rather than reusing `key2tweaks`.
- Either way, add a corresponding screen in `ui/` (following e.g.
  `KeyRemapScreen.kt` for the simple case or `NavLockScreen.kt` /
  `ImeBlockScreen.kt` for the prefs-based case, all built on the shared
  `ScreenScaffold`), and wire it into `DetailHost` plus the section lists in
  `ui/HomeScreen.kt` and `ui/Screen.kt`.
- Drop any new boot scripts in `app/src/main/assets/`.
