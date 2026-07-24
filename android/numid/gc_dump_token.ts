import Java from "frida-java-bridge";

const TOKEN_OUT = "/data/data/app.source.getcontact/cache/gc_app_token.txt";
const KEYS_OUT = "/data/data/app.source.getcontact/cache/gc_crypto_keys.txt";
const TOKENS_PATH = "/data/data/app.source.getcontact/files/datastore/tokens.preferences_pb";
const ENC_KEYS_PATH = "/data/data/app.source.getcontact/files/datastore/encryption_keys.preferences_pb";

function writeFile(path: string, text: string): void {
  const f = new File(path, "w");
  f.write(text);
  f.flush();
  f.close();
}

function readAllBytes(path: string): any {
  const FileInputStream = Java.use("java.io.FileInputStream");
  const ByteArrayOutputStream = Java.use("java.io.ByteArrayOutputStream");
  const fis = FileInputStream.$new(path);
  const bos = ByteArrayOutputStream.$new();
  const buf = Java.array("byte", Array(8192).fill(0));
  let n: number;
  while ((n = fis.read(buf)) > 0) {
    bos.write(buf, 0, n);
  }
  fis.close();
  return bos.toByteArray();
}

function decryptCryptoBlob(path: string, key: any): string {
  const Base64 = Java.use("java.util.Base64");
  const Arrays = Java.use("java.util.Arrays");
  const StringCls = Java.use("java.lang.String");
  const Cipher = Java.use("javax.crypto.Cipher");
  const IvParameterSpec = Java.use("javax.crypto.spec.IvParameterSpec");

  const raw = readAllBytes(path);
  const data = Base64.getDecoder().decode(raw);
  const iv = Arrays.copyOfRange(data, 0, 16);
  const ct = Arrays.copyOfRange(data, 16, data.length);
  let cipher;
  try {
    cipher = Cipher.getInstance("AES/CBC/PKCS7Padding");
  } catch {
    cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
  }
  cipher.init(2, key, IvParameterSpec.$new(iv));
  const plain = cipher.doFinal(ct);
  return StringCls.$new(plain, "UTF-8").toString();
}

function extractJsonString(json: string, field: string): string | null {
  const needle = '"' + field + '":"';
  const i = json.indexOf(needle);
  if (i < 0) return null;
  const start = i + needle.length;
  const end = json.indexOf('"', start);
  if (end < 0) return null;
  return json.substring(start, end);
}

function dumpAll(): void {
  Java.perform(() => {
    try {
      const KeyStore = Java.use("java.security.KeyStore");
      const ks = KeyStore.getInstance("AndroidKeyStore");
      ks.load(null);
      const key = ks.getKey("crypto_client", null);
      if (key === null) {
        writeFile(TOKEN_OUT, "ERROR:KEY_MISSING\n");
        return;
      }

      const tokenJson = decryptCryptoBlob(TOKENS_PATH, key);
      const app = extractJsonString(tokenJson, "app");
      if (!app) {
        writeFile(TOKEN_OUT, "ERROR:APP_MISSING\n" + tokenJson + "\n");
        return;
      }
      writeFile(TOKEN_OUT, app + "\n");
      console.log("APP_TOKEN=" + app);

      const keysPlain = decryptCryptoBlob(ENC_KEYS_PATH, key);
      const shared = extractJsonString(keysPlain, "sharedKey");
      const privateKey = extractJsonString(keysPlain, "privateKey");
      if (!shared) {
        writeFile(KEYS_OUT, "ERROR:SHARED_MISSING\n" + keysPlain + "\n");
        return;
      }
      // LINE format consumed by GetContactCredentialExtractor
      writeFile(
        KEYS_OUT,
        "sharedKey=" + shared + "\n" +
          "privateKey=" + (privateKey || "") + "\n"
      );
      console.log("SHARED_KEY=" + shared);
      console.log("PRIVATE_KEY=" + privateKey);
    } catch (e) {
      writeFile(TOKEN_OUT, "ERROR:" + e + "\n");
      console.log("ERROR:" + e);
    }
  });
}

dumpAll();
