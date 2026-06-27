#!/system/bin/sh
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 5

settings put global adb_wifi_enabled 1
setprop persist.adb.tcp.port __PORT__
setprop service.adb.tcp.port __PORT__
