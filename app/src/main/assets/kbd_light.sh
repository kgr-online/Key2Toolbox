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

while true; do
    # `read` is a builtin: no fork per tick (the old $(cat ...) forked every 0.3-0.5 s while awake)
    LCD=""
    read -r LCD < /sys/class/leds/lcd-backlight/brightness
    if [ -z "$LCD" ]; then sleep 0.5; continue; fi

    if [ "$LCD" -eq 0 ]; then
        if [ "$LAST_VAL" -ne 0 ]; then
            echo 0 > /sys/class/leds/keyboard-backlight_1/brightness
            echo 0 > /sys/class/leds/keyboard-backlight_2/brightness
            echo 0 > /sys/class/leds/keyboard-backlight_3/brightness
            LAST_VAL=0
        fi
        SCREEN_ON=0
        sleep 0.5
        continue
    fi

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
