package com.github.yanxianchao.dubboeasyinvoke.registry;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.yanxianchao.dubboeasyinvoke.model.DiscoverySnapshot;
import com.github.yanxianchao.dubboeasyinvoke.model.DubboMethodEndpoint;
import com.intellij.openapi.application.PathManager;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dubbo 注册中心读取器（Zookeeper 实现）。
 *
 * <h2>类职责</h2>
 * <p>本类是 dubbo-easy-invoke 插件与 ZooKeeper 注册中心交互的核心入口，负责：</p>
 * <ol>
 *   <li>从 ZooKeeper 的 {@code /dubbo} 根节点下扫描所有已注册的 Dubbo provider 和 consumer 信息；</li>
 *   <li>将注册信息解析为 {@link DubboMethodEndpoint}，按应用维度聚合后组装成 {@link DiscoverySnapshot}；</li>
 *   <li>提供<b>内存缓存 + 磁盘缓存</b>双层缓存机制，加速 UI 打开速度，并在网络异常时提供降级兜底；</li>
 *   <li>支持 <b>Watch 订阅模式</b>（{@link #subscribe}），当 ZooKeeper 节点变更时自动推送最新快照给 UI 层。</li>
 * </ol>
 *
 * <h2>在项目中的角色</h2>
 * <p>本类属于 registry 层，是 IntelliJ IDEA 插件前端展示 Dubbo 服务列表的数据源。
 * UI 层通过调用 {@link #discover(String)} 获取一次性快照，或通过 {@link #subscribe} 持续监听变更。</p>
 *
 * <h2>设计思路</h2>
 * <ul>
 *   <li><b>并发拉取</b>：使用虚拟线程（{@code newVirtualThreadPerTaskExecutor}）并行拉取各 service 节点，
 *       显著降低大集群下的首屏等待时间；</li>
 *   <li><b>双层缓存</b>：内存缓存（ConcurrentHashMap，TTL 45 秒）用于同一会话内快速响应；
 *       磁盘缓存（JSON 文件，TTL 24 小时）用于重启 IDE 后冷启动加速；</li>
 *   <li><b>降级容错</b>：网络拉取失败时，允许读取过期磁盘缓存（忽略 TTL），优先保证"能用"；</li>
 *   <li><b>自动重连</b>：Watch 订阅模式下，检测到 ZK 会话过期或连接丢失时，自动发起重连并恢复所有 Watch。</li>
 * </ul>
 *
 * <h2>线程安全性</h2>
 * <p>本类是线程安全的。{@link #MEMORY_CACHE} 使用 {@link ConcurrentHashMap} 保护；
 * 订阅模式（{@link ServiceSubscription}）内部通过单线程 {@link ScheduledExecutorService} 串行化所有
 * Watch 回调和状态变更，避免并发修改 {@code endpointsByService}。</p>
 *
 * <h2>核心数据结构</h2>
 * <ul>
 *   <li>{@link ProviderNode} —— 解析后的单个 provider 节点信息（主机、端口、方法列表等）</li>
 *   <li>{@link ProviderMetadata} —— provider URL 查询参数中提取的元数据</li>
 *   <li>{@link CacheEntry} —— 内存缓存条目，携带快照和缓存时间戳</li>
 *   <li>{@link CachePayload} / {@link CacheEndpoint} —— 磁盘缓存的 JSON 序列化结构</li>
 *   <li>{@link ServiceSubscription} —— Watch 订阅会话，管理 ZK 连接、Watch 注册和重连逻辑</li>
 *   <li>{@link DiscoverResult} —— 发现结果包装，携带快照及其数据来源标记</li>
 *   <li>{@link DataSource} —— 枚举，标识快照来自内存缓存、磁盘缓存、网络还是降级磁盘缓存</li>
 * </ul>
 */
public final class ZooKeeperDubboRegistryClient {

    /**
     * ZooKeeper 中 Dubbo 服务注册的根路径，所有 Dubbo 服务都注册在此节点下
     */
    private static final String DUBBO_ROOT = "/dubbo";

    /**
     * ZooKeeper 客户端会话超时时间（毫秒），超过此时间未收到心跳则会话过期
     */
    private static final int SESSION_TIMEOUT_MS = 6000;

    /**
     * 内存缓存的存活时间（毫秒），45 秒内重复查询直接返回内存快照，避免频繁访问 ZK
     */
    private static final long MEMORY_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(45);

    /**
     * 磁盘缓存的最大有效期（毫秒），24 小时内的磁盘缓存视为有效；超过后仅在网络失败时作为降级兜底
     */
    private static final long DISK_CACHE_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24);

    /**
     * Jackson ObjectMapper 实例，专用于磁盘缓存的序列化和反序列化
     */
    private static final ObjectMapper CACHE_MAPPER = new ObjectMapper();

    /**
     * 全局内存缓存，key 为 ZooKeeper 地址（已 trim），value 为缓存条目。
     * 使用 ConcurrentHashMap 保证多线程安全。
     * 设计为 static 是因为同一个 ZK 地址的发现结果在整个 IDE 生命周期内可共享。
     */
    private static final Map<String, CacheEntry> MEMORY_CACHE = new ConcurrentHashMap<>();

    /**
     * 服务发现订阅接口，表示一个活跃的 ZooKeeper Watch 订阅会话。
     *
     * <p>调用方通过 {@link #subscribe} 获取此接口的实例，当不再需要监听时调用 {@link #close()} 释放资源。
     * 实现了 {@link AutoCloseable}，支持 try-with-resources 用法。</p>
     */
    public interface DiscoverySubscription extends AutoCloseable {
        /**
         * 获取当前订阅的服务数量。
         *
         * @return 订阅的 Dubbo 服务节点数量
         */
        int getServiceCount();

        /**
         * 关闭订阅，释放 ZooKeeper 连接和内部线程池等资源。
         * 关闭后不会再收到任何回调通知。
         */
        @Override
        void close();
    }

    /**
     * 服务发现订阅监听器，用于接收快照更新和错误通知。
     *
     * <p>由 UI 层实现此接口，注册到 {@link #subscribe} 后，
     * 当 ZooKeeper 节点发生变更导致快照更新时会回调 {@link #onSnapshotUpdated}，
     * 当发生错误（连接异常、重连等）时会回调 {@link #onError}。</p>
     */
    public interface DiscoverySubscriptionListener {
        /**
         * 快照更新回调，当 ZooKeeper 中的服务注册信息发生变化时触发。
         *
         * <p>注意：此回调在内部单线程执行器中调用，实现方如需更新 UI 应切换到 EDT 线程。</p>
         *
         * @param snapshot 最新的服务发现快照，不为 null
         */
        void onSnapshotUpdated(@NotNull DiscoverySnapshot snapshot);

        /**
         * 错误通知回调，当订阅过程中发生错误或状态变化时触发。
         *
         * <p>不是所有错误都是致命的，例如重连通知也通过此回调传递。</p>
         *
         * @param message 错误或状态描述信息
         * @param error   异常对象，可能为 null（例如状态通知场景）
         */
        void onError(@NotNull String message, @Nullable Throwable error);
    }

    /**
     * 创建一个 ZooKeeper Watch 订阅，持续监听指定 ZK 地址下的 Dubbo 服务变更。
     *
     * <p>基于 {@code baselineSnapshot} 中已有的服务节点列表建立 Watch，当任意节点的
     * providers 或 consumers 发生变化时，自动拉取最新数据并通过 {@code listener} 回调通知。
     * 同时会监听 {@code /dubbo} 根节点，发现新注册的服务时也会自动纳入订阅。</p>
     *
     * <p>内部会建立独立的 ZooKeeper 连接，支持自动重连（最多 {@value ServiceSubscription#MAX_RECONNECT_ATTEMPTS} 次）。</p>
     *
     * @param zkAddress        ZooKeeper 地址，格式如 {@code host1:port1,host2:port2}
     * @param baselineSnapshot 基线快照，用于提取初始需要监听的服务节点列表
     * @param listener         订阅监听器，接收快照更新和错误通知
     * @return 订阅句柄，调用 {@link DiscoverySubscription#close()} 可关闭订阅
     * @throws Exception                ZooKeeper 连接失败等异常
     * @throws IllegalArgumentException 如果 zkAddress 为空
     */
    public @NotNull DiscoverySubscription subscribe(
            @NotNull String zkAddress,
            @NotNull DiscoverySnapshot baselineSnapshot,
            @NotNull DiscoverySubscriptionListener listener
    ) throws Exception {
        String normalizedAddress = zkAddress.trim();
        if (normalizedAddress.isEmpty()) {
            throw new IllegalArgumentException("请先配置 Zookeeper 地址");
        }
        return new ServiceSubscription(normalizedAddress, extractServiceNodes(baselineSnapshot), baselineSnapshot, listener);
    }

    /**
     * 执行一次性服务发现，返回当前 ZooKeeper 中所有 Dubbo 服务的快照。
     *
     * <p>优先从缓存读取（内存 -> 磁盘），缓存未命中时从 ZooKeeper 网络拉取。
     * 等价于 {@code discover(zkAddress, false)}。</p>
     *
     * @param zkAddress ZooKeeper 地址
     * @return 服务发现快照
     * @throws Exception                ZooKeeper 连接或查询失败
     * @throws IllegalArgumentException 如果 zkAddress 为空
     */
    public @NotNull DiscoverySnapshot discover(@NotNull String zkAddress) throws Exception {
        return discover(zkAddress, false);
    }

    /**
     * 执行一次性服务发现，支持强制刷新。
     *
     * @param zkAddress    ZooKeeper 地址
     * @param forceRefresh 是否跳过缓存强制从 ZooKeeper 拉取
     * @return 服务发现快照
     * @throws Exception                ZooKeeper 连接或查询失败
     * @throws IllegalArgumentException 如果 zkAddress 为空
     */
    public @NotNull DiscoverySnapshot discover(@NotNull String zkAddress, boolean forceRefresh) throws Exception {
        return discoverWithSource(zkAddress, forceRefresh).getSnapshot();
    }

    /**
     * 执行一次性服务发现，返回包含数据来源标记的结果。
     *
     * <p>完整的缓存查询流程：</p>
     * <ol>
     *   <li>非强制刷新时，先查内存缓存（TTL 45 秒）；</li>
     *   <li>内存未命中，查磁盘缓存（TTL 24 小时）；</li>
     *   <li>都未命中，从 ZooKeeper 网络拉取并回填双层缓存；</li>
     *   <li>网络拉取失败时，降级读取过期磁盘缓存（忽略 TTL），保证"能用"。</li>
     * </ol>
     *
     * @param zkAddress    ZooKeeper 地址
     * @param forceRefresh 是否跳过缓存强制从 ZooKeeper 拉取
     * @return 发现结果，包含快照和数据来源
     * @throws Exception                所有缓存和网络都失败时抛出原始网络异常
     * @throws IllegalArgumentException 如果 zkAddress 为空
     */
    public @NotNull DiscoverResult discoverWithSource(@NotNull String zkAddress, boolean forceRefresh) throws Exception {
        String normalizedAddress = zkAddress.trim();
        if (normalizedAddress.isEmpty()) {
            throw new IllegalArgumentException("请先配置 Zookeeper 地址");
        }

        long now = System.currentTimeMillis();

        if (!forceRefresh) {
            // 优先读缓存：先内存，后磁盘。
            DiscoverySnapshot memoryHit = getMemoryCache(normalizedAddress, now);
            if (memoryHit != null) {
                return new DiscoverResult(memoryHit, DataSource.MEMORY_CACHE);
            }

            DiscoverySnapshot diskHit = readDiskCache(normalizedAddress, now, false);
            if (diskHit != null) {
                // 磁盘命中后回填内存缓存，后续请求可直接走内存
                putMemoryCache(normalizedAddress, diskHit, now);
                return new DiscoverResult(diskHit, DataSource.DISK_CACHE);
            }
        }

        try {
            // 缓存未命中或强制刷新时，走网络拉取。
            DiscoverySnapshot fresh = discoverFromZooKeeper(normalizedAddress);
            // 拉取成功后同时回填内存和磁盘缓存
            putMemoryCache(normalizedAddress, fresh, now);
            writeDiskCache(normalizedAddress, fresh, now);
            return new DiscoverResult(fresh, DataSource.NETWORK);
        } catch (Exception networkError) {
            // 网络失败时允许回退到过期磁盘缓存，优先保证"能用"。
            DiscoverySnapshot fallback = readDiskCache(normalizedAddress, now, true);
            if (fallback != null) {
                putMemoryCache(normalizedAddress, fallback, now);
                return new DiscoverResult(fallback, DataSource.FALLBACK_DISK);
            }
            // 缓存也没有，只能抛出原始网络异常
            throw networkError;
        }
    }

    /**
     * 从 ZooKeeper 网络拉取全量 Dubbo 服务发现数据。
     *
     * <p>流程：连接 ZK -> 获取 /dubbo 下所有子节点（即 service 列表）
     * -> 并发拉取每个 service 的 providers 和 consumers -> 聚合为快照。</p>
     *
     * <p>使用虚拟线程并发拉取各 service 节点，显著减少大集群下的首屏等待时间。
     * 单个 service 拉取失败不影响其他 service，仅跳过。</p>
     *
     * @param zkAddress ZooKeeper 地址
     * @return 完整的服务发现快照
     * @throws Exception ZooKeeper 连接或查询失败
     */
    private @NotNull DiscoverySnapshot discoverFromZooKeeper(@NotNull String zkAddress) throws Exception {
        try (ZooKeeper zooKeeper = connect(zkAddress)) {
            List<String> services;
            try {
                services = zooKeeper.getChildren(DUBBO_ROOT, false);
            } catch (KeeperException.NoNodeException ignored) {
                // /dubbo 节点不存在说明没有任何 Dubbo 服务注册
                return DiscoverySnapshot.empty();
            }

            if (services.isEmpty()) {
                return DiscoverySnapshot.empty();
            }

            // 按应用名分组，TreeMap 保证应用名有序
            Map<String, Map<String, DubboMethodEndpoint>> groupedByApp = new TreeMap<>();
            // 使用虚拟线程池并发拉取，每个 service 一个虚拟线程
            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

            try {
                // 每个 service 节点并发拉取，明显减少大集群下的首屏等待。
                List<Future<List<DubboMethodEndpoint>>> futures = new ArrayList<>(services.size());
                for (String serviceNode : services) {
                    futures.add(executor.submit(() -> discoverEndpointsForService(zooKeeper, serviceNode)));
                }

                for (Future<List<DubboMethodEndpoint>> future : futures) {
                    List<DubboMethodEndpoint> endpoints;
                    try {
                        endpoints = future.get();
                    } catch (ExecutionException ignored) {
                        // 单个 service 拉取失败不影响整体，跳过即可
                        continue;
                    }

                    for (DubboMethodEndpoint endpoint : endpoints) {
                        mergeEndpoint(groupedByApp, endpoint);
                    }
                }
            } catch (InterruptedException interruptedException) {
                // 恢复中断标志，让上层感知到中断
                Thread.currentThread().interrupt();
                throw interruptedException;
            } finally {
                // 确保虚拟线程池被关闭，避免资源泄漏
                executor.shutdownNow();
            }

            return buildSnapshot(groupedByApp);
        }
    }

    /**
     * 拉取单个 Dubbo 服务节点下的所有 provider 端点信息。
     *
     * <p>读取 {@code /dubbo/{serviceNode}/providers} 下的子节点列表，
     * 同时读取 {@code /dubbo/{serviceNode}/consumers} 获取消费者应用列表。
     * 将每个 provider URL 解析为 {@link ProviderNode}，再按方法展开为多个 {@link DubboMethodEndpoint}。</p>
     *
     * @param zooKeeper   ZooKeeper 客户端连接
     * @param serviceNode Dubbo 服务节点名称（通常是全限定接口名）
     * @return 该服务下的所有方法端点列表，可能为空
     */
    private @NotNull List<DubboMethodEndpoint> discoverEndpointsForService(
            @NotNull ZooKeeper zooKeeper,
            @NotNull String serviceNode
    ) {
        String providersPath = DUBBO_ROOT + "/" + serviceNode + "/providers";
        // 先获取消费者列表，后续会附加到每个端点上，方便 UI 展示哪些应用在调用此服务
        List<String> consumerApplications = discoverConsumerApplications(zooKeeper, serviceNode);

        List<String> providers;
        try {
            providers = zooKeeper.getChildren(providersPath, false);
        } catch (KeeperException.NoNodeException ignored) {
            // providers 节点不存在说明没有提供者注册
            return List.of();
        } catch (Exception ignored) {
            return List.of();
        }

        List<DubboMethodEndpoint> endpoints = new ArrayList<>();
        for (String encodedProvider : providers) {
            // 每个子节点是 URL 编码的 provider 注册 URL
            ProviderNode providerNode = parseProviderNode(serviceNode, encodedProvider);
            if (providerNode == null) {
                continue;
            }

            // 一个 provider 可能暴露多个方法，按方法维度展开为多个端点
            for (String method : providerNode.methods) {
                endpoints.add(new DubboMethodEndpoint(
                        providerNode.application,
                        providerNode.serviceName,
                        method,
                        providerNode.host,
                        providerNode.port,
                        providerNode.timeoutMillis,
                        providerNode.serviceVersion,
                        providerNode.dubboVersion,
                        consumerApplications
                ));
            }
        }
        return endpoints;
    }

    /**
     * 获取指定服务的所有消费者应用名列表。
     *
     * <p>读取 {@code /dubbo/{serviceNode}/consumers} 下的子节点，从每个消费者 URL 中
     * 解析出 {@code application} 参数，去重排序后返回。</p>
     *
     * @param zooKeeper   ZooKeeper 客户端连接
     * @param serviceNode Dubbo 服务节点名称
     * @return 消费者应用名列表（不区分大小写排序，去重），可能为空
     */
    private @NotNull List<String> discoverConsumerApplications(@NotNull ZooKeeper zooKeeper, @NotNull String serviceNode) {
        String consumersPath = DUBBO_ROOT + "/" + serviceNode + "/consumers";

        List<String> consumers;
        try {
            consumers = zooKeeper.getChildren(consumersPath, false);
        } catch (KeeperException.NoNodeException ignored) {
            return List.of();
        } catch (Exception ignored) {
            return List.of();
        }

        if (consumers.isEmpty()) {
            return List.of();
        }

        // 使用 TreeSet + CASE_INSENSITIVE_ORDER 实现不区分大小写的去重和排序
        TreeSet<String> applications = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (String encodedConsumer : consumers) {
            String consumerApplication = parseConsumerApplication(encodedConsumer);
            if (consumerApplication != null) {
                applications.add(consumerApplication);
            }
        }
        return applications.isEmpty() ? List.of() : List.copyOf(applications);
    }

    /**
     * 从 URL 编码的消费者节点值中解析出应用名称。
     *
     * <p>消费者注册的节点值是 URL 编码的 URI 字符串，其查询参数中包含 {@code application}
     * 或 {@code remote.application} 字段，优先取 {@code application}。</p>
     *
     * @param encodedConsumer URL 编码的消费者节点值
     * @return 消费者应用名称，解析失败返回 null
     */
    private @Nullable String parseConsumerApplication(@NotNull String encodedConsumer) {
        try {
            String decoded = URLDecoder.decode(encodedConsumer, StandardCharsets.UTF_8);
            int queryIndex = decoded.indexOf('?');
            String queryPart = queryIndex >= 0 ? decoded.substring(queryIndex + 1) : "";
            Map<String, String> queryValues = parseQueryValues(queryPart);
            // 优先取 application，其次取 remote.application
            return firstNonBlankOrNull(
                    queryValues.get("application"),
                    queryValues.get("remote.application")
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 将一个方法端点合并到按应用分组的 Map 中。
     *
     * <p>使用 {@code putIfAbsent} 策略：同一个 {@code displayName} 的端点只保留第一个出现的，
     * 避免同一方法因多个 provider 实例而重复展示。使用 {@link LinkedHashMap} 保持插入顺序。</p>
     *
     * @param groupedByApp 按应用名分组的端点集合（外层 key 是应用名，内层 key 是 displayName）
     * @param endpoint     待合并的方法端点
     */
    private void mergeEndpoint(
            @NotNull Map<String, Map<String, DubboMethodEndpoint>> groupedByApp,
            @NotNull DubboMethodEndpoint endpoint
    ) {
        groupedByApp
                .computeIfAbsent(endpoint.getApplication(), key -> new LinkedHashMap<>())
                .putIfAbsent(endpoint.getDisplayName(), endpoint);
    }

    /**
     * 将按应用分组的端点集合构建为不可变的 {@link DiscoverySnapshot}。
     *
     * <p>对每个应用下的端点列表按 displayName 不区分大小写排序，
     * 最终返回的 Map 和 List 都是不可变副本。</p>
     *
     * @param groupedByApp 按应用名分组的端点集合
     * @return 构建完成的服务发现快照
     */
    private @NotNull DiscoverySnapshot buildSnapshot(@NotNull Map<String, Map<String, DubboMethodEndpoint>> groupedByApp) {
        if (groupedByApp.isEmpty()) {
            return DiscoverySnapshot.empty();
        }

        Map<String, List<DubboMethodEndpoint>> result = new TreeMap<>();
        for (Map.Entry<String, Map<String, DubboMethodEndpoint>> entry : groupedByApp.entrySet()) {
            List<DubboMethodEndpoint> interfaces = new ArrayList<>(entry.getValue().values());
            interfaces.sort(Comparator.comparing(DubboMethodEndpoint::getDisplayName, String.CASE_INSENSITIVE_ORDER));
            result.put(entry.getKey(), List.copyOf(interfaces));
        }

        return new DiscoverySnapshot(Map.copyOf(result));
    }

    /**
     * 建立 ZooKeeper 连接（无自定义会话 Watcher）。
     *
     * @param zkAddress ZooKeeper 地址
     * @return 已连接就绪的 ZooKeeper 客户端
     * @throws IOException          连接 IO 异常
     * @throws InterruptedException 等待连接时被中断
     */
    private @NotNull ZooKeeper connect(@NotNull String zkAddress) throws IOException, InterruptedException {
        return connect(zkAddress, null);
    }

    /**
     * 建立 ZooKeeper 连接，支持自定义会话 Watcher。
     *
     * <p>使用 {@link CountDownLatch} 阻塞等待连接就绪（{@code SyncConnected} 状态），
     * 超时未连接则主动关闭客户端并抛出异常。连接建立后的会话事件会同时转发给 {@code sessionWatcher}，
     * 用于订阅模式下的会话状态监听（如过期重连）。</p>
     *
     * @param zkAddress      ZooKeeper 地址
     * @param sessionWatcher 可选的会话 Watcher，连接事件和后续会话事件都会转发给它
     * @return 已连接就绪的 ZooKeeper 客户端
     * @throws IOException           连接 IO 异常
     * @throws InterruptedException  等待连接时被中断
     * @throws IllegalStateException 连接超时
     */
    private @NotNull ZooKeeper connect(@NotNull String zkAddress, @Nullable Watcher sessionWatcher)
            throws IOException, InterruptedException {
        CountDownLatch connectedLatch = new CountDownLatch(1);

        ZooKeeper zooKeeper = new ZooKeeper(zkAddress, SESSION_TIMEOUT_MS, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                // 连接成功时释放阻塞的 CountDownLatch
                if (event.getState() == Event.KeeperState.SyncConnected) {
                    connectedLatch.countDown();
                }
                // 将事件转发给调用方提供的 sessionWatcher（用于订阅模式的会话监听）
                if (sessionWatcher != null) {
                    sessionWatcher.process(event);
                }
            }
        });

        // 阻塞等待连接就绪，超时时间与会话超时一致
        boolean connected = connectedLatch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!connected) {
            zooKeeper.close();
            throw new IllegalStateException("连接 Zookeeper 超时，请检查地址和网络: " + zkAddress);
        }
        return zooKeeper;
    }

    /**
     * 解析 URL 编码的 provider 节点值为 {@link ProviderNode} 结构。
     *
     * <p>Dubbo provider 在 ZooKeeper 中注册的节点值是 URL 编码的 URI，格式如：
     * {@code dubbo://192.168.1.1:20880/com.example.FooService?application=foo&methods=bar,baz&...}
     * 本方法解析出主机、端口、服务名、方法列表、超时时间、版本号等元数据。</p>
     *
     * @param serviceNode     服务节点名称（作为 serviceName 的兜底值）
     * @param encodedProvider URL 编码的 provider 注册 URL
     * @return 解析后的 ProviderNode，解析失败返回 null
     */
    private @Nullable ProviderNode parseProviderNode(@NotNull String serviceNode, @NotNull String encodedProvider) {
        try {
            String decoded = URLDecoder.decode(encodedProvider, StandardCharsets.UTF_8);
            int queryIndex = decoded.indexOf('?');
            String addressPart = queryIndex >= 0 ? decoded.substring(0, queryIndex) : decoded;
            String queryPart = queryIndex >= 0 ? decoded.substring(queryIndex + 1) : "";

            URI uri = URI.create(addressPart);
            String host = uri.getHost();
            int port = uri.getPort();
            // host 或 port 无效则跳过此 provider
            if (host == null || port <= 0) {
                return null;
            }

            // 从 URI 路径中提取服务名，如果路径为空则使用外层传入的 serviceNode
            String serviceName = uri.getPath();
            if (serviceName == null || serviceName.isBlank() || serviceName.equals("/")) {
                serviceName = serviceNode;
            } else if (serviceName.startsWith("/")) {
                // 去掉路径开头的斜杠
                serviceName = serviceName.substring(1);
            }

            ProviderMetadata metadata = parseProviderMetadata(queryPart);
            // 没有方法列表的 provider 无意义，跳过
            if (metadata.methodsRaw.isBlank()) {
                return null;
            }

            List<String> methods = splitMethods(metadata.methodsRaw);
            if (methods.isEmpty()) {
                return null;
            }

            return new ProviderNode(
                    metadata.application,
                    serviceName,
                    host,
                    port,
                    methods,
                    metadata.timeoutMillis,
                    metadata.serviceVersion,
                    metadata.dubboVersion
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 从 provider URL 的查询参数中提取元数据。
     *
     * <p>提取的字段包括：应用名（application / remote.application）、方法列表（methods）、
     * 超时时间（timeout）、服务版本（通过 revision/artifact/release 组合解析）、Dubbo 版本（dubbo）。</p>
     *
     * @param queryPart URL 的查询参数部分（不含 '?'）
     * @return 解析后的 ProviderMetadata
     */
    private @NotNull ProviderMetadata parseProviderMetadata(@NotNull String queryPart) {
        Map<String, String> queryValues = parseQueryValues(queryPart);
        String application = queryValues.get("application");
        String remoteApplication = queryValues.get("remote.application");
        String methods = queryValues.get("methods");
        String timeout = queryValues.get("timeout");
        String packageRevision = resolveServiceVersion(queryValues);
        String dubbo = queryValues.get("dubbo");
        String dubboVersion = dubbo == null ? "" : dubbo.trim();

        return new ProviderMetadata(
                firstNonBlank(application, remoteApplication, "unknown"),
                methods == null ? "" : methods,
                parsePositiveIntOrNull(timeout),
                packageRevision,
                dubboVersion
        );
    }

    /**
     * 从查询参数中解析服务版本号。
     *
     * <p>版本号的解析策略较为复杂，需要综合 revision、artifact、release 三个参数：</p>
     * <ol>
     *   <li>如果 artifact 和 revision 都存在，且 revision 是通用值（如 SNAPSHOT/RELEASE/UNKNOWN），
     *       则拼接为 {@code artifact-revision} 格式；</li>
     *   <li>如果 revision 已经以 artifact 开头，则直接使用 revision；</li>
     *   <li>否则优先取 revision，其次取 release，最后返回空字符串。</li>
     * </ol>
     *
     * @param queryValues 查询参数 Map
     * @return 解析后的服务版本号，可能为空字符串
     */
    private @NotNull String resolveServiceVersion(@NotNull Map<String, String> queryValues) {
        String revision = normalizeQueryValue(queryValues.get("revision"));
        String artifact = normalizeQueryValue(queryValues.get("artifact"));
        String release = normalizeQueryValue(queryValues.get("release"));

        if (artifact != null && revision != null) {
            // revision 是通用值时，需要拼上 artifact 前缀才有辨识度
            if (isGenericServiceVersion(revision)) {
                return composeArtifactRevision(artifact, revision);
            }
            // revision 已经包含 artifact 前缀则直接使用
            if (revision.startsWith(artifact + "-")) {
                return revision;
            }
        }

        if (revision != null) {
            return revision;
        }
        if (release != null) {
            return release;
        }
        return "";
    }

    /**
     * 将查询参数值标准化：trim 后如果为空则视为 null。
     *
     * @param rawValue 原始参数值，可能为 null
     * @return 标准化后的值，空白字符串返回 null
     */
    private @Nullable String normalizeQueryValue(@Nullable String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String trimmed = rawValue.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 判断版本号是否为通用/泛化值（如 SNAPSHOT、RELEASE、UNKNOWN）。
     *
     * <p>这类通用版本号缺乏辨识度，需要与 artifact 名称组合后才有意义。</p>
     *
     * @param value 版本号字符串
     * @return 如果是通用版本号返回 true
     */
    private boolean isGenericServiceVersion(@NotNull String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("snapshot")
                || normalized.equals("release")
                || normalized.equals("unknown");
    }

    /**
     * 将 artifact 名称和 revision 拼接为完整的版本标识。
     *
     * <p>如果 revision 已经以 artifact 开头，则不重复拼接；否则用 '-' 连接。</p>
     *
     * @param artifact artifact 名称
     * @param revision 版本号
     * @return 拼接后的完整版本标识
     */
    private @NotNull String composeArtifactRevision(@NotNull String artifact, @NotNull String revision) {
        String normalizedArtifact = artifact.trim();
        String normalizedRevision = revision.trim();
        if (normalizedArtifact.isEmpty()) {
            return normalizedRevision;
        }
        // 避免重复拼接，如 artifact="foo", revision="foo-1.0" 则直接返回 "foo-1.0"
        if (normalizedRevision.startsWith(normalizedArtifact + "-")) {
            return normalizedRevision;
        }
        return normalizedArtifact + "-" + normalizedRevision;
    }

    /**
     * 解析 URL 查询参数字符串为 Map。
     *
     * <p>按 '&' 分割参数对，按 '=' 分割键值。键和值都做 URL 解码。
     * 同名参数只保留第一个出现的值（{@code putIfAbsent} 语义）。
     * 使用 {@link LinkedHashMap} 保持参数出现顺序。</p>
     *
     * @param queryPart 查询参数字符串（不含 '?'）
     * @return 参数名 -> 参数值的 Map
     */
    private @NotNull Map<String, String> parseQueryValues(@NotNull String queryPart) {
        Map<String, String> values = new LinkedHashMap<>();
        if (queryPart.isBlank()) {
            return values;
        }

        String[] pairs = queryPart.split("&");
        for (String pair : pairs) {
            if (pair.isBlank()) {
                continue;
            }

            int splitIndex = pair.indexOf('=');
            String rawKey = splitIndex >= 0 ? pair.substring(0, splitIndex) : pair;
            String rawValue = splitIndex >= 0 ? pair.substring(splitIndex + 1) : "";
            String key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8);
            String value = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
            // 同名参数只保留第一个值
            if (!key.isBlank() && !values.containsKey(key)) {
                values.put(key, value);
            }
        }
        return values;
    }

    /**
     * 从多个候选值中返回第一个非空白的值，都为空白时返回 fallback。
     *
     * @param first    第一候选值
     * @param second   第二候选值
     * @param fallback 兜底默认值
     * @return 第一个非空白的值或 fallback
     */
    private @NotNull String firstNonBlank(@Nullable String first, @Nullable String second, @NotNull String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    /**
     * 从两个候选值中返回第一个非空白的值，都为空白时返回 null。
     *
     * @param first  第一候选值
     * @param second 第二候选值
     * @return 第一个非空白的值或 null
     */
    private @Nullable String firstNonBlankOrNull(@Nullable String first, @Nullable String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    /**
     * 将字符串解析为正整数，非正数或解析失败返回 null。
     *
     * <p>用于解析 timeout 等必须为正数的参数。</p>
     *
     * @param rawValue 原始字符串值
     * @return 正整数值或 null
     */
    private @Nullable Integer parsePositiveIntOrNull(@Nullable String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(rawValue.trim());
            return value > 0 ? value : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 将逗号分隔的方法名字符串拆分为去重、排序后的方法名列表。
     *
     * @param methodsRaw 逗号分隔的方法名字符串，如 "foo,bar,baz"
     * @return 去重且按不区分大小写排序的方法名列表
     */
    private @NotNull List<String> splitMethods(@NotNull String methodsRaw) {
        return Arrays.stream(methodsRaw.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /**
     * 从快照中提取所有唯一的 Dubbo 服务节点名称（即全限定接口名）。
     *
     * <p>用于订阅模式下确定需要 Watch 的服务列表。</p>
     *
     * @param snapshot 服务发现快照
     * @return 排序后的服务节点名称列表
     */
    private @NotNull List<String> extractServiceNodes(@NotNull DiscoverySnapshot snapshot) {
        Set<String> serviceNodes = new TreeSet<>();
        for (String appName : snapshot.getApplications()) {
            for (DubboMethodEndpoint endpoint : snapshot.getInterfacesForApp(appName)) {
                String serviceName = endpoint.getServiceName();
                if (serviceName != null && !serviceName.isBlank()) {
                    serviceNodes.add(serviceName);
                }
            }
        }
        return List.copyOf(serviceNodes);
    }

    // ===================== 内存缓存操作 =====================

    /**
     * 从内存缓存中获取指定 ZK 地址的快照。
     *
     * <p>如果缓存条目已超过 {@link #MEMORY_CACHE_TTL_MS}（45 秒），则移除并返回 null。
     * 使用 {@code remove(key, expectedValue)} 保证只移除当前过期的条目，避免误删其他线程刚写入的新缓存。</p>
     *
     * @param zkAddress ZooKeeper 地址
     * @param nowMillis 当前时间戳
     * @return 缓存的快照，未命中或已过期返回 null
     */
    private @Nullable DiscoverySnapshot getMemoryCache(@NotNull String zkAddress, long nowMillis) {
        CacheEntry entry = MEMORY_CACHE.get(zkAddress);
        if (entry == null) {
            return null;
        }

        if (nowMillis - entry.cachedAtMillis > MEMORY_CACHE_TTL_MS) {
            // 使用 remove(key, value) 而非 remove(key)，避免并发场景下误删其他线程刚写入的新条目
            MEMORY_CACHE.remove(zkAddress, entry);
            return null;
        }

        return entry.snapshot;
    }

    /**
     * 将快照写入内存缓存。
     *
     * <p>当缓存条目数超过 16 个时，先清理过期条目，防止内存无限增长。
     * 阈值 16 足够覆盖绝大多数使用场景（一个用户不太可能同时连接超过 16 个不同的 ZK 集群）。</p>
     *
     * @param zkAddress ZooKeeper 地址
     * @param snapshot  服务发现快照
     * @param nowMillis 当前时间戳
     */
    private void putMemoryCache(@NotNull String zkAddress, @NotNull DiscoverySnapshot snapshot, long nowMillis) {
        // 简单的容量保护：超过 16 个条目时清理过期的
        if (MEMORY_CACHE.size() > 16) {
            MEMORY_CACHE.entrySet().removeIf(entry ->
                    nowMillis - entry.getValue().cachedAtMillis > MEMORY_CACHE_TTL_MS
            );
        }
        MEMORY_CACHE.put(zkAddress, new CacheEntry(snapshot, nowMillis));
    }

    // ===================== 磁盘缓存操作 =====================

    /**
     * 从磁盘缓存文件中读取快照。
     *
     * <p>缓存文件路径由 ZK 地址的 SHA-256 哈希值决定，存储在 IDE 系统目录下。
     * 读取后会校验时间戳有效性，超过 {@link #DISK_CACHE_MAX_AGE_MS}（24 小时）的缓存默认视为过期，
     * 但 {@code allowStale=true} 时忽略过期检查（用于网络失败时的降级兜底）。</p>
     *
     * @param zkAddress  ZooKeeper 地址
     * @param nowMillis  当前时间戳
     * @param allowStale 是否允许读取过期缓存
     * @return 缓存的快照，未命中/过期/损坏返回 null
     */
    private @Nullable DiscoverySnapshot readDiskCache(
            @NotNull String zkAddress,
            long nowMillis,
            boolean allowStale
    ) {
        Path cacheFile = getCacheFile(zkAddress);
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }

        try {
            CachePayload payload = CACHE_MAPPER.readValue(cacheFile.toFile(), CachePayload.class);
            if (payload == null || payload.timestampMillis <= 0 || payload.endpoints == null) {
                return null;
            }

            long age = nowMillis - payload.timestampMillis;
            // 非降级模式下，超过 24 小时的缓存视为过期
            if (!allowStale && age > DISK_CACHE_MAX_AGE_MS) {
                return null;
            }

            // 将扁平的 CacheEndpoint 列表重新聚合为按应用分组的快照
            Map<String, Map<String, DubboMethodEndpoint>> groupedByApp = new TreeMap<>();
            for (CacheEndpoint endpoint : payload.endpoints) {
                // 跳过不完整的缓存条目
                if (endpoint == null
                        || endpoint.application == null
                        || endpoint.serviceName == null
                        || endpoint.methodName == null
                        || endpoint.host == null
                        || endpoint.port <= 0) {
                    continue;
                }

                DubboMethodEndpoint rebuilt = new DubboMethodEndpoint(
                        endpoint.application,
                        endpoint.serviceName,
                        endpoint.methodName,
                        endpoint.host,
                        endpoint.port,
                        endpoint.timeoutMillis,
                        endpoint.serviceVersion,
                        endpoint.dubboVersion,
                        endpoint.consumerApplications == null ? List.of() : endpoint.consumerApplications
                );
                mergeEndpoint(groupedByApp, rebuilt);
            }

            return buildSnapshot(groupedByApp);
        } catch (Exception ignored) {
            // 缓存文件损坏不影响主流程
            return null;
        }
    }

    /**
     * 将服务发现快照序列化写入磁盘缓存文件。
     *
     * <p>写入采用"先写临时文件再原子重命名"策略，防止写入过程中崩溃导致缓存文件损坏。
     * 如果原子移动不支持（某些文件系统），则降级为普通替换。
     * 写入完成后会清理同目录下超过 7 天的过期缓存文件。</p>
     *
     * <p>缓存写入失败不影响主流程，所有异常被静默吞掉。</p>
     *
     * @param zkAddress ZooKeeper 地址
     * @param snapshot  服务发现快照
     * @param nowMillis 当前时间戳
     */
    private void writeDiskCache(@NotNull String zkAddress, @NotNull DiscoverySnapshot snapshot, long nowMillis) {
        Path cacheFile = getCacheFile(zkAddress);
        Path parent = cacheFile.getParent();

        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }

            // 构建缓存载荷：将快照中的所有端点展开为扁平列表
            CachePayload payload = new CachePayload();
            payload.timestampMillis = nowMillis;
            for (String application : snapshot.getApplications()) {
                for (DubboMethodEndpoint endpoint : snapshot.getInterfacesForApp(application)) {
                    payload.endpoints.add(CacheEndpoint.fromEndpoint(endpoint));
                }
            }

            // 先写临时文件，再原子重命名，防止写入中途崩溃导致缓存文件损坏
            Path tempFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            CACHE_MAPPER.writeValue(tempFile.toFile(), payload);

            try {
                // 优先尝试原子移动
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                // 某些文件系统不支持原子移动，降级为普通替换
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }

            // 清理过期的缓存文件
            cleanStaleDiskCacheFiles(cacheFile, nowMillis);
        } catch (Exception ignored) {
            // 缓存写入失败不影响主流程
        }
    }

    /**
     * 清理磁盘缓存目录中超过 7 天的过期文件。
     *
     * <p>只清理 {@code zk-*.json} 和 {@code zk-*.tmp} 格式的文件，
     * 且不会删除当前正在使用的缓存文件。清理失败静默忽略。</p>
     *
     * @param currentCacheFile 当前正在使用的缓存文件（不会被清理）
     * @param nowMillis        当前时间戳
     */
    private void cleanStaleDiskCacheFiles(@NotNull Path currentCacheFile, long nowMillis) {
        Path cacheDir = currentCacheFile.getParent();
        if (cacheDir == null || !Files.isDirectory(cacheDir)) {
            return;
        }
        try (var stream = Files.list(cacheDir)) {
            stream.filter(path -> {
                        String name = path.getFileName().toString();
                        // 只清理 zk 缓存相关文件，不影响其他文件
                        return (name.startsWith("zk-") && (name.endsWith(".json") || name.endsWith(".tmp")))
                                && !path.equals(currentCacheFile);
                    })
                    .forEach(path -> {
                        try {
                            long lastModified = Files.getLastModifiedTime(path).toMillis();
                            // 超过 7 天（= DISK_CACHE_MAX_AGE_MS * 7 = 168 小时）的文件才清理
                            if (nowMillis - lastModified > DISK_CACHE_MAX_AGE_MS * 7) {
                                Files.deleteIfExists(path);
                            }
                        } catch (Exception ignored) {
                            // ignore
                        }
                    });
        } catch (Exception ignored) {
            // ignore
        }
    }

    /**
     * 根据 ZK 地址生成对应的磁盘缓存文件路径。
     *
     * <p>文件名使用 ZK 地址的 SHA-256 哈希值，避免地址中的特殊字符导致文件名非法。
     * 缓存目录位于 IDE 系统路径下的 {@code dubbo-easy-invoke/cache/} 子目录。</p>
     *
     * @param zkAddress ZooKeeper 地址
     * @return 缓存文件路径
     */
    private @NotNull Path getCacheFile(@NotNull String zkAddress) {
        Path cacheDir = Paths.get(PathManager.getSystemPath(), "dubbo-easy-invoke", "cache");
        return cacheDir.resolve("zk-" + sha256(zkAddress) + ".json");
    }

    /**
     * 计算字符串的 SHA-256 哈希值，返回十六进制表示。
     *
     * <p>如果 SHA-256 算法不可用（极罕见情况），降级使用 {@link String#hashCode()} 的十六进制值。</p>
     *
     * @param text 待哈希的字符串
     * @return 十六进制哈希值
     */
    private @NotNull String sha256(@NotNull String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ignored) {
            return Integer.toHexString(text.hashCode());
        }
    }

    // ===================== 内部数据类 =====================

    /**
     * Provider 节点解析结果，封装从 ZooKeeper provider URL 中提取的核心信息。
     *
     * <p>每个 ProviderNode 代表一个 Dubbo 服务提供者实例在注册中心的注册信息，
     * 包含网络地址（host:port）、服务接口名、暴露的方法列表以及元数据（超时、版本等）。</p>
     *
     * <p>本类为不可变值对象，线程安全。仅在 {@link #parseProviderNode} 方法中构造。</p>
     */
    private static final class ProviderNode {
        /**
         * 提供者所属的应用名称
         */
        private final String application;
        /**
         * Dubbo 服务接口全限定名
         */
        private final String serviceName;
        /**
         * 提供者主机地址（IP 或域名）
         */
        private final String host;
        /**
         * 提供者端口号
         */
        private final int port;
        /**
         * 该提供者暴露的方法名列表（已去重排序）
         */
        private final List<String> methods;
        /**
         * 超时时间（毫秒），null 表示未配置
         */
        private final Integer timeoutMillis;
        /**
         * 服务版本号（由 revision/artifact/release 组合解析得到）
         */
        private final String serviceVersion;
        /**
         * Dubbo 框架版本号
         */
        private final String dubboVersion;

        /**
         * 构造 ProviderNode。
         *
         * @param application    应用名称
         * @param serviceName    服务接口名
         * @param host           主机地址
         * @param port           端口号
         * @param methods        方法名列表
         * @param timeoutMillis  超时时间，可为 null
         * @param serviceVersion 服务版本号
         * @param dubboVersion   Dubbo 框架版本号
         */
        private ProviderNode(
                String application,
                String serviceName,
                String host,
                int port,
                List<String> methods,
                @Nullable Integer timeoutMillis,
                @NotNull String serviceVersion,
                @NotNull String dubboVersion
        ) {
            this.application = application;
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
            this.methods = methods;
            this.timeoutMillis = timeoutMillis;
            this.serviceVersion = serviceVersion;
            this.dubboVersion = dubboVersion;
        }
    }

    /**
     * Provider URL 查询参数的元数据提取结果。
     *
     * <p>从 provider 注册 URL 的查询参数（{@code ?key=value&...}）中提取出的关键元数据，
     * 作为 {@link #parseProviderMetadata} 的返回值，供 {@link #parseProviderNode} 使用。</p>
     *
     * <p>本类为不可变值对象，线程安全。</p>
     */
    private static final class ProviderMetadata {
        /**
         * 应用名称，优先取 application 参数，其次取 remote.application，兜底为 "unknown"
         */
        private final String application;
        /**
         * 原始方法名字符串（逗号分隔），空字符串表示未声明方法
         */
        private final String methodsRaw;
        /**
         * 超时时间（毫秒），null 表示未配置或值无效
         */
        private final Integer timeoutMillis;
        /**
         * 服务版本号（组合解析自 revision/artifact/release）
         */
        private final String serviceVersion;
        /**
         * Dubbo 框架版本号
         */
        private final String dubboVersion;

        /**
         * 构造 ProviderMetadata。
         *
         * @param application    应用名称
         * @param methodsRaw     原始方法名字符串
         * @param timeoutMillis  超时时间，可为 null
         * @param serviceVersion 服务版本号
         * @param dubboVersion   Dubbo 框架版本号
         */
        private ProviderMetadata(
                String application,
                String methodsRaw,
                @Nullable Integer timeoutMillis,
                @NotNull String serviceVersion,
                @NotNull String dubboVersion
        ) {
            this.application = application;
            this.methodsRaw = methodsRaw;
            this.timeoutMillis = timeoutMillis;
            this.serviceVersion = serviceVersion;
            this.dubboVersion = dubboVersion;
        }
    }

    /**
     * 将快照中的端点按服务名（接口名）重新分组。
     *
     * <p>与 {@link #mergeEndpoint} 的按应用分组不同，此方法按服务接口名分组，
     * 用于订阅模式下按服务维度管理 Watch 和增量更新。</p>
     *
     * <p>返回的 Map 中每个 List 按 displayName -> host -> port 多级排序，且为不可变副本。</p>
     *
     * @param snapshot 服务发现快照
     * @return 服务名 -> 端点列表的有序 Map（不可变）
     */
    private @NotNull Map<String, List<DubboMethodEndpoint>> groupEndpointsByService(@NotNull DiscoverySnapshot snapshot) {
        Map<String, List<DubboMethodEndpoint>> grouped = new TreeMap<>();
        for (String appName : snapshot.getApplications()) {
            for (DubboMethodEndpoint endpoint : snapshot.getInterfacesForApp(appName)) {
                grouped.computeIfAbsent(endpoint.getServiceName(), ignored -> new ArrayList<>()).add(endpoint);
            }
        }

        Map<String, List<DubboMethodEndpoint>> immutable = new TreeMap<>();
        for (Map.Entry<String, List<DubboMethodEndpoint>> entry : grouped.entrySet()) {
            List<DubboMethodEndpoint> endpoints = new ArrayList<>(entry.getValue());
            endpoints.sort(Comparator
                    .comparing(DubboMethodEndpoint::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DubboMethodEndpoint::getHost, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(DubboMethodEndpoint::getPort));
            immutable.put(entry.getKey(), List.copyOf(endpoints));
        }
        return immutable;
    }

    /**
     * 从按服务分组的端点 Map 重新构建按应用分组的 {@link DiscoverySnapshot}。
     *
     * <p>这是 {@link #groupEndpointsByService} 的逆操作，用于订阅模式下
     * 将按服务维度管理的端点数据重新转换为 UI 层需要的按应用维度的快照。</p>
     *
     * @param endpointsByService 服务名 -> 端点列表的 Map
     * @return 按应用分组的服务发现快照
     */
    private @NotNull DiscoverySnapshot buildSnapshotFromServiceEndpoints(
            @NotNull Map<String, List<DubboMethodEndpoint>> endpointsByService
    ) {
        Map<String, Map<String, DubboMethodEndpoint>> groupedByApp = new TreeMap<>();
        for (List<DubboMethodEndpoint> endpoints : endpointsByService.values()) {
            for (DubboMethodEndpoint endpoint : endpoints) {
                mergeEndpoint(groupedByApp, endpoint);
            }
        }
        return buildSnapshot(groupedByApp);
    }

    /**
     * 静默关闭 ZooKeeper 连接，忽略所有异常。
     *
     * <p>用于在重连或清理场景中安全关闭旧连接，不让异常干扰主逻辑。</p>
     *
     * @param zooKeeper 待关闭的 ZooKeeper 客户端，可为 null
     */
    private void closeQuietly(@Nullable ZooKeeper zooKeeper) {
        if (zooKeeper == null) {
            return;
        }
        try {
            zooKeeper.close();
        } catch (Exception ignored) {
            // ignore
        }
    }

    // ===================== Watch 订阅内部类 =====================

    /**
     * ZooKeeper Watch 订阅实现，管理一个长期运行的服务变更监听会话。
     *
     * <h2>类职责</h2>
     * <p>维护与 ZooKeeper 的持久连接，通过 ZK Watch 机制监听 Dubbo 服务节点变更，
     * 当 providers 或 consumers 发生增减时自动拉取最新数据并通知监听器。</p>
     *
     * <h2>设计思路</h2>
     * <ul>
     *   <li><b>单线程串行化</b>：所有 Watch 回调和状态变更都通过 {@link #watchExecutor}（单线程）
     *       串行执行，避免并发修改 {@code endpointsByService} 导致数据不一致；</li>
     *   <li><b>自动重连</b>：检测到 ZK 会话过期（{@code Expired}）或连接丢失时，自动发起重连
     *       （指数退避，最多 {@value #MAX_RECONNECT_ATTEMPTS} 次），重连成功后恢复所有 Watch；</li>
     *   <li><b>Watch 重试</b>：单个服务节点的 Watch 拉取失败时，独立重试最多 {@value #MAX_WATCH_RETRIES} 次，
     *       不影响其他服务的监听；</li>
     *   <li><b>新服务发现</b>：监听 {@code /dubbo} 根节点的子节点变更，发现新注册的服务时自动纳入订阅；</li>
     *   <li><b>变更去重</b>：每次数据变更后通过 {@link DiscoverySnapshot#equals} 比较，
     *       仅当快照真正变化时才回调监听器，避免无效通知。</li>
     * </ul>
     *
     * <h2>线程安全性</h2>
     * <p>所有状态变更通过单线程 {@link ScheduledExecutorService} 串行化执行。
     * {@link #closed} 和 {@link #reconnecting} 使用 {@link AtomicBoolean} 保护，
     * {@link #zooKeeper} 使用 volatile 保证可见性。
     * {@link #knownRootServices} 使用 {@link ConcurrentHashMap} 的 key set 保证线程安全。</p>
     *
     * <h2>ZK Watch 机制说明</h2>
     * <p>ZooKeeper 的 Watch 是一次性的——触发一次后就失效。因此每次 Watch 回调处理完后，
     * 需要在 {@code getChildren} 调用中重新注册 Watch（通过传入 Watcher 参数），
     * 形成"Watch -> 触发 -> 重新注册 Watch"的循环。</p>
     */
    private final class ServiceSubscription implements DiscoverySubscription {
        /**
         * 重连延迟时间（毫秒），每次重连尝试之间等待 2 秒
         */
        private static final long RECONNECT_DELAY_MS = 2000;
        /**
         * 最大重连尝试次数，超过后放弃自动重连，需用户手动刷新
         */
        private static final int MAX_RECONNECT_ATTEMPTS = 15;
        /**
         * 单个服务 Watch 失败后的重试延迟（毫秒）
         */
        private static final long WATCH_RETRY_DELAY_MS = 3000;
        /**
         * 单个服务 Watch 的最大重试次数
         */
        private static final int MAX_WATCH_RETRIES = 5;

        /**
         * 当前订阅的 ZooKeeper 地址
         */
        private final String zkAddress;
        /**
         * 初始需要监听的服务节点列表（不可变，从基线快照中提取）
         */
        private final List<String> serviceNodes;
        /**
         * 订阅监听器，接收快照更新和错误通知
         */
        private final DiscoverySubscriptionListener listener;

        /**
         * 按服务名维度管理的端点数据，key 是服务接口名，value 是该服务下的所有端点。
         * 所有读写操作都通过 {@link #watchExecutor} 串行化，无需额外同步。
         */
        private final Map<String, List<DubboMethodEndpoint>> endpointsByService = new TreeMap<>();

        /**
         * 单线程调度执行器，所有 Watch 回调、数据刷新、重连逻辑都在此线程中串行执行。
         * 这是本类线程安全的核心保障——避免并发修改 endpointsByService。
         * 使用守护线程，不阻止 JVM 退出。
         */
        private final ScheduledExecutorService watchExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "dubbo-easy-invoke-subscription");
            thread.setDaemon(true);
            return thread;
        });

        /**
         * 是否已关闭标志，使用 CAS 保证 close 只执行一次
         */
        private final AtomicBoolean closed = new AtomicBoolean(false);
        /**
         * 是否正在重连标志，使用 CAS 保证不会并发发起多个重连流程
         */
        private final AtomicBoolean reconnecting = new AtomicBoolean(false);
        /**
         * 当前重连尝试次数计数器
         */
        private final AtomicInteger reconnectAttempts = new AtomicInteger(0);

        /**
         * 已知的 /dubbo 根节点下的服务集合，用于检测新注册的服务。
         * 使用 ConcurrentHashMap.newKeySet() 保证线程安全的增量添加。
         */
        private final Set<String> knownRootServices = ConcurrentHashMap.newKeySet();

        /**
         * 当前 ZooKeeper 客户端连接，volatile 保证重连后新连接对其他线程可见
         */
        private volatile ZooKeeper zooKeeper;
        /**
         * 最新的服务发现快照，用于变更去重比较
         */
        private volatile DiscoverySnapshot latestSnapshot = DiscoverySnapshot.empty();

        /**
         * 构造 ServiceSubscription 并启动订阅。
         *
         * <p>初始化流程：</p>
         * <ol>
         *   <li>将 serviceNodes 去重并保持顺序（LinkedHashSet）；</li>
         *   <li>从基线快照中按服务维度初始化 {@link #endpointsByService}；</li>
         *   <li>建立 ZooKeeper 连接；</li>
         *   <li>异步触发全量刷新和根节点 Watch 注册。</li>
         * </ol>
         *
         * @param zkAddress        ZooKeeper 地址
         * @param serviceNodes     需要监听的服务节点列表
         * @param baselineSnapshot 基线快照，提供初始数据
         * @param listener         订阅监听器
         * @throws Exception ZooKeeper 连接失败
         */
        private ServiceSubscription(
                @NotNull String zkAddress,
                @NotNull List<String> serviceNodes,
                @NotNull DiscoverySnapshot baselineSnapshot,
                @NotNull DiscoverySubscriptionListener listener
        ) throws Exception {
            this.zkAddress = zkAddress;
            // 使用 LinkedHashSet 去重并保持顺序
            this.serviceNodes = List.copyOf(new LinkedHashSet<>(serviceNodes));
            this.listener = listener;

            // 从基线快照按服务维度初始化端点数据
            Map<String, List<DubboMethodEndpoint>> baselineByService = groupEndpointsByService(baselineSnapshot);
            for (String serviceNode : this.serviceNodes) {
                endpointsByService.put(serviceNode, baselineByService.getOrDefault(serviceNode, List.of()));
            }
            latestSnapshot = buildSnapshotFromServiceEndpoints(endpointsByService);

            // 没有需要监听的服务时直接退出，不建立连接
            if (this.serviceNodes.isEmpty()) {
                watchExecutor.shutdownNow();
                return;
            }

            try {
                // 建立带会话事件监听的 ZK 连接，会话过期时会触发自动重连
                this.zooKeeper = connect(this.zkAddress, this::handleSessionEvent);
            } catch (Exception ex) {
                watchExecutor.shutdownNow();
                throw ex;
            }
            // 异步触发全量刷新（注册所有 Watch）和根节点监听
            enqueue(this::refreshAllServices);
            enqueue(this::watchRootForNewServices);
        }

        /**
         * 获取当前订阅的服务数量。
         *
         * @return 初始订阅的 Dubbo 服务节点数量
         */
        @Override
        public int getServiceCount() {
            return serviceNodes.size();
        }

        /**
         * 关闭订阅，释放所有资源。
         *
         * <p>使用 CAS 保证只执行一次。关闭后会立即停止线程池并断开 ZK 连接，
         * 后续所有 Watch 回调都会通过 {@code closed.get()} 检查后直接返回。</p>
         */
        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            watchExecutor.shutdownNow();
            closeQuietly(zooKeeper);
        }

        /**
         * 处理 ZooKeeper 会话级事件。
         *
         * <p>只关注两种状态：</p>
         * <ul>
         *   <li>{@code Expired} —— 会话过期，需要建立新连接，触发重连流程；</li>
         *   <li>{@code SyncConnected} + 正在重连 —— 重连成功（连接恢复），触发全量刷新恢复 Watch。</li>
         * </ul>
         *
         * @param event ZooKeeper 会话事件
         */
        private void handleSessionEvent(@NotNull WatchedEvent event) {
            if (closed.get() || event.getType() != Watcher.Event.EventType.None) {
                return;
            }

            if (event.getState() == Watcher.Event.KeeperState.Expired) {
                // 会话过期意味着 ZK 服务端已清除所有临时节点和 Watch，必须重新连接
                scheduleReconnect("Zookeeper 会话已过期，正在自动重连...");
                return;
            }

            if (event.getState() == Watcher.Event.KeeperState.SyncConnected && reconnecting.get()) {
                // 重连成功后需要重新注册所有 Watch（因为旧会话的 Watch 已失效）
                enqueue(this::refreshAllServices);
            }
        }

        /**
         * 处理单个服务节点路径上的 Watch 事件。
         *
         * <p>当 providers 或 consumers 子节点发生变更时（{@code NodeChildrenChanged}、
         * {@code NodeCreated}、{@code NodeDeleted}、{@code NodeDataChanged}），
         * 触发对应服务的增量刷新。</p>
         *
         * <p>注意：ZK Watch 是一次性的，每次触发后会在 {@link #loadServiceEndpoints} 中
         * 通过 {@code getChildren(path, watcher)} 重新注册。</p>
         *
         * @param serviceNode 触发事件的服务节点名称
         * @param event       ZooKeeper Watch 事件
         */
        private void handleServicePathEvent(@NotNull String serviceNode, @NotNull WatchedEvent event) {
            if (closed.get()) {
                return;
            }

            // 会话级事件（type=None）交给 handleSessionEvent 处理
            if (event.getType() == Watcher.Event.EventType.None) {
                if (event.getState() == Watcher.Event.KeeperState.Expired) {
                    scheduleReconnect("订阅通道已过期，正在自动重连...");
                }
                return;
            }

            switch (event.getType()) {
                case NodeChildrenChanged, NodeCreated, NodeDeleted, NodeDataChanged ->
                        enqueue(() -> refreshOneService(serviceNode));
                default -> {
                    // ignore
                }
            }
        }

        /**
         * 全量刷新所有订阅的服务节点数据。
         *
         * <p>遍历所有服务节点，逐个拉取最新端点数据并重新注册 Watch。
         * 如果某个服务拉取失败且判断为可恢复的连接错误，则触发整体重连；
         * 否则仅对该服务进行独立重试。全部刷新完成后发布快照变更通知。</p>
         */
        private void refreshAllServices() {
            if (closed.get()) {
                return;
            }

            for (String serviceNode : serviceNodes) {
                try {
                    endpointsByService.put(serviceNode, loadServiceEndpoints(serviceNode));
                } catch (Exception ex) {
                    notifyError("订阅刷新失败(" + serviceNode + "): " + ex.getMessage(), ex);
                    if (isRecoverableConnectionError(ex)) {
                        // 连接级错误，所有 Watch 都已失效，需要整体重连
                        scheduleReconnect("订阅连接异常，正在自动重连...");
                        return;
                    } else {
                        // 非连接级错误，仅对此服务进行独立重试
                        scheduleWatchRetry(serviceNode, 1);
                    }
                }
            }
            publishSnapshotIfChanged();
        }

        /**
         * 增量刷新单个服务节点的数据。
         *
         * <p>由 Watch 回调触发，只重新拉取变化的服务节点，拉取成功后立即发布快照变更通知。</p>
         *
         * @param serviceNode 需要刷新的服务节点名称
         */
        private void refreshOneService(@NotNull String serviceNode) {
            if (closed.get()) {
                return;
            }

            try {
                endpointsByService.put(serviceNode, loadServiceEndpoints(serviceNode));
                publishSnapshotIfChanged();
            } catch (Exception ex) {
                notifyError("订阅更新失败(" + serviceNode + "): " + ex.getMessage(), ex);
                if (isRecoverableConnectionError(ex)) {
                    scheduleReconnect("订阅连接异常，正在自动重连...");
                } else {
                    scheduleWatchRetry(serviceNode, 1);
                }
            }
        }

        /**
         * 调度单个服务节点的 Watch 重试。
         *
         * <p>当某个服务的 Watch 拉取失败（非连接级错误）时，延迟 {@value #WATCH_RETRY_DELAY_MS} 毫秒
         * 后重试，最多重试 {@value #MAX_WATCH_RETRIES} 次。每次重试如果仍然失败，
         * 继续递增重试计数直到达到上限。</p>
         *
         * @param serviceNode 需要重试的服务节点名称
         * @param attempt     当前重试次数（从 1 开始）
         */
        private void scheduleWatchRetry(@NotNull String serviceNode, int attempt) {
            if (closed.get() || attempt > MAX_WATCH_RETRIES) {
                if (attempt > MAX_WATCH_RETRIES) {
                    notifyError("服务 " + serviceNode + " 监听恢复失败，已达最大重试次数", null);
                }
                return;
            }
            try {
                watchExecutor.schedule(() -> {
                    if (closed.get()) {
                        return;
                    }
                    try {
                        endpointsByService.put(serviceNode, loadServiceEndpoints(serviceNode));
                        publishSnapshotIfChanged();
                    } catch (Exception retryEx) {
                        notifyError("服务 " + serviceNode + " 监听重试失败(" + attempt + "): " + retryEx.getMessage(), retryEx);
                        if (isRecoverableConnectionError(retryEx)) {
                            scheduleReconnect("订阅连接异常，正在自动重连...");
                        } else {
                            // 递归调度下一次重试
                            scheduleWatchRetry(serviceNode, attempt + 1);
                        }
                    }
                }, WATCH_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                // 线程池已关闭时忽略拒绝异常
            }
        }

        /**
         * 在 /dubbo 根节点上注册 Watch，监听新服务的注册。
         *
         * <p>通过 {@code getChildren(/dubbo, watcher)} 获取当前所有服务节点并注册 Watch，
         * 当根节点子节点变更时触发 {@link #checkForNewServices}。
         * 初始获取的服务列表加入 {@link #knownRootServices} 作为基线。</p>
         */
        private void watchRootForNewServices() {
            if (closed.get()) {
                return;
            }
            ZooKeeper currentClient = zooKeeper;
            if (currentClient == null) {
                return;
            }
            try {
                List<String> currentServices = currentClient.getChildren(DUBBO_ROOT, event -> {
                    if (closed.get()) {
                        return;
                    }
                    if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                        enqueue(this::checkForNewServices);
                    }
                });
                // 记录已知服务，后续用于差量检测新服务
                knownRootServices.addAll(currentServices);
            } catch (Exception ex) {
                notifyError("监听根节点失败: " + ex.getMessage(), ex);
            }
        }

        /**
         * 检查 /dubbo 根节点下是否有新注册的服务。
         *
         * <p>将当前 ZK 中的服务列表与 {@link #knownRootServices} 对比，
         * 找出新增的服务节点，为其拉取端点数据并纳入订阅。
         * 同时重新注册根节点 Watch，形成持续监听循环。</p>
         */
        private void checkForNewServices() {
            if (closed.get()) {
                return;
            }
            ZooKeeper currentClient = zooKeeper;
            if (currentClient == null) {
                return;
            }
            try {
                // 重新获取服务列表并注册 Watch（ZK Watch 是一次性的，需要每次重新注册）
                List<String> latestServices = currentClient.getChildren(DUBBO_ROOT, event -> {
                    if (closed.get()) {
                        return;
                    }
                    if (event.getType() == Watcher.Event.EventType.NodeChildrenChanged) {
                        enqueue(this::checkForNewServices);
                    }
                });

                // 通过 knownRootServices.add() 的返回值判断是否为新服务
                List<String> newServices = new ArrayList<>();
                for (String svc : latestServices) {
                    // add 返回 true 说明是新增的，且不在初始订阅列表中
                    if (knownRootServices.add(svc) && !serviceNodes.contains(svc)) {
                        newServices.add(svc);
                    }
                }

                if (!newServices.isEmpty()) {
                    boolean changed = false;
                    for (String serviceNode : newServices) {
                        try {
                            List<DubboMethodEndpoint> endpoints = loadServiceEndpoints(serviceNode);
                            if (!endpoints.isEmpty()) {
                                endpointsByService.put(serviceNode, endpoints);
                                changed = true;
                            }
                        } catch (Exception ex) {
                            notifyError("加载新服务失败(" + serviceNode + "): " + ex.getMessage(), ex);
                        }
                    }
                    if (changed) {
                        publishSnapshotIfChanged();
                        notifyError("发现 " + newServices.size() + " 个新服务并已自动订阅", null);
                    }
                }
            } catch (Exception ex) {
                notifyError("检查新服务失败: " + ex.getMessage(), ex);
            }
        }

        /**
         * 加载单个服务节点的完整端点数据，并注册 Watch 监听后续变更。
         *
         * <p>读取 providers 和 consumers 两个子路径，同时在两者上注册 Watch。
         * Watch 触发后会回调 {@link #handleServicePathEvent}，从而形成持续监听循环。</p>
         *
         * <p>返回的端点列表按 displayName -> host -> port 多级排序，为不可变列表。</p>
         *
         * @param serviceNode 服务节点名称
         * @return 该服务的所有方法端点列表（不可变），可能为空
         * @throws Exception ZooKeeper 查询异常
         */
        private @NotNull List<DubboMethodEndpoint> loadServiceEndpoints(@NotNull String serviceNode) throws Exception {
            ZooKeeper currentClient = zooKeeper;
            if (currentClient == null) {
                return List.of();
            }

            // 获取消费者列表并注册 Watch
            List<String> consumerApplications = discoverConsumerApplicationsWithWatch(currentClient, serviceNode);
            String providersPath = DUBBO_ROOT + "/" + serviceNode + "/providers";
            // 获取提供者列表并注册 Watch
            List<String> providers = getChildrenWithWatch(
                    currentClient,
                    providersPath,
                    event -> handleServicePathEvent(serviceNode, event)
            );
            if (providers.isEmpty()) {
                return List.of();
            }

            // 排序以保证确定性输出
            List<String> sortedProviders = new ArrayList<>(providers);
            sortedProviders.sort(String.CASE_INSENSITIVE_ORDER);

            List<DubboMethodEndpoint> endpoints = new ArrayList<>();
            for (String encodedProvider : sortedProviders) {
                ProviderNode providerNode = parseProviderNode(serviceNode, encodedProvider);
                if (providerNode == null) {
                    continue;
                }
                for (String method : providerNode.methods) {
                    endpoints.add(new DubboMethodEndpoint(
                            providerNode.application,
                            providerNode.serviceName,
                            method,
                            providerNode.host,
                            providerNode.port,
                            providerNode.timeoutMillis,
                            providerNode.serviceVersion,
                            providerNode.dubboVersion,
                            consumerApplications
                    ));
                }
            }

            if (endpoints.isEmpty()) {
                return List.of();
            }
            // 多级排序：先按展示名，再按主机，最后按端口
            endpoints.sort(Comparator
                    .comparing(DubboMethodEndpoint::getDisplayName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(DubboMethodEndpoint::getHost, String.CASE_INSENSITIVE_ORDER)
                    .thenComparingInt(DubboMethodEndpoint::getPort));
            return List.copyOf(endpoints);
        }

        /**
         * 获取消费者应用列表并注册 Watch 监听变更。
         *
         * <p>与 {@link ZooKeeperDubboRegistryClient#discoverConsumerApplications} 功能相同，
         * 但额外在 consumers 路径上注册了 Watch，使得消费者列表变更时也能触发回调。</p>
         *
         * @param zooKeeper   ZooKeeper 客户端
         * @param serviceNode 服务节点名称
         * @return 消费者应用名列表（不区分大小写去重排序）
         * @throws Exception ZooKeeper 查询异常
         */
        private @NotNull List<String> discoverConsumerApplicationsWithWatch(
                @NotNull ZooKeeper zooKeeper,
                @NotNull String serviceNode
        ) throws Exception {
            String consumersPath = DUBBO_ROOT + "/" + serviceNode + "/consumers";
            List<String> consumers = getChildrenWithWatch(
                    zooKeeper,
                    consumersPath,
                    event -> handleServicePathEvent(serviceNode, event)
            );
            if (consumers.isEmpty()) {
                return List.of();
            }

            TreeSet<String> applications = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (String encodedConsumer : consumers) {
                String consumerApplication = parseConsumerApplication(encodedConsumer);
                if (consumerApplication != null) {
                    applications.add(consumerApplication);
                }
            }
            return applications.isEmpty() ? List.of() : List.copyOf(applications);
        }

        /**
         * 获取指定 ZK 路径的子节点列表，并注册 Watch 监听变更。
         *
         * <p>处理节点不存在（{@code NoNodeException}）的情况：
         * 当路径不存在时，通过 {@code exists(path, watcher)} 注册 Watch，
         * 这样当该路径后续被创建时 Watch 会触发，保证不会遗漏新增的节点。</p>
         *
         * @param zooKeeper ZooKeeper 客户端
         * @param path      ZK 节点路径
         * @param watcher   Watch 监听器
         * @return 子节点列表，路径不存在时返回空列表
         * @throws Exception ZooKeeper 查询异常（NoNodeException 除外）
         */
        private @NotNull List<String> getChildrenWithWatch(
                @NotNull ZooKeeper zooKeeper,
                @NotNull String path,
                @NotNull Watcher watcher
        ) throws Exception {
            try {
                return zooKeeper.getChildren(path, watcher);
            } catch (KeeperException.NoNodeException ignored) {
                // 路径不存在时，用 exists 注册 Watch，等待路径被创建时触发通知
                try {
                    zooKeeper.exists(path, watcher);
                } catch (KeeperException.NoNodeException alsoMissing) {
                    // ignore
                }
                return List.of();
            }
        }

        /**
         * 发起重连流程。
         *
         * <p>使用 CAS 保证同一时刻只有一个重连流程在进行（{@link #reconnecting}），
         * 避免多个 Watch 回调同时触发重连导致资源浪费。
         * 重连通过延迟调度 {@link #attemptReconnect} 实现。</p>
         *
         * @param statusMessage 发给监听器的状态消息
         */
        private void scheduleReconnect(@NotNull String statusMessage) {
            if (closed.get() || serviceNodes.isEmpty()) {
                return;
            }
            // CAS 保证同时只有一个重连流程在运行
            if (!reconnecting.compareAndSet(false, true)) {
                return;
            }
            reconnectAttempts.set(0);
            notifyError(statusMessage, null);
            try {
                watchExecutor.schedule(this::attemptReconnect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
            } catch (Exception ignored) {
                reconnecting.set(false);
            }
        }

        /**
         * 执行一次重连尝试。
         *
         * <p>流程：建立新 ZK 连接 -> 替换旧连接 -> 关闭旧连接 -> 全量刷新恢复 Watch。
         * 如果连接失败，延迟 {@value #RECONNECT_DELAY_MS} 毫秒后重试，
         * 最多尝试 {@value #MAX_RECONNECT_ATTEMPTS} 次。</p>
         */
        private void attemptReconnect() {
            if (closed.get()) {
                reconnecting.set(false);
                return;
            }

            int attempt = reconnectAttempts.incrementAndGet();
            if (attempt > MAX_RECONNECT_ATTEMPTS) {
                reconnecting.set(false);
                notifyError("重连 Zookeeper 已达最大重试次数(" + MAX_RECONNECT_ATTEMPTS + ")，请手动刷新", null);
                return;
            }

            try {
                // 建立新连接，带会话事件监听
                ZooKeeper nextClient = connect(zkAddress, this::handleSessionEvent);
                ZooKeeper previousClient = zooKeeper;
                // 先替换引用，再关闭旧连接，减少不可用窗口
                zooKeeper = nextClient;
                closeQuietly(previousClient);
                // 重连成功，重置状态
                reconnecting.set(false);
                reconnectAttempts.set(0);
                // 重新注册所有 Watch 并刷新数据
                refreshAllServices();
                watchRootForNewServices();
            } catch (Exception ex) {
                notifyError("重连 Zookeeper 失败(" + attempt + "/" + MAX_RECONNECT_ATTEMPTS + ")，"
                        + RECONNECT_DELAY_MS / 1000 + " 秒后重试: " + ex.getMessage(), ex);
                try {
                    // 延迟后再次尝试
                    watchExecutor.schedule(this::attemptReconnect, RECONNECT_DELAY_MS, TimeUnit.MILLISECONDS);
                } catch (Exception ignored) {
                    reconnecting.set(false);
                }
            }
        }

        /**
         * 比较当前端点数据生成的快照与上一次快照，仅在有变化时发布通知。
         *
         * <p>通过 {@link DiscoverySnapshot#equals} 进行比较，避免 Watch 触发但数据实际未变化时
         * 产生无效的回调通知（例如 ZK 节点顺序变化但内容不变的情况）。</p>
         *
         * <p>发布时同步更新内存缓存和磁盘缓存，保证缓存与订阅数据一致。</p>
         */
        private void publishSnapshotIfChanged() {
            DiscoverySnapshot nextSnapshot = buildSnapshotFromServiceEndpoints(endpointsByService);
            // 快照未变化时不发布通知，避免无效回调
            if (nextSnapshot.equals(latestSnapshot)) {
                return;
            }

            latestSnapshot = nextSnapshot;
            // 同步更新缓存，保证缓存数据与订阅数据一致
            long now = System.currentTimeMillis();
            putMemoryCache(zkAddress, nextSnapshot, now);
            writeDiskCache(zkAddress, nextSnapshot, now);

            try {
                listener.onSnapshotUpdated(nextSnapshot);
            } catch (Exception listenerError) {
                notifyError("订阅回调执行失败: " + listenerError.getMessage(), listenerError);
            }
        }

        /**
         * 安全地通知监听器发生错误，吞掉监听器自身抛出的异常。
         *
         * @param message 错误或状态描述信息
         * @param error   异常对象，可为 null
         */
        private void notifyError(@NotNull String message, @Nullable Throwable error) {
            try {
                listener.onError(message, error);
            } catch (Exception ignored) {
                // 防止监听器的异常影响内部逻辑
            }
        }

        /**
         * 将任务提交到 {@link #watchExecutor} 单线程执行器中串行执行。
         *
         * <p>所有涉及 {@link #endpointsByService} 读写的操作都通过此方法投递，
         * 利用单线程保证线程安全，避免加锁。已关闭或线程池拒绝时静默忽略。</p>
         *
         * @param task 待执行的任务
         */
        private void enqueue(@NotNull Runnable task) {
            if (closed.get()) {
                return;
            }
            try {
                watchExecutor.execute(() -> {
                    // 二次检查 closed 状态，防止关闭后队列中残留的任务继续执行
                    if (closed.get()) {
                        return;
                    }
                    task.run();
                });
            } catch (Exception ignored) {
                // 线程池已关闭（shutdownNow）时会抛出 RejectedExecutionException，静默忽略
            }
        }

        /**
         * 判断异常是否为可恢复的 ZooKeeper 连接级错误。
         *
         * <p>以下异常类型视为可恢复，触发整体重连而非单服务重试：</p>
         * <ul>
         *   <li>{@code ConnectionLossException} —— 网络中断</li>
         *   <li>{@code SessionExpiredException} —— 会话过期</li>
         *   <li>{@code SessionMovedException} —— 会话被迁移到另一个 ZK 节点</li>
         * </ul>
         * <p>会遍历异常链（cause chain）检查，因为实际异常可能被包装在外层异常中。</p>
         *
         * @param ex 待判断的异常
         * @return 如果是可恢复的连接级错误返回 true
         */
        private boolean isRecoverableConnectionError(@NotNull Exception ex) {
            if (ex instanceof KeeperException.ConnectionLossException
                    || ex instanceof KeeperException.SessionExpiredException
                    || ex instanceof KeeperException.SessionMovedException) {
                return true;
            }
            // 遍历异常链，检查被包装的根因
            Throwable cause = ex.getCause();
            while (cause != null) {
                if (cause instanceof KeeperException.ConnectionLossException
                        || cause instanceof KeeperException.SessionExpiredException
                        || cause instanceof KeeperException.SessionMovedException) {
                    return true;
                }
                cause = cause.getCause();
            }
            return false;
        }
    }

    // ===================== 公共数据类和枚举 =====================

    /**
     * 数据来源枚举，标识服务发现快照的数据来源。
     *
     * <p>用于 {@link DiscoverResult}，让调用方了解本次发现数据来自哪一层，
     * 便于 UI 层展示缓存状态提示或触发手动刷新。</p>
     */
    public enum DataSource {
        /**
         * 来自内存缓存（TTL 45 秒内的热数据）
         */
        MEMORY_CACHE,
        /**
         * 来自磁盘缓存（TTL 24 小时内的持久化数据）
         */
        DISK_CACHE,
        /**
         * 来自 ZooKeeper 网络实时拉取
         */
        NETWORK,
        /**
         * 来自降级磁盘缓存（网络失败后读取的过期缓存，忽略 TTL）
         */
        FALLBACK_DISK
    }

    /**
     * 服务发现结果包装类，封装快照及其数据来源。
     *
     * <p>相比直接返回 {@link DiscoverySnapshot}，此类额外携带 {@link DataSource} 信息，
     * 让 UI 层可以根据数据来源展示不同的提示（如"数据来自缓存，可能不是最新"）。</p>
     *
     * <p>本类为不可变对象，线程安全。</p>
     */
    public static final class DiscoverResult {
        /**
         * 服务发现快照
         */
        private final DiscoverySnapshot snapshot;
        /**
         * 数据来源标记
         */
        private final DataSource source;

        /**
         * 构造 DiscoverResult。
         *
         * @param snapshot 服务发现快照
         * @param source   数据来源
         */
        public DiscoverResult(@NotNull DiscoverySnapshot snapshot, @NotNull DataSource source) {
            this.snapshot = snapshot;
            this.source = source;
        }

        /**
         * 获取服务发现快照。
         *
         * @return 服务发现快照，不为 null
         */
        public @NotNull DiscoverySnapshot getSnapshot() {
            return snapshot;
        }

        /**
         * 获取数据来源标记。
         *
         * @return 数据来源枚举值，不为 null
         */
        public @NotNull DataSource getSource() {
            return source;
        }
    }

    /**
     * 内存缓存条目，封装快照及其缓存时间戳。
     *
     * <p>用于 {@link #MEMORY_CACHE} 中存储，通过比较 {@link #cachedAtMillis} 与当前时间
     * 判断是否过期。本类为不可变对象，线程安全。</p>
     */
    private static final class CacheEntry {
        /**
         * 缓存的服务发现快照
         */
        private final DiscoverySnapshot snapshot;
        /**
         * 缓存写入时间戳（毫秒），用于 TTL 过期判断
         */
        private final long cachedAtMillis;

        /**
         * 构造 CacheEntry。
         *
         * @param snapshot       服务发现快照
         * @param cachedAtMillis 缓存时间戳
         */
        private CacheEntry(DiscoverySnapshot snapshot, long cachedAtMillis) {
            this.snapshot = snapshot;
            this.cachedAtMillis = cachedAtMillis;
        }
    }

    /**
     * 磁盘缓存的 JSON 载荷结构，对应缓存文件的顶层 JSON 对象。
     *
     * <p>包含缓存时间戳和所有端点的扁平列表。反序列化时忽略未知字段（{@code @JsonIgnoreProperties}），
     * 保证缓存格式向前兼容——新版本添加字段不影响旧版本读取。</p>
     *
     * <p>使用 public 字段是因为 Jackson 默认通过字段访问序列化/反序列化。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CachePayload {
        /**
         * 缓存写入时间戳（毫秒），用于过期判断
         */
        public long timestampMillis;
        /**
         * 所有端点的扁平列表（跨应用、跨服务），序列化时展开存储
         */
        public List<CacheEndpoint> endpoints = new ArrayList<>();
    }

    /**
     * 磁盘缓存中单个端点的 JSON 序列化结构。
     *
     * <p>是 {@link DubboMethodEndpoint} 的序列化映射，包含方法端点的所有必要字段。
     * 提供 {@link #fromEndpoint} 工厂方法从 DubboMethodEndpoint 转换。
     * 反序列化时忽略未知字段，保证向前兼容。</p>
     *
     * <p>使用 public 字段是因为 Jackson 默认通过字段访问序列化/反序列化。</p>
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CacheEndpoint {
        /**
         * 应用名称
         */
        public String application;
        /**
         * 服务接口全限定名
         */
        public String serviceName;
        /**
         * 方法名
         */
        public String methodName;
        /**
         * 提供者主机地址
         */
        public String host;
        /**
         * 提供者端口号
         */
        public int port;
        /**
         * 超时时间（毫秒），可为 null
         */
        public Integer timeoutMillis;
        /**
         * 服务版本号
         */
        public String serviceVersion;
        /**
         * Dubbo 框架版本号
         */
        public String dubboVersion;
        /**
         * 消费者应用名列表
         */
        public List<String> consumerApplications = new ArrayList<>();

        /**
         * 从 {@link DubboMethodEndpoint} 创建缓存端点对象。
         *
         * <p>将 DubboMethodEndpoint 的所有字段复制到 CacheEndpoint 中，
         * consumerApplications 做防御性复制以避免共享引用。</p>
         *
         * @param endpoint 源端点对象
         * @return 缓存端点对象
         */
        private static @NotNull CacheEndpoint fromEndpoint(@NotNull DubboMethodEndpoint endpoint) {
            CacheEndpoint cacheEndpoint = new CacheEndpoint();
            cacheEndpoint.application = endpoint.getApplication();
            cacheEndpoint.serviceName = endpoint.getServiceName();
            cacheEndpoint.methodName = endpoint.getMethodName();
            cacheEndpoint.host = endpoint.getHost();
            cacheEndpoint.port = endpoint.getPort();
            cacheEndpoint.timeoutMillis = endpoint.getTimeoutMillis();
            cacheEndpoint.serviceVersion = endpoint.getServiceVersion();
            cacheEndpoint.dubboVersion = endpoint.getDubboVersion();
            // 防御性复制，避免与原始对象共享可变引用
            cacheEndpoint.consumerApplications = new ArrayList<>(endpoint.getConsumerApplications());
            return cacheEndpoint;
        }
    }
}
