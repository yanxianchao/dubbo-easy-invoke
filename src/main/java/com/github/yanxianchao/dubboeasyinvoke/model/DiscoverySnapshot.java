package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class DiscoverySnapshot {

    private static final DiscoverySnapshot EMPTY = new DiscoverySnapshot(Collections.emptyMap());

    private final Map<String, List<DubboMethodEndpoint>> appToInterfaces;

    public DiscoverySnapshot(@NotNull Map<String, List<DubboMethodEndpoint>> appToInterfaces) {
        this.appToInterfaces = appToInterfaces;
    }

    public static @NotNull DiscoverySnapshot empty() {
        return EMPTY;
    }

    public @NotNull List<String> getApplications() {
        return appToInterfaces.keySet().stream().sorted().toList();
    }

    public @NotNull List<DubboMethodEndpoint> getInterfacesForApp(@NotNull String application) {
        return appToInterfaces.getOrDefault(application, List.of());
    }

    public int getApplicationCount() {
        return appToInterfaces.size();
    }

    public int getInterfaceCount(@NotNull String application) {
        return getInterfacesForApp(application).size();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DiscoverySnapshot that)) {
            return false;
        }
        return Objects.equals(appToInterfaces, that.appToInterfaces);
    }

    @Override
    public int hashCode() {
        return Objects.hash(appToInterfaces);
    }
}
