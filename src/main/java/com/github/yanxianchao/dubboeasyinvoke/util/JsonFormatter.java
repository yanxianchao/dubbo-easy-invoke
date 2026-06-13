package com.github.yanxianchao.dubboeasyinvoke.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * JSON 相关的纯工具类：
 * 1) 输入参数自动美化；
 * 2) 入参转换为 Dubbo telnet 可直接使用的表达式；
 * 3) 调用结果尝试格式化展示。
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>本类封装了两个 {@link ObjectMapper} 实例：一个用于美化输出（带缩进），
 *       一个用于紧凑输出。两者都是线程安全的，作为静态常量共享使用。</li>
 *   <li>所有方法都遵循"尽力而为"的策略：如果输入是合法 JSON 则进行格式化处理，
 *       如果不是合法 JSON 则原样返回，不会抛出异常。这使得调用方无需预先判断输入格式。</li>
 *   <li>私有构造函数确保本类不会被实例化，所有功能通过静态方法提供。</li>
 * </ul>
 *
 * <h3>线程安全性</h3>
 * <p>两个 {@link ObjectMapper} 实例在初始化后仅用于读操作（序列化/反序列化），
 * 根据 Jackson 文档，这些操作是线程安全的。本类无其他可变状态，因此完全线程安全。</p>
 *
 * @see com.github.yanxianchao.dubboeasyinvoke.invoke.DubboTelnetClient 使用本类转换调用参数和格式化结果
 */
public final class JsonFormatter {

    /**
     * 美化输出的 ObjectMapper：启用了缩进（{@link SerializationFeature#INDENT_OUTPUT}）。
     * <p>用于将 JSON 格式化为多行、带缩进的可读形式，适合 UI 展示和调试场景。</p>
     */
    private static final ObjectMapper PRETTY_MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /**
     * 紧凑输出的 ObjectMapper：使用默认配置，输出单行无缩进的 JSON。
     * <p>用于生成 Dubbo telnet 调用所需的紧凑参数表达式，以及作为通用的 JSON 解析器。</p>
     */
    private static final ObjectMapper COMPACT_MAPPER = new ObjectMapper();

    /**
     * 私有构造函数，防止工具类被实例化。
     */
    private JsonFormatter() {
    }

    /**
     * 如果输入文本是合法的 JSON，则美化输出（带缩进的多行格式）；否则原样返回。
     *
     * <p>典型用途：用户在参数输入框中输入 JSON 后，按下格式化按钮时调用此方法，
     * 将紧凑的 JSON 转换为易读的多行格式。</p>
     *
     * <p>安全性：对于 null 返回空字符串，对于空白字符串原样返回，
     * 对于非 JSON 文本也原样返回，不会抛出任何异常。</p>
     *
     * @param text 待处理的文本，可以为 null
     * @return 美化后的 JSON 字符串；如果输入不是 JSON 则原样返回；如果输入为 null 则返回空字符串
     */
    public static @NotNull String prettyIfJson(@Nullable String text) {
        if (text == null || text.isBlank()) {
            // null 转空字符串，空白串原样返回（保留用户可能有意输入的空格/换行）
            return text == null ? "" : text;
        }

        // 尝试将文本解析为 JSON 树，如果失败返回 null
        JsonNode jsonNode = readJsonOrNull(text);
        if (jsonNode == null) {
            // 不是合法 JSON，原样返回
            return text;
        }

        try {
            return PRETTY_MAPPER.writeValueAsString(jsonNode);
        } catch (JsonProcessingException ignored) {
            // 理论上解析成功后序列化不应失败，但作为防御性处理仍原样返回
            return text;
        }
    }

    /**
     * 将用户输入的参数文本转换为 Dubbo telnet invoke 命令可直接使用的参数表达式。
     *
     * <p>如果输入是合法 JSON，则输出紧凑格式（去除多余空白和换行），
     * 确保生成的 invoke 命令是单行的。如果不是 JSON，则 trim 后原样返回。</p>
     *
     * <p>例如：</p>
     * <pre>
     * 输入: {
     *   "orderId": "12345",
     *   "amount": 100
     * }
     * 输出: {"orderId":"12345","amount":100}
     * </pre>
     *
     * @param inputText 用户输入的参数文本，可以为 null
     * @return 紧凑格式的参数表达式；如果输入为 null 或空白则返回空字符串
     */
    public static @NotNull String toDubboArgumentExpression(@Nullable String inputText) {
        if (inputText == null || inputText.isBlank()) {
            return "";
        }

        String trimmed = inputText.trim();
        // 尝试解析为 JSON，如果成功则输出紧凑格式
        JsonNode jsonNode = readJsonOrNull(trimmed);
        if (jsonNode == null) {
            // 不是 JSON（可能是简单类型如 "hello" 或 123），直接返回 trim 后的文本
            return trimmed;
        }

        try {
            // 使用紧凑 Mapper 序列化，去除所有不必要的空白
            return COMPACT_MAPPER.writeValueAsString(jsonNode);
        } catch (JsonProcessingException ignored) {
            return trimmed;
        }
    }

    /**
     * 尝试将调用结果文本美化为格式化的 JSON；如果结果不是 JSON 则原样返回。
     *
     * <p>Dubbo 服务返回的结果通常是 JSON 格式（对象或数组），美化后更易于阅读和分析。
     * 如果返回值是非 JSON 文本（如简单字符串或错误信息），则直接展示。</p>
     *
     * @param rawResult Dubbo 调用返回的原始结果文本，可以为 null
     * @return 美化后的 JSON 或原始文本；如果输入为 null 或空白则返回空字符串
     */
    public static @NotNull String prettyInvokeResult(@Nullable String rawResult) {
        if (rawResult == null || rawResult.isBlank()) {
            return "";
        }

        String trimmed = rawResult.trim();
        // 尝试解析为 JSON 并美化输出
        JsonNode node = readJsonOrNull(trimmed);
        if (node != null) {
            try {
                return PRETTY_MAPPER.writeValueAsString(node);
            } catch (JsonProcessingException ignored) {
                return trimmed;
            }
        }

        // 不是 JSON，原样返回（可能是 Dubbo 返回的纯文本结果或错误信息）
        return trimmed;
    }

    /**
     * 尝试将文本解析为 JSON 树节点，解析失败则返回 null。
     *
     * <p>这是所有公开方法的底层工具方法，统一了"尝试解析 JSON"的逻辑。
     * 使用 {@link ObjectMapper#readTree(String)} 而非 {@code readValue}，
     * 因为 readTree 能处理任意 JSON 结构（对象、数组、基本类型）而无需指定目标类型。</p>
     *
     * <p>捕获所有异常（而非仅 {@link JsonProcessingException}）是为了防御
     * Jackson 在某些边界情况下可能抛出的其他运行时异常。</p>
     *
     * @param text 待解析的文本，不允许为 null
     * @return 解析成功返回 JsonNode 树；解析失败返回 null
     */
    private static @Nullable JsonNode readJsonOrNull(@NotNull String text) {
        try {
            return COMPACT_MAPPER.readTree(text);
        } catch (Exception ignored) {
            // 解析失败，返回 null 表示输入不是合法 JSON
            return null;
        }
    }
}
