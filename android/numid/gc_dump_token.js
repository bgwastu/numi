/*
 * Injected into app.source.getcontact (via frida-inject).
 * Decrypts tokens.preferences_pb with Keystore alias crypto_client and
 * writes the app token to /data/local/tmp/gc_app_token.txt
 */
var OUT = "/data/local/tmp/gc_app_token.txt";
var PATH = "/data/data/app.source.getcontact/files/datastore/tokens.preferences_pb";

function writeOut(text) {
    var f = new File(OUT, "w");
    f.write(text);
    f.flush();
    f.close();
}

function dumpToken() {
    Java.perform(function () {
        try {
            var Base64 = Java.use("java.util.Base64");
            var Arrays = Java.use("java.util.Arrays");
            var StringCls = Java.use("java.lang.String");
            var KeyStore = Java.use("java.security.KeyStore");
            var Cipher = Java.use("javax.crypto.Cipher");
            var IvParameterSpec = Java.use("javax.crypto.spec.IvParameterSpec");
            var FileInputStream = Java.use("java.io.FileInputStream");
            var ByteArrayOutputStream = Java.use("java.io.ByteArrayOutputStream");

            var fis = FileInputStream.$new(PATH);
            var bos = ByteArrayOutputStream.$new();
            var buf = Java.array("byte", new Array(8192).fill(0));
            var n;
            while ((n = fis.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            fis.close();
            var raw = bos.toByteArray();
            var data = Base64.getDecoder().decode(raw);
            var iv = Arrays.copyOfRange(data, 0, 16);
            var ct = Arrays.copyOfRange(data, 16, data.length);

            var ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            var key = ks.getKey("crypto_client", null);
            if (key === null) {
                writeOut("ERROR:KEY_MISSING\n");
                return;
            }

            var cipher;
            try {
                cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
            } catch (e) {
                cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            }
            cipher.init(2, key, IvParameterSpec.$new(iv));
            var plain = cipher.doFinal(ct);
            var json = StringCls.$new(plain, "UTF-8").toString();
            var needle = '"app":"';
            var i = json.indexOf(needle);
            if (i < 0) {
                writeOut("ERROR:APP_MISSING\n" + json + "\n");
                return;
            }
            var start = i + needle.length;
            var end = json.indexOf('"', start);
            var app = json.substring(start, end);
            writeOut(app + "\n");
            console.log("APP_TOKEN=" + app);
        } catch (e) {
            writeOut("ERROR:" + e + "\n");
            console.log("ERROR:" + e);
        }
    });
}

if (Java.available) {
    dumpToken();
} else {
    var tries = 0;
    var timer = setInterval(function () {
        tries++;
        if (Java.available) {
            clearInterval(timer);
            dumpToken();
        } else if (tries > 50) {
            clearInterval(timer);
            writeOut("ERROR:JAVA_UNAVAILABLE\n");
        }
    }, 100);
}
