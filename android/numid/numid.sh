#!/system/bin/sh
# KernelSU boot script for numid daemon

LOG_FILE="/data/local/tmp/numid.log"
echo "=== numid boot script started: $(date) ===" > "$LOG_FILE"

# Wait for boot to complete and system package manager to be ready
until [ "$(getprop sys.boot_completed)" = "1" ]; do
    sleep 3
done

echo "Boot completed. Starting numid daemon..." >> "$LOG_FILE"

# Locate python binary (check Termux path first)
PYTHON_BIN="/data/data/com.termux/files/usr/bin/python"
if [ ! -x "$PYTHON_BIN" ]; then
    # Fallbacks
    for path in /data/data/com.termux/files/usr/bin/python3 /system/bin/python /system/bin/python3; do
        if [ -x "$path" ]; then
            PYTHON_BIN="$path"
            break
        fi
    done
fi

echo "Using Python binary: $PYTHON_BIN" >> "$LOG_FILE"

DAEMON_SCRIPT="/data/local/tmp/ipc_server.py"

# Copy daemon script to /data/local/tmp
cp -f /data/local/tmp/numi/android/numid/ipc_server.py "$DAEMON_SCRIPT" || cp -f /data/user/0/com.bgwastu.numi/files/ipc_server.py "$DAEMON_SCRIPT" || true
chmod 755 "$DAEMON_SCRIPT"

if [ -x "$PYTHON_BIN" ]; then
    # Execute daemon in background
    nohup "$PYTHON_BIN" "$DAEMON_SCRIPT" >> "$LOG_FILE" 2>&1 &
    echo "numid daemon launched in background with PID $!" >> "$LOG_FILE"
else
    echo "ERROR: Python binary not found! numid daemon cannot start." >> "$LOG_FILE"
fi
