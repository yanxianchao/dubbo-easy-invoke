package com.github.yanxianchao.dubboeasyinvoke.settings;

import com.github.yanxianchao.dubboeasyinvoke.model.DubboMethodEndpoint;
import com.github.yanxianchao.dubboeasyinvoke.model.InvokeFavorite;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 插件的本地持久化服务（Application 级单例）。
 *
 * <p>负责两类数据：
 * 1) Zookeeper 地址；
 * 2) 收藏接口与入参。
 *
 * <p>这里实现了 PersistentStateComponent，IDEA 会自动把 state 序列化到
 * {@code dubbo-easy-invoke.xml}，重启 IDE 后可恢复。</p>
 */
@Service(Service.Level.APP)
@State(name = "DubboInvokeSettings", storages = @Storage("dubbo-easy-invoke.xml"))
public final class DubboInvokeSettingsService implements PersistentStateComponent<DubboInvokeSettingsService.State> {

    public enum FavoriteSaveAction {
        CREATED,
        UPDATED
    }

    public static final class FavoriteSaveResult {
        private final FavoriteSaveAction action;
        private final InvokeFavorite favorite;

        public FavoriteSaveResult(@NotNull FavoriteSaveAction action, @NotNull InvokeFavorite favorite) {
            this.action = action;
            this.favorite = favorite;
        }

        public @NotNull FavoriteSaveAction getAction() {
            return action;
        }

        public @NotNull InvokeFavorite getFavorite() {
            return favorite;
        }
    }

    public static final class State {
        public String zookeeperAddress = "10.55.0.157:2181";
        public List<FavoriteState> favorites = new ArrayList<>();
    }

    public static final class FavoriteState {
        public String id;
        public String name;
        public String application;
        public String serviceName;
        public String methodName;
        public String parameterText;
        public long updatedAtMillis;

        // 历史版本字段：现在不再使用，但保留可避免旧配置反序列化报错。
        public String category;
    }

    private State state = createDefaultState();

