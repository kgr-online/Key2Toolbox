#!/system/bin/sh
# K2TB - Lockscreen Keyboard Lock watchdog (event-driven state, cheap fallback)
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
# (stmpe_keypad) and re-verified against its sysfs name every cycle rather
# than hardcoded - hardware node paths have moved across kernel updates on
# this device before.
#
# Cost history (why this loop looks the way it does):
#  - v1 polled grep pipelines: ~9 forks/tick, ~16% of a core measured.
#  - v2 kept one `dumpsys window policy` fork per tick (1 s) - measured
#    on-device at 176 ms CPU EVERY second (10.6 s/min, own+children), which
#    kept system_server churning and cut the platform's overnight suspend to
#    one short cycle every ~6 s: several %/h of continuous battery drain.
#  - v3 (this): the app (KeyguardStateWriter, driven by KeyguardManager +
#    screen broadcasts) writes the keyguard state to $STATE_FILE on every
#    transition, stamped in elapsedRealtime seconds. While the stamp is
#    fresh (< STATE_FRESH_SEC) the daemon trusts it using ONLY builtins
#    (read of the state file and /proc/uptime): zero forks per tick. When
#    the app is dead or the root write fails, the stamp goes stale and the
#    daemon falls back to the dumpsys check every FALLBACK_EVERY-th tick
#    (5 s at the 1 s poll) - same semantics, 1/5 of v2's cost, applied on
#    transitions only.
#
# Hard safety rule: if "Lockscreen PIN on Keyboard" is enabled (per its own
# SharedPreferences file - single source of truth, not a duplicated marker),
# the keypad is NEVER disabled, or the user is locked out of their own
# unlock method. The app-side writer applies the same rule before it ever
# writes "locked"; the fallback path re-checks the prefs file directly so
# the rule holds with the app dead too.

POLL_INTERVAL="__POLL_INTERVAL_SEC__"
PIN_PREFS_FILE="__PIN_PREFS_FILE__"
PIN_INPUT_KEY="__PIN_INPUT_KEY__"
STATE_FILE="/data/adb/.k2tb_keyguard_state"
STATE_FRESH_SEC=120
FALLBACK_EVERY=5

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

# Sets DECISION_G to "lock"/"unlock" when the app-written state file is
# fresh, or leaves it empty when it is absent/stale (caller falls back to
# dumpsys). All builtins - no fork, not even a $( ) subshell, on this path.
app_decision() {
    DECISION_G=""
    st=""; stamp=""
    IFS=' ' read -r st stamp 2>/dev/null < "$STATE_FILE" || return 1
    [ -n "$st" ] && [ -n "$stamp" ] || return 1
    up=""
    read -r up rest 2>/dev/null < /proc/uptime || return 1
    [ -n "$up" ] || return 1
    age=$(( ${up%%.*} - stamp ))
    [ "$age" -ge 0 ] && [ "$age" -lt "$STATE_FRESH_SEC" ] || return 1
    if [ "$st" = "locked" ]; then DECISION_G="lock"; else DECISION_G="unlock"; fi
    return 0
}

restore_and_exit() {
    find_keypad_node && chmod 660 "$NODE" 2>/dev/null
    exit 0
}
trap restore_and_exit TERM INT

current_state="unlocked"
tick=0

while true; do
    if find_keypad_node; then
        node="$NODE"
        decision=""

        # 1) Trust the app's event-driven state while the stamp is fresh.
        app_decision
        decision="$DECISION_G"

        # 2) App absent/stale -> dumpsys every FALLBACK_EVERY-th tick.
        #    Cheap check first: with the PIN keyboard on, the keypad is never
        #    disabled, so the dumpsys in is_locked is skipped entirely.
        if [ -z "$decision" ]; then
            tick=$((tick + 1))
            if [ $((tick % FALLBACK_EVERY)) -eq 0 ]; then
                if pin_kbd_active; then
                    decision="unlock"
                elif is_locked; then
                    decision="lock"
                else
                    decision="unlock"
                fi
            fi
            # else: no new information this tick - keep current_state.
        else
            tick=0
        fi

        # 3) Apply on transitions only - no chmod churn while idle.
        if [ "$decision" = "lock" ] && [ "$current_state" != "locked" ]; then
            chmod 000 "$node" 2>/dev/null
            current_state="locked"
        elif [ "$decision" = "unlock" ] && [ "$current_state" != "unlocked" ]; then
            chmod 660 "$node" 2>/dev/null
            current_state="unlocked"
        fi
    fi
    sleep "$POLL_INTERVAL"
done
