package com.github.yanxianchao.dubboeasyinvoke.invoke;

import com.github.yanxianchao.dubboeasyinvoke.model.DubboMethodEndpoint;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

public final class DubboTelnetClient {

    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 500;
    private static final int MAX_WAIT_MS = 12000;

    public @NotNull String invoke(
            @NotNull DubboMethodEndpoint endpoint,
            @NotNull String argumentExpression
    ) throws IOException {
        String command = buildInvokeCommand(endpoint, argumentExpression);
        String raw = send(endpoint.getHost(), endpoint.getPort(), command);
        return extractResult(raw);
    }

    private @NotNull String buildInvokeCommand(
            @NotNull DubboMethodEndpoint endpoint,
            @NotNull String argumentExpression
    ) {
        StringBuilder builder = new StringBuilder();
        builder.append("invoke ")
                .append(endpoint.getServiceName())
                .append('.')
                .append(endpoint.getMethodName())
                .append('(')
                .append(argumentExpression)
                .append(')');
        return builder.toString();
    }

    private @NotNull String send(@NotNull String host, int port, @NotNull String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 InputStream inputStream = socket.getInputStream()) {
                readUntilPrompt(inputStream, 1500);

                writer.write(command);
                writer.write('\n');
                writer.flush();

                return readUntilPrompt(inputStream, MAX_WAIT_MS);
            }
        }
    }

    private @NotNull String readUntilPrompt(@NotNull InputStream inputStream, int maxWaitMs) throws IOException {
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[1024];
        long deadline = System.currentTimeMillis() + maxWaitMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead < 0) {
                    break;
                }
                if (bytesRead == 0) {
                    continue;
                }

                String chunk = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                response.append(chunk);
                if (response.indexOf("dubbo>") >= 0) {
                    break;
                }
            } catch (SocketTimeoutException ignored) {
                // Continue polling until the deadline is reached.
            }
        }

        return response.toString();
    }

    private @NotNull String extractResult(@NotNull String rawResponse) {
        String[] lines = rawResponse.split("\\R");
        StringBuilder resultBuilder = new StringBuilder();
        boolean collectingResult = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("dubbo>")) {
                continue;
            }

            if (trimmed.startsWith("result:")) {
                collectingResult = true;
                String firstLine = trimmed.substring("result:".length()).trim();
                if (!firstLine.isEmpty()) {
                    resultBuilder.append(firstLine).append('\n');
                }
                continue;
            }

            if (collectingResult) {
                if (trimmed.startsWith("elapsed:")) {
                    break;
                }
                resultBuilder.append(line).append('\n');
            }
        }

        if (resultBuilder.length() > 0) {
            return resultBuilder.toString().trim();
        }

        String cleaned = rawResponse
                .replace("dubbo>", "")
                .trim();
        return cleaned.isEmpty() ? "调用完成，但未返回可解析结果。" : cleaned;
    }
}
