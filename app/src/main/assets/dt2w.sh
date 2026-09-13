#!/system/bin/sh
# DT2W enable - kgr
# Must run while screen is on for gesture mode to take effect on suspend.
#
# The gesture-wake sysfs attribute's I2C bus/address and attribute name
# are not stable across kernel builds - confirmed changed between the
# 4.4-kernel ROM (i2c-4/4-0070, "wake_gesture") and the LOS 22.2 /
# 4.19-kernel ROM (i2c-1/1-0020, "wakeup_gesture"). Resolve it at boot
# instead of hardcoding either.

sleep 5

NODE=$(find /sys/devices/platform/soc -maxdepth 8 \( -name wakeup_gesture -o -name wake_gesture \) 2>/dev/null | head -n1)

if [ -n "$NODE" ]; then
    echo 1 > "$NODE"
else
    log -p w -t dt2w "gesture-wake attribute not found under /sys/devices/platform/soc"
fi
