package com.github.yanxianchao.dubboeasyinvoke.model;

import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * 用户收藏的一条接口记录（纯本地数据）。
 *
 * <p>注意：这里不保存 host/port，避免地址变化后收藏失效。
 * 实际调用时会基于 "应用 + service + method" 去当前最新快照里重新匹配目标地址。</p>
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>收藏记录的核心目的是让用户能快速重复调用常用接口，因此需要保存
 *       应用名、接口名、方法名以及上次使用的参数文本。</li>
 *   <li>不保存 host/port 是一个关键设计决策——Dubbo 服务的提供者地址会因
 *       扩缩容、重启等原因频繁变化，如果收藏绑定了地址，一旦地址失效收藏就无法使用。
 *       因此调用时通过 (application, serviceName, methodName) 三元组去当前
 *       {@link DiscoverySnapshot} 中动态查找可用地址。</li>
 *   <li>每条收藏有唯一 {@code id}，相等性仅基于 id 判断（业务语义上同一条收藏
 *       即使修改了名称或参数，只要 id 相同就是同一条记录）。</li>
 * </ul>
 *
 * <h3>线程安全性</h3>
 * <p>本类为不可变对象（immutable），所有字段在构造后不再修改，天然线程安全。</p>
 *
 * @see DiscoverySnapshot 调用时用于匹配最新地址的快照
 * @see DubboMethodEndpoint 匹配到的具体调用目标
 */
public final class InvokeFavorite {

    /**
     * 收藏记录的唯一标识符（通常为 UUID）。
     * <p>用于持久化存储的主键和 {@link #equals(Object)} / {@link #hashCode()} 的计算依据。</p>
     */
    private final String id;

    /**
     * 用户为这条收藏设定的自定义名称，如 "下单接口-测试参数"。
     * <p>仅用于 UI 展示，方便用户识别和区分不同的收藏条目。</p>
     */
    private final String name;

    /**
     * 目标应用名，如 "order-service"。
     * <p>与 {@link #serviceName}、{@link #methodName} 共同构成调用目标的匹配键。</p>
     */
    private final String application;

    /**
     * Dubbo 服务接口的全限定名，如 "com.example.OrderService"。
     */
    private final String serviceName;

    /**
     * 方法名，如 "createOrder"。
     */
    private final String methodName;

    /**
     * 用户上次调用时填写的参数文本（通常为 JSON 格式）。
     * <p>保存参数文本是为了让用户在下次调用时能直接复用，不必重新输入。
     * 此字段存储的是用户原始输入，不做格式化处理。</p>
     */
    private final String parameterText;

    /**
     * 收藏记录的最后更新时间戳（Unix 毫秒值）。
     * <p>用于按时间排序展示收藏列表，最近使用/修改的排在前面。</p>
     */
    private final long updatedAtMillis;

    /**
     * 构造一条完整的收藏记录。
     *
     * @param id              唯一标识符，通常为 UUID，不允许为 null
     * @param name            用户自定义的收藏名称，不允许为 null
     * @param application     目标应用名，不允许为 null
     * @param serviceName     Dubbo 服务接口全限定名，不允许为 null
     * @param methodName      方法名，不允许为 null
     * @param parameterText   调用参数文本（通常为 JSON），不允许为 null（可以是空字符串）
     * @param updatedAtMillis 最后更新时间戳（Unix 毫秒值），通常使用 {@code System.currentTimeMillis()}
     */
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

    /**
     * 获取收藏记录的唯一标识符。
     *
     * @return 唯一 ID，永不为 null
     */
    public @NotNull String getId() {
        return id;
    }

    /**
     * 获取用户自定义的收藏名称。
     *
     * @return 收藏名称，永不为 null
     */
    public @NotNull String getName() {
        return name;
    }

    /**
     * 获取目标应用名。
     *
     * @return 应用名，永不为 null
     */
    public @NotNull String getApplication() {
        return application;
    }

    /**
     * 获取 Dubbo 服务接口的全限定名。
     *
     * @return 接口全限定名，永不为 null
     */
    public @NotNull String getServiceName() {
        return serviceName;
    }

    /**
     * 获取方法名。
     *
     * @return 方法名，永不为 null
     */
    public @NotNull String getMethodName() {
        return methodName;
    }

    /**
     * 获取保存的调用参数文本。
     *
     * @return 参数文本（通常为 JSON 格式），永不为 null，可能为空字符串
     */
    public @NotNull String getParameterText() {
        return parameterText;
    }

    /**
     * 获取最后更新时间戳。
     *
     * @return Unix 毫秒时间戳
     */
    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    /**
     * 获取用于 UI 展示的接口显示名称，格式为 "接口名.方法名"。
     *
     * <p>例如 "com.example.OrderService.createOrder"，
     * 与 {@link DubboMethodEndpoint#getDisplayName()} 格式保持一致，
     * 方便在 UI 中统一展示风格。</p>
     *
     * @return 展示名称，永不为 null
     */
    public @NotNull String getInterfaceDisplayName() {
        return serviceName + "." + methodName;
    }

    /**
     * 基于 {@link #id} 判断两条收藏记录是否为同一条。
     *
     * <p>仅依据 id 判断相等性，而非比较所有字段。这意味着同一条收藏在修改
     * 名称或参数后，仍然被视为"同一条记录"，符合实体对象的语义。</p>
     *
     * @param o 待比较对象
     * @return 如果 id 相同则返回 true
     */
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

    /**
     * 基于 {@link #id} 计算哈希值，与 {@link #equals(Object)} 保持一致。
     *
     * @return 哈希值
     */
    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
