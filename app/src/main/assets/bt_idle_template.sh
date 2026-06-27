#!/system/bin/sh
# Auto-disable Bluetooth when idle - Key2 Toolbox
#
# Turns Bluetooth off after __TIMEOUT_MIN__ minutes with no device connected,
# so an idle radio (bonded devices + GMS/Fast Pair scanning) can't hold
# hal_bluetooth_lock and drain the battery overnight. Connecting any device
# (earbuds, watch, speaker) resets the timer; manually it just stays off until
# you re-enable Bluetooth.
#
# Runs as a root daemon: launched at boot from /data/adb/service.d (the module
# manager runs service.d scripts in their own process, so the loop is fine) and
# also started live by the app. A pid lock keeps a single instance.

LOCK=/data/adb/.bt_idle.lock
if [ -f "$LOCK" ] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
    exit 0
fi
echo $$ > "$LOCK"

TIMEOUT=__TIMEOUT_MIN__
idle=0
while true; do
    sleep 60
    if [ "$(settings get global bluetooth_on 2>/dev/null)" != "1" ]; then
        idle=0
        continue
    fi
    # Check for any actively connected BT device.
    #
    # "mConnectionState: 2" is too broad — it appears in bonded-but-idle device
    # records and internal state objects, causing false positives.
    #
    # Strategy (first match wins):
    #   1. ACL level: "state: ACL_CONNECTED" lines (reliable on AOSP / LineageOS).
    #   2. Profile level: lines that start with a profile name and contain
    #      "state=connected" (A2DP, HFP, Headset, etc.).
    #   3. Legacy fallback: "mConnectionState=2" *only* on lines that also carry
    #      a device MAC address, anchoring it to actual device-state lines.
    _bt_dump="$(dumpsys bluetooth_manager 2>/dev/null)"
    _connected=false
    # 1. ACL connected (most reliable)
    echo "$_bt_dump" | grep -qiE "state[[:space:]]*:[[:space:]]*ACL_CONNECTED" && _connected=true
    # 2. Profile-level connected
    if ! $_connected; then
        echo "$_bt_dump" | grep -qiE "^[[:space:]]+(A2dp|Headset|HFP|Gatt|Map|Pan|HID|PAN)[^:]*state[[:space:]]*=[[:space:]]*connected" && _connected=true
    fi
    # 3. Legacy fallback: mConnectionState=2 anchored to a line with a BT MAC
    if ! $_connected; then
        echo "$_bt_dump" | grep -E "([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}" | grep -q "mConnectionState[[:space:]]*:[[:space:]]*2" && _connected=true
    fi
    if $_connected; then
        idle=0
    else
        idle=$((idle + 1))
        if [ "$idle" -ge "$TIMEOUT" ]; then
            cmd bluetooth_manager disable >/dev/null 2>&1
            idle=0
        fi
    fi
done
