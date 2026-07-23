#!/usr/bin/env bash
# Manual rollback script to restore telegram-sms default gateway role on Atlas

set -euo pipefail

TARGET_DEVICE="100.96.0.44:5555"
TELEGRAM_SMS_PKG="com.qwe7002.telegram_sms"
NUMI_PKG="com.bgwastu.numi"

echo "=== Numi Gateway Rollback Tool ==="

# 1. Connect to Atlas
echo "Connecting to Atlas ($TARGET_DEVICE)..."
adb connect "$TARGET_DEVICE" || true
sleep 1

# Check device list
if ! adb devices | grep -q "$TARGET_DEVICE"; then
    echo "ERROR: Atlas is not reachable via ADB on $TARGET_DEVICE!"
    echo "Check if ADB over WiFi is running or if the device is offline."
    exit 1
fi

echo "Atlas is connected."

# 2. Backup check
echo "Current default SMS app:"
adb -s "$TARGET_DEVICE" shell "settings get global sms_default_application"

# 3. Trigger role change
echo "Restoring telegram-sms as the default SMS app..."
adb -s "$TARGET_DEVICE" shell "settings put global sms_default_application $TELEGRAM_SMS_PKG"
adb -s "$TARGET_DEVICE" shell "cmd role add-role-holder android.app.role.SMS $TELEGRAM_SMS_PKG"

# 4. Start telegram-sms and stop Numi
echo "Launching telegram-sms..."
adb -s "$TARGET_DEVICE" shell "am start -n $TELEGRAM_SMS_PKG/.MainActivity" || true

echo "Stopping Numi..."
adb -s "$TARGET_DEVICE" shell "am force-stop $NUMI_PKG"

# 5. Verify role
echo "Verification:"
NEW_DEFAULT=$(adb -s "$TARGET_DEVICE" shell "settings get global sms_default_application" | tr -d '\r')
if [ "$NEW_DEFAULT" = "$TELEGRAM_SMS_PKG" ]; then
    echo "SUCCESS: Rollback complete. telegram-sms is now the default SMS app."
else
    echo "WARNING: Rollback failed or returned unexpected state: $NEW_DEFAULT"
fi
