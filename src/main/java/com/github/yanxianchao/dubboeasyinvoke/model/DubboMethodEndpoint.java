package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.TreeSet;
import java.util.List;
import java.util.Objects;

/**
 * Dubbo 单个可调用方法的定位信息。
 *
 * <p>这个对象表示"把请求打到哪里 + 调哪个方法 + 关联展示信息"：
 * 应用、接口、方法、host:port，以及解析接口时展示的超时/版本/消费应用列表。</p>
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>本类是一个典型的值对象（Value Object），封装了从注册中心解析出的一条完整的
 *       Dubbo 方法调用地址信息。所有字段在构造后不可修改。</li>
 *   <li>提供三个构造函数，按"详细程度"递进：最简版只需要核心五元组
 *       (application, serviceName, methodName, host, port)，
 *       完整版还包含超时、服务版本、Dubbo 版本和消费者应用列表。</li>
 *   <li>消费者应用列表在构造时会自动去重、排序和清理（{@link #sanitizeConsumerApplications}），
 *       保证对外暴露的数据是干净且确定的。</li>
 * </ul>
 *
 * <h3>线程安全性</h3>
 * <p>本类为不可变对象（immutable），所有字段在构造后不再修改，天然线程安全。</p>
 *
 * @see DiscoverySnapshot 持有本类实例的快照容器
 */
public final class DubboMethodEndpoint {

    /** 所属应用名，如 "order-service"。用于在 UI 中按应用分组展示。 */
    private final String application;

    /** Dubbo 服务接口的全限定名，如 "com.example.OrderService"。 */
    private final String serviceName;

    /** 方法名，如 "createOrder"。与 serviceName 组合构成完整的调用目标。 */
    private final String methodName;

    /** 服务提供者的 IP 地址或主机名，用于建立 Telnet 连接。 */
    private final String host;

    /** 服务提供者的端口号（Dubbo 协议端口），用于建立 Telnet 连接。 */
    private final int port;

    /**
     * 服务超时时间（毫秒）。
     * <p>从注册中心的 URL 参数中解析得来，可能为 null（表示未设置或使用默认值）。
     * 主要用于 UI 展示，让用户了解调用的预期耗时上限。</p>
     */
    private final Integer timeoutMillis;

    /**
     * 服务版本号，如 "1.0.0"。
     * <p>对应 Dubbo 服务注册时的 version 参数。空字符串表示未指定版本。</p>
     */
    private final String serviceVersion;

    /**
     * Dubbo 框架版本号，如 "3.2.0"。
     * <p>从注册中心的 URL 参数中解析得来。空字符串表示未知或未提供。</p>
     */
    private final String dubboVersion;

    /**
     * 消费此服务的应用列表（已去重、已排序）。
     * <p>用于 UI 展示该接口被哪些下游应用所依赖，帮助用户了解服务的调用关系。</p>
     */
    private final List<String> consumerApplications;

    /**
     * 简易构造函数：仅包含调用所必需的核心五元组。
     *
     * <p>适用于不关心超时、版本等辅助信息的场景，如单元测试或手动构造端点。
     * 超时设为 null，版本设为空字符串，消费者列表设为空。</p>
     *
     * @param application 所属应用名，不允许为 null
     * @param serviceName 服务接口全限定名，不允许为 null
     * @param methodName  方法名，不允许为 null
     * @param host        服务提供者主机地址，不允许为 null
     * @param port        服务提供者端口号
     */
    public DubboMethodEndpoint(
            @NotNull String application,
            @NotNull String serviceName,
            @NotNull String methodName,
            @NotNull String host,
            int port
    ) {
        this(application, serviceName, methodName, host, port, null, "", List.of());
    }

    /**
     * 中间构造函数：包含核心五元组 + 超时、服务版本和消费者列表，但不含 Dubbo 版本。
     *
     * <p>适用于不需要 Dubbo 框架版本信息的场景。Dubbo 版本会被默认设为空字符串。</p>
     *
     * @param application          所属应用名，不允许为 null
     * @param serviceName          服务接口全限定名，不允许为 null
     * @param methodName           方法名，不允许为 null
     * @param host                 服务提供者主机地址，不允许为 null
     * @param port                 服务提供者端口号
     * @param timeoutMillis        超时时间（毫秒），可为 null 表示未设置
     * @param serviceVersion       服务版本号，可为 null（会被规范化为空字符串）
     * @param consumerApplications 消费者应用列表，可为 null（会被规范化为空列表）
     */
    public DubboMethodEndpoint(
            @NotNull String application,
            @NotNull String serviceName,
            @NotNull String methodName,
            @NotNull String host,
            int port,
            @Nullable Integer timeoutMillis,
            @Nullable String serviceVersion,
            @Nullable List<String> consumerApplications
    ) {
        this(application, serviceName, methodName, host, port, timeoutMillis, serviceVersion, "", consumerApplications);
    }

