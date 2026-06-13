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

/**
 * 通过 Dubbo telnet 协议发起一次调用。
 *
 * <p>流程非常直接：连上 provider -> 等提示符 -> 写入 invoke 命令 -> 读回响应并提取 result 区段。</p>
 *
 * <h3>工作原理</h3>
 * <p>Dubbo 框架内置了 telnet 协议支持，服务提供者在其 Dubbo 协议端口上同时监听 telnet 连接。
 * 连接建立后，会输出 {@code "dubbo>"} 提示符，表示可以接收命令。本类利用这一机制，
 * 通过发送 {@code invoke serviceName.methodName(args)} 命令来调用远程服务。</p>
 *
 * <h3>调用时序</h3>
 * <ol>
 *   <li>建立 TCP 连接（超时 {@value #CONNECT_TIMEOUT_MS}ms）</li>
 *   <li>等待首次 {@code "dubbo>"} 提示符（最长 1500ms），确认连接就绪</li>
 *   <li>发送 invoke 命令并换行</li>
 *   <li>读取响应直到出现 {@code "dubbo>"} 提示符或超时（最长 {@value #MAX_WAIT_MS}ms）</li>
 *   <li>从原始响应中提取 {@code "result:"} 和 {@code "elapsed:"} 之间的内容作为调用结果</li>
 * </ol>
 *
 * <h3>线程安全性</h3>
 * <p>本类无状态（无实例字段），每次调用 {@link #invoke} 都会创建独立的 Socket 连接，
 * 因此可以安全地在多线程环境中共享同一实例。</p>
 *
 * @see DubboMethodEndpoint 调用目标的定位信息
 */
public final class DubboTelnetClient {

    /**
     * TCP 连接超时时间（毫秒）。
     * <p>4 秒对于内网服务来说是比较宽裕的值，能容忍一定程度的网络延迟。</p>
     */
    private static final int CONNECT_TIMEOUT_MS = 4000;

    /**
     * 单次 Socket 读操作的超时时间（毫秒）。
     * <p>设置较短的读超时是为了能在循环中反复尝试读取，
     * 同时检查整体截止时间（deadline），避免被单次阻塞读卡死。</p>
     */
    private static final int READ_TIMEOUT_MS = 500;

    /**
     * 发送 invoke 命令后等待响应的最大时间（毫秒）。
     * <p>12 秒的上限考虑了 Dubbo 服务默认超时通常为数秒的情况，
     * 加上一些网络传输和序列化的缓冲时间。</p>
     */
    private static final int MAX_WAIT_MS = 12000;

    /**
     * 对指定的 Dubbo 方法端点发起一次 telnet 调用，并返回解析后的调用结果。
     *
     * <p>完整流程：构建 invoke 命令 -> 通过 Socket 发送 -> 读取原始响应 -> 提取 result 部分。</p>
     *
     * @param endpoint           调用目标端点，包含 host、port、serviceName、methodName 等信息
     * @param argumentExpression 调用参数表达式，即 invoke 命令中括号内的内容，
     *                           例如 {@code {"orderId":"12345"}} 或多参数的逗号分隔形式
     * @return 解析后的调用结果文本；如果无法解析出 result 区段，则返回原始响应的清理版本
     * @throws IOException 连接失败、发送失败或读取超时等网络异常
     */
    public @NotNull String invoke(
            @NotNull DubboMethodEndpoint endpoint,
            @NotNull String argumentExpression
    ) throws IOException {
        String command = buildInvokeCommand(endpoint, argumentExpression);
        String raw = send(endpoint.getHost(), endpoint.getPort(), command);
        return extractResult(raw);
    }

    /**
     * 构建 Dubbo telnet invoke 命令字符串。
     *
     * <p>生成格式为：{@code invoke com.example.Service.method(args)}</p>
     *
     * @param endpoint           调用目标端点，提供 serviceName 和 methodName
     * @param argumentExpression 参数表达式，将直接放入括号中
     * @return 完整的 invoke 命令字符串（不含换行符）
     */
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

    /**
     * 与目标主机建立 Socket 连接，发送命令并读取响应。
     *
     * <p>使用 try-with-resources 确保 Socket、Writer 和 InputStream 在方法结束时自动关闭。
     * 连接建立后会先等待首次 "dubbo>" 提示符（最长 1500ms），确认服务端已就绪，
     * 然后发送命令并等待响应。</p>
     *
     * @param host    目标主机地址
     * @param port    目标端口号
     * @param command 要发送的 invoke 命令
     * @return 服务端返回的原始响应文本（包含提示符和各种前缀）
     * @throws IOException 连接或读写过程中的 IO 异常
     */
    private @NotNull String send(@NotNull String host, int port, @NotNull String command) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            // 设置较短的单次读超时，配合 readUntilPrompt 中的 deadline 机制实现灵活的超时控制
            socket.setSoTimeout(READ_TIMEOUT_MS);

