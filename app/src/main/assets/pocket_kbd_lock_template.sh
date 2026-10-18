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

# This loop runs every POLL_INTERVAL seconds whenever the CPU is awake, so it
# sticks to shell builtins (read, case, test): the old grep|grep|grep|head
# pipelines cost ~9 forks per tick, about 16% of a core measured on-device.
# The one process left per tick is `dumpsys window policy`, the only source of
# the keyguard state.

NODE=""   # cached /dev/input/eventN of the keypad
find_keypad_node() {
    # The cached node is trusted only while its sysfs name still says
    # stmpe_keypad; otherwise it is resolved by name again (node numbers have
    # moved across kernel updates on this device).
    if [ -n "$NODE" ]; then
        name=""   # a failed read leaves the old value in place
        read -r name 2>/dev/null < "/sys/class/input/${NODE##*/}/device/name"
        [ "$name" = "stmpe_keypad" ] && return 0
        NODE=""
    fi
    for d in /sys/class/input/event*; do
        read -r name 2>/dev/null < "$d/device/name" || continue
        if [ "$name" = "stmpe_keypad" ]; then
            NODE="/dev/input/${d##*/}"
            return 0
        fi
    done
    return 1
}

is_locked() {
    # Confirmed on-device: `dumpsys window policy` prints both "showing=true"
    # and "mIsShowing=true" while locked - neither matches "mShowing=true"
    # (the original, wrong, guess), so this matches the "howing=true" tail both
    # lines share, which keeps the old case-insensitive behaviour.
    # mCurrentFocus was also checked and does NOT contain "Keyguard" on this
    # ROM (it showed "NotificationShade" instead) - dropped as unreliable.
    out=$(dumpsys window policy 2>/dev/null)
    case "$out" in
        *howing=true*) return 0 ;;
    esac
    return 1
}

PIN_STAMP="/data/adb/.k2tb_pocket_stamp"   # mtime marks the last prefs read
PIN_ACTIVE=0
PIN_TICKS=0
pin_kbd_active() {
    # Reads the app's own SharedPreferences XML directly (root has full
    # filesystem access) rather than duplicating this state - a line like
    # <boolean name="pin_input" value="true" /> means the pref is on.
    # The file is re-read only when it is newer than the stamp (`-nt` is a
    # builtin), plus every 30 ticks as a guard against same-second writes.
    PIN_TICKS=$((PIN_TICKS + 1))
    if [ ! -e "$PIN_STAMP" ] || [ "$PIN_PREFS_FILE" -nt "$PIN_STAMP" ] || [ "$PIN_TICKS" -ge 30 ]; then
        PIN_TICKS=0
        : > "$PIN_STAMP"   # stamp first, so a write during the read is seen next tick
        PIN_ACTIVE=0
        while read -r line; do
            case "$line" in
                *"name=\"$PIN_INPUT_KEY\""*'value="true"'*) PIN_ACTIVE=1; break ;;
            esac
        done 2>/dev/null < "$PIN_PREFS_FILE"
    fi
    [ "$PIN_ACTIVE" = "1" ]
}

restore_and_exit() {
    find_keypad_node && chmod 660 "$NODE" 2>/dev/null
    exit 0
}
trap restore_and_exit TERM INT

current_state="unlocked"

while true; do
    if find_keypad_node; then
        node="$NODE"
        # Cheap check first: with the PIN keyboard on, the keypad is never
        # disabled, so the `dumpsys` in is_locked is skipped entirely.
        if ! pin_kbd_active && is_locked; then
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
