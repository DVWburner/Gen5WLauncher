#!/system/bin/sh
# Standalone NEMESIS preuninstall: on true uninstall (not in-place update), restores Stock Home
# cleanly before pm uninstall removes com.gen5w.launcher.
umask 022
STAGE=${ROOT:-}/data/local/tmp/nemesis/stage/gen5w.launcher
caller=$(cat /proc/$PPID/cmdline 2>/dev/null)
case $caller in
    *uninstall*) ;;
    *)
        if grep -q '^id=gen5w\.launcher$' "$STAGE/NEMESIS.data" 2>/dev/null; then
            echo "update: keeping preferred Home state across upgrade"
            exit 0
        fi ;;
esac

if [ -z "${ROOT:-}" ]; then
    export GEN5W_DATA_DIR=/data/gen5w_launcher/run/home-switcher
    export CLASSPATH=/data/nemesis/apps/gen5w.launcher/svc/home-switcher.jar
    /system/bin/app_process /system/bin com.gen5w.launcher.svc.HomeSwitcher --restore-stock >/dev/null 2>&1 || true
fi
exit 0
