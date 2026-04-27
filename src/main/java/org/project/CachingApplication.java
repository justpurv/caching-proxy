package org.project;

import org.project.cache.CacheStore;
import org.project.cacheclient.CacheClient;
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
        System.out.println("Starting proxy server...");
        System.out.println("Port: " + port);
        System.out.println("Origin: " + origin);

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Proxy server running on port : " + port);
        } catch (IOException e) {
            System.out.println(
                    "There were some problems with starting the server on : "
                            + port
                            + " : "
                            + e.getMessage());
            return;
        }
        boolean running = true;
        while (running) {
            try (Socket socket = serverSocket.accept()) {
                System.out.println("Client connected : " + socket.getInetAddress());
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String requestLine = reader.readLine();
                if (requestLine == null || requestLine.isBlank()) {
                    continue;
                }
                String[] parts = requestLine.trim().split("\\s+");
                if (parts.length < 2) {
                    writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request");
                    continue;
                }
                String method = parts[0];
                String path;
                try {
                    path = normalizePath(parts[1]);
                } catch (IllegalArgumentException invalidRequestTarget) {
                    writeSimpleResponse(socket.getOutputStream(), 400, "Bad Request");
                    continue;
                }
                String cacheKey = origin + path;
                System.out.println("key : " +cacheKey);
                System.out.println("Method : " + method);
                System.out.println("Path : " + path);
                byte[] response; 
                if(store.contains(cacheKey)){
                    System.out.println("CACHE_HIT :");
                    response = store.get(cacheKey);
                    response = client.addCacheHeader(response, "HIT");
                }else{
                    System.out.println("CACHE_MISS : ");
                    try {
                        response = client.getResponse(origin, path);
                        store.put(cacheKey, response);
                        response = client.addCacheHeader(response, "MISS");
                    } catch (IOException | InterruptedException upstreamFailure) {
                        if (upstreamFailure instanceof InterruptedException) {
                            Thread.currentThread().interrupt();
                        }
                        System.out.println("Upstream request failed: " + upstreamFailure.getMessage());
                        writeSimpleResponse(socket.getOutputStream(), 502, "Bad Gateway");
                        continue;
                    }
                }
                OutputStream outputStream = socket.getOutputStream();
                System.out.println("Writing response to the socekt");
                try (InputStream responseStream = client.asInputStream(response)) {
                    responseStream.transferTo(outputStream);
                }
                outputStream.flush();
            } catch (IOException e) {
                System.out.println(
                        "There were some problems with connecting to client : " + e.getMessage());
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
