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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class ZooKeeperDubboRegistryClient {

    private static final String DUBBO_ROOT = "/dubbo";
    private static final int SESSION_TIMEOUT_MS = 6000;
    private static final int MAX_FETCH_THREADS = 24;

    private static final long MEMORY_CACHE_TTL_MS = TimeUnit.SECONDS.toMillis(45);
    private static final long DISK_CACHE_MAX_AGE_MS = TimeUnit.HOURS.toMillis(24);

    private static final ObjectMapper CACHE_MAPPER = new ObjectMapper();
    private static final Map<String, CacheEntry> MEMORY_CACHE = new ConcurrentHashMap<>();

    public @NotNull DiscoverySnapshot discover(@NotNull String zkAddress) throws Exception {
        return discover(zkAddress, false);
    }

    public @NotNull DiscoverySnapshot discover(@NotNull String zkAddress, boolean forceRefresh) throws Exception {
        return discoverWithSource(zkAddress, forceRefresh).getSnapshot();
    }

    public @NotNull DiscoverResult discoverWithSource(@NotNull String zkAddress, boolean forceRefresh) throws Exception {
        String normalizedAddress = zkAddress.trim();
        if (normalizedAddress.isEmpty()) {
            throw new IllegalArgumentException("请先配置 Zookeeper 地址");
        }

        long now = System.currentTimeMillis();

        if (!forceRefresh) {
            DiscoverySnapshot memoryHit = getMemoryCache(normalizedAddress, now);
            if (memoryHit != null) {
                return new DiscoverResult(memoryHit, DataSource.MEMORY_CACHE);
            }

            DiscoverySnapshot diskHit = readDiskCache(normalizedAddress, now, false);
            if (diskHit != null) {
                putMemoryCache(normalizedAddress, diskHit, now);
                return new DiscoverResult(diskHit, DataSource.DISK_CACHE);
            }
        }

        try {
            DiscoverySnapshot fresh = discoverFromZooKeeper(normalizedAddress);
            putMemoryCache(normalizedAddress, fresh, now);
            writeDiskCache(normalizedAddress, fresh, now);
            return new DiscoverResult(fresh, DataSource.NETWORK);
        } catch (Exception networkError) {
            DiscoverySnapshot fallback = readDiskCache(normalizedAddress, now, true);
            if (fallback != null) {
                putMemoryCache(normalizedAddress, fallback, now);
                return new DiscoverResult(fallback, DataSource.FALLBACK_DISK);
            }
            throw networkError;
        }
    }

    private @NotNull DiscoverySnapshot discoverFromZooKeeper(@NotNull String zkAddress) throws Exception {
        try (ZooKeeper zooKeeper = connect(zkAddress)) {
            List<String> services;
            try {
                services = zooKeeper.getChildren(DUBBO_ROOT, false);
            } catch (KeeperException.NoNodeException ignored) {
                return DiscoverySnapshot.empty();
            }

            if (services.isEmpty()) {
                return DiscoverySnapshot.empty();
            }

            Map<String, Map<String, DubboMethodEndpoint>> groupedByApp = new TreeMap<>();
            int workerCount = Math.max(4, Math.min(MAX_FETCH_THREADS, Runtime.getRuntime().availableProcessors() * 2));
            ExecutorService executor = Executors.newFixedThreadPool(workerCount);

            try {
                List<Future<List<DubboMethodEndpoint>>> futures = new ArrayList<>(services.size());
                for (String serviceNode : services) {
                    futures.add(executor.submit(() -> discoverEndpointsForService(zooKeeper, serviceNode)));
                }

                for (Future<List<DubboMethodEndpoint>> future : futures) {
                    List<DubboMethodEndpoint> endpoints;
                    try {
                        endpoints = future.get();
                    } catch (ExecutionException ignored) {
                        continue;
                    }

                    for (DubboMethodEndpoint endpoint : endpoints) {
                        mergeEndpoint(groupedByApp, endpoint);
                    }
                }
            } catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                throw interruptedException;
            } finally {
                executor.shutdownNow();
            }

            return buildSnapshot(groupedByApp);
        }
    }

    private @NotNull List<DubboMethodEndpoint> discoverEndpointsForService(
            @NotNull ZooKeeper zooKeeper,
            @NotNull String serviceNode
    ) {
        String providersPath = DUBBO_ROOT + "/" + serviceNode + "/providers";

        List<String> providers;
        try {
            providers = zooKeeper.getChildren(providersPath, false);
        } catch (KeeperException.NoNodeException ignored) {
            return List.of();
        } catch (Exception ignored) {
            return List.of();
        }

        List<DubboMethodEndpoint> endpoints = new ArrayList<>();
        for (String encodedProvider : providers) {
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
                        providerNode.port
                ));
            }
        }
        return endpoints;
    }

    private void mergeEndpoint(
            @NotNull Map<String, Map<String, DubboMethodEndpoint>> groupedByApp,
            @NotNull DubboMethodEndpoint endpoint
    ) {
        groupedByApp
                .computeIfAbsent(endpoint.getApplication(), key -> new LinkedHashMap<>())
                .putIfAbsent(endpoint.getDisplayName(), endpoint);
    }

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

    private @NotNull ZooKeeper connect(@NotNull String zkAddress) throws IOException, InterruptedException {
        CountDownLatch connectedLatch = new CountDownLatch(1);

        ZooKeeper zooKeeper = new ZooKeeper(zkAddress, SESSION_TIMEOUT_MS, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getState() == Event.KeeperState.SyncConnected) {
                    connectedLatch.countDown();
                }
            }
        });

        boolean connected = connectedLatch.await(SESSION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        if (!connected) {
            zooKeeper.close();
            throw new IllegalStateException("连接 Zookeeper 超时，请检查地址和网络: " + zkAddress);
        }
        return zooKeeper;
    }

    private @Nullable ProviderNode parseProviderNode(@NotNull String serviceNode, @NotNull String encodedProvider) {
        try {
            String decoded = URLDecoder.decode(encodedProvider, StandardCharsets.UTF_8);
            int queryIndex = decoded.indexOf('?');
            String addressPart = queryIndex >= 0 ? decoded.substring(0, queryIndex) : decoded;
            String queryPart = queryIndex >= 0 ? decoded.substring(queryIndex + 1) : "";

            URI uri = URI.create(addressPart);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port <= 0) {
                return null;
            }

            String serviceName = uri.getPath();
            if (serviceName == null || serviceName.isBlank() || serviceName.equals("/")) {
                serviceName = serviceNode;
            } else if (serviceName.startsWith("/")) {
                serviceName = serviceName.substring(1);
            }

            ProviderMetadata metadata = parseProviderMetadata(queryPart);
            if (metadata.methodsRaw.isBlank()) {
                return null;
            }

            List<String> methods = splitMethods(metadata.methodsRaw);
            if (methods.isEmpty()) {
                return null;
            }

            return new ProviderNode(metadata.application, serviceName, host, port, methods);
        } catch (Exception ignored) {
            return null;
        }
    }

    private @NotNull ProviderMetadata parseProviderMetadata(@NotNull String queryPart) {
        String application = null;
        String remoteApplication = null;
        String methods = null;

        if (!queryPart.isBlank()) {
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

                switch (key) {
                    case "application" -> application = value;
                    case "remote.application" -> remoteApplication = value;
                    case "methods" -> methods = value;
                    default -> {
                    }
                }
            }
        }

        return new ProviderMetadata(firstNonBlank(application, remoteApplication, "unknown"), methods == null ? "" : methods);
    }

    private @NotNull String firstNonBlank(@Nullable String first, @Nullable String second, @NotNull String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private @NotNull List<String> splitMethods(@NotNull String methodsRaw) {
        return Arrays.stream(methodsRaw.split(","))
                .map(String::trim)
                .filter(name -> !name.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    private @Nullable DiscoverySnapshot getMemoryCache(@NotNull String zkAddress, long nowMillis) {
        CacheEntry entry = MEMORY_CACHE.get(zkAddress);
        if (entry == null) {
            return null;
        }

        if (nowMillis - entry.cachedAtMillis > MEMORY_CACHE_TTL_MS) {
            MEMORY_CACHE.remove(zkAddress, entry);
            return null;
        }

        return entry.snapshot;
    }

    private void putMemoryCache(@NotNull String zkAddress, @NotNull DiscoverySnapshot snapshot, long nowMillis) {
        MEMORY_CACHE.put(zkAddress, new CacheEntry(snapshot, nowMillis));
    }

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
            if (!allowStale && age > DISK_CACHE_MAX_AGE_MS) {
                return null;
            }

            Map<String, Map<String, DubboMethodEndpoint>> groupedByApp = new TreeMap<>();
            for (CacheEndpoint endpoint : payload.endpoints) {
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
                        endpoint.port
                );
                mergeEndpoint(groupedByApp, rebuilt);
            }

            return buildSnapshot(groupedByApp);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeDiskCache(@NotNull String zkAddress, @NotNull DiscoverySnapshot snapshot, long nowMillis) {
        Path cacheFile = getCacheFile(zkAddress);
        Path parent = cacheFile.getParent();

        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }

            CachePayload payload = new CachePayload();
            payload.timestampMillis = nowMillis;
            for (String application : snapshot.getApplications()) {
                for (DubboMethodEndpoint endpoint : snapshot.getInterfacesForApp(application)) {
                    payload.endpoints.add(CacheEndpoint.fromEndpoint(endpoint));
                }
            }

            Path tempFile = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
            CACHE_MAPPER.writeValue(tempFile.toFile(), payload);

            try {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception ignored) {
                Files.move(tempFile, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
            // 缓存写入失败不影响主流程
        }
    }

    private @NotNull Path getCacheFile(@NotNull String zkAddress) {
        Path cacheDir = Paths.get(PathManager.getSystemPath(), "dubbo-easy-invoke", "cache");
        return cacheDir.resolve("zk-" + sha256(zkAddress) + ".json");
    }

    private @NotNull String sha256(@NotNull String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (Exception ignored) {
            return Integer.toHexString(text.hashCode());
        }
    }

    private static final class ProviderNode {
        private final String application;
        private final String serviceName;
        private final String host;
        private final int port;
        private final List<String> methods;

        private ProviderNode(String application, String serviceName, String host, int port, List<String> methods) {
            this.application = application;
            this.serviceName = serviceName;
            this.host = host;
            this.port = port;
            this.methods = methods;
        }
    }

    private static final class ProviderMetadata {
        private final String application;
        private final String methodsRaw;

        private ProviderMetadata(String application, String methodsRaw) {
            this.application = application;
            this.methodsRaw = methodsRaw;
        }
    }

    public enum DataSource {
        MEMORY_CACHE,
        DISK_CACHE,
        NETWORK,
        FALLBACK_DISK
    }

    public static final class DiscoverResult {
        private final DiscoverySnapshot snapshot;
        private final DataSource source;

        public DiscoverResult(@NotNull DiscoverySnapshot snapshot, @NotNull DataSource source) {
            this.snapshot = snapshot;
            this.source = source;
        }

        public @NotNull DiscoverySnapshot getSnapshot() {
            return snapshot;
        }

        public @NotNull DataSource getSource() {
            return source;
        }
    }

    private static final class CacheEntry {
        private final DiscoverySnapshot snapshot;
        private final long cachedAtMillis;

        private CacheEntry(DiscoverySnapshot snapshot, long cachedAtMillis) {
            this.snapshot = snapshot;
            this.cachedAtMillis = cachedAtMillis;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CachePayload {
        public long timestampMillis;
        public List<CacheEndpoint> endpoints = new ArrayList<>();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class CacheEndpoint {
        public String application;
        public String serviceName;
        public String methodName;
        public String host;
        public int port;

        private static @NotNull CacheEndpoint fromEndpoint(@NotNull DubboMethodEndpoint endpoint) {
            CacheEndpoint cacheEndpoint = new CacheEndpoint();
            cacheEndpoint.application = endpoint.getApplication();
            cacheEndpoint.serviceName = endpoint.getServiceName();
            cacheEndpoint.methodName = endpoint.getMethodName();
            cacheEndpoint.host = endpoint.getHost();
            cacheEndpoint.port = endpoint.getPort();
            return cacheEndpoint;
        }
    }
}
