package com.github.yanxianchao.dubboeasyinvoke.settings;

import com.intellij.openapi.options.SearchableConfigurable;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * IDEA Settings 页面（Tools > Dubbo Easy Invoke）。
 *
 * <p>这里只配置注册中心地址，业务数据（收藏等）由 {@link DubboInvokeSettingsService} 统一管理。</p>
 */
public final class DubboInvokeSettingsConfigurable implements SearchableConfigurable {

    private JPanel mainPanel;
    private JBTextField zookeeperAddressField;

    @Override
    public @NotNull String getId() {
        return "com.github.yanxianchao.dubboeasyinvoke.settings";
    }

    @Override
    public @Nls String getDisplayName() {
        return "Dubbo Easy Invoke";
    }

    @Override
    public @Nullable JComponent createComponent() {
        // 这里是插件 Settings 页面的输入框，用于保存 Zookeeper 地址。
        zookeeperAddressField = new JBTextField();
        zookeeperAddressField.putClientProperty("JTextField.placeholderText", "例如：127.0.0.1:2181 或 zk1:2181,zk2:2181");

        JBLabel tipLabel = new JBLabel("配置 Dubbo 注册中心的 Zookeeper 地址（用于加载应用和接口列表）。");
        tipLabel.setBorder(JBUI.Borders.emptyBottom(8));

        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(tipLabel)
                .addLabeledComponent("Zookeeper 地址", zookeeperAddressField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();

        reset();
        return mainPanel;
    }

    @Override
    public boolean isModified() {
        DubboInvokeSettingsService settings = DubboInvokeSettingsService.getInstance();
        String saved = settings.getZookeeperAddress();
        String current = zookeeperAddressField == null ? "" : zookeeperAddressField.getText().trim();
        return !saved.equals(current);
    }

    @Override
    public void apply() {
        DubboInvokeSettingsService.getInstance().setZookeeperAddress(zookeeperAddressField.getText());
    }

    @Override
    public void reset() {
        if (zookeeperAddressField != null) {
            zookeeperAddressField.setText(DubboInvokeSettingsService.getInstance().getZookeeperAddress());
        }
    }

    @Override
    public void disposeUIResources() {
        mainPanel = null;
        zookeeperAddressField = null;
    }
}
