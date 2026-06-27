#!/system/bin/sh
# Force WiFi regulatory domain to US - Key2 Toolbox
#
# This build exposes no 5GHz SoftAP channels for any EU regdomain; only US has
# them. The country override resets on reboot, so re-apply it once the WiFi
# service is up. We wait for boot completion + 15 seconds to outlast the system's
# initial baseband carrier country code initialization, then apply the override.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 15

i=0
while [ "$i" -lt 60 ]; do
    if cmd wifi status >/dev/null 2>&1; then
        cmd wifi force-country-code enabled US && break
    fi
    sleep 2
    i=$((i + 1))
done
