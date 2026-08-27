# Changelog

All notable changes to Key2 Toolbox are documented here.

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
