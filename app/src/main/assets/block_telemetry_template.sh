#!/system/bin/sh
# Block Telemetry & Crashlytics - Key2 Toolbox
#
# Wait for boot to complete, then add a short delay so we run after apps initialize
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 5
done
sleep 15

# Scan all apps and disable Firebase Crashlytics collection
find /data/data/ -name "com.google.firebase.crashlytics.xml" 2>/dev/null | while read f; do
    if [ ! -f "$f" ]; then continue; fi
    if grep -q "firebase_crashlytics_collection_enabled" "$f"; then
        sed -i 's/firebase_crashlytics_collection_enabled" value="true"/firebase_crashlytics_collection_enabled" value="false"/g' "$f"
    else
        sed -i 's#</map>#    <boolean name="firebase_crashlytics_collection_enabled" value="false" />\n</map>#g' "$f"
    fi
done
