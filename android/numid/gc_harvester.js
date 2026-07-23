// Numi GC Harvester — captures all GetContact credentials at runtime:
//   AES key, HMAC key, Device Token, Device ID
//
// Hooks:
//   1. SecretKeySpec constructor — AES (32B) and HMAC (64B) keys
//   2. javax.crypto.Mac.init — HMAC active key confirmation
//   3. OkHttp Headers.Builder.add — captures X-Token
//   4. java.net.URL.openConnection — fallback for header capture
//   5. SharedPreferences.getString — captures PREF_KEY_FOR_UNIQ_ID
//   6. android.content.Context.getSharedPreferences — to find prefs

var g_foundAes = false;
var g_foundHmac = false;
var g_foundToken = false;
var g_foundDeviceId = false;

Java.perform(function() {
    // ── 1. SecretKeySpec — AES + HMAC keys ─────────────────────
    console.log("[NUMI] Hook: SecretKeySpec.$init");
    var SecretKeySpec = Java.use('javax.crypto.spec.SecretKeySpec');
    SecretKeySpec.$init.overload('[B', 'java.lang.String').implementation = function(keyData, algo) {
        var hexKey = Array.from(keyData).map(function(b) {
            return ('0' + (b & 0xFF).toString(16)).slice(-2);
        }).join('');

        if (hexKey.length === 64 && !g_foundAes) {
            console.log("[KEY_FOUND] TYPE: AES | ALGO: " + algo + " | KEY: " + hexKey);
            g_foundAes = true;
        } else if (hexKey.length === 128 && !g_foundHmac) {
            console.log("[KEY_FOUND] TYPE: HMAC | ALGO: " + algo + " | KEY: " + hexKey);
            g_foundHmac = true;
        }

        return this.$init(keyData, algo);
    };

    // ── 2. Mac.init — HMAC key confirmation ────────────────────
    console.log("[NUMI] Hook: Mac.init");
    var Mac = Java.use('javax.crypto.Mac');
    Mac.init.overload('java.security.Key').implementation = function(key) {
        var keyBytes = key.getEncoded();
        if (keyBytes) {
            var hexKey = Array.from(keyBytes).map(function(b) {
                return ('0' + (b & 0xFF).toString(16)).slice(-2);
            }).join('');
            if (hexKey.length === 128 && !g_foundHmac) {
                console.log("[KEY_FOUND] TYPE: HMAC_ACTIVE | ALGO: " + key.getAlgorithm() + " | KEY: " + hexKey);
                g_foundHmac = true;
            }
        }
        return this.init(key);
    };

    // ── 3. OkHttp Headers.Builder — captures X-Token ──────────
    try {
        console.log("[NUMI] Hook: okhttp3.Headers.Builder.add");
        var HeadersBuilder = Java.use('okhttp3.Headers$Builder');
        HeadersBuilder.add.overload('java.lang.String', 'java.lang.String').implementation = function(name, value) {
            if (name.toLowerCase() === 'x-token' && value.length > 20 && !g_foundToken) {
                console.log("[KEY_FOUND] TYPE: TOKEN | VALUE: " + value);
                g_foundToken = true;
            }
            if (name.toLowerCase() === 'x-client-device-id' && !g_foundDeviceId) {
                console.log("[KEY_FOUND] TYPE: DEVICE_ID | VALUE: " + value);
                g_foundDeviceId = true;
            }
            return this.add(name, value);
        };
    } catch(e) {
        console.log("[NUMI] okhttp3 Headers hook failed: " + e);
    }

    // ── 4. Fallback: java.net.URLConnection headers ────────────
    try {
        console.log("[NUMI] Hook: java.net.URLConnection.setRequestProperty");
        var URLConnection = Java.use('java.net.URLConnection');
        URLConnection.setRequestProperty.overload('java.lang.String', 'java.lang.String').implementation = function(key, value) {
            if (key.toLowerCase() === 'x-token' && value.length > 20 && !g_foundToken) {
                console.log("[KEY_FOUND] TYPE: TOKEN | VALUE: " + value);
                g_foundToken = true;
            }
            if (key.toLowerCase() === 'x-client-device-id' && !g_foundDeviceId) {
                console.log("[KEY_FOUND] TYPE: DEVICE_ID | VALUE: " + value);
                g_foundDeviceId = true;
            }
            return this.setRequestProperty(key, value);
        };
    } catch(e) {
        console.log("[NUMI] URLConnection hook failed: " + e);
    }

    // ── 5. SharedPreferences.getString — Device ID fallback ────
    try {
        console.log("[NUMI] Hook: SharedPreferences.getString");
        var SharedPreferences = Java.use('android.content.SharedPreferences');
        SharedPreferences.getString.overload('java.lang.String', 'java.lang.String').implementation = function(key, defValue) {
            var result = this.getString(key, defValue);
            if (key === 'PREF_KEY_FOR_UNIQ_ID' && result && result.length > 5 && !g_foundDeviceId) {
                console.log("[KEY_FOUND] TYPE: DEVICE_ID | VALUE: " + result);
                g_foundDeviceId = true;
            }
            return result;
        };
    } catch(e) {
        console.log("[NUMI] SharedPreferences hook failed: " + e);
    }

    console.log("[NUMI] All hooks registered. Waiting for keys...");
});
