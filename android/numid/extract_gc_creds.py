#!/usr/bin/env python3
"""
numi-gc-harvester: Automated GetContact credential extraction for Numi.

Extracts all four credentials needed for GetContact API lookups:
  1. AES-256 key    - from Frida hooking (runtime capture)
  2. HMAC-SHA256 key - from Frida hooking (runtime capture)
  3. Device token    - from GetContact's DataStore (tokens.preferences_pb)
  4. Device ID       - from GetContact's SharedPreferences

Usage:
  python3 extract_gc_creds.py                    # Full extraction with Frida
  python3 extract_gc_creds.py --no-frida          # Skip Frida, use cached keys if available
  python3 extract_gc_creds.py --deploy-only       # Deploy existing creds to Numi (no extraction)
"""

import os
import sys
import json
import time
import re
import subprocess
import tempfile
from pathlib import Path

# ── Configuration ────────────────────────────────────────────────────
GC_PACKAGE = "app.source.getcontact"
NUMI_PACKAGE = "com.bgwastu.numi"
DEVICE_SERIAL = "100.96.0.44:5555"
HARVESTER_JS = os.path.join(os.path.dirname(__file__), "gc_harvester.js")
CREDS_OUTPUT = "/tmp/getcontact_creds.json"

# Frida connects via ADB forwarded port, not USB
FRIDA_TARGET = "localhost:27042"

ADB = ["adb", "-s", DEVICE_SERIAL]

# ── ADB Helpers ──────────────────────────────────────────────────────

def adb_shell(cmd, su=False):
    """Run a command on the device via ADB, optionally as root."""
    full_cmd = ADB + ["shell"]
    if su:
        full_cmd += ["su", "-c", cmd]
    else:
        full_cmd += [cmd]
    r = subprocess.run(full_cmd, capture_output=True, text=True, timeout=30)
    return r.stdout.strip(), r.returncode


def adb_push(local, remote):
    subprocess.run(ADB + ["push", local, remote], check=True, timeout=15)


def adb_pull(remote, local):
    subprocess.run(ADB + ["pull", remote, local], check=True, timeout=15)


# ── Extraction Functions ─────────────────────────────────────────────

def get_pid():
    """Get PID of GetContact process."""
    out, _ = adb_shell(f"pgrep -f {GC_PACKAGE}")
    if out:
        pid = out.split()[0]
        if pid.isdigit():
            return int(pid)
    return None


def extract_device_id():
    """Extract Device ID from GetContact's SharedPreferences."""
    print("[*] Extracting Device ID from SharedPreferences...")
    out, rc = adb_shell(
        f"cat /data/data/{GC_PACKAGE}/shared_prefs/GetContactSettingsPref.xml",
        su=True
    )
    if rc != 0 or not out:
        print("  [!] Could not read GetContactSettingsPref.xml")
        return None

    match = re.search(
        r'<string\s+name="PREF_KEY_FOR_UNIQ_ID">([^<]+)</string>',
        out
    )
    if match:
        device_id = match.group(1).strip()
        print(f"  [+] Device ID: {device_id}")
        return device_id

    print("  [!] PREF_KEY_FOR_UNIQ_ID not found in prefs")
    return None


def extract_token():
    """Extract API token from GetContact's DataStore."""
    print("[*] Extracting Token from DataStore...")
    out, rc = adb_shell(
        f"strings /data/data/{GC_PACKAGE}/files/datastore/tokens.preferences_pb 2>/dev/null",
        su=True
    )
    if rc != 0 or not out:
        print("  [!] Could not read tokens.preferences_pb")
        return None

    # Look for the token pattern — a long base64-ish string
    # Tokens in DataStore appear as plain strings between protobuf markers
    lines = out.strip().split('\n')
    for line in lines:
        line = line.strip()
        # Filter for plausible token strings: 30-80 chars, mostly alphanumeric
        if 30 <= len(line) <= 80 and not re.search(r'[^a-zA-Z0-9+/=]', line):
            # Likely a token
            print(f"  [+] Token found: {line[:16]}...{line[-8:]}")
            return line

    print("  [!] Token not found in DataStore strings")
    return None


def run_frida_harvester(timeout=25):
    """Run Frida to capture AES and HMAC keys from GetContact runtime."""
    print("[*] Running Frida key harvester...")

    pid = get_pid()
    if pid is None:
        print("  [!] GetContact is not running. Launching...")
        adb_shell(f"monkey -p {GC_PACKAGE} -c android.intent.category.LAUNCHER 1")
        time.sleep(4)
        pid = get_pid()
        if pid is None:
            print("  [!] Failed to launch GetContact")
            return None, None

    print(f"  [+] Attaching Frida to PID {pid}...")
    harvester_path = HARVESTER_JS
    if not os.path.exists(harvester_path):
        # Try default path
        harvester_path = os.path.join(
            os.path.dirname(__file__), "gc_harvester.js"
        )
        if not os.path.exists(harvester_path):
            print("  [!] gc_harvester.js not found")
            return None, None, None, None

    frida_cmd = [
        "frida", "-H", FRIDA_TARGET,
        "-p", str(pid),
        "-l", harvester_path, "--no-pause"
    ]

    proc = subprocess.Popen(
        frida_cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True
    )

    aes_key = None
    hmac_key = None
    device_token = None
    device_id = None
    start = time.time()

    if proc.stdout is None:
        print("  [!] Frida stdout not available")
        proc.terminate()
        return None, None, None, None

    try:
        while time.time() - start < timeout:
            line = proc.stdout.readline()
            if not line:
                break
            line = line.strip()
            if line:
                print(f"    [Frida] {line[:120]}")

            aes_m = re.search(
                r"TYPE:\s*AES\s*\|.*?KEY:\s*([0-9a-fA-F]{64})",
                line
            )
            hmac_m = re.search(
                r"TYPE:\s*(?:HMAC|HMAC_ACTIVE)\s*\|.*?KEY:\s*([0-9a-fA-F]{128})",
                line
            )
            token_m = re.search(
                r"TYPE:\s*TOKEN\s*\|.*?VALUE:\s*(\S+)",
                line
            )
            devid_m = re.search(
                r"TYPE:\s*DEVICE_ID\s*\|.*?VALUE:\s*(\S+)",
                line
            )

            if aes_m and not aes_key:
                aes_key = aes_m.group(1)
                print(f"  [+] AES key captured: {aes_key[:16]}...")
            if hmac_m and not hmac_key:
                hmac_key = hmac_m.group(1)
                print(f"  [+] HMAC key captured: {hmac_key[:16]}...")
            if token_m and not device_token:
                device_token = token_m.group(1)
                print(f"  [+] Token captured: {device_token[:16]}...")
            if devid_m and not device_id:
                device_id = devid_m.group(1)
                print(f"  [+] Device ID captured: {device_id}")

            if aes_key and hmac_key and device_token and device_id:
                print("  [+] All four credentials captured.")
                break
    finally:
        proc.terminate()
        proc.wait()

    return aes_key, hmac_key, device_token, device_id


