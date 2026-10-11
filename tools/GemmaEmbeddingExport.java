import com.google.ai.edge.litertlm.*;
import net.wastu.numi.spam.GemmaTextInput;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Export public SMS embeddings with exactly the Android preprocessing. */
public final class GemmaEmbeddingExport {
    public static void main(String[] args) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(Path.of(args[0]))) {
            byte[] buffer = new byte[1024 * 1024];
            for (int count; (count = input.read(buffer)) >= 0;) digest.update(buffer, 0, count);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals("2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb"))
            throw new IllegalArgumentException("Wrong Gemma encoder; verify the pinned download");
        Engine.Companion.setNativeMinLogSeverity(LogSeverity.ERROR);
        Files.createDirectories(Path.of(args[1]));
        List<String> rows = Files.readAllLines(Path.of(args[2]));
        long start = System.currentTimeMillis();
        var config = new EmbeddingEngineConfig(args[0], new Backend.CPU(4, null), null, null, args[1], null, 128, null, null);
        try (var engine = new EmbeddingEngine(config);
             var output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(Path.of(args[3]))))) {
            engine.initialize();
            output.writeBytes("NUMIEMB1"); output.writeInt(rows.size()); output.writeInt(768);
            int count = 0;
            for (String row : rows) {
                String text = new String(Base64.getDecoder().decode(row), StandardCharsets.UTF_8);
                List<float[]> vectors = new ArrayList<>();
                for (String chunk : GemmaTextInput.chunks(text)) {
                    vectors.add(engine.computeEmbedding(List.of(new InputData.Text(chunk)), new EmbeddingOptions(true, null, 768, null)).getEmbedding());
                }
                for (float value : GemmaTextInput.pool(vectors)) output.writeFloat(value);
                if (++count % 256 == 0) {
                    output.flush();
                    System.out.println("embedded=" + count + "/" + rows.size() + " elapsed_ms=" + (System.currentTimeMillis() - start));
                }
            }
            System.out.println("embedded=" + count + "/" + rows.size() + " elapsed_ms=" + (System.currentTimeMillis() - start));
        }
    }
}
