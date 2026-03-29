package org.project.cacheclient;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import junit.framework.TestCase;

public class CacheClientTest extends TestCase {

    public void testAddCacheHeaderPreservesBinaryBody() {
        CacheClient client = new CacheClient();
        byte[] binaryBody = new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, (byte) 0xFF};
        byte[] headers =
                "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Length: 10\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1);
        byte[] response = new byte[headers.length + binaryBody.length];
        System.arraycopy(headers, 0, response, 0, headers.length);
        System.arraycopy(binaryBody, 0, response, headers.length, binaryBody.length);

        byte[] updated = client.addCacheHeader(response, "MISS");
        String updatedAsText = new String(updated, StandardCharsets.ISO_8859_1);

        assertTrue(updatedAsText.contains("\r\nX-Cache: MISS\r\n\r\n"));
        byte[] updatedBody = Arrays.copyOfRange(updated, updated.length - binaryBody.length, updated.length);
        assertTrue(Arrays.equals(binaryBody, updatedBody));
    }
}
