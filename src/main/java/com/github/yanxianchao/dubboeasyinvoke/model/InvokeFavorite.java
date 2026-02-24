package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 用户收藏的一条接口记录（纯本地数据）。
 *
 * <p>注意：这里不保存 host/port，避免地址变化后收藏失效。
 * 实际调用时会基于 “应用 + service + method” 去当前最新快照里重新匹配目标地址。</p>
 */
public final class InvokeFavorite {
    private final String id;
    private final String name;
    private final String application;
    private final String serviceName;
    private final String methodName;
    private final String parameterText;
    private final long updatedAtMillis;

    public InvokeFavorite(
            @NotNull String id,
            @NotNull String name,
            @NotNull String application,
            @NotNull String serviceName,
            @NotNull String methodName,
            @NotNull String parameterText,
            long updatedAtMillis
    ) {
        this.id = id;
        this.name = name;
        this.application = application;
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.parameterText = parameterText;
        this.updatedAtMillis = updatedAtMillis;
    }

    public @NotNull String getId() {
        return id;
    }

    public @NotNull String getName() {
        return name;
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

    public @NotNull String getParameterText() {
        return parameterText;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public @NotNull String getInterfaceDisplayName() {
        return serviceName + "." + methodName;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof InvokeFavorite that)) {
            return false;
        }
        return id.equals(that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
