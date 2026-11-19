#!/system/bin/sh
# Gen5WLauncher On-Car Read-Only Recon Script (NEMESIS type=execute)
# Strictly KitKat mksh + toolbox compatible (NO head, tail, sed, awk, tr, cut, basename, busybox).
# Does NOT modify /system or /data; writes only to the USB drive.

export PATH=/data/nemesis/bin:/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib

USB_DIR=""
if [ -f "./NEMESIS.data" ] && [ -w "." ]; then
    USB_DIR="$(pwd)"
fi

if [ -z "$USB_DIR" ]; then
    for d in /storage/usb0 /storage/usb1 /storage/usb2 /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2 /mnt/usb_storage /storage/usbdisk; do
        if [ -d "$d" ] && [ -w "$d" ]; then
            if grep -q " $d " /proc/mounts 2>/dev/null; then
                USB_DIR="$d"
                break
            fi
        fi
    done
fi

if [ -z "$USB_DIR" ]; then
    echo "ERROR: No writable mounted USB drive found."
    exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S 2>/dev/null)"
if [ -z "$STAMP" ]; then
    STAMP="recon"
fi

OUT_DIR="$USB_DIR/gen5w-launcher-recon-$STAMP"
mkdir -p "$OUT_DIR" 2>/dev/null
if [ ! -d "$OUT_DIR" ]; then
    OUT_DIR=""
    for d in /mnt/media_rw/usb0 /mnt/media_rw/usb1 /mnt/media_rw/usb2 /storage/usb0 /storage/usb1 /storage/usb2; do
        if [ -d "$d" ] && [ -w "$d" ]; then
            mkdir -p "$d/gen5w-launcher-recon-$STAMP" 2>/dev/null
            if [ -d "$d/gen5w-launcher-recon-$STAMP" ]; then
                OUT_DIR="$d/gen5w-launcher-recon-$STAMP"
                break
            fi
        fi
    done
fi

if [ -z "$OUT_DIR" ] || [ ! -d "$OUT_DIR" ]; then
    echo "ERROR: Could not create output folder on USB."
    exit 1
fi

# 1. Environment, properties, active mounts, and running processes
{
    echo "=== GEN5W LAUNCHER ON-CAR RECON (READ-ONLY) ==="
    echo "Date:            $(date 2>&1)"
    echo "Identity:        $(id 2>&1)"
    echo "Uptime:          $(uptime 2>&1)"
    echo "USB Out Dir:     $OUT_DIR"
    echo "Boot completed:  $(getprop sys.boot_completed)"
    echo "Product/FW:      $(getprop ro.build.product) | $(getprop ro.build.display.id)"
    echo ""
    echo "=== KEY PROPS (persist.sys.key / launcher / splitscreen / adb / hmgapi) ==="
    getprop 2>&1 | grep -e 'persist.sys.key' -e 'launcher' -e 'splitscreen' -e 'adbd' -e 'usb.config' -e 'hkmc' -e 'nemesis' -e 'hmgapi' -e 'ro.build' -e 'ro.sf.lcd'
    echo ""
    echo "=== FULL GETPROP ==="
    getprop 2>&1
    echo ""
    echo "=== /proc/mounts ==="
    cat /proc/mounts 2>&1
    echo ""
    echo "=== df ==="
    df 2>&1
    echo ""
    echo "=== ps ==="
    ps 2>&1
} > "$OUT_DIR/01_environment.txt"