    /**
     * 完整构造函数：包含所有可用的端点信息。
     *
     * <p>在构造过程中会对输入参数进行规范化处理：</p>
     * <ul>
     *   <li>{@code serviceVersion} 和 {@code dubboVersion}：null 会被转为空字符串，且会去除首尾空白。</li>
     *   <li>{@code consumerApplications}：会通过 {@link #sanitizeConsumerApplications} 进行
     *       去 null、去空白、去重、排序处理。</li>
     * </ul>
     *
     * @param application          所属应用名，不允许为 null
     * @param serviceName          服务接口全限定名，不允许为 null
     * @param methodName           方法名，不允许为 null
     * @param host                 服务提供者主机地址，不允许为 null
     * @param port                 服务提供者端口号
     * @param timeoutMillis        超时时间（毫秒），可为 null 表示未设置
     * @param serviceVersion       服务版本号，可为 null（会被规范化为空字符串）
     * @param dubboVersion         Dubbo 框架版本号，可为 null（会被规范化为空字符串）
     * @param consumerApplications 消费者应用列表，可为 null（会被清理并去重）
     */
    public DubboMethodEndpoint(
            @NotNull String application,
            @NotNull String serviceName,
            @NotNull String methodName,
            @NotNull String host,
            int port,
            @Nullable Integer timeoutMillis,
            @Nullable String serviceVersion,
            @Nullable String dubboVersion,
            @Nullable List<String> consumerApplications
    ) {
        this.application = application;
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        // null 安全处理：统一转为非 null 的 trim 后字符串，简化后续使用
        this.serviceVersion = serviceVersion == null ? "" : serviceVersion.trim();
        this.dubboVersion = dubboVersion == null ? "" : dubboVersion.trim();
        // 清洗消费者列表：去 null、去空白、去重并按大小写不敏感排序
        this.consumerApplications = sanitizeConsumerApplications(consumerApplications);
    }

    /**
     * 获取所属应用名。
     *
     * @return 应用名，永不为 null
     */
    public @NotNull String getApplication() {
        return application;
    }

    /**
     * 获取 Dubbo 服务接口的全限定名。
     *
     * @return 接口全限定名，如 "com.example.OrderService"，永不为 null
     */
    public @NotNull String getServiceName() {
        return serviceName;
    }

    /**
     * 获取方法名。
     *
     * @return 方法名，如 "createOrder"，永不为 null
     */
    public @NotNull String getMethodName() {
        return methodName;
    }

    /**
     * 获取服务提供者的 IP 地址或主机名。
     *
     * @return 主机地址，永不为 null
     */
    public @NotNull String getHost() {
        return host;
    }

    /**
     * 获取服务提供者的 Dubbo 协议端口号。
     *
     * @return 端口号
     */
    public int getPort() {
        return port;
    }

    /**
     * 获取服务超时时间。
     *
     * @return 超时时间（毫秒），可能为 null 表示未设置
     */
    public @Nullable Integer getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 获取服务版本号。
     *
     * @return 服务版本号，空字符串表示未指定版本，永不为 null
     */
    public @NotNull String getServiceVersion() {
        return serviceVersion;
    }

    /**
     * 获取 Dubbo 框架版本号。
     *
     * @return Dubbo 版本号，空字符串表示未知，永不为 null
     */
    public @NotNull String getDubboVersion() {
        return dubboVersion;
    }

    /**
     * 获取消费此服务的应用列表。
     *
     * <p>返回的列表已去重且按大小写不敏感的字典序排列，是不可变列表。</p>
     *
     * @return 消费者应用名列表，永不为 null，可能为空列表
     */
    public @NotNull List<String> getConsumerApplications() {
        return consumerApplications;
    }

    /**
     * 获取用于 UI 展示的名称，格式为 "接口名.方法名"。
     *
     * <p>例如 "com.example.OrderService.createOrder"，方便用户快速识别调用目标。</p>
     *
     * @return 展示名称，永不为 null
     */
    public @NotNull String getDisplayName() {
        return serviceName + "." + methodName;
    }

    /**
     * 返回与 {@link #getDisplayName()} 一致的字符串表示，便于日志和调试。
     *
     * @return "接口名.方法名" 格式的字符串
     */
    @Override
    public String toString() {
        return getDisplayName();
    }

    /**
     * 基于所有字段判断两个端点是否完全相同。
     *
     * <p>所有字段（包括超时、版本、消费者列表）都参与比较，
     * 确保语义上完全等价的端点才被视为相等。</p>
     *
     * @param o 待比较对象
     * @return 所有字段均相等时返回 true
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DubboMethodEndpoint that)) {
            return false;
        }
        return port == that.port
                && application.equals(that.application)
                && serviceName.equals(that.serviceName)
                && methodName.equals(that.methodName)
                && host.equals(that.host)
                && Objects.equals(timeoutMillis, that.timeoutMillis)
                && serviceVersion.equals(that.serviceVersion)
                && dubboVersion.equals(that.dubboVersion)
                && consumerApplications.equals(that.consumerApplications);
    }

    /**
     * 基于所有字段计算哈希值，与 {@link #equals(Object)} 保持一致。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(application, serviceName, methodName, host, port,
                timeoutMillis, serviceVersion, dubboVersion, consumerApplications);
    }

    /**
     * 清洗消费者应用列表：去除 null 元素、去除空白项、去重并按大小写不敏感排序。
     *
     * <p>使用 {@link TreeSet} 配合 {@link String#CASE_INSENSITIVE_ORDER} 比较器，
     * 同时实现去重和排序两个目标。选择大小写不敏感是因为应用名在不同注册中心
     * 可能有大小写差异（如 "OrderApp" 和 "orderapp" 应视为同一应用）。</p>
     *
     * @param values 原始消费者应用列表，可能为 null 或包含 null/空白元素
     * @return 清洗后的不可变列表；如果输入为 null 或清洗后无有效元素则返回空列表
     */
    private @NotNull List<String> sanitizeConsumerApplications(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        // 使用 TreeSet + CASE_INSENSITIVE_ORDER 实现大小写不敏感去重 + 自然排序
        TreeSet<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isEmpty()) {
                seen.add(trimmed);
            }
        }
        // 使用 List.copyOf 返回不可变列表，防止外部修改
        return seen.isEmpty() ? List.of() : List.copyOf(seen);
    }
}
