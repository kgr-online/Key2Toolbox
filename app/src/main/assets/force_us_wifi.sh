#!/system/bin/sh
# Force WiFi regulatory domain to US - Key2 Toolbox
#
# This build exposes no 5GHz SoftAP channels for any EU regdomain; only US has
# them. The country override resets on reboot, AND the telephony stack keeps
# re-asserting the SIM's country for a minute or two after boot (and again on
# every airplane-mode toggle / SIM re-registration), so applying it once is not
# enough - the earlier version broke out of the loop on the first success and
# lost the override again as soon as the SIM registered.
#
# Strategy: wait for the WiFi service, re-apply every 3s for ~4 minutes to win
# the race against the post-boot country detection, then keep a slow watchdog
# that re-applies only when the live country has drifted off US.
#
# This script is its own on/off switch: the app removes it from service.d when
# the feature is toggled off, and every loop below bails out once it's gone, so
# a running instance stops within a minute of the toggle.

SELF="/data/adb/service.d/force_us_wifi.sh"

apply()   { cmd wifi force-country-code enabled US >/dev/null 2>&1; }
current() { cmd wifi get-country-code 2>/dev/null | sed 's/.*=//' | tr -d '[:space:]'; }

# 1) wait for the wifi service to come up (up to ~2 min)
i=0
while [ "$i" -lt 60 ]; do
    [ -f "$SELF" ] || exit 0
    cmd wifi status >/dev/null 2>&1 && break
    sleep 2
    i=$((i + 1))
done

# 2) aggressive phase: hammer it for ~4 minutes to beat post-boot telephony
i=0
while [ "$i" -lt 80 ]; do
    [ -f "$SELF" ] || exit 0
    apply
    sleep 3
    i=$((i + 1))
done

# 3) watchdog: cheap check every 60s, re-apply only if it has drifted
while [ -f "$SELF" ]; do
    [ "$(current)" = "US" ] || apply
    sleep 60
done
