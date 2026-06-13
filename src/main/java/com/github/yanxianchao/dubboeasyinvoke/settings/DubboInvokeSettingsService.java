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
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>使用 {@link Service.Level#APP} 表明这是应用级单例，所有项目共享同一份配置。</li>
 *   <li>通过 {@link State} 注解 + {@link Storage} 注解指定持久化文件名，
 *       IDEA 会在 config 目录下自动管理该 XML 文件的读写。</li>
 *   <li>所有对收藏列表的增删改操作都加了 {@code synchronized}，保证多线程安全
 *       （例如 UI 线程和后台刷新线程同时操作时不会出现并发问题）。</li>
 *   <li>每次读取 state 前先调用 {@link #ensureStateConsistency()} 进行清洗，
 *       兼容旧版本插件遗留的脏数据或字段缺失情况。</li>
 * </ul>
 *
 * @see DubboInvokeSettingsConfigurable 插件设置页面（UI 层）
 * @see InvokeFavorite 收藏条目的不可变模型类
 */
@Service(Service.Level.APP)
@State(name = "DubboInvokeSettings", storages = @Storage("dubbo-easy-invoke.xml"))
public final class DubboInvokeSettingsService implements PersistentStateComponent<DubboInvokeSettingsService.State> {

    /**
     * 收藏保存操作的结果类型枚举。
     *
     * <p>用于告知调用方本次保存是"新建"还是"更新"了已有收藏，
     * 以便 UI 层给出不同的提示信息。</p>
     */
    public enum FavoriteSaveAction {
        /** 新建了一条收藏记录 */
        CREATED,
        /** 更新了已有的同名收藏记录 */
        UPDATED
    }

    /**
     * 收藏保存操作的返回结果，包含操作类型和最终的收藏对象。
     *
     * <p>封装为独立的结果类，避免方法返回多个值时使用 Map 或 Pair 等不明确的结构，
     * 让调用方可以通过类型安全的方式获取信息。</p>
     */
    public static final class FavoriteSaveResult {
        /** 本次保存的操作类型：新建 or 更新 */
        private final FavoriteSaveAction action;
        /** 保存后的收藏对象（包含最新的各字段值） */
        private final InvokeFavorite favorite;

        /**
         * 构造收藏保存结果。
         *
         * @param action   本次保存的操作类型（新建/更新）
         * @param favorite 保存完成后的收藏不可变对象
         */
        public FavoriteSaveResult(@NotNull FavoriteSaveAction action, @NotNull InvokeFavorite favorite) {
            this.action = action;
            this.favorite = favorite;
        }

        /**
         * 获取本次保存的操作类型。
         *
         * @return {@link FavoriteSaveAction#CREATED} 或 {@link FavoriteSaveAction#UPDATED}
         */
        public @NotNull FavoriteSaveAction getAction() {
            return action;
        }

        /**
         * 获取保存后的收藏对象。
         *
         * @return 包含所有最新字段值的不可变收藏对象
         */
        public @NotNull InvokeFavorite getFavorite() {
            return favorite;
        }
    }

    /**
     * IDEA 持久化状态的载体类（POJO）。
     *
     * <p>IDEA 的 PersistentStateComponent 机制要求 state 类的字段必须是 public 的，
     * 这样框架才能通过反射进行 XML 序列化/反序列化。</p>
     *
     * <p>该类仅用于数据存储层，不应在业务逻辑中直接传递，
     * 对外暴露时应转换为 {@link InvokeFavorite} 等不可变模型。</p>
     */
    public static final class State {
        /** Zookeeper 注册中心地址，默认指向本地，格式如 "host:port" 或 "h1:p1,h2:p2" */
        public String zookeeperAddress = "127.0.0.1:2181";
        /** 用户保存的收藏列表，每条记录对应一个 Dubbo 接口方法及其调用参数 */
        public List<FavoriteState> favorites = new ArrayList<>();
    }

    /**
     * 单条收藏记录的持久化结构。
     *
     * <p>字段全部为 public，供 IDEA XML 序列化框架使用。
     * 注意：该类仅用于存储层，对外应通过 {@link InvokeFavorite} 暴露。</p>
     */
    public static final class FavoriteState {
        /** 收藏记录的唯一标识，UUID 格式，用于精确定位某条收藏 */
        public String id;
        /** 用户自定义的收藏名称，在同一应用内唯一 */
        public String name;
        /** 收藏对应的 Dubbo 应用名（即 dubbo.application.name） */
        public String application;
        /** Dubbo 服务的全限定接口名，例如 "com.example.UserService" */
        public String serviceName;
        /** 被收藏的方法名，例如 "getUserById" */
        public String methodName;
        /** 用户填写的调用参数文本（通常为 JSON 格式） */
        public String parameterText;
        /** 最后更新时间的毫秒时间戳，用于收藏列表的排序（最近更新的排前面） */
        public long updatedAtMillis;

        // 历史版本字段：现在不再使用，但保留可避免旧配置反序列化报错。
        /** @deprecated 历史遗留字段，早期版本用于分类，当前版本不再使用，保留仅为兼容旧配置反序列化 */
        public String category;
    }

    /** 当前的持久化状态对象，IDEA 框架通过 getState/loadState 读写此字段 */
    private State state = createDefaultState();

    /**
     * 获取全局唯一的服务实例。
     *
     * <p>通过 IDEA 的 Application 级服务容器获取，确保整个 IDE 生命周期内只有一个实例。</p>
     *
     * @return 本服务的单例实例
     */
    public static DubboInvokeSettingsService getInstance() {
        return ApplicationManager.getApplication().getService(DubboInvokeSettingsService.class);
    }

    /**
     * 返回当前状态对象，供 IDEA 框架序列化到 XML。
     *
     * <p>IDEA 会在合适的时机（如 IDE 关闭、设置变更时）调用此方法，
     * 将返回的 State 对象序列化为 XML 存入 {@code dubbo-easy-invoke.xml}。</p>
     *
     * @return 当前的持久化状态对象，可能为 null（此时 IDEA 会删除已有的存储文件）
     */
    @Override
    public @Nullable State getState() {
        return state;
    }

    /**
     * IDE 启动时由框架调用，将 XML 中反序列化得到的状态加载到内存。
     *
     * <p>加载时会先经过 {@link #sanitizeState(State)} 清洗，
     * 确保旧版本遗留的脏数据不会导致运行时异常。</p>
     *
     * @param state 从 XML 反序列化得到的状态对象
     */
    @Override
    public void loadState(@NotNull State state) {
        this.state = sanitizeState(state);
    }

    /**
     * 获取当前配置的 Zookeeper 地址。
     *
     * @return 去除首尾空格后的 Zookeeper 地址字符串，不会为 null（最差返回空串）
     */
    public @NotNull String getZookeeperAddress() {
        return state.zookeeperAddress == null ? "" : state.zookeeperAddress.trim();
    }

    /**
     * 设置 Zookeeper 地址。
     *
     * <p>设置后 IDEA 会在合适时机自动持久化到 XML 文件。</p>
     *
     * @param zookeeperAddress 新的 Zookeeper 地址，可以为 null（会被当作空串处理）
     */
    public void setZookeeperAddress(@Nullable String zookeeperAddress) {
        state.zookeeperAddress = zookeeperAddress == null ? "" : zookeeperAddress.trim();
    }

    /**
     * 获取所有收藏记录，按最后更新时间降序排列。
     *
     * <p>返回的是不可变模型 {@link InvokeFavorite} 列表，外部无法直接修改内部状态，
     * 保证了数据的安全性。</p>
     *
     * @return 按更新时间倒序排列的收藏列表，不会为 null（可能为空列表）
     */
    public synchronized @NotNull List<InvokeFavorite> getFavorites() {
        ensureStateConsistency();
        return state.favorites.stream()
                .map(this::toFavorite)
                .sorted(Comparator.comparingLong(InvokeFavorite::getUpdatedAtMillis).reversed())
                .toList();
    }

    /**
     * 保存（新建或更新）一条收藏记录。
     *
     * <p>判断逻辑：在同一个应用（application）内，如果已存在同名收藏，则视为"更新"操作，
     * 覆盖原有的接口信息和参数；否则新建一条记录并分配新的 UUID。</p>
     *
     * @param favoriteName  用户指定的收藏名称，不能为空
     * @param endpoint      当前要收藏的 Dubbo 方法端点，包含应用名、接口名、方法名
     * @param parameterText 用户填写的调用参数文本（JSON），可以为 null
     * @return 包含操作类型（新建/更新）和最终收藏对象的结果
     * @throws IllegalArgumentException 如果收藏名或应用名为空
     */
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

        // 当前规则：同一个应用内，"收藏名"唯一；若重名则按"更新收藏"处理。
        FavoriteState existing = findFavoriteByNameAndApplication(normalizedName, normalizedApplication);
        FavoriteSaveAction action;
        FavoriteState target;

        if (existing == null) {
            // 不存在同名收藏，创建新记录
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
            // 已存在同名收藏，执行覆盖更新（保留原有 id，更新其余所有字段）
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

    /**
     * 重命名指定收藏的名称。
     *
     * <p>会检查新名称在同一应用内是否唯一，如果已存在同名收藏则抛出异常。</p>
     *
     * @param favoriteId      要重命名的收藏记录 ID
     * @param newFavoriteName 新的收藏名称，不能为空
     * @return 重命名后的收藏不可变对象
     * @throws IllegalArgumentException 如果 ID 或新名称为空、收藏不存在、或新名称在同应用内已被占用
     */
    public synchronized @NotNull InvokeFavorite renameFavoriteName(
            @NotNull String favoriteId,
            @NotNull String newFavoriteName
    ) {
        ensureStateConsistency();

        String normalizedId = normalizeRequiredText(favoriteId, "收藏ID不能为空");
        String normalizedNewName = normalizeRequiredText(newFavoriteName, "收藏名不能为空");

        // 第一步：根据 ID 查找目标收藏
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

        // 第二步：检查同应用内是否已存在同名收藏（排除自身）
        for (FavoriteState favorite : state.favorites) {
            if (favorite == null || favorite.id == null) {
                continue;
            }
            // 跳过自身，避免"改为原名"时误判为冲突
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

    /**
     * 根据 ID 删除一条收藏记录。
     *
     * @param favoriteId 要删除的收藏记录 ID，不能为空
     * @throws IllegalArgumentException 如果 ID 为空或对应的收藏不存在
     */
    public synchronized void deleteFavorite(@NotNull String favoriteId) {
        ensureStateConsistency();
        String normalizedId = normalizeRequiredText(favoriteId, "收藏ID不能为空");

        // 先找到目标索引，再按索引删除；避免在迭代过程中修改列表
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

    /**
     * 在每次读取或操作收藏列表前调用，确保 state 处于一致、干净的状态。
     *
     * <p>相当于"防御性编程"：即使外部因素导致 state 被污染，也能在操作前修正。</p>
     */
    private synchronized void ensureStateConsistency() {
        state = sanitizeState(state);
    }

    /**
     * 统一的状态清洗入口：对加载的 State 进行标准化处理。
     *
     * <p>处理内容包括：
     * <ul>
     *   <li>state 本身为 null 时创建默认实例</li>
     *   <li>Zookeeper 地址去除首尾空格</li>
     *   <li>遍历收藏列表，逐条清洗并过滤掉核心字段缺失的脏数据</li>
     * </ul>
     * 这样后续的业务方法就不需要反复判空，简化了主流程的代码。</p>
     *
     * @param loaded 待清洗的原始状态，可能为 null
     * @return 清洗后的合法状态对象，不会为 null
     */
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

    /**
     * 清洗单条收藏记录。
     *
     * <p>对每个字段做 trim 和空值检查。如果核心字段（name、application、serviceName、methodName）
     * 任一缺失，则返回 null 表示该条记录应被丢弃。</p>
     *
     * <p>对于可选字段（如 id），如果缺失则自动补全（生成 UUID）；
     * 对于 parameterText，缺失则设为空串。</p>
     *
     * @param favorite 待清洗的收藏记录，可能为 null
     * @return 清洗后的合法记录，核心字段缺失时返回 null
     */
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
        // id 缺失时自动生成，保证每条记录都有唯一标识
        sanitized.id = normalizeRequiredTextOrNull(favorite.id);
        if (sanitized.id == null) {
            sanitized.id = UUID.randomUUID().toString();
        }
        sanitized.name = name;
        sanitized.application = application;
        sanitized.serviceName = serviceName;
        sanitized.methodName = methodName;
        sanitized.parameterText = favorite.parameterText == null ? "" : favorite.parameterText;
        // 时间戳非法时用当前时间兜底，确保排序逻辑不会出错
        sanitized.updatedAtMillis = favorite.updatedAtMillis > 0 ? favorite.updatedAtMillis : System.currentTimeMillis();
        return sanitized;
    }

    /**
     * 将内部持久化结构 {@link FavoriteState} 转换为对外暴露的不可变模型 {@link InvokeFavorite}。
     *
     * <p>这层转换实现了存储层与业务层的解耦：
     * 内部结构可以自由调整字段（如兼容旧版），而对外接口保持稳定。</p>
     *
     * @param stateFavorite 内部持久化的收藏记录
     * @return 对外暴露的不可变收藏对象
     */
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

    /**
     * 在收藏列表中按"收藏名 + 应用名"查找已有记录（忽略大小写）。
     *
     * <p>查找规则：同一应用内收藏名唯一。该方法用于在保存时判断是"新建"还是"更新"。</p>
     *
     * @param favoriteName 收藏名称
     * @param application  应用名
     * @return 匹配的收藏记录，未找到时返回 null
     */
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

    /**
     * 创建带有默认值的初始状态对象。
     *
     * <p>用于首次安装插件或 state 为 null 时提供兜底值。</p>
     *
     * @return 包含默认 Zookeeper 地址和空收藏列表的初始状态
     */
    private static @NotNull State createDefaultState() {
        State initial = new State();
        initial.favorites = new ArrayList<>();
        return initial;
    }

    /**
     * 将文本去除首尾空格，如果结果为空则抛出异常。
     *
     * <p>用于校验必填字段，将 trim + 非空校验合并为一步，简化调用方代码。</p>
     *
     * @param text         待校验的文本
     * @param errorMessage 文本为空时抛出的异常信息
     * @return 去除首尾空格后的非空字符串
     * @throws IllegalArgumentException 如果文本为 null 或 trim 后为空
     */
    private @NotNull String normalizeRequiredText(@Nullable String text, @NotNull String errorMessage) {
        String normalized = normalizeRequiredTextOrNull(text);
        if (normalized == null) {
            throw new IllegalArgumentException(errorMessage);
        }
        return normalized;
    }

    /**
     * 将文本去除首尾空格，如果结果为空则返回 null。
     *
     * <p>与 {@link #normalizeRequiredText} 的区别在于：本方法不抛异常，
     * 适用于清洗场景中对可选字段的处理。</p>
     *
     * @param text 待处理的文本
     * @return 去除首尾空格后的非空字符串，或 null（如果原始文本为 null 或空白）
     */
    private @Nullable String normalizeRequiredTextOrNull(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
