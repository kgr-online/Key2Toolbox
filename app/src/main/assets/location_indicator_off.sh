#!/system/bin/sh
# Suppress the status-bar location privacy icon - Key2 Toolbox
#
# The device_config flag does not survive a reboot on its own: the persisted
# sync-disable and the flag value are re-established by the framework during
# boot, so applying once from the app is not enough. This re-applies it after
# boot (aggressive phase to beat late flag pushes), then runs a slow watchdog
# that only writes when the live value has drifted.
#
# The script is its own on/off switch: the app removes it from service.d when
# the feature is turned off, and every loop bails out once it is gone.

SELF="/data/adb/service.d/location_indicator_off.sh"

apply() {
    device_config set_sync_disabled_for_tests persistent >/dev/null 2>&1
    device_config put privacy location_indicators_enabled false >/dev/null 2>&1
}
current() { device_config get privacy location_indicators_enabled 2>/dev/null | tr -d '[:space:]'; }

# 1) wait for boot to finish (up to ~3 min)
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ "$i" -lt 90 ]; do
    [ -f "$SELF" ] || exit 0
    sleep 2
    i=$((i + 1))
done

# 2) aggressive phase: every 5s for ~3 min
i=0
while [ "$i" -lt 36 ]; do
    [ -f "$SELF" ] || exit 0
    apply
    sleep 5
    i=$((i + 1))
done

# 3) watchdog
while [ -f "$SELF" ]; do
    [ "$(current)" = "false" ] || apply
    sleep 60
done
