package org.project;

import org.project.cacheclient.CacheClient;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

public class CachingApplication {
    static void main(String[] args) {
        int port = 0;
        String origin = null;
        CacheClient client = new CacheClient();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port")) {
                port = Integer.parseInt(args[i + 1]);
            }
            if (args[i].equals("--origin")) {
                origin = args[i + 1];
            }
        }
        System.out.println("Starting Proxy Server...");
        System.out.println("Port: " + port);
        System.out.println("Origin: " + origin);

        ServerSocket serverSocket = null;
        try {
            serverSocket = new ServerSocket(port);
            System.out.println("Proxy server running on port : " + port);
        } catch (IOException e) {
            System.out.println("There were some problems with starting the server on : " + port + " : " + e.getMessage());
        }
        boolean running = true;
        while (running) {
            Socket socket = null;
            try {
                socket = serverSocket.accept();
                BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                String requestLine = reader.readLine();
                if (requestLine == null) {
                    socket.close();
                    continue;
                }
                String[] parts = requestLine.split(" ");
                String method = parts[0];
                String path = parts[1];
                System.out.println("Method : " + method);
                System.out.println("Path : " + path);
                // response is will be stored in this variable
                byte[] response = client.getResponse(origin, path);
                OutputStream outputStream = socket.getOutputStream();
                System.out.println("writing response to the socekt");
                outputStream.write(response);
                System.out.println("written response : "+response.toString());
                outputStream.flush();
                System.out.println("listening for other request...");
            } catch (IOException | InterruptedException e) {
                System.out.println("There were some problems with connecting to client : " + e.getMessage());
            }
            System.out.println("Client Connected : " + socket.getInetAddress());
        }
    }
}