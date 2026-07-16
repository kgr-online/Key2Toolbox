#!/system/bin/sh

sleep 20

LAST_VAL=0
SCREEN_ON=1

fade_up_fast() {
    TARGET=$1
    CURRENT=$LAST_VAL
    while [ "$CURRENT" -lt "$TARGET" ]; do
        CURRENT=$((CURRENT + 30))
        [ "$CURRENT" -gt "$TARGET" ] && CURRENT=$TARGET
        echo $CURRENT > /sys/class/leds/keyboard-backlight_1/brightness
        echo $CURRENT > /sys/class/leds/keyboard-backlight_2/brightness
        echo $CURRENT > /sys/class/leds/keyboard-backlight_3/brightness
        sleep 0.01
    done
    LAST_VAL=$TARGET
}

fade_up_slow() {
    TARGET=$1
    CURRENT=$LAST_VAL
    while [ "$CURRENT" -lt "$TARGET" ]; do
        CURRENT=$((CURRENT + 10))
        [ "$CURRENT" -gt "$TARGET" ] && CURRENT=$TARGET
        echo $CURRENT > /sys/class/leds/keyboard-backlight_1/brightness
        echo $CURRENT > /sys/class/leds/keyboard-backlight_2/brightness
        echo $CURRENT > /sys/class/leds/keyboard-backlight_3/brightness
        sleep 0.03
    done
    LAST_VAL=$TARGET
}

fade_down() {
    TARGET=$1
    CURRENT=$LAST_VAL
    while [ "$CURRENT" -gt "$TARGET" ]; do
        CURRENT=$((CURRENT - 10))
        [ "$CURRENT" -lt "$TARGET" ] && CURRENT=$TARGET
        echo $CURRENT > /sys/class/leds/keyboard-backlight_1/brightness
        echo $CURRENT > /sys/class/leds/keyboard-backlight_2/brightness
        echo $CURRENT > /sys/class/leds/keyboard-backlight_3/brightness
        sleep 0.03
    done
    LAST_VAL=$TARGET
}

OFF_SLEEP=0.5

while true; do
    LCD=$(cat /sys/class/leds/lcd-backlight/brightness)

    if [ "$LCD" -eq 0 ]; then
        if [ "$LAST_VAL" -ne 0 ]; then
            echo 0 > /sys/class/leds/keyboard-backlight_1/brightness
            echo 0 > /sys/class/leds/keyboard-backlight_2/brightness
            echo 0 > /sys/class/leds/keyboard-backlight_3/brightness
            LAST_VAL=0
        fi
        SCREEN_ON=0
        sleep "$OFF_SLEEP"
        # Back off the longer the screen stays off, so a sleeping phone isn't
        # woken every 0.5s all night just to find nothing changed - ramps up
        # to a 5s cap within a few cycles of screen-off.
        case "$OFF_SLEEP" in
            0.5) OFF_SLEEP=1 ;;
            1) OFF_SLEEP=2 ;;
            2) OFF_SLEEP=5 ;;
        esac
        continue
    fi
    OFF_SLEEP=0.5

    if [ "$SCREEN_ON" -eq 0 ]; then
        WAKE=1
        SCREEN_ON=1
    else
        WAKE=0
    fi

    if [ "$LCD" -le 3 ]; then
        TARGET=120
    elif [ "$LCD" -lt 45 ]; then
        TARGET=255
    elif [ "$LCD" -ge 50 ]; then
        TARGET=0
    else
        TARGET=$LAST_VAL
    fi

    if [ "$TARGET" -gt "$LAST_VAL" ]; then
        if [ "$WAKE" -eq 1 ]; then
            fade_up_slow $TARGET
        else
            fade_up_fast $TARGET
        fi
    elif [ "$TARGET" -lt "$LAST_VAL" ]; then
        fade_down $TARGET
    fi

    sleep 0.3
done
