#!/system/bin/sh
# DT2W enable - kgr
# Must run while screen is on for gesture mode to take effect on suspend.
#
# The KEY2 ships with different touch-controller chips across production
# units (FocalTech or Synaptics DSX - confirmed via the ROM maintainer's
# athena-luna-panel-check.sh). Detect which is actually live rather than
# hardcoding either.

sleep 5

FOCALTECH_PATH="/sys/class/tp_device/tp_gesture/gesture_enable"

if [ -e "$FOCALTECH_PATH" ]; then
    echo 1 > "$FOCALTECH_PATH"
else
    SYNAPTICS_NODE=$(find /sys/devices/platform/soc -maxdepth 8 \( -name wakeup_gesture -o -name wake_gesture \) 2>/dev/null | head -n1)
    if [ -n "$SYNAPTICS_NODE" ]; then
        echo 1 > "$SYNAPTICS_NODE"
    else
        log -p w -t dt2w "no supported gesture-wake touch controller found"
    fi
fi
