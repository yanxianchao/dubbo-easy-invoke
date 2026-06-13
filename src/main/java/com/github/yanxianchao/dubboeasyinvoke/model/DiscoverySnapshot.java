package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一次"服务发现结果"的快照对象。
 *
 * <p>可以把它理解为：某一时刻从注册中心拿到的完整视图，
 * 结构是 "应用名 -> 这个应用下可调用的接口列表"。
 * UI 只依赖这个对象读数据，不直接读 Zookeeper，便于缓存和回放。</p>
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>将注册中心的动态数据固化为一个不可变快照，避免 UI 层在遍历过程中遭遇并发修改。</li>
 *   <li>应用列表在构造时一次性排序并缓存，后续取用无需重复排序，保证 UI 展示顺序的稳定性。</li>
 *   <li>提供静态空快照 {@link #empty()}，避免业务逻辑中出现 {@code null} 判断。</li>
 * </ul>
 *
 * <h3>线程安全性</h3>
 * <p>本类为不可变对象（immutable），所有字段在构造后不再修改，因此天然线程安全，
 * 可在多线程间自由共享而无需额外同步。</p>
 *
 * @see DubboMethodEndpoint 快照中每个可调用方法的定位信息
 */
public final class DiscoverySnapshot {

    /**
     * 全局共享的空快照单例。
     * <p>使用 {@link Collections#emptyMap()} 构造，避免每次调用 {@link #empty()} 都创建新对象。</p>
     */
    private static final DiscoverySnapshot EMPTY = new DiscoverySnapshot(Collections.emptyMap());

    /**
     * 核心数据结构：应用名 -> 该应用下所有可调用的 Dubbo 方法端点列表。
     * <p>Key 为应用名（如 "order-service"），Value 为该应用暴露的所有方法端点。</p>
     */
    private final Map<String, List<DubboMethodEndpoint>> appToInterfaces;

    /**
     * 按字典序排序后的应用名列表（缓存），用于 UI 下拉列表等有序展示场景。
     * <p>在构造函数中一次性计算并缓存，后续调用 {@link #getApplications()} 直接返回，无需重复排序。</p>
     */
    private final List<String> sortedApplications;

    /**
     * 根据给定的应用-接口映射构造快照对象。
     *
     * <p>构造过程中会对应用名进行排序并缓存排序结果，
     * 因此即使传入的 Map 无序，对外提供的应用列表也始终是有序的。</p>
     *
     * @param appToInterfaces 应用名到方法端点列表的映射，不允许为 null。
     *                        如果为空 Map 则等价于空快照。
     */
    public DiscoverySnapshot(@NotNull Map<String, List<DubboMethodEndpoint>> appToInterfaces) {
        this.appToInterfaces = appToInterfaces;
        // 空 Map 时直接返回空列表，避免不必要的 stream 操作
        this.sortedApplications = appToInterfaces.isEmpty()
                ? List.of()
                : appToInterfaces.keySet().stream().sorted().toList();
    }

    /**
     * 获取全局共享的空快照实例。
     *
     * <p>在尚未完成服务发现、或发现结果为空时，使用此方法代替 {@code null}，
     * 可以让调用方安全地调用快照上的方法而不必做空值检查。</p>
     *
     * @return 不包含任何应用和接口的空快照，始终返回同一实例
     */
    public static @NotNull DiscoverySnapshot empty() {
        return EMPTY;
    }

    /**
     * 获取按字典序排序的应用名列表。
     *
     * <p>返回的列表是不可变的，适合直接用于 UI 渲染或遍历。</p>
     *
     * @return 排序后的应用名列表；如果快照为空则返回空列表，不会返回 null
     */
    public @NotNull List<String> getApplications() {
        return sortedApplications;
    }

    /**
     * 获取指定应用下所有可调用的方法端点列表。
     *
     * @param application 应用名，不允许为 null
     * @return 该应用暴露的方法端点列表；如果应用不存在则返回空列表，不会返回 null
     */
    public @NotNull List<DubboMethodEndpoint> getInterfacesForApp(@NotNull String application) {
        return appToInterfaces.getOrDefault(application, List.of());
    }

    /**
     * 获取快照中包含的应用总数。
     *
     * @return 应用数量，>= 0
     */
    public int getApplicationCount() {
        return appToInterfaces.size();
    }

    /**
     * 基于 {@link #appToInterfaces} 的内容判断两个快照是否相同。
     *
     * <p>两个快照的相等性完全取决于其包含的应用-接口映射是否一致，
     * 这使得可以通过 equals 判断注册中心数据是否发生了变化，从而决定是否需要刷新 UI。</p>
     *
     * @param o 待比较对象
     * @return 如果两个快照包含相同的应用和接口数据则返回 true
     */
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

    /**
     * 基于 {@link #appToInterfaces} 计算哈希值，与 {@link #equals(Object)} 保持一致。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(appToInterfaces);
    }
}
