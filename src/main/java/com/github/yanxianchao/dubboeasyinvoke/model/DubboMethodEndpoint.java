package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

public final class DubboMethodEndpoint {
    private final String application;
    private final String serviceName;
    private final String methodName;
    private final String host;
    private final int port;

    public DubboMethodEndpoint(
            @NotNull String application,
            @NotNull String serviceName,
            @NotNull String methodName,
            @NotNull String host,
            int port
    ) {
        this.application = application;
        this.serviceName = serviceName;
        this.methodName = methodName;
        this.host = host;
        this.port = port;
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
}
