#!/usr/bin/env python3
import os
import sys
import socket
import struct
import json
import subprocess

SOCKET_PATH = "/data/local/tmp/numid.sock"
ALLOWED_PACKAGE_NAME = "com.bgwastu.numi"

def get_allowed_uid():
    try:
        # Get package UID on Android
        out = subprocess.check_output(["pm", "list", "packages", "-U", ALLOWED_PACKAGE_NAME]).decode("utf-8")
        # Format: package:com.bgwastu.numi uid:10245
        for line in out.splitlines():
            if ALLOWED_PACKAGE_NAME in line and "uid:" in line:
                return int(line.split("uid:")[1].strip())
    except Exception as e:
        print(f"Error resolving UID for package {ALLOWED_PACKAGE_NAME}: {e}", file=sys.stderr)
    return None

def check_sip_gateway():
    try:
        # Check if the gateway package is running
        out = subprocess.check_output(["pgrep", "-f", "Android-sip-gateway"]).decode("utf-8")
        return {"status": "RUNNING", "pids": out.strip().split()}
    except subprocess.CalledProcessError:
        return {"status": "STOPPED", "pids": []}

def restart_sip_gateway():
    try:
        subprocess.check_call(["am", "force-stop", "com.qwe7002.telegram_sms"]) # Stop old
        subprocess.check_call(["am", "start", "-n", "s_xander.android_sip_gateway/.MainActivity"])
        return {"status": "SUCCESS", "message": "SIP Gateway restart command issued"}
    except Exception as e:
        return {"status": "FAILED", "error": str(e)}

def get_getcontact_keys():
    keys_path = "/data/user/0/com.bgwastu.numi/files/providers/getcontact.json"
    if os.path.exists(keys_path):
        try:
            with open(keys_path, "r") as f:
                return {"status": "SUCCESS", "data": json.load(f)}
        except Exception as e:
            return {"status": "FAILED", "error": f"Read error: {e}"}
    return {"status": "FAILED", "error": "Keys not found/extracted yet"}

def handle_client(conn, allowed_uid):
    try:
        # Check caller credentials on Linux socket (SO_PEERCRED = 17)
        # struct ucred: pid (int), uid (uid_t), gid (gid_t)
        cred = conn.getsockopt(socket.SOL_SOCKET, 17, 12)
        pid, uid, gid = struct.unpack("3i", cred)
        
        if allowed_uid is not None and uid != allowed_uid and uid != 0:
            print(f"REJECTED: Unauthorized UID {uid} tried to access root IPC", file=sys.stderr)
            conn.sendall(json.dumps({"error": "Unauthorized access"}).encode("utf-8"))
            return

        # Read command
        data = conn.recv(1024).decode("utf-8").strip()
        if not data:
            return
        
        try:
            req = json.loads(data)
            action = req.get("action")
            
            if action == "sip.status":
                res = check_sip_gateway()
            elif action == "sip.restart":
                res = restart_sip_gateway()
            elif action == "provider.get_keys":
                res = get_getcontact_keys()
            else:
                res = {"status": "FAILED", "error": f"Unknown action: {action}"}
                
            conn.sendall(json.dumps(res).encode("utf-8"))
        except Exception as e:
            conn.sendall(json.dumps({"status": "FAILED", "error": str(e)}).encode("utf-8"))

    except Exception as e:
        print(f"Error handling connection: {e}", file=sys.stderr)
    finally:
        conn.close()

def main():
    if os.path.exists(SOCKET_PATH):
        try:
            os.remove(SOCKET_PATH)
        except OSError:
            pass

    allowed_uid = get_allowed_uid()
    if allowed_uid is None:
        print("WARNING: Could not resolve Numi app UID. Falling back to accepting connections...", file=sys.stderr)

    s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s.bind(SOCKET_PATH)
    s.listen(5)
    
    # Change socket permissions so the Android application can read/write to it
    os.chmod(SOCKET_PATH, 0o666)
    
    print(f"numid companion running on UNIX socket: {SOCKET_PATH}")
    
    while True:
        try:
            conn, addr = s.accept()
            handle_client(conn, allowed_uid)
        except KeyboardInterrupt:
            break
        except Exception as e:
            print(f"Server loop exception: {e}", file=sys.stderr)

if __name__ == "__main__":
    main()
