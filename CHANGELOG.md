# Changelog

All notable changes to Key2 Toolbox are documented here.

## [5.3.6] - 2026-08-31

### Fixed

- **Ctrl / SYM key remap no longer risks bricking the physical keyboard.** The
  old mechanism ran a non-atomic in-place `sed -i` on the vendor keylayout
  file; a reset caught mid-write left it as garbage and the physical keyboard
  dead until it was rebuilt by hand (the capacitive nav keys are a separate
  input device and kept working), and a boot script re-ran the same `sed` every
  boot without ever repairing it. The feature now resolves the keyboard's real
  `KeyLayoutFile` from `dumpsys input` and edits it safely: build the new
  keylayout from a pristine golden copy, validate it, write it to a temp file
  and `mv` (atomic rename) - never in place. A hardened boot script re-applies
  it every boot and self-heals a corrupt keylayout from the golden copy first.
  Takes effect live via a `uevent` remove/add, no reboot. (An APatch module
  overlay of `/vendor` was tried and does not mount on the Key2's partitions.)
- **Ctrl / SYM remap no longer disables SELinux.** The old scripts wrapped the
  keylayout edit in `setenforce 0` / `setenforce 1`, making the whole system
  permissive for a moment on every boot. Verified on-device that the root
  domain can remount `/vendor` rw, write the keylayout and relabel it with
  SELinux enforcing, so the `setenforce` calls are gone.

### Added

- **Ctrl remap: pick which key becomes Ctrl** - the Convenience/Fn key
  (scancode 110) or the Currency key (scancode 5). Previously fixed to Fn.

## [5.3.1] - 2026-08-29

### Added

- **Toolbelt: "Suppress the location privacy icon".** New toggle on the Toolbelt
  screen. Every time an app reads location, Android briefly forces the
  navigation bar back over the current fullscreen app to show the status-bar
  location icon — which pops the belt back on top of the app. The toggle turns
  that icon off system-wide (`device_config privacy
  location_indicators_enabled=false`) and freezes feature-flag sync so it
  survives a reboot. Disclaimer spells out the trade-offs (no GPS-in-use cue, no
  further device_config server updates; mic/camera indicators unaffected). Root
  only; covered by Backup/Restore (Toolbelt module); translated into all seven
  languages.

### Fixed

- **Nav Lock: capacitive keys coming back to life after unlocking.** The
  synaptics touch controller reloads its firmware default (keys enabled)
  whenever the panel powers back up, and on this device that reload can land
  seconds after the wake broadcast — past the old fixed retry ladder — after
  which nothing re-asserted because the reconcile path trusted a stale cache.
  The wake path now runs a self-verifying poll that re-reads the real sysfs
  node and re-pushes until it confirms the keys are off, and the
  accessibility-event path re-checks the node (throttled) whenever the keys are
  meant to be disabled.

## [5.3] - 2026-08-29

### Added

- **Global Telemetry Block — per-app viewer.** The Telemetry screen gets a
  "Show per-app status" list: every app carrying a Crashlytics / Analytics /
  Performance prefs file, and for each surface whether collection is currently
  forced off or still leaking (leaking apps listed first).
- **Backup/Restore now covers Toolbelt and Recents Layout.** The eight Toolbelt
  config keys join the per-key backup map under a new *Toolbelt* module; Recents
  Layout (which lives in two `Settings.Global` keys, not prefs) gets its own
  backup section. On restore the launcher-hook mirror is re-pushed so a restored
  belt takes effect without a manual toggle.

### Changed

- **Global Telemetry Block — broader and faster.** The watchdog now also
  neutralises Firebase / Google Analytics (`measurement_enabled` /
  `measurement_enabled_from_api` / `firebase_analytics_collection_enabled`),
  Performance Monitoring and the Firebase master data-collection flag — not just
  Crashlytics — flipping every occurrence to false and injecting the opt-out
  into the Crashlytics and GMS-measurement prefs of apps that don't have one.
  Re-scan interval 30 → 10 minutes, with a burst of passes after boot and
  immediately after any app install/removal, so new apps are covered in about a
  minute instead of up to half an hour.
- **Toolbelt slots respond instantly.** A slot with no double-tap action fires
  on finger-up instead of waiting out the ~300 ms double-tap timeout, and the
  haptic fires on touch-down.
- **Toolbelt in the Key2 Toolbox app itself** now paints the reserved
  navigation-bar strip with the app's own Material You surface tint, so the
  Translucent belt reads as part of the app chrome rather than sitting on a flat
  black/white band.

### Fixed

- Toolbelt and Recents Layout are now translated into all seven shipped
  languages (both were English-only since 5.1).

## [5.2] - 2026-08-29

### Fixed

- **Toolbelt / Recents Layout: launcher crash-loop and stranded app
  transitions.** With both modules active, `RecentsHookInit` forced
  `DeviceProfile.isTablet = true` across the launcher process and cleared
  `isTaskbarPresent` on every `DeviceProfile` - including the Taskbar's own.
  On this build `NavButtonLayoutFactory.getUiLayoutter()` has no branch for
  `(isTablet && !isTaskbarPresent)` and throws `"No layoutter found"` on every
  Taskbar-window configuration change (entering / leaving fullscreen, rotation,
  dark-mode toggle, IME), killing `com.android.launcher3` in a loop. The
  Taskbar flashing over fullscreen apps and app transitions freezing
  mid-animation (a `GestureState` crash in `AbsSwipeUpHandler` when the
  launcher process dies during the recents animation) both followed from that.
  The tablet override and the `isTaskbarPresent` clear now skip the Taskbar's
  own `DeviceProfile`, and a guard on `getUiLayoutter()` flips
  `isTaskbarPresent` on for the call if a profile still reaches the throwing
  branch.

- **Toolbelt reappearing over a fullscreen app.** The immersive check keyed off
  whether a status-bar strip was on screen, so a transient reveal - the privacy
  indicator on a location / mic / camera access, or a deliberate swipe from the
  top edge - counted as "left fullscreen" and slid the belt back in. It now
  reads the focused app window's *requested* inset visibility from
  `dumpsys window` (cached, refreshed on window changes); a transient reveal
  does not change the request, so the belt stays hidden and only returns when
  the app itself drops immersive.

- **Overview showing the launcher's own tile.** On a third-party-home setup
  (KISS) QuickStep's Launcher / RecentsActivity task could leak into Overview
  as a blank, thumbnail-less card. `RecentsView.applyLoadPlan` now drops any
  task belonging to the launcher package or carrying a HOME intent.

### Changed

- **Toolbelt collapse is now a pull-to-grab gesture.** The grip strip and
  handle pill drag the belt with the finger between shown and collapsed, with
  an elastic pull past either end and a damped-spring settle on release (a
  fling picks the direction, otherwise it snaps to the nearer state). A tap
  still toggles. The fullscreen / IME auto-hide uses the same spring.

## [5.1] - 2026-08-28

### Added

- **Toolbelt** (Display tab): a BlackBerry Q20-style bar of five customizable
  icons pinned to the bottom of the screen that replaces the on-screen
  navigation. It reserves its height like a real nav bar, so app content ends
  above it. Q20 defaults: phone (dialer, long-press = voice assist), BlackBerry
  logo (Recents), centre Home, Back (long-press = last app), hangup. Each
  slot's icon and its single / double / long-tap actions are configurable
  (Home, Back, Recents, Notifications, Quick Settings, Power dialog, Screenshot,
  Lock, Split-screen, Voice assist, Dialer, Last app, Launch app, Toggle belt,
  Hangup, Hangup-or-Home). "Launch app" opens an app picker. Hangup-or-Home
  acts as Home unless a call is in progress (detected via `AudioManager.mode`,
  no permission), then it ends the call with a root `KEYCODE_ENDCALL`.

  Appearance settings: bar height (36-88 dp), icon size, haptic-feedback
  strength (via the `Vibrator` API - `View.performHapticFeedback` does not fire
  from an overlay window), and a colour mode - Fixed (black), Material You
  (`system_neutral*`), or Transparent (a faint scrim so the app's own window
  background shows through the reserved strip). Optional "Collapsible" mode adds
  a grab strip: swipe down / tap / long-press to hide the belt and reclaim its
  space, tap the strip to bring it back. The belt also auto-hides in fullscreen
  apps, while the soft keyboard is up, and on the lockscreen.

  The belt is a `TYPE_ACCESSIBILITY_OVERLAY` window drawn by
  `Key2AccessibilityService` (same mechanism as the Ticker). Hiding the
  on-screen nav bar, reserving the belt's inset and disabling the **bottom**
  swipe-up gesture (home / recents / quickswitch - the **edge back-gesture is
  deliberately left working**) is a second LSPosed hook,
  `com.kgr.key2toolbox.xposed.NavBarHookInit`, scoped to the launcher and gated
  on the world-readable `Settings.Global` keys `key2_toolbelt_active` /
  `key2_toolbelt_inset_px`. On this device the nav bar is the Launcher3
  Taskbar, so the hook overrides `TaskbarStashController` /
  `TaskbarInsetsController` height reporting and skips
  `TouchInteractionService.onInputEvent`. Verified by decompiling
  `TrebuchetQuickStep.apk`. The taskbar only re-reads its inset on recreation,
  so the app restarts the launcher when the reserved height changes (invisible
  when a third-party launcher is the home app). Without an Xposed framework the
  belt still draws but the nav bar / bottom gesture stay active.

- **Recents Layout** (Display tab): a toggle that forces the launcher's
  two-row grid Overview instead of the stock single row of task cards.
  Implemented entirely as an LSPosed module
  (`com.kgr.key2toolbox.xposed.RecentsHookInit`), scoped to
  `com.android.launcher3` (the AOSP Launcher3 that LineageOS 22.2 ships on
  the Key2). Needs an Xposed framework (LSPosed / APatch's built-in).

  Verified by decompiling `TrebuchetQuickStep.apk`: Overview task geometry
  branches on the `DeviceProfile.isTablet` field, not on
  `RecentsView.showAsGrid()`, and `isTablet` is derived in the
  `DeviceProfile` constructor from
  `DisplayController.Info.isTablet(WindowBounds)`. The hook forces that
  method to return `true` while the toggle is on, so every downstream metric
  is computed on the tablet path. It also clears `isTaskbarPresent` /
  `taskbarHeight` right after `DeviceProfile` construction to drop the
  floating tablet nav bar that would otherwise come with it (the home
  screen / hotseat may still shift slightly, since they share the profile).
  `RecentsView.showAsGrid()` is pinned too, mainly to make the off state
  deterministic. Includes an Overview background-transparency slider that
  scales the scrim colour.

  Config: two world-readable `Settings.Global` keys
  (`key2_recents_layout_mode`, `key2_recents_scrim_alpha`) written with root,
  read by the hook with no permission. Every hook logs to
  `Key2Toolbox-Xposed`.

  Not done: a real "masonry" varied-height mosaic. That layout does not
  exist in AOSP Launcher3 (it was bespoke to BlackBerry's launcher), so it
  would mean injecting a custom `TaskView` layout pass at runtime. Left for
  a later pass.

## [5.0] - 2026-08-27

Adds eleven modules, splits the bottom navigation, and localizes the whole
app into seven languages. versionCode 1 → 31. Debug build tested on a KEY2
(LineageOS 22.2 / Android 15, 4.19 kernel).

### Added — modules

- **Calculator Keys**: routes physical keys to the on-screen buttons of the
  AOSP/Google Calculator (digits Q W E R / S D F / Z X C, operators, Sym
  toggles scientific mode, Backspace deletes). Accessibility service only,
  no root.
- **IME Suggestion Shortcuts**: Ctrl+W / Ctrl+E / Ctrl+R pick candidate
  1/2/3 from the physical-keyboard candidate strip (BlackBerry Keyboard and
  similar). Only fires while a suggestion is showing, so the shortcuts
  behave normally elsewhere. Requires a key remapped to Ctrl. No root.
- **Chat Enter-to-Send**: Enter sends, Alt+Enter / Shift+Enter inserts a
  newline, in ~15 messaging apps (Messages, WhatsApp, Telegram, Signal,
  Element, Mattermost, ChatGPT, Perplexity, …). Accessibility service only.
- **Call Shortcuts**: on the Google Phone in-call screen, M mutes, the
  Currency key (or Ctrl if remapped) toggles speaker, and Q W E R / S D F /
  Z X C open the dialpad and send DTMF. Per-locale button-label matching.
  No root.
- **Auto-Focus**: in selected apps, focuses and types into the first text
  field as soon as a printable key is pressed with nothing focused. No root.
- **Extra Dim**: dims below the system minimum brightness via an overlay,
  with an optional boot-persisted night schedule.
- **Battery Usage**: per-app battery estimate read from the platform's
  native power model; adds an Info-tab row and a detail screen.
- **Global Telemetry Block**: disables Firebase Crashlytics system-wide.
  Root, boot-persisted.
- **Auto-disable Bluetooth**: turns Bluetooth off after a configurable idle
  period with nothing connected. Root daemon from service.d; multi-signal
  connection detection (adapter ACL state, A2DP/HFP active device, playback,
  AVRCP) so it never turns off under a device in use; wall-clock deadline so
  suspended time counts.
- **Auto-disable Location**: same design as Auto-disable Bluetooth, for
  Location. Passive / low-power Play-services location checks do not reset
  the timer.
- **Ticker Notifications**: scrolling status-bar banner for new
  notifications instead of a heads-up popup, and turns heads-up off
  system-wide while enabled. Uses a `NotificationListenerService` plus a
  `TYPE_ACCESSIBILITY_OVERLAY` window. Per-app and per-category blocklist;
  fixed / app-icon / Monet colour modes. Adds an `androidx.palette`
  dependency.

### Added — localization

- Every user-facing string (~499) moved from Kotlin literals into resources.
  `Screen` titles/subtitles, `AppTab` labels and `AccessType` labels are now
  `@StringRes`; `SettingsBackup.BackupModule` labels are string resources.
- Translations for **Catalan, German, Spanish, French, Italian, Dutch,
  Portuguese**. The 326 previously-translated strings for the older
  overlapping modules are carried over verbatim; ~173 new strings per
  language cover the new modules and Ticker. Key parity and
  printf-specifier parity with the English source verified for all seven
  files.

### Changed

- **Navigation**: the bottom bar is split into Keyboard / Display / System /
  Network tabs (plus Info and Settings) instead of one flat list.
- **ZRAM screen**: seeds its controls from the live device state and marks
  which values come from ROM defaults.
- **Status bar**: icon and background colour set explicitly per light/dark
  scheme rather than relying on the framework default.

### Fixed

- **Home menu**: the per-module access badges (`[root]` / `[accessibility]`
  / `[notification]`) shared an unweighted row with the subtitle, so a long
  subtitle squeezed them to near-zero width and each badge wrapped one
  character per line (surfaced by the longer translated subtitles). The
  subtitle now yields space and wraps itself; badges stay on one line.
- **5GHz Hotspot Workaround**: the boot script broke out of its retry loop
  on the first successful `force-country-code` call, so the telephony
  stack's SIM-country detection (~30–60 s into boot) silently reverted it.
  It now re-applies every 3 s for ~4 minutes after boot, then runs a 60 s
  drift watchdog, and stops as soon as the feature is toggled off.

## [4.8-beta9] - 2026-08-21

### Fixed
- **Play Store Tagger reporting the wrong installer for every app**: two
  bugs in installer detection. `getInstaller()` shelled out to
  `cmd package get-install-source`, which doesn't exist on this ROM and
  silently returned null, so the post-tag status refresh never reflected
  reality. Separately, `loadApps()` read each app's installer via
  `PackageManager.getInstallSourceInfo()`/`getInstallerPackageName()`
  in-process, which returned wrong data on this ROM (e.g. F-Droid itself
  showing as Play Store-installed) even though the equivalent shell read
  was correct. Both are replaced with `dumpsys`-based reads:
  `getInstaller()` now parses `dumpsys package <pkg>`, and a new
  `getAllInstallers()` parses one `dumpsys package -a` dump for the whole
  app list in a single root shell call instead of one `PackageManager`
  call per app.
- **Accessibility service status always showing disabled**: both
  `Settings.Secure` and `AccessibilityManager` returned incorrect/empty
  results when read in-process on this ROM, even though the service was
  genuinely enabled and actively bound. `Key2AccessibilityService` now
  reports its own state via a companion `isRunning` flag, set in
  `onServiceConnected()` and cleared in `onUnbind()`/`onDestroy()`; both
  status checks read that flag directly instead.

## [4.8-beta7] - 2026-08-20

### Fixed
- **ZRAM swappiness reverting to default after reboot**: LineageOS/AOSP's
  `init.rc` unconditionally writes `swappiness=60` on its "on boot"
  trigger, which fires after `/data/adb/service.d/zram_size.sh` has
  already run at the late_start service stage - silently reverting any
  persisted swappiness value back to 60 on every boot (size and
  comp_algorithm were unaffected). Added `ZramBootCompletedReceiver`, a
  `BOOT_COMPLETED` receiver that re-applies the persisted swappiness
  after boot fully completes, guaranteeing it runs after the ROM's own
  write rather than racing it.

## [4.8-beta5] - 2026-08-15

### Added
- **LED Notify - Respect Battery Saver** (default on): suppresses the LED
  whenever `PowerManager.isPowerSaveMode` is true, however it was
  triggered (manual, schedule, or automatic low-battery). Same shape as
  the existing Respect DND check, with its own broadcast receiver for
  `ACTION_POWER_SAVE_MODE_CHANGED`.
- **LED Notify - Notification Acknowledgement** (default off): replicates
  factory 8.1 KEY2 behavior. Once the screen is turned on, a notification
  is seen on the lock screen, and the screen is turned back off with the
  power button, that notification's LED won't re-trigger. A screen
  timeout does not count as acknowledgement. Screen-off reason is read
  via `dumpsys power`'s `mLastSleepReason` field over root shell (same
  approach as the existing DND fallback).

## [4.8-beta4] - 2026-08-13

### Added
- **LED Notify - minimum importance threshold**: `recompute()` now checks
  each notification's ranking importance against a configurable floor
  (default: Default and above) before assigning it a color, filtering out
  low-importance notifications - e.g. an app that posts and immediately
  self-retracts a low-priority notification - before the LED ever lights.
  Adds a "Minimum importance to light the LED" chip row in Settings (Any
  importance / Low and above / Default and above / High only). Unknown
  importance always passes the filter rather than silently suppressing
  the LED.

### Fixed
- **LED Notify settings chip row**: the longest importance-preset label
  could get squeezed into a near-zero-width chip and wrap one character
  per line. Labels no longer wrap, and the row scrolls horizontally
  instead of forcing all four presets into one screen width.

## [4.8-beta3] - 2026-08-13

### Added
- **LED Notify Colors now respects Do Not Disturb**: `recompute()` checks
  `NotificationManager.getCurrentInterruptionFilter()` before blinking, so
  any DND-style suppression - manual toggle, schedule, or a LOS Mode
  configured to enable DND - stops the LED, regardless of what triggered it.
  A `BroadcastReceiver` on `ACTION_INTERRUPTION_FILTER_CHANGED` stops an
  in-progress blink as soon as DND engages rather than waiting for the next
  notification event. New "Respect Do Not Disturb" toggle (on by default)
  lets users opt out if they want the LED to keep flashing during DND.

## [4.8-beta2] - 2026-08-09

### Added
- **Denylist Manager**: unified, opt-in control for Magisk's DenyList and
  Zygisk-Hide's `config.json`, plus a launch shortcut into HMA-OSS. Adding
  an app denies its full declared process set via `PackageManager`, not
  just the base package. Master toggle defaults off so setups already using
  FolkPatch/APatch/HMA independently are left untouched.

## [4.7-beta1] - 2026-08-09

### Added
- **AdBlock module**: ports the standalone systemless-hosts module into
  K2TB as `k2tb_adblock`, replacing its WebUI with a native Compose screen.
  `AdBlockController` deploys and drives the module via `hosts_ctl.sh`
  (install, enable/disable, add/remove entries, whitelist, sources, update,
  reset); live edits apply immediately post-install via `rebuild()`, with
  only the initial install requiring a reboot. Bundles a default blacklist
  as an asset for offline-ready filtering out of the box.
- AdBlock added to selective settings backup/restore, round-tripping
  sources/user-added/wildcard-added/user-removed/whitelist entries;
  restoring installs the module if it isn't already present.

## [4.6-beta7] - 2026-08-02

### Added
- **Independent SYM key fix**: SYM and Ctrl are now independent toggles on
  one shared Magisk module file (read-modify-write instead of a pre-patched
  asset), so toggling one no longer disturbs the other. Hidden/no-op on
  older-kernel devices where SYM already works natively.

### Changed
- Renamed the "Convenience Key → Ctrl" card to **Physical Keyboard Fixes**
  to reflect it now covers both keys.

## [4.6-beta5] - 2026-08-02

### Added
- **Device auto-detection for the Ctrl key fix**: routes 4.19-kernel devices
  through the old sed+boot-script path and 4.4-kernel devices through a
  Magisk module, auto-detected via `/proc/bus/input/devices`; surfaces a
  reboot-required message on the newer path.
- **Selective module backup/restore**: `SettingsBackup` gains a
  `BackupModule` enum so individual modules (PinKeyboard, NavLock,
  ImeBlock, LedNotify, ZRAM) can be included or excluded per backup instead
  of all-or-nothing.

## [4.6-beta3] / [4.6-beta2] - 2026-07-18

### Added
- **Settings backup/restore**: export/import of `key2tweaks` and
  `led_notify` preferences to/from JSON via the Storage Access Framework,
  with typed values for a safe round-trip.
- **Live status indicators** on the Settings screen: Accessibility,
  Notification, and Root rows now show live green/red status, refreshing
  on resume.

### Changed
- Updated the repo remote reference.

### Fixed
- Settings screen wasn't scrollable; content below Quick Access was
  clipped.

## [4.5-beta3] - 2026-07-14

### Added
- **Settings tab**: GitHub Releases update checker with in-app
  download-and-root-install via libsu, a live contributors list, and
  quick-access shortcuts to Accessibility and Notification Listener
  settings. Version comparison correctly handles beta suffixes (e.g.
  4.5-beta2 vs 4.4-beta4).

### Fixed
- Contributors fetch was running on the main thread
  (`NetworkOnMainThreadException`); now wrapped in `Dispatchers.IO`.

## [4.4-beta4] - 2026-07-11

### Fixed
- **Uneven LED blink timing**: root-shell write latency was confirmed fast
  and steady via new per-write timing logs, ruling it out as the cause -
  the actual culprit was Doze mode. Screen off + on battery + idle
  suspends CPU timing precision, so the blink loop's `delay()` calls fired
  late and in bursts once Doze kicked in (and Doze explicitly doesn't
  engage while charging, which is why the bug was invisible plugged in). A
  partial wake lock is now held for the duration of an active blink,
  released the moment blinking stops, with a 10-minute safety timeout that
  renews every 5 minutes so a long-unread notification doesn't lose it
  partway through.

## [4.4-beta2] - 2026-07-06

### Fixed
- **LED blink concurrency race** causing intermittent stuck-on/stuck-off/
  flicker patterns: the blink loop ran on a multi-threaded dispatcher while
  LED writes are blocking, not suspending, so cancelling an old blink job
  couldn't interrupt it mid-write and two loops could briefly race on the
  same LED. Every LED write now funnels through one dedicated
  single-thread executor regardless of caller, and the listener service
  uses one unified single-thread dispatcher for both `recompute()` and the
  blink loop.
- **Cycle mode** was swapping directly between colors with no gap; each
  color now gets its own on/off blink in turn, matching single-color
  behavior.

### Changed
- The "already running" check for the blink loop now compares colors as a
  set instead of an ordered list, so notification reordering alone doesn't
  needlessly restart the loop and reopen the race window.

## [4.3-beta7] - 2026-07-05

### Added
- Access-type tags (`[root]`/`[accessibility]`/`[notification]`) on module
  cards.

## [4.3-beta6] - 2026-07-05

### Fixed
- **LED blink didn't actually blink**: this device's LED driver has no
  kernel `timer` trigger - only fixed hardware triggers are listed, and
  every `echo timer > trigger` was silently rejected. Blinking is now done
  in software instead: `LedNotifyListenerService` alternates `setColor()`/
  `off()` via a coroutine, for both the single-color and cycle-mode cases.

## [4.3-beta5] - 2026-07-04

### Added
- **LED Notify Colors module**: per-app notification LED colors driven via
  root sysfs writes, bypassing LineageOS's own per-app light-color picker
  (whose color quantization doesn't match this device's LED hardware).
  Configurable flash length, screen-on suppression toggle, and a
  cycle-through-colors option for multiple active notifications.

## [4.3-beta1] - 2026-06-28

### Added
- **BBProdFix** companion page.

## [4.2-beta2] - 2026-06-25

### Added
- Untag mode for Play Store Tagger.

## [4.2-beta1] - 2026-06-25

### Added
- **Play Store Tagger module**: retags (or untags) installed apps as Play
  Store-installed.

## [4.1-beta5] - 2026-06-22

### Added
- **Bottom navigation** restructured into Info / Keyboard / System tabs,
  with Info as the landing page (device + battery + access status).
- **Per-App Keyboard Block** reworked to switch to a bundled do-nothing
  passthrough IME (`Key2PassthroughIme`) while a selected app is
  foreground, so physical keys reach the app raw instead of being
  intercepted by the BlackBerry IME - replaces the previous (ineffective)
  `show_ime_with_hard_keyboard` toggle.
- **5GHz Hotspot Workaround**: forces the WiFi region to US (live +
  persisted boot script) so 5GHz SoftAP works, since every EU regdomain on
  this build exposes zero 5GHz AP channels.
- Full **Material You** theming that follows the system light/dark
  setting (previously forced pure-black).

### Changed
- Fixed scrolling glitches by letting the `Scaffold` own the system-bar
  insets instead of each screen re-applying them.

### Removed
- **Audio FX** (system-wide EQ/BassBoost/LoudnessEnhancer) in favor of
  NLSound.

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
