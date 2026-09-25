#!/system/bin/sh
# K2TB - Lockscreen Keyboard Lock watchdog
# Installed via PocketKeyboardLockController -> /data/adb/service.d/pocket_kbd_lock.sh
#
# Physical D-pad keys (DPAD_UP/DOWN/LEFT/RIGHT/CENTER, mapped in
# stmpe_keypad.kl) can navigate focus on the lockscreen and activate
# whatever's focused - including the emergency-call button - with zero
# touchscreen involvement. The screen and keyboard also stay fully live
# in-pocket on this device (see CallProximitySleepController for the related
# in-call proximity bug), so this is a real path to an accidental dial.
#
# This chmod's the keypad's /dev/input/eventN node to 000 while the keyguard
# is showing, restoring 660 on unlock. The node is resolved by name
# (stmpe_keypad) at every cycle rather than hardcoded - hardware node paths
# have moved across kernel updates on this device before.
#
# NOT YET VERIFIED ON-DEVICE: the is_locked() check below. Capture
# `dumpsys window policy` and `dumpsys window` output both locked and
# unlocked before trusting this - the exact key/line this device's ROM
# exposes for keyguard-showing state hasn't been confirmed yet.
#
# Hard safety rule: if "Lockscreen PIN on Keyboard" is enabled (per its own
# SharedPreferences file - single source of truth, not a duplicated marker),
# this NEVER disables the keypad, or the user is locked out of their own
# unlock method.

POLL_INTERVAL="__POLL_INTERVAL_SEC__"
PIN_PREFS_FILE="__PIN_PREFS_FILE__"
PIN_INPUT_KEY="__PIN_INPUT_KEY__"

find_keypad_node() {
    grep -A6 'Name="stmpe_keypad"' /proc/bus/input/devices 2>/dev/null \
        | grep '^H: Handlers=' \
        | grep -o 'event[0-9]*' \
        | head -n1
}

is_locked() {
    # Confirmed on-device: `dumpsys window policy` prints both "showing=true"
    # and "mIsShowing=true" while locked - neither matches "mShowing=true"
    # (the original, wrong, guess), so this now matches case-insensitively
    # on the "showing=true" substring both lines actually share.
    # mCurrentFocus was also checked and does NOT contain "Keyguard" on this
    # ROM (it showed "NotificationShade" instead) - dropped as unreliable.
    dumpsys window policy 2>/dev/null | grep -qi 'showing=true'
}

pin_kbd_active() {
    # Reads the app's own SharedPreferences XML directly (root has full
    # filesystem access) rather than duplicating this state - a line like
    # <boolean name="pin_input" value="true" /> means the pref is on.
    grep "name=\"$PIN_INPUT_KEY\"" "$PIN_PREFS_FILE" 2>/dev/null | grep -q 'value="true"'
}

restore_and_exit() {
    node_name=$(find_keypad_node)
    [ -n "$node_name" ] && chmod 660 "/dev/input/$node_name" 2>/dev/null
    exit 0
}
trap restore_and_exit TERM INT

current_state="unlocked"

while true; do
    node_name=$(find_keypad_node)
    if [ -n "$node_name" ]; then
        node="/dev/input/$node_name"
        if is_locked && ! pin_kbd_active; then
            if [ "$current_state" != "locked" ]; then
                chmod 000 "$node" 2>/dev/null
                current_state="locked"
            fi
        else
            if [ "$current_state" != "unlocked" ]; then
                chmod 660 "$node" 2>/dev/null
                current_state="unlocked"
            fi
        fi
    fi
    sleep "$POLL_INTERVAL"
done
