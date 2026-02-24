package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Dubbo 单个可调用方法的定位信息。
 *
 * <p>这个对象表示“把请求打到哪里 + 调哪个方法 + 关联展示信息”：
 * 应用、接口、方法、host:port，以及解析接口时展示的超时/版本/消费方列表。</p>
 */
public final class DubboMethodEndpoint {
    private final String application;
    private final String serviceName;
    private final String methodName;
    private final String host;
    private final int port;
    private final Integer timeoutMillis;
    private final String serviceVersion;
    private final String dubboVersion;
    private final List<String> consumerApplications;

    public DubboMethodEndpoint(
            @NotNull String application,
            @NotNull String serviceName,
            @NotNull String methodName,
            @NotNull String host,
            int port
    ) {
        this(application, serviceName, methodName, host, port, null, "", List.of());
    }

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
        // 这里只做轻量归一化（trim/空值兜底），不做业务判断，避免构造器副作用过大。
        this.application = application;
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.host = host;
        this.port = port;
        this.timeoutMillis = timeoutMillis;
        this.serviceVersion = serviceVersion == null ? "" : serviceVersion.trim();
        this.dubboVersion = dubboVersion == null ? "" : dubboVersion.trim();
        this.consumerApplications = sanitizeConsumerApplications(consumerApplications);
    }

    public @NotNull String getApplication() {
        return application;
    }

    public @NotNull String getServiceName() {
        return serviceName;
    }

    public @NotNull String getMethodName() {
        return methodName;
    }

    public @NotNull String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public @Nullable Integer getTimeoutMillis() {
        return timeoutMillis;
    }

    public @NotNull String getServiceVersion() {
        return serviceVersion;
    }

    public @NotNull String getDubboVersion() {
        return dubboVersion;
    }

    public @NotNull List<String> getConsumerApplications() {
        return consumerApplications;
    }

    public @NotNull String getDisplayName() {
        return serviceName + "." + methodName;
    }

    @Override
    public String toString() {
        return getDisplayName();
    }

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
                && host.equals(that.host);
    }

    @Override
    public int hashCode() {
        return Objects.hash(application, serviceName, methodName, host, port);
    }

    private @NotNull List<String> sanitizeConsumerApplications(@Nullable List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        // 消费方列表来自注册中心，可能有空值/重复/大小写不同，这里统一清洗后再展示。
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!containsIgnoreCase(normalized, trimmed)) {
                normalized.add(trimmed);
            }
        }
        if (normalized.isEmpty()) {
            return List.of();
        }
        normalized.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(normalized);
    }

    private boolean containsIgnoreCase(@NotNull List<String> values, @NotNull String target) {
        for (String value : values) {
            if (value.equalsIgnoreCase(target)) {
                return true;
            }
        }
        return false;
    }
}
