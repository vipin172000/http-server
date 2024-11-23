import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

public class Main {
    private static String directory;

    public static void main(String[] args) {
        // Parse command line arguments
        if (args.length > 1 && args[0].equals("--directory")) {
            directory = args[1];
        } else {
            System.out.println("Usage: java Main --directory <directory>");
            return;
        }

        System.out.println("Logs from your program will appear here!");
        try (ServerSocket serverSocket = new ServerSocket(4221)) {
            serverSocket.setReuseAddress(true);
            while (true) {
                Socket clientSocket = serverSocket.accept(); // Wait for connection from client.
                System.out.println("Accepted new connection");
                // Handle each client connection in a separate thread.
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        }
    }

    private static void handleClient(Socket clientSocket) {
        try (
            BufferedReader inputStream = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
            OutputStream outputStream = clientSocket.getOutputStream()
        ) {
            // Read the request line
            String requestLine = inputStream.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                return;
            }

            String httpMethod = requestLine.split(" ")[0];
            String urlPath = requestLine.split(" ")[1];

            // Read all the headers from the HTTP request.
            Map<String, String> headers = new HashMap<>();
            String headerLine;
            while (!(headerLine = inputStream.readLine()).isEmpty()) {
                String[] headerParts = headerLine.split(": ", 2);
                if (headerParts.length == 2) {
                    headers.put(headerParts[0], headerParts[1]);
                }
            }

            // Generate the HTTP response
            String httpResponse = getHttpResponse(httpMethod, urlPath, headers, inputStream, outputStream);

            // Send the response
            System.out.println("Sending response:\n" + httpResponse);
            outputStream.write(httpResponse.getBytes("UTF-8"));
        } catch (IOException e) {
            System.out.println("IOException: " + e.getMessage());
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                System.out.println("IOException: " + e.getMessage());
            }
        }
    }

    private static String getHttpResponse(String httpMethod, String urlPath, Map<String, String> headers,
                                          BufferedReader inputStream, OutputStream outputStream) throws IOException {
        String httpResponse;
        if ("GET".equals(httpMethod)) {
            if ("/".equals(urlPath)) {
                httpResponse = "HTTP/1.1 200 OK\r\n\r\n";
            } else if (urlPath.startsWith("/echo/")) {
                String echoStr = urlPath.substring(6);
                String acceptEncoding = headers.get("Accept-Encoding");
                boolean supportsGzip = acceptEncoding != null && acceptEncoding.contains("gzip");

                if (supportsGzip) {
                    ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                    try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(byteArrayOutputStream)) {
                        gzipOutputStream.write(echoStr.getBytes("UTF-8"));
                    }
                    byte[] gzipData = byteArrayOutputStream.toByteArray();
                    httpResponse = "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\nContent-Type: text/plain\r\nContent-Length: "
                            + gzipData.length + "\r\n\r\n";
                    outputStream.write(httpResponse.getBytes("UTF-8"));
                    outputStream.write(gzipData);
                    return "";
                } else {
                    httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                            + echoStr.length() + "\r\n\r\n" + echoStr;
                }
            } else if ("/user-agent".equals(urlPath)) {
                String userAgent = headers.getOrDefault("User-Agent", "Unknown");
                httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: "
                        + userAgent.length() + "\r\n\r\n" + userAgent;
            } else if (urlPath.startsWith("/files/")) {
                String filename = urlPath.substring(7);
                File file = new File(directory, filename);
                if (file.exists() && file.isFile()) {
                    byte[] fileContent = Files.readAllBytes(file.toPath());
                    httpResponse = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "
                            + fileContent.length + "\r\n\r\n";
                    outputStream.write(httpResponse.getBytes("UTF-8"));
                    outputStream.write(fileContent);
                    return "";
                } else {
                    httpResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
                }
            } else {
                httpResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
            }
        } else if ("POST".equals(httpMethod) && urlPath.startsWith("/files/")) {
            String filename = urlPath.substring(7);
            File file = new File(directory, filename);
            if (!file.getCanonicalPath().startsWith(new File(directory).getCanonicalPath())) {
                httpResponse = "HTTP/1.1 403 Forbidden\r\n\r\n";
            } else {
                int contentLength = Integer.parseInt(headers.getOrDefault("Content-Length", "0"));
                char[] buffer = new char[contentLength];
                int bytesRead = inputStream.read(buffer, 0, contentLength);
                if (bytesRead == contentLength) {
                    try (BufferedWriter writer = new BufferedWriter(new FileWriter(file))) {
                        writer.write(buffer, 0, bytesRead);
                    }
                    httpResponse = "HTTP/1.1 201 Created\r\n\r\n";
                } else {
                    httpResponse = "HTTP/1.1 500 Internal Server Error\r\n\r\n";
                }
            }
        } else {
            httpResponse = "HTTP/1.1 404 Not Found\r\n\r\n";
        }
        return httpResponse;
    }
}
