#!/system/bin/sh
setenforce 0
nsenter -t 1 -m -- mount -o rw,remount /vendor
nsenter -t 1 -m -- sed -E -i 's/key __SCANCODE__[[:space:]]+__ORIGINAL_KEYCODE__/key __SCANCODE__ CTRL_LEFT/' /vendor/usr/keylayout/stmpe.kl
setenforce 1
