#!/system/bin/sh
# Extract GetContact app token + AES sharedKey from encrypted DataStore via frida-inject.
# Prerequisites:
#   /data/local/tmp/frida-inject          (frida-inject android-arm64, same version as frida-server)
#   /data/local/tmp/gc_dump_token.bundle.js
#   frida-server running

PKG=app.source.getcontact
INJECT=/data/local/tmp/frida-inject
SCRIPT=/data/local/tmp/gc_dump_token.bundle.js
OUT=/data/data/$PKG/cache/gc_app_token.txt
KEYS_OUT=/data/data/$PKG/cache/gc_crypto_keys.txt

if [ ! -x "$INJECT" ]; then
  echo "ERROR:NO_FRIDA_INJECT" >&2
  exit 10
fi
if [ ! -f "$SCRIPT" ]; then
  echo "ERROR:NO_SCRIPT" >&2
  exit 11
fi

if ! pgrep -f frida-server >/dev/null 2>&1; then
  if [ -x /data/adb/modules/magisk-frida/bin/frida-server ]; then
    /data/adb/modules/magisk-frida/bin/frida-server -D >/dev/null 2>&1 || true
    sleep 1
  fi
fi

PID=$(pidof "$PKG" | awk '{print $1}')
if [ -z "$PID" ]; then
  monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true
  sleep 3
  PID=$(pidof "$PKG" | awk '{print $1}')
fi
if [ -z "$PID" ]; then
  echo "ERROR:NO_PID" >&2
  exit 12
fi

rm -f "$OUT" "$KEYS_OUT"
# -e eternalize: inject script and detach (don't wait for target process to exit)
"$INJECT" -p "$PID" -s "$SCRIPT" -e >/data/local/tmp/gc_inject.log 2>&1 &
INJECT_PID=$!

i=0
while [ $i -lt 80 ]; do
  if [ -s "$OUT" ] && [ -s "$KEYS_OUT" ]; then
    kill "$INJECT_PID" 2>/dev/null || true
    wait "$INJECT_PID" 2>/dev/null || true
    TOKEN=$(tr -d '\r\n' < "$OUT")
    case "$TOKEN" in
      ERROR:*)
        echo "$TOKEN" >&2
        exit 13
        ;;
      *)
        echo "$TOKEN"
        exit 0
        ;;
    esac
  fi
  if ! kill -0 "$INJECT_PID" 2>/dev/null; then
    wait "$INJECT_PID" 2>/dev/null || true
    if [ -s "$OUT" ]; then
      tr -d '\r\n' < "$OUT"
      exit 0
    fi
    echo "ERROR:INJECT_EXITED" >&2
    cat /data/local/tmp/gc_inject.log >&2 || true
    exit 15
  fi
  i=$((i + 1))
  sleep 0.1
done

kill "$INJECT_PID" 2>/dev/null || true
wait "$INJECT_PID" 2>/dev/null || true
# Token alone is still useful
if [ -s "$OUT" ]; then
  TOKEN=$(tr -d '\r\n' < "$OUT")
  case "$TOKEN" in
    ERROR:*) echo "$TOKEN" >&2; exit 13 ;;
    *) echo "$TOKEN"; exit 0 ;;
  esac
fi
echo "ERROR:TIMEOUT" >&2
cat /data/local/tmp/gc_inject.log >&2 || true
exit 14
