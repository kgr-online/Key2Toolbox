#!/system/bin/sh
# Wait for boot to complete, then add a short delay so we run *after*
# /vendor/bin/init.qcom.post_boot.sh (which hardcodes swappiness=100 at
# late_start). Without the delay, the two scripts race on sys.boot_completed
# and post_boot.sh wins roughly half the time.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
sleep 15  # outlast init.qcom.post_boot.sh

# Re-initialise zram with configured size and algorithm.
swapoff /dev/block/zram0 2>/dev/null
echo 1 > /sys/block/zram0/reset
echo __ALGO__ > /sys/block/zram0/comp_algorithm
echo __SIZE_MB__m > /sys/block/zram0/disksize
mkswap /dev/block/zram0
swapon /dev/block/zram0

# VM tuning
echo __SWAPPINESS__  > /proc/sys/vm/swappiness      # less aggressive swap churn
echo 50              > /proc/sys/vm/vfs_cache_pressure  # keep dentries/inodes in cache longer
echo 0               > /proc/sys/vm/page-cluster       # swap 1 page at a time (zram prefers small transfers)
echo 15000           > /proc/sys/vm/min_free_kbytes    # raise reclaim watermark to prevent thrashing