def save_credentials(aes_key, hmac_key, token, device_id):
    """Save credentials to a JSON file for Numi to consume."""
    creds = {
        "aes_key": aes_key or "",
        "hmac_key": hmac_key or "",
        "device_token": token or "",
        "device_id": device_id or "",
        "updated_at": int(time.time() * 1000)
    }
    with open(CREDS_OUTPUT, "w") as f:
        json.dump(creds, f, indent=2)
    print(f"\n[+] Credentials saved to {CREDS_OUTPUT}")
    return creds


def deploy_to_numi(creds):
    """Deploy credentials to Numi app data directory on Atlas."""
    print("[*] Deploying credentials to Numi...")

    # Push to temp location
    adb_push(CREDS_OUTPUT, "/data/local/tmp/getcontact_creds.json")

    # Get Numi UID
    out, rc = adb_shell(
        f"stat -c %u:%g /data/user/0/{NUMI_PACKAGE}",
        su=True
    )
    if rc != 0 or not out:
        print("  [!] Numi app data directory not found. Install APK first.")
        return False

    uid_gid = out.strip()

    # Create providers dir and deploy
    target_dir = f"/data/user/0/{NUMI_PACKAGE}/files/providers"
    adb_shell(
        f"mkdir -p {target_dir} && "
        f"cp /data/local/tmp/getcontact_creds.json {target_dir}/getcontact.json && "
        f"chmod 600 {target_dir}/getcontact.json && "
        f"chown -R {uid_gid} {target_dir}",
        su=True
    )
    print(f"  [+] Deployed to {target_dir}/getcontact.json (owner: {uid_gid})")
    return True


def main():
    no_frida = "--no-frida" in sys.argv
    deploy_only = "--deploy-only" in sys.argv

    if deploy_only:
        if not os.path.exists(CREDS_OUTPUT):
            print(f"[!] No credentials file at {CREDS_OUTPUT}")
            sys.exit(1)
        with open(CREDS_OUTPUT) as f:
            creds = json.load(f)
        deploy_to_numi(creds)
        return

    print("=" * 60)
    print("  Numi GetContact Credential Harvester")
    print("=" * 60)

    # 1. Extract Device ID from SharedPreferences
    device_id = extract_device_id()
    if not device_id:
        print("[!] Critical: Device ID is required")
        sys.exit(1)

    # 2. Extract Token from DataStore
    token = extract_token()
    if not token:
        print("[!] Critical: Token is required")
        sys.exit(1)

    # 3. Extract AES + HMAC keys + Token + Device ID via Frida
    aes_key, hmac_key, frida_token, frida_devid = None, None, None, None
    if not no_frida:
        aes_key, hmac_key, frida_token, frida_devid = run_frida_harvester()
        if not aes_key or not hmac_key:
            print("[!] Frida key extraction incomplete.")
    else:
        print("[*] Skipping Frida (--no-frida)")

    # 4. Use Frida result if available, otherwise fall back to static extraction
    #    (token and device ID from static files are fallback only)
    final_token = frida_token or token or ""
    final_devid = frida_devid or device_id or ""

    # 5. Save credentials
    creds = save_credentials(aes_key, hmac_key, final_token, final_devid)

    # 5. Deploy to Numi
    deploy_to_numi(creds)

    # Summary
    print("\n" + "=" * 60)
    print("  Extraction Complete")
    print("=" * 60)
    fields = [
        ("AES Key", creds["aes_key"][:16] + "..." if creds["aes_key"] else "MISSING"),
        ("HMAC Key", creds["hmac_key"][:16] + "..." if creds["hmac_key"] else "MISSING"),
        ("Token", creds["device_token"][:16] + "..." if creds["device_token"] else "MISSING"),
        ("Device ID", creds["device_id"]),
    ]
    for name, val in fields:
        status = "✅" if "..." in val or (len(val) > 5 and "MISSING" not in val) else "❌"
        print(f"  {status} {name}: {val}")

    missing = [n for n, v in fields if "MISSING" in v]
    if missing:
        print(f"\n[!] Missing: {', '.join(missing)}")
        sys.exit(1)

    print("\n[+] All credentials extracted and deployed successfully!")


if __name__ == "__main__":
    main()