            try (Writer writer = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                 InputStream inputStream = socket.getInputStream()) {
                // 先消费掉连接后服务端发来的首次 "dubbo>" 提示符，确认连接就绪
                // 首次等待较短（1500ms），因为提示符应该很快就会到来
                readUntilPrompt(inputStream, 1500);

                // 发送 invoke 命令，以换行符结束（telnet 协议以换行符分隔命令）
                writer.write(command);
                writer.write('\n');
                writer.flush();

                // 等待调用结果返回，这里等待时间较长因为服务端需要执行实际的业务逻辑
                return readUntilPrompt(inputStream, MAX_WAIT_MS);
            }
        }
    }

    /**
     * 从输入流中持续读取数据，直到遇到 "dubbo>" 提示符或超过最大等待时间。
     *
     * <p>实现机制：在一个循环中反复调用阻塞读（每次最多阻塞 {@value #READ_TIMEOUT_MS}ms），
     * 每读到一批数据就检查累积的响应中是否包含 "dubbo>" 提示符。
     * 使用 deadline（绝对截止时间）而非累计计时来控制超时，更加精确可靠。</p>
     *
     * <p>当发生 {@link SocketTimeoutException} 时不抛出异常，而是继续循环——
     * 这是设计上的选择：短读超时 + 循环 = 既不会永远阻塞，又能在 deadline 前持续尝试读取。</p>
     *
     * @param inputStream 要读取的输入流
     * @param maxWaitMs   最大等待时间（毫秒），从调用此方法开始计算
     * @return 累积读取到的全部文本内容
     * @throws IOException 非超时类的 IO 异常
     */
    private @NotNull String readUntilPrompt(@NotNull InputStream inputStream, int maxWaitMs) throws IOException {
        StringBuilder response = new StringBuilder();
        byte[] buffer = new byte[1024];
        // 使用绝对时间作为截止点，避免因循环内耗时导致实际等待时间不准
        long deadline = System.currentTimeMillis() + maxWaitMs;

        while (System.currentTimeMillis() < deadline) {
            try {
                int bytesRead = inputStream.read(buffer);
                if (bytesRead < 0) {
                    // 流已关闭（对端关闭连接），提前退出
                    break;
                }
                if (bytesRead == 0) {
                    // 读到 0 字节（理论上阻塞流不会出现，但作为防御性处理）
                    continue;
                }

                String chunk = new String(buffer, 0, bytesRead, StandardCharsets.UTF_8);
                response.append(chunk);
                // 检测到 "dubbo>" 提示符说明服务端已输出完毕，可以停止读取
                if (response.indexOf("dubbo>") >= 0) {
                    break;
                }
            } catch (SocketTimeoutException ignored) {
                // 单次读超时是预期行为，忽略后继续循环检查 deadline
            }
        }

        return response.toString();
    }

    /**
     * 从 Dubbo telnet 的原始响应文本中提取调用结果。
     *
     * <p>Dubbo telnet invoke 命令的典型响应格式如下：</p>
     * <pre>
     * dubbo> invoke com.example.Service.method({"key":"value"})
     * result: {"code":0,"msg":"success"}
     * elapsed: 12 ms.
     * dubbo>
     * </pre>
     *
     * <p>解析策略：</p>
     * <ol>
     *   <li>逐行扫描，跳过空行和 "dubbo>" 提示符行</li>
     *   <li>当遇到以 "result:" 开头的行时，开始收集结果内容</li>
     *   <li>持续收集直到遇到以 "elapsed:" 开头的行（表示结果结束）</li>
     *   <li>如果无法找到 "result:" 区段，则返回清理后的原始响应作为兜底</li>
     * </ol>
     *
     * @param rawResponse Dubbo telnet 返回的原始响应文本
     * @return 提取的调用结果；如果无法解析则返回清理后的原始响应或提示信息
     */
    private @NotNull String extractResult(@NotNull String rawResponse) {
        // 按行分割响应文本（\R 匹配任意换行符：\n, \r, \r\n）
        String[] lines = rawResponse.split("\\R");
        StringBuilder resultBuilder = new StringBuilder();
        // 标记是否已进入 result 收集阶段
        boolean collectingResult = false;

        for (String line : lines) {
            String trimmed = line.trim();
            // 跳过空行和提示符行，它们不属于结果内容
            if (trimmed.isEmpty() || trimmed.startsWith("dubbo>")) {
                continue;
            }

            if (trimmed.startsWith("result:")) {
                // 进入结果收集阶段
                collectingResult = true;
                // "result:" 后面可能紧跟着结果的第一行内容
                String firstLine = trimmed.substring("result:".length()).trim();
                if (!firstLine.isEmpty()) {
                    resultBuilder.append(firstLine).append('\n');
                }
                continue;
            }

            if (collectingResult) {
                if (trimmed.startsWith("elapsed:")) {
                    // "elapsed:" 行标志着结果内容的结束，停止收集
                    break;
                }
                // 收集结果行（保留原始缩进，使用未 trim 的 line）
                resultBuilder.append(line).append('\n');
            }
        }

        // 如果成功提取到 result 区段，返回去除首尾空白的结果
        if (!resultBuilder.isEmpty()) {
            return resultBuilder.toString().trim();
        }

        // 兜底处理：无法解析出 result 区段时，尝试返回清理后的原始响应
        String cleaned = rawResponse
                .replace("dubbo>", "")
                .trim();
        return cleaned.isEmpty() ? "调用完成，但未返回可解析结果。" : cleaned;
    }
}
