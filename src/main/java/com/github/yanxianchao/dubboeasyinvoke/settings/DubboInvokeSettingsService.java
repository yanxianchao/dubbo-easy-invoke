package com.github.yanxianchao.dubboeasyinvoke.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

@Service(Service.Level.APP)
@State(name = "DubboInvokeSettings", storages = @Storage("dubbo-easy-invoke.xml"))
public final class DubboInvokeSettingsService implements PersistentStateComponent<DubboInvokeSettingsService.State> {

    public static final class State {
        public String zookeeperAddress = "127.0.0.1:2181";
    }

    private State state = new State();

    public static DubboInvokeSettingsService getInstance() {
        return ApplicationManager.getApplication().getService(DubboInvokeSettingsService.class);
    }

    @Override
    public @Nullable State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State state) {
        this.state = state;
    }

    public @NotNull String getZookeeperAddress() {
        return state.zookeeperAddress == null ? "" : state.zookeeperAddress.trim();
    }

    public void setZookeeperAddress(@Nullable String zookeeperAddress) {
        state.zookeeperAddress = zookeeperAddress == null ? "" : zookeeperAddress.trim();
    }
}
