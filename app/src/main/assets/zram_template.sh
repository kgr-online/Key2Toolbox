#!/system/bin/sh
# Wait for boot to complete and wait for the Qualcomm post-boot script to
# finish executing, so we win the race and override the memory parameters.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 2
done
until [ "$(getprop init.svc.qcom-post-boot)" = "stopped" ]; do
    sleep 2
done
sleep 5

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
