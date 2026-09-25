#!/system/bin/sh
# K2TB - Call Proximity Force-Sleep watchdog
# Installed via CallProximitySleepController -> /data/adb/service.d/call_proximity_sleep.sh
#
# On this device/ROM, PowerManager's own PROXIMITY_SCREEN_OFF_WAKE_LOCK path
# is broken: the dialer correctly ACQ/REL's InCallProximitySensorWakeLock
# (confirmed via `dumpsys power` wake-lock log around real calls) and
# mProximityPositive flips correctly - but the screen never actually blanks;
# mWakefulness stays Awake for the whole call. This bypasses PowerManager's
# broken handling by issuing KEYCODE_SLEEP directly via root once "near" has
# held for a short debounce window.
#
# NOT YET VERIFIED ON-DEVICE: the mCallState grep below. mProximityPositive
# was confirmed live in this device's `dumpsys power` output; mCallState via
# `dumpsys telephony.registry` has not. Capture both locked/unlocked and
# idle/ringing/offhook dumps before trusting this in production - the key
# name or value scheme may differ on this LineageOS build.

POLL_INTERVAL="__POLL_INTERVAL_SEC__"           # seconds between checks with no call active
CALL_POLL_INTERVAL="__CALL_POLL_INTERVAL_SEC__" # seconds between proximity checks during a call
DEBOUNCE_TICKS=__DEBOUNCE_TICKS__               # consecutive "near" reads required before forcing sleep

is_in_call() {
    # mCallState: 0=idle, 1=ringing, 2=offhook. UNVERIFIED - confirm this key
    # name/format against `dumpsys telephony.registry` on-device.
    state=$(dumpsys telephony.registry 2>/dev/null | grep -o 'mCallState=[0-9]*' | head -n1 | cut -d= -f2)
    [ "$state" = "2" ]
}

is_proximity_near() {
    # Confirmed live on-device: `dumpsys power | grep mProximityPositive`.
    dumpsys power 2>/dev/null | grep -q 'mProximityPositive=true'
}

near_ticks=0
forced_asleep=0

while true; do
    if is_in_call; then
        if is_proximity_near; then
            near_ticks=$((near_ticks + 1))
            if [ "$near_ticks" -ge "$DEBOUNCE_TICKS" ] && [ "$forced_asleep" = "0" ]; then
                input keyevent 223  # KEYCODE_SLEEP - forces sleep, unlike POWER
                forced_asleep=1
            fi
        else
            near_ticks=0
            if [ "$forced_asleep" = "1" ]; then
                # Proximity cleared while we'd forced sleep - PowerManager's
                # own proximity-driven wake is equally broken on this ROM as
                # its sleep side was, so this has to be done explicitly too.
                # KEYCODE_WAKEUP (224), not POWER (26) - WAKEUP only wakes,
                # never toggles back off if something else already woke it.
                input keyevent 224
                forced_asleep=0
            fi
        fi
        sleep "$CALL_POLL_INTERVAL"
    else
        # Call ended without ever going "far" again (e.g. the other party
        # hung up while still held to the ear) - don't leave the screen
        # stuck asleep waiting for a proximity transition that will never
        # come now that the call is over.
        if [ "$forced_asleep" = "1" ]; then
            input keyevent 224
            forced_asleep=0
        fi
        near_ticks=0
        sleep "$POLL_INTERVAL"
    fi
done
