#!/system/bin/sh
# Keep paired watch dormant - Key2 Toolbox
#
# Re-disable the Play Services wearable connection at boot so an out-of-range
# watch doesn't drain the battery with constant Bluetooth reconnect attempts.
# GMS can re-enable the node on a cold boot, so retry until the DB is present
# and the write lands (the data partition / GMS may not be ready immediately).
DB=/data/data/com.google.android.gms/databases/connectionconfig.db
i=0
while [ "$i" -lt 30 ]; do
    if [ -f "$DB" ]; then
        sqlite3 "$DB" "UPDATE connectionConfigurations SET connectionEnabled=0;" && break
    fi
    sleep 2
    i=$((i + 1))
done
