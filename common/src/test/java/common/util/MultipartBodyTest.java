package common.util;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class MultipartBodyTest {

    @Test
    void encodesRawBytesWithMatchingBoundary() {
        byte[] data = {0, 1, 2, 'm', 's', 'a', 'v', (byte) 0xFF};

        MultipartBody multipart = MultipartBody.encodeFile("data", "save.msav", data);

        String boundary = multipart.contentType().substring("multipart/form-data; boundary=".length());
        assertTrue(multipart.contentType().startsWith("multipart/form-data; boundary="));

        byte[] body = multipart.body();
        String header = new String(body, 0, indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}) + 4,
                StandardCharsets.UTF_8);
        assertEquals("--" + boundary + "\r\n", header.substring(0, boundary.length() + 4));
        assertTrue(header.contains(
                "Content-Disposition: form-data; name=\"data\"; filename=\"save.msav\"\r\n"));
        assertTrue(header.contains("Content-Type: application/octet-stream\r\n"));

        int payloadStart = indexOf(body, new byte[]{'\r', '\n', '\r', '\n'}) + 4;
        byte[] footer = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        int payloadEnd = body.length - footer.length;
        assertArrayEquals(footer, Arrays.copyOfRange(body, payloadEnd, body.length));
        assertArrayEquals(data, Arrays.copyOfRange(body, payloadStart, payloadEnd));
    }

    @Test
    void emptyDataProducesEmptyPart() {
        MultipartBody multipart = MultipartBody.encodeFile("data", "save.msav", new byte[0]);

        String boundary = multipart.contentType().substring("multipart/form-data; boundary=".length());
        String emptyPart = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"data\"; filename=\"save.msav\""
                + "\r\nContent-Type: application/octet-stream\r\n\r\n"
                + "\r\n--" + boundary + "--\r\n";
        assertEquals(emptyPart, new String(multipart.body(), StandardCharsets.UTF_8));
    }

    @Test
    void stripsDirectoriesFromFilename() {
        assertEquals("foo.msav", MultipartBody.basename("maps/foo.msav"));
        assertEquals("foo.msav", MultipartBody.basename("maps\\foo.msav"));
        assertEquals("foo.msav", MultipartBody.basename("foo.msav"));

        byte[] body = MultipartBody.encodeFile("data", "maps/foo.msav", new byte[0]).body();
        assertTrue(new String(body, StandardCharsets.UTF_8).contains("filename=\"foo.msav\""));
    }

    @Test
    void fallsBackToSaveMsav() {
        assertEquals("save.msav", MultipartBody.basename(null));
        assertEquals("save.msav", MultipartBody.basename(""));
        assertEquals("save.msav", MultipartBody.basename("   "));

        byte[] body = MultipartBody.encodeFile("data", null, new byte[0]).body();
        assertTrue(new String(body, StandardCharsets.UTF_8).contains("filename=\"save.msav\""));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