    public static DubboInvokeSettingsService getInstance() {
        return ApplicationManager.getApplication().getService(DubboInvokeSettingsService.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = sanitizeState(state);
    }

    public @NotNull String getZookeeperAddress() {
        return state.zookeeperAddress == null ? "" : state.zookeeperAddress.trim();
    }

    public void setZookeeperAddress(@Nullable String zookeeperAddress) {
        state.zookeeperAddress = zookeeperAddress == null ? "" : zookeeperAddress.trim();
    }

    public synchronized @NotNull List<InvokeFavorite> getFavorites() {
        ensureStateConsistency();
        return state.favorites.stream()
                .map(this::toFavorite)
                .sorted(Comparator.comparingLong(InvokeFavorite::getUpdatedAtMillis).reversed())
                .toList();
    }

    public synchronized @NotNull FavoriteSaveResult saveFavorite(
            @NotNull String favoriteName,
            @NotNull DubboMethodEndpoint endpoint,
            @Nullable String parameterText
    ) {
        ensureStateConsistency();

        String normalizedName = normalizeRequiredText(favoriteName, "收藏名不能为空");
        String normalizedApplication = normalizeRequiredText(endpoint.getApplication(), "应用名不能为空");
        String normalizedParam = parameterText == null ? "" : parameterText;
        long nowMillis = System.currentTimeMillis();

        // 当前规则：同一个应用内，“收藏名”唯一；若重名则按“更新收藏”处理。
        FavoriteState existing = findFavoriteByNameAndApplication(normalizedName, normalizedApplication);
        FavoriteSaveAction action;
        FavoriteState target;

        if (existing == null) {
            FavoriteState created = new FavoriteState();
            created.id = UUID.randomUUID().toString();
            created.name = normalizedName;
            created.application = normalizedApplication;
            created.serviceName = endpoint.getServiceName();
            created.methodName = endpoint.getMethodName();
            created.parameterText = normalizedParam;
            created.updatedAtMillis = nowMillis;
            state.favorites.add(created);
            target = created;
            action = FavoriteSaveAction.CREATED;
        } else {
            existing.name = normalizedName;
            existing.application = normalizedApplication;
            existing.serviceName = endpoint.getServiceName();
            existing.methodName = endpoint.getMethodName();
            existing.parameterText = normalizedParam;
            existing.updatedAtMillis = nowMillis;
            target = existing;
            action = FavoriteSaveAction.UPDATED;
        }

        return new FavoriteSaveResult(action, toFavorite(target));
    }

    public synchronized @NotNull InvokeFavorite renameFavoriteName(
            @NotNull String favoriteId,
            @NotNull String newFavoriteName
    ) {
        ensureStateConsistency();

        String normalizedId = normalizeRequiredText(favoriteId, "收藏ID不能为空");
        String normalizedNewName = normalizeRequiredText(newFavoriteName, "收藏名不能为空");

        FavoriteState target = null;
        for (FavoriteState favorite : state.favorites) {
            if (favorite == null || favorite.id == null) {
                continue;
            }
            if (normalizedId.equals(favorite.id)) {
                target = favorite;
                break;
            }
        }

        if (target == null) {
            throw new IllegalArgumentException("收藏不存在，可能已被删除");
        }

        for (FavoriteState favorite : state.favorites) {
            if (favorite == null || favorite.id == null) {
                continue;
            }
            if (favorite.id.equals(target.id)) {
                continue;
            }
            if (favorite.application != null
                    && favorite.application.equalsIgnoreCase(target.application)
                    && favorite.name != null
                    && favorite.name.equalsIgnoreCase(normalizedNewName)) {
                throw new IllegalArgumentException("当前应用下已存在同名收藏: " + normalizedNewName);
            }
        }

        target.name = normalizedNewName;
        target.updatedAtMillis = System.currentTimeMillis();
        return toFavorite(target);
    }

    public synchronized void deleteFavorite(@NotNull String favoriteId) {
        ensureStateConsistency();
        String normalizedId = normalizeRequiredText(favoriteId, "收藏ID不能为空");

        int removedIndex = -1;
        for (int i = 0; i < state.favorites.size(); i++) {
            FavoriteState favorite = state.favorites.get(i);
            if (favorite != null && normalizedId.equals(favorite.id)) {
                removedIndex = i;
                break;
            }
        }

        if (removedIndex < 0) {
            throw new IllegalArgumentException("收藏不存在，可能已被删除");
        }
        state.favorites.remove(removedIndex);
    }

    private synchronized void ensureStateConsistency() {
        state = sanitizeState(state);
    }

    private @NotNull State sanitizeState(@Nullable State loaded) {
        // 统一清洗入口：任何读到的状态都先标准化，避免后续每个方法重复判空。
        State cleaned = loaded == null ? createDefaultState() : loaded;
        cleaned.zookeeperAddress = cleaned.zookeeperAddress == null ? "" : cleaned.zookeeperAddress.trim();

        List<FavoriteState> sanitizedFavorites = new ArrayList<>();
        if (cleaned.favorites != null) {
            for (FavoriteState favorite : cleaned.favorites) {
                FavoriteState sanitized = sanitizeFavorite(favorite);
                if (sanitized != null) {
                    sanitizedFavorites.add(sanitized);
                }
            }
        }

        cleaned.favorites = sanitizedFavorites;
        return cleaned;
    }

    private @Nullable FavoriteState sanitizeFavorite(@Nullable FavoriteState favorite) {
        if (favorite == null) {
            return null;
        }

        String name = normalizeRequiredTextOrNull(favorite.name);
        String application = normalizeRequiredTextOrNull(favorite.application);
        String serviceName = normalizeRequiredTextOrNull(favorite.serviceName);
        String methodName = normalizeRequiredTextOrNull(favorite.methodName);
        // 核心字段缺失则直接丢弃，防止 UI 出现无法使用的脏数据。
        if (name == null || application == null || serviceName == null || methodName == null) {
            return null;
        }

        FavoriteState sanitized = new FavoriteState();
        sanitized.id = normalizeRequiredTextOrNull(favorite.id);
        if (sanitized.id == null) {
            sanitized.id = UUID.randomUUID().toString();
        }
        sanitized.name = name;
        sanitized.application = application;
        sanitized.serviceName = serviceName;
        sanitized.methodName = methodName;
        sanitized.parameterText = favorite.parameterText == null ? "" : favorite.parameterText;
        sanitized.updatedAtMillis = favorite.updatedAtMillis > 0 ? favorite.updatedAtMillis : System.currentTimeMillis();
        return sanitized;
    }

    private @NotNull InvokeFavorite toFavorite(@NotNull FavoriteState stateFavorite) {
        return new InvokeFavorite(
                stateFavorite.id,
                stateFavorite.name,
                stateFavorite.application,
                stateFavorite.serviceName,
                stateFavorite.methodName,
                stateFavorite.parameterText,
                stateFavorite.updatedAtMillis
        );
    }

    private @Nullable FavoriteState findFavoriteByNameAndApplication(
            @NotNull String favoriteName,
            @NotNull String application
    ) {
        for (FavoriteState favorite : state.favorites) {
            if (favorite == null) {
                continue;
            }
            if (favorite.application != null
                    && application.equalsIgnoreCase(favorite.application)
                    && favorite.name != null
                    && favoriteName.equalsIgnoreCase(favorite.name)) {
                return favorite;
            }
        }
        return null;
    }

    private static @NotNull State createDefaultState() {
        State initial = new State();
        initial.favorites = new ArrayList<>();
        return initial;
    }

    private @NotNull String normalizeRequiredText(@Nullable String text, @NotNull String errorMessage) {
        String normalized = normalizeRequiredTextOrNull(text);
        if (normalized == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }

    private @Nullable String normalizeRequiredTextOrNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