# 2. NEMESIS + HMG-API installation & active mod/hook state
{
    echo "=== NEMESIS STATE ==="
    if [ -x /system/xbin/nemesis ]; then
        echo "--- nemesis version ---"
        /system/xbin/nemesis version 2>&1
        echo "--- nemesis compat ---"
        /system/xbin/nemesis compat 2>&1
        echo "--- nemesis pkg-list ---"
        /system/xbin/nemesis pkg-list 2>&1
        echo "--- nemesis service list ---"
        /system/xbin/nemesis service list 2>&1
    else
        echo "/system/xbin/nemesis not found"
    fi
    echo ""
    echo "=== ls /data/nemesis ==="
    ls -la /data/nemesis /data/nemesis/apps 2>&1
    echo ""
    echo "=== HMG-API FILES ==="
    ls -la /system/bin/hmgapid /system/bin/app_process* /data/hmgapi /data/hmgapi/state /data/hmgapi/mods /data/hmgapi/hook /data/hmgapi/settings 2>&1
    echo ""
    if [ -x /system/bin/hmgapid ]; then
        echo "--- hmgapid ping ---"
        /system/bin/hmgapid ping 2>&1
        echo "--- hmgapid version ---"
        /system/bin/hmgapid version 2>&1
        echo "--- hmgapid mods ---"
        /system/bin/hmgapid mods 2>&1
        echo "--- hmgapid engine plan ---"
        /system/bin/hmgapid engine plan 2>&1
    else
        echo "/system/bin/hmgapid not present (expected if HMG-API is < 1.0.0)"
    fi
    echo ""
    echo "=== HMG-API NON-SECRET STATE FILES ==="
    for f in \
        /data/hmgapi/hook/starts \
        /data/hmgapi/state/apps.txt \
        /data/hmgapi/state/binds.txt \
        /data/hmgapi/state/services.txt \
        /data/hmgapi/state/vehicle.txt \
        /data/hmgapi/state/journal.json \
        /data/hmgapi/settings/hmgapi.json \
        /data/hmgapi/hmgapi.json; do
        if [ -f "$f" ]; then
            echo "--- $f ---"
            cat "$f" 2>&1
            echo ""
        fi
    done
} > "$OUT_DIR/02_nemesis_hmgapi_state.txt"

# 3. Preferred HOME / package registration files across users
for udir in /data/system/users/*; do
    if [ -d "$udir" ] && [ -r "$udir/package-restrictions.xml" ]; then
        uid="${udir##*/}"
        cp -f "$udir/package-restrictions.xml" "$OUT_DIR/package-restrictions-user-${uid}.xml" 2>/dev/null
    fi
done

for f in \
    /data/system/packages.xml \
    /data/system/packages.list; do
    if [ -r "$f" ]; then
        cp -f "$f" "$OUT_DIR/" 2>/dev/null
    fi
done

{
    echo "=== /data/system/users ==="
    ls -la /data/system/users /data/system/users/* 2>&1
} > "$OUT_DIR/03b_system_users_ls.txt"

# 4. Root dumpsys (packages, HOME resolution, activity/window stacks, status bar)
{
    echo "=== PM LIST PACKAGES -F ==="
    /system/bin/pm list packages -f 2>&1
    echo ""
    echo "=== DUMPSYS ACTIVITY ACTIVITIES ==="
    /system/bin/dumpsys activity activities 2>&1
    echo ""
    echo "=== DUMPSYS ACTIVITY RECENTS ==="
    /system/bin/dumpsys activity recents 2>&1
    echo ""
    echo "=== DUMPSYS WINDOW WINDOWS ==="
    /system/bin/dumpsys window windows 2>&1
    echo ""
    echo "=== DUMPSYS WINDOW POLICY ==="
    /system/bin/dumpsys window policy 2>&1
    echo ""
    echo "=== DUMPSYS STATUSBAR ==="
    /system/bin/dumpsys statusbar 2>&1
} > "$OUT_DIR/03_dumpsys_activity_window.txt"

{
    for pkg in \
        com.android.launcher3 \
        com.mobis.splitscreen.right \
        com.android.systemui \
        hmgapi.app \
        com.deflight.carplaykeys \
        paytondev.launchpad \
        com.gen5w.themer; do
        echo "=== DUMPSYS PACKAGE $pkg ==="
        /system/bin/dumpsys package "$pkg" 2>&1
        echo ""
    done
} > "$OUT_DIR/04_dumpsys_packages.txt"

# 5. Copy non-personal HMG-API / NEMESIS diagnostic logs (if present)
mkdir -p "$OUT_DIR/logs" 2>/dev/null
for lf in \
    /data/nemesis/nemesis.log \
    /data/hmgapi/install.log \
    /data/hmgapi/boot.log \
    /data/hmgapi/binds.log \
    /data/hmgapi/recover.log \
    /data/hmgapi/nemesis-sync.log \
    /data/hmgapi/hmgapid.log; do
    if [ -r "$lf" ]; then
        cp -f "$lf" "$OUT_DIR/logs/" 2>/dev/null
    fi
done

chmod 777 "$OUT_DIR" "$OUT_DIR/logs" 2>/dev/null
chmod 666 "$OUT_DIR/"* "$OUT_DIR/logs/"* 2>/dev/null
sync

# Verify key capture succeeded before returning 0 to NEMESIS usb-handler
if [ -s "$OUT_DIR/01_environment.txt" ] && [ -s "$OUT_DIR/03_dumpsys_activity_window.txt" ]; then
    echo "Recon complete -> $OUT_DIR"
    exit 0
fi

echo "ERROR: Recon output missing expected files in $OUT_DIR"
exit 1
