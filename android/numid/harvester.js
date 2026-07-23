// Frida script to hook GetContact v8.15.0 and dump AES + HMAC cryptographic keys
console.log("Frida GetContact key harvester script loaded.");

Java.perform(function() {
    // Hook SecretKeySpec constructor
    var SecretKeySpec = Java.use('javax.crypto.spec.SecretKeySpec');
    SecretKeySpec.$init.overload('[B', 'java.lang.String').implementation = function(keyData, algo) {
        var keyHex = Array.from(keyData).map(function(b) {
            return ('0' + (b & 0xFF).toString(16)).slice(-2);
        }).join('');

        // Identify keys based on length and algorithm
        // GetContact uses 32-byte key (64 hex chars) for AES and 64-byte key (128 hex chars) for HMAC
        if (keyHex.length === 64) {
            console.log("[KEY_FOUND] TYPE: AES | ALGO: " + algo + " | KEY: " + keyHex);
        } else if (keyHex.length === 128) {
            console.log("[KEY_FOUND] TYPE: HMAC | ALGO: " + algo + " | KEY: " + keyHex);
        } else {
            console.log("[KEY_DEBUG] TYPE: OTHER | LENGTH: " + keyHex.length + " | ALGO: " + algo + " | KEY: " + keyHex);
        }

        return this.$init(keyData, algo);
    };

    // Hook Mac initialization (HMAC) to double check keys
    var Mac = Java.use('javax.crypto.Mac');
    Mac.init.overload('java.security.Key').implementation = function(key) {
        var keyAlgo = key.getAlgorithm();
        var keyFormat = key.getFormat();
        var keyBytes = key.getEncoded();
        
        if (keyBytes) {
            var keyHex = Array.from(keyBytes).map(function(b) {
                return ('0' + (b & 0xFF).toString(16)).slice(-2);
            }).join('');
            if (keyHex.length === 128) {
                console.log("[KEY_FOUND] TYPE: HMAC_ACTIVE | ALGO: " + keyAlgo + " | KEY: " + keyHex);
            }
        }
        return this.init(key);
    };
});
