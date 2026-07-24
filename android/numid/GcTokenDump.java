import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;

/**
 * Runs as GetContact's UID so Android Keystore alias {@code crypto_client} is reachable.
 * Decrypts tokens.preferences_pb (AES/CBC, IV||ciphertext, base64) and prints Tokens JSON.
 */
public final class GcTokenDump {
    private static final String DEFAULT_PATH =
            "/data/data/app.source.getcontact/files/datastore/tokens.preferences_pb";
    private static final String KEY_ALIAS = "crypto_client";

    public static void main(String[] args) throws Exception {
        String path = args.length > 0 ? args[0] : DEFAULT_PATH;
        File file = new File(path);
        if (!file.isFile()) {
            System.err.println("MISSING_FILE " + path);
            System.exit(2);
            return;
        }

        byte[] raw = readAll(file);
        String b64 = new String(raw, StandardCharsets.US_ASCII).trim();
        byte[] data = Base64.getDecoder().decode(b64);
        if (data.length <= 16) {
            System.err.println("CIPHERTEXT_TOO_SHORT " + data.length);
            System.exit(3);
            return;
        }

        byte[] iv = new byte[16];
        System.arraycopy(data, 0, iv, 0, 16);
        byte[] ct = new byte[data.length - 16];
        System.arraycopy(data, 16, ct, 0, ct.length);

        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        Key key = ks.getKey(KEY_ALIAS, null);
        if (key == null) {
            System.err.println("KEY_MISSING " + KEY_ALIAS);
            System.exit(4);
            return;
        }

        Cipher cipher;
        try {
            cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
        } catch (Exception ignored) {
            cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        }
        cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
        byte[] plain = cipher.doFinal(ct);
        String json = new String(plain, StandardCharsets.UTF_8);
        System.out.println(json);

        String app = extractJsonString(json, "app");
        if (app != null && !app.isEmpty()) {
            System.out.println("APP_TOKEN=" + app);
        } else {
            System.err.println("APP_FIELD_MISSING");
            System.exit(5);
        }
    }

    private static byte[] readAll(File file) throws Exception {
        byte[] buf = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int off = 0;
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) break;
                off += n;
            }
        }
        return buf;
    }

    /** Minimal JSON string extractor for {"app":"..."}. */
    private static String extractJsonString(String json, String key) {
        String needle = "\"" + key + "\":\"";
        int i = json.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = json.indexOf('"', start);
        if (end < 0) return null;
        return json.substring(start, end);
    }

    private GcTokenDump() {}
}
