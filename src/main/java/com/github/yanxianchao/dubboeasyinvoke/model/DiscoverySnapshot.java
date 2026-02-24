package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次“服务发现结果”的快照对象。
 *
 * <p>可以把它理解为：某一时刻从注册中心拿到的完整视图，
 * 结构是 “应用名 -> 这个应用下可调用的接口列表”。
 * UI 只依赖这个对象读数据，不直接读 Zookeeper，便于缓存和回放。</p>
 */
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
        // 对应用名排序后返回，保证 UI 每次展示顺序稳定。
        return appToInterfaces.keySet().stream().sorted().toList();
    }

    public @NotNull List<DubboMethodEndpoint> getInterfacesForApp(@NotNull String application) {
        return appToInterfaces.getOrDefault(application, List.of());
    }

    public int getApplicationCount() {
        return appToInterfaces.size();
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
