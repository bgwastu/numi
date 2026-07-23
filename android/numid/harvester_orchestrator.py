#!/usr/bin/env python3
import os
import sys
import subprocess
import time
import json
import re

PACKAGE_NAME = "app.source.getcontact"
OUTPUT_DIR = "/data/user/0/com.bgwastu.numi/files/providers"
OUTPUT_FILE = os.path.join(OUTPUT_DIR, "getcontact.json")

def get_process_pid():
    try:
        out = subprocess.check_output(["pgrep", "-f", PACKAGE_NAME]).decode("utf-8").strip()
        if out:
            # Return first PID in case of multiple processes
            return int(out.split()[0])
    except subprocess.CalledProcessError:
        pass
    return None

def start_getcontact():
    print(f"Launching GetContact ({PACKAGE_NAME})...")
    subprocess.call(["am", "force-stop", PACKAGE_NAME])
    time.sleep(1)
    subprocess.call(["monkey", "-p", PACKAGE_NAME, "-c", "android.intent.category.LAUNCHER", "1"])
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
        "-l", "/data/local/tmp/numi/android/numid/harvester.js",
        "--no-pause"
    ]
    
    proc = subprocess.Popen(frida_cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    
    aes_key = None
    hmac_key = None
    
    start_time = time.time()
    timeout = 15 # Wait up to 15 seconds for keys
    
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

def write_keys_to_app(aes_key, hmac_key):
    if not os.path.exists(OUTPUT_DIR):
        os.makedirs(OUTPUT_DIR, exist_ok=True)
        # Ensure Numi app owns the directory
        try:
            uid_out = subprocess.check_output(["stat", "-c", "%u:%g", "/data/user/0/com.bgwastu.numi"]).decode("utf-8").strip()
            subprocess.call(["chown", "-R", uid_out, OUTPUT_DIR])
        except Exception:
            pass

    data = {
        "aes_key": aes_key,
        "hmac_key": hmac_key,
        "updated_at": int(time.time() * 1000)
    }
    
    with open(OUTPUT_FILE, "w") as f:
        json.dump(data, f, indent=4)
        
    # Grant permissions to the application
    try:
        uid_out = subprocess.check_output(["stat", "-c", "%u:%g", "/data/user/0/com.bgwastu.numi"]).decode("utf-8").strip()
        subprocess.call(["chown", uid_out, OUTPUT_FILE])
        subprocess.call(["chmod", "600", OUTPUT_FILE])
    except Exception:
        pass
        
    print(f"Keys successfully stored at {OUTPUT_FILE}")

def main():
    result = run_frida_harvester()
    if result is None:
        print("ERROR: Harvester run failed completely.")
        sys.exit(1)

    aes_key, hmac_key = result
    
    if aes_key and hmac_key:
        write_keys_to_app(aes_key, hmac_key)
        sys.exit(0)
    else:
        print("ERROR: Key harvesting failed. AES or HMAC key missing.")
        sys.exit(1)

if __name__ == "__main__":
    main()
