package common.util;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Single-part multipart/form-data body used by the map preview renderer. */
public record MultipartBody(byte[] body, String contentType) {

    private static final String FALLBACK_FILENAME = "save.msav";

    public static MultipartBody encodeFile(String fieldName, String filename, byte[] data) {
        String boundary = UUID.randomUUID().toString();
        String name = basename(filename);

        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 256);
        write(out, "--" + boundary + "\r\n");
        write(out, "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + name + "\"\r\n");
        write(out, "Content-Type: application/octet-stream\r\n\r\n");
        out.write(data, 0, data.length);
        write(out, "\r\n--" + boundary + "--\r\n");

        return new MultipartBody(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    static String basename(String path) {
        if (path == null || path.isBlank()) {
            return FALLBACK_FILENAME;
        }

        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = (slash >= 0 ? path.substring(slash + 1) : path)
                .replace("\"", "")
                .replace("\r", "")
                .replace("\n", "");

        return name.isBlank() ? FALLBACK_FILENAME : name;
    }

    private static void write(ByteArrayOutputStream out, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.write(bytes, 0, bytes.length);
    }
}
