#!/system/bin/sh
# Lockscreen PIN digits via root - Key2 Toolbox
#
# Types the KEY2's letter-digits (W E R / S D F / Z X C = 1..9, Q = 0) into the lockscreen PIN pad. Runs from
# /data/adb/service.d, so it also works BEFORE the first unlock after a boot, when the accessibility service
# cannot run (it is not direct-boot aware), and it costs nothing per key press in other apps.
#
# It only OBSERVES the keyboard (getevent is read-only); it cannot swallow a key. So it injects the digit and
# leaves the raw letter to the system, which ignores letters on a PIN pad. Raw digits, Enter and Delete stay on
# the accessibility path.
#
# Never logs which digit was pressed: the log is only about the daemon's own life.

LOCK=/data/adb/.pin_keys.lock
LOG=/data/adb/.pin_keys.log
if [ -f "$LOCK" ]; then
    OLD_PID=$(cat "$LOCK" 2>/dev/null)
    if [ -n "$OLD_PID" ] && grep -q pin_keys "/proc/$OLD_PID/cmdline" 2>/dev/null; then
        exit 0
    fi
fi
echo $$ > "$LOCK"

KBD_NAME=stmpe_keypad
BACKLIGHT=/sys/class/leds/lcd-backlight/brightness
CACHE_S=2        # how long a lock-state answer is trusted

log() {
    echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
    if [ "$(wc -l < "$LOG" 2>/dev/null || echo 0)" -gt 100 ]; then
        tail -n 50 "$LOG" > "$LOG.tmp" 2>/dev/null && mv "$LOG.tmp" "$LOG"
    fi
}

find_node() {
    for d in /sys/class/input/event*; do
        if [ "$(cat "$d/device/name" 2>/dev/null)" = "$KBD_NAME" ]; then
            echo "/dev/input/${d##*/}"
            return
        fi
    done
}

# One dumpsys call answers "keyguard up, not covered by an app, screen awake".
locked_awake() {
    s=$(dumpsys window policy 2>/dev/null)
    echo "$s" | grep -q '^ *showing=true' || return 1
    echo "$s" | grep -q '^ *occluded=true' && return 1
    echo "$s" | grep -q 'INTERACTIVE_STATE_AWAKE' || return 1
    return 0
}

log "start"

while true; do
    NODE=$(find_node)
    if [ -z "$NODE" ]; then sleep 2; continue; fi

    lock_ok=0; lock_ts=0; was_off=1
    # getevent prints "type code value" in hex for a single device; value 1 = key down.
    getevent -q "$NODE" 2>/dev/null | while read -r t c v; do
        [ "$t" = "0001" ] && [ "$v" = "00000001" ] || continue

        # Which key? Anything else costs nothing, in particular no dumpsys.
        act=""
        case "$c" in
            0010) act=7 ;;                                    # Q -> KEYCODE_0
            0011) act=8 ;;  0012) act=9 ;;  0013) act=10 ;;   # W E R -> KEYCODE_1..3
            001f) act=11 ;; 0020) act=12 ;; 0021) act=13 ;;   # S D F -> 4..6
            002c) act=14 ;; 002d) act=15 ;; 002e) act=16 ;;   # Z X C -> 7..9
            *) continue ;;
        esac

        # Screen off: nothing to do, and the next key must re-check the lock state.
        if [ -r "$BACKLIGHT" ] && [ "$(cat "$BACKLIGHT" 2>/dev/null)" = "0" ]; then
            lock_ok=0; was_off=1
            continue
        fi

        now=$(date +%s)
        if [ "$was_off" = "1" ] || [ $((now - lock_ts)) -gt $CACHE_S ]; then
            if locked_awake; then lock_ok=1; else lock_ok=0; fi
            lock_ts=$now; was_off=0
        fi
        [ "$lock_ok" = "1" ] || continue

        input keyevent "$act" >/dev/null 2>&1
    done
    # getevent ended: the keyboard node went away (driver reload, uevent remove/add). Find it again.
    sleep 1
done
