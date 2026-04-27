package org.project;

import org.project.cache.CacheStore;
import org.project.cacheclient.CacheClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class CachingApplication {
    private static final Logger logger = LoggerFactory.getLogger(CachingApplication.class);

    public static void main(String[] args) {
        int port = 0;
        String origin = null;
        CacheClient client = new CacheClient();
        CacheStore store = new CacheStore();

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port")) {
                port = Integer.parseInt(args[i + 1]);
            }
            if (args[i].equals("--origin")) {
                origin = args[i + 1];
            }
        }
        logger.atInfo()
                .setMessage("starting proxy server")
                .addKeyValue("port", port)
                .addKeyValue("origin", origin)
                .log();

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            logger.atInfo().setMessage("proxy server running").addKeyValue("port", port).log();
        } catch (IOException e) {
            logger.atError()
                    .setMessage("failed to start proxy server")
                    .addKeyValue("port", port)
                    .setCause(e)
                    .log();
            return;
        }
        boolean running = true;
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                logger.atInfo()
                        .setMessage("client connected")
                        .addKeyValue("client_ip", socket.getInetAddress())
                        .log();
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isBlank()) {
                    logger.atWarn().log("empty request line");
                    continue;
                }
                String[] parts = requestLine.trim().split("\\s+");
                if (parts.length < 2) {
                    logger.atWarn().setMessage("malformed request line").addKeyValue("request_line", requestLine).log();
                    writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request");
                    continue;
                }
                String method = parts[0];
                String path;
                try {
                    path = normalizePath(parts[1]);
                } catch (IllegalArgumentException invalidRequestTarget) {
                    logger.atWarn()
                            .setMessage("invalid request target")
                            .addKeyValue("request_target", parts[1])
                            .setCause(invalidRequestTarget)
                            .log();
                    writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request");
                    continue;
                }
                String cacheKey = origin + path;
                logger.atInfo()
                        .setMessage("request received")
                        .addKeyValue("method", method)
                        .addKeyValue("path", path)
                        .addKeyValue("cache_key", cacheKey)
                        .log();
                byte[] response; 
                if(store.contains(cacheKey)){
                    logger.atInfo().setMessage("cache lookup").addKeyValue("cache_status", "HIT").addKeyValue("cache_key", cacheKey).log();
                    response = store.get(cacheKey);
                    response = client.addCacheHeader(response, "HIT");
                }else{
                    logger.atInfo().setMessage("cache lookup").addKeyValue("cache_status", "MISS").addKeyValue("cache_key", cacheKey).log();
                    try {
                        response = client.getResponse(origin, path);
                        store.put(cacheKey, response);
                        response = client.addCacheHeader(response, "MISS");
                    } catch (IOException | InterruptedException upstreamFailure) {
                        if (upstreamFailure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        logger.atWarn()
                                .setMessage("upstream request failed")
                                .addKeyValue("origin", origin)
                                .addKeyValue("path", path)
                                .setCause(upstreamFailure)
                                .log();
                        writeSimpleResponse(socket.getOutputStream(), 502, "Bad Gateway");
                        continue;
                    }
                }
                OutputStream outputStream = socket.getOutputStream();
                logger.atInfo().setMessage("writing response to socket").addKeyValue("path", path).log();
                try (InputStream responseStream = client.asInputStream(response)) {
                    responseStream.transferTo(outputStream);
                }
                outputStream.flush();
            } catch (IOException e) {
                logger.atError().setMessage("proxy request handling failed").setCause(e).log();
            }
        }
    }

    private static String normalizePath(String requestTarget) {
        if (requestTarget == null || requestTarget.isBlank()) {
            return "/";
        }
        if (requestTarget.startsWith("http://") || requestTarget.startsWith("https://")) {
            URI uri = URI.create(requestTarget);
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath();
            return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
        }
        return requestTarget;
    }

    private static void writeSimpleResponse(OutputStream outputStream, int statusCode, String message)
            throws IOException {
        String statusLine = "HTTP/1.1 " + statusCode + " " + message + "\r\n";
        byte[] body = (message + "\n").getBytes(StandardCharsets.ISO_8859_1);
        String headers =
                "Content-Type: text/plain\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n";
        outputStream.write(statusLine.getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(headers.getBytes(StandardCharsets.ISO_8859_1));
        outputStream.write(body);
        outputStream.flush();
    }
}
