#!/system/bin/sh
# Key2 Toolbox - Ctrl key remap (crash-safe).
#
# Managed by the app's Ctrl Key screen. Replaces the old in-place `sed -i` on
# the vendor keylayout, which a reset once caught mid-write and turned into
# garbage, killing the physical keyboard until it was rebuilt by hand.
#
# This runs at every boot and:
#   * self-heals the live keylayout from a known-good copy if it is unusable,
#   * (re)applies the remap through a temp file + validation + atomic rename,
#     never in place,
#   * leaves the live file untouched on any failure,
#   * remounts /vendor read-only again when done.
#
#   __SRC__     scancode that becomes CTRL_LEFT
#   __STOCK__   what that scancode maps to before the remap
#   __KL__      the keylayout the keyboard resolves to
#   __GOLDEN__  pristine, un-remapped copy of __KL__
#
# No `setenforce 0`: verified on-device that the APatch su domain can remount
# /vendor rw, write the keylayout and relabel it with SELinux enforcing.

nsenter -t 1 -m -- sh -c '
  KL=__KL__
  GOLDEN=__GOLDEN__
  SRC=__SRC__
  STOCK=__STOCK__
  SECTX=u:object_r:vendor_keylayout_file:s0

  sane() { [ -s "$1" ] && grep -q "^key 16[[:space:]]" "$1" && grep -q "^key 30[[:space:]]" "$1"; }

  mount -o rw,remount /vendor 2>/dev/null

  # Heal a corrupt live keylayout from the pristine copy first.
  if ! sane "$KL" && sane "$GOLDEN"; then
    cp "$GOLDEN" "$KL" && chmod 644 "$KL" && chcon "$SECTX" "$KL" 2>/dev/null
  fi

  # Then (re)apply the remap on whatever is now there, via temp + validate + swap.
  if sane "$KL"; then
    TMP="$KL.k2boot.$$"
    if sed -E "s/^key ${SRC}[[:space:]]+${STOCK}\$/key ${SRC} CTRL_LEFT/" "$KL" > "$TMP" 2>/dev/null \
       && sane "$TMP" && grep -q "^key ${SRC}[[:space:]]\{1,\}CTRL_LEFT" "$TMP"; then
      chmod 644 "$TMP"; chcon "$SECTX" "$TMP" 2>/dev/null; mv -f "$TMP" "$KL"
    else
      rm -f "$TMP"
    fi
  fi

  sync
  mount -o ro,remount /vendor 2>/dev/null
'
