#!/system/bin/sh
# Fallback mount for the compiled hosts file.
#
# Normally a root manager overlays <module>/system/etc/hosts onto /system/etc/hosts itself
# (Magisk, APatch, KernelSU with a metamodule). KernelSU Next without a metamodule mounts nothing
# from a module's system/ tree, so the file is built but never shows up. This runs late in boot
# and, only if the overlay is missing, bind-mounts the module's hosts file over the live path.
MODDIR=${0%/*}
HOSTS="$MODDIR/system/etc/hosts"
LIVE=/system/etc/hosts

# Already overlaid by the root manager: nothing to do.
grep -q 'Systemless Hosts by the' "$LIVE" 2>/dev/null && exit 0
# Module disabled/removed, or nothing compiled yet.
[ -f "$MODDIR/disable" ] && exit 0
[ -f "$MODDIR/remove" ] && exit 0
[ -f "$HOSTS" ] || exit 0

# netd resolves names through this file; it can only read it with the label system files have.
chcon u:object_r:system_file:s0 "$HOSTS" 2>/dev/null
chmod 644 "$HOSTS" 2>/dev/null
mount --bind "$HOSTS" "$LIVE"
