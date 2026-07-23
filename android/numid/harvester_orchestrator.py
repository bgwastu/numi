#!/usr/bin/env python3
import os
import sys
import subprocess
import time
import json
import re

PACKAGE_NAME = "app.source.getcontact"
TARGET_DEVICE = "localhost:5555" # Connected via SSH tunnel
NUMI_PACKAGE = "com.bgwastu.numi"
LOCAL_TMP_FILE = "/tmp/getcontact.json"

def get_process_pid():
    try:
        # Run adb pgrep
        out = subprocess.check_output([
            "adb", "-s", TARGET_DEVICE, "shell", 
            f"pgrep -f {PACKAGE_NAME}"
        ]).decode("utf-8").strip()
        if out:
            return int(out.split()[0])
    except subprocess.CalledProcessError:
        pass
    return None

def start_getcontact():
    print(f"Launching GetContact on Atlas ({PACKAGE_NAME})...")
    subprocess.call(["adb", "-s", TARGET_DEVICE, "shell", f"am force-stop {PACKAGE_NAME}"])
    time.sleep(1)
    subprocess.call([
        "adb", "-s", TARGET_DEVICE, "shell", 
        f"monkey -p {PACKAGE_NAME} -c android.intent.category.LAUNCHER 1"
    ])
    time.sleep(3)

def run_frida_harvester():
    pid = get_process_pid()
    if pid is None:
        start_getcontact()
        pid = get_process_pid()
        if pid is None:
            print("ERROR: Failed to start GetContact.")
            return None

    print(f"Attaching Frida to GetContact (PID: {pid})...")
    
    # Run frida command and capture output
    frida_cmd = [
        "frida", 
        "-U", 
        "-p", str(pid), 
        "-l", "/home/alyssa/Projects/numi/android/numid/harvester.js",
        "--no-pause"
    ]
    
    proc = subprocess.Popen(frida_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    
    aes_key = None
    hmac_key = None
    
    start_time = time.time()
    timeout = 20 # Wait up to 20 seconds for keys
    
    if proc.stdout is None:
        print("ERROR: Process stdout is None.")
        proc.terminate()
        return None, None

    try:
        while time.time() - start_time < timeout:
            line = proc.stdout.readline()
            if not line:
                break
            line = line.strip()
            print(f"[Frida] {line}")
            
            # Check for keys
            aes_match = re.search(r"TYPE:\s*AES\s*\|\s*ALGO:\s*\w+\s*\|\s*KEY:\s*([0-9a-fA-F]{64})", line)
            hmac_match = re.search(r"TYPE:\s*(?:HMAC|HMAC_ACTIVE)\s*\|\s*ALGO:\s*\w+\s*\|\s*KEY:\s*([0-9a-fA-F]{128})", line)
            
            if aes_match:
                aes_key = aes_match.group(1)
                print(f"Extracted AES key: {aes_key[:8]}...")
            if hmac_match:
                hmac_key = hmac_match.group(1)
                print(f"Extracted HMAC key: {hmac_key[:8]}...")
                
            if aes_key and hmac_key:
                print("Both AES and HMAC keys harvested successfully.")
                break
    except KeyboardInterrupt:
        pass
    finally:
        proc.terminate()
        proc.wait()
        
    return aes_key, hmac_key

def deploy_keys_to_atlas(aes_key, hmac_key):
    # Save locally first
    data = {
        "aes_key": aes_key,
        "hmac_key": hmac_key,
        "updated_at": int(time.time() * 1000)
    }
    
    with open(LOCAL_TMP_FILE, "w") as f:
        json.dump(data, f, indent=4)
        
    print(f"Stored harvested keys locally at {LOCAL_TMP_FILE}")
    
    # Check if Numi is installed on Atlas to resolve its UID
    try:
        uid_out = subprocess.check_output([
            "adb", "-s", TARGET_DEVICE, "shell",
            f"su -c 'stat -c %u:%g /data/user/0/{NUMI_PACKAGE}'"
        ]).decode("utf-8").strip()
    except subprocess.CalledProcessError:
        print("WARNING: Numi package does not seem to be installed yet. Storing keys at /data/local/tmp/getcontact.json for now.")
        uid_out = None

    # Push to device temporary storage
    subprocess.check_call(["adb", "-s", TARGET_DEVICE, "push", LOCAL_TMP_FILE, "/data/local/tmp/getcontact.json"])
    
    if uid_out:
        target_dir = f"/data/user/0/{NUMI_PACKAGE}/files/providers"
        # Deploy as root and set correct package permissions
        subprocess.check_call([
            "adb", "-s", TARGET_DEVICE, "shell",
            f"su -c 'mkdir -p {target_dir} && cp -f /data/local/tmp/getcontact.json {target_dir}/getcontact.json && chmod 600 {target_dir}/getcontact.json && chown -R {uid_out} {target_dir}'"
        ])
        print(f"Keys successfully pushed and permissions configured at {target_dir}/getcontact.json")
    else:
        print("To complete deployment, install the Numi APK and run: adb push /tmp/getcontact.json /data/local/tmp/getcontact.json")

def main():
    result = run_frida_harvester()
    if result is None:
        print("ERROR: Harvester run failed completely.")
        sys.exit(1)

    aes_key, hmac_key = result
    
    if aes_key and hmac_key:
        deploy_keys_to_atlas(aes_key, hmac_key)
        sys.exit(0)
    else:
        print("ERROR: Key harvesting failed. AES or HMAC key missing.")
        sys.exit(1)

if __name__ == "__main__":
    main()
