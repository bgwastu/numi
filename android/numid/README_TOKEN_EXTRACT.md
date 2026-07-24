# GetContact token extraction (v8.15+)

## What changed

`tokens.preferences_pb` is no longer a plaintext Preferences protobuf. It is a
**CryptoSerializer** blob:

- File contents: Base64(`IV[16] || AES-CBC ciphertext`)
- Keystore alias: `crypto_client` (hardware-backed, app-UID bound)
- Plaintext JSON: `{"app":"<token>","chat":"...","push":"...","isVerified":true,"isPushSynced":true}`
- API header `X-Token` = the `app` field

## On-device (GCExtractor / Numi)

Requires root + frida-server + frida-inject:

```bash
# Once per device (match frida-server version, e.g. 17.15.4):
adb push frida-inject-*-android-arm64 /data/local/tmp/frida-inject
adb shell su -c 'chmod 755 /data/local/tmp/frida-inject'
```

Numi deploys `gc_dump_token.bundle.js` + `gc_extract_token.sh` from assets to
`/data/local/tmp/` and runs the shell script via `su`.

Rebuild the Frida agent after editing `gc_dump_token.ts`:

```bash
cd android/numid
npm init -y
npm i frida-java-bridge frida-compile
npx frida-compile gc_dump_token.ts -o gc_dump_token.bundle.js
cp gc_dump_token.bundle.js ../app/src/main/assets/gcextract/
```

## Host harvester

```bash
python3 extract_gc_creds.py
# or: ADB_SERIAL=85b81bce python3 extract_gc_creds.py
```
