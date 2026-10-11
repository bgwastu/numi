package net.wastu.numi.spam;

import java.util.ArrayList;
import java.util.List;

/** Shared by Android inference and the public-data training exporter. */
public final class GemmaTextInput {
    public static final String PREFIX = "task: classification | query: ";
    public static final int MAX_CODE_POINTS = 3000;
    // A UTF-8 byte budget also bounds byte-fallback tokens. Leave room for the
    // model's task prefix and special tokens in its 128-token signature.
    public static final int CHUNK_BYTES = 96;

    private GemmaTextInput() {}

    public static List<String> chunks(String body) {
        String text = body.trim();
        int count = text.codePointCount(0, text.length());
        if (count > MAX_CODE_POINTS) text = text.substring(0, text.offsetByCodePoints(0, MAX_CODE_POINTS));
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < text.length();) {
            int end = start;
            int bytes = 0;
            while (end < text.length()) {
                int code = text.codePointAt(end);
                int size = code < 0x80 ? 1 : code < 0x800 ? 2 : code < 0x10000 ? 3 : 4;
                if (bytes + size > CHUNK_BYTES) break;
                bytes += size;
                end += Character.charCount(code);
            }
            if (end < text.length()) {
                int space = text.lastIndexOf(' ', end - 1);
                if (space > start + 48) end = space + 1;
            }
            String chunk = text.substring(start, end).trim();
            if (!chunk.isEmpty()) chunks.add(PREFIX + chunk);
            start = end;
        }
        if (chunks.isEmpty()) chunks.add(PREFIX);
        return chunks;
    }

    public static float[] pool(List<float[]> embeddings) {
        if (embeddings.isEmpty()) throw new IllegalArgumentException("Missing Gemma embedding");
        int dimension = embeddings.get(0).length;
        if (dimension != 768) throw new IllegalArgumentException("Unexpected Gemma embedding size");
        double[] sum = new double[dimension];
        for (float[] vector : embeddings) {
            if (vector.length != dimension) throw new IllegalArgumentException("Inconsistent Gemma embedding size");
            for (int i = 0; i < dimension; i++) {
                if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("Invalid Gemma embedding");
                sum[i] += vector[i];
            }
        }
        double squared = 0;
        for (double value : sum) squared += value * value;
        if (!Double.isFinite(squared) || squared <= 0) throw new IllegalArgumentException("Empty Gemma embedding");
        double norm = Math.sqrt(squared);
        float[] result = new float[dimension];
        for (int i = 0; i < dimension; i++) result[i] = (float) (sum[i] / norm);
        return result;
    }
}
