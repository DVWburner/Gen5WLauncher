#!/system/bin/sh
export PATH=/sbin:/vendor/bin:/system/sbin:/system/bin:/system/xbin
export LD_LIBRARY_PATH=/vendor/lib:/system/lib
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 2
done
APP_DIR="/data/nemesis/apps/gen5w.launcher"
DATA_DIR="/data/gen5w_launcher/run/home-switcher"
mkdir -p "$DATA_DIR" 2>/dev/null || true
export GEN5W_APP_DIR="$APP_DIR"
export GEN5W_DATA_DIR="$DATA_DIR"
export CLASSPATH="$APP_DIR/svc/home-switcher.jar"
exec /system/bin/app_process /system/bin com.gen5w.launcher.svc.HomeSwitcher
