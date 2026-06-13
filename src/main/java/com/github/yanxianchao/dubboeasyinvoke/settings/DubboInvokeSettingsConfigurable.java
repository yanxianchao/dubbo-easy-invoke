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
 *
 * <h3>在项目中的角色</h3>
 * <p>本类是插件与 IDEA "Settings / Preferences" 对话框的桥梁。
 * 实现 {@link SearchableConfigurable} 接口后，IDEA 会自动在 Settings 中展示一个配置页，
 * 用户可以在这里输入或修改 Zookeeper 地址。</p>
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>UI 组件（{@link #mainPanel}、{@link #zookeeperAddressField}）在
 *       {@link #createComponent()} 时懒创建，在 {@link #disposeUIResources()} 时释放，
 *       遵循 IDEA 的生命周期管理规范，避免内存泄漏。</li>
 *   <li>配置数据的读写全部委托给 {@link DubboInvokeSettingsService}，
 *       本类只负责 UI 渲染和交互逻辑，实现了视图与数据的分离。</li>
 *   <li>IDEA 通过 {@link #isModified()} 判断 "Apply" 按钮是否可用，
 *       只有当用户实际修改了内容时才允许保存，避免无意义的写入。</li>
 * </ul>
 *
 * @see DubboInvokeSettingsService 数据持久化服务
 */
public final class DubboInvokeSettingsConfigurable implements SearchableConfigurable {

    /** 设置页面的根面板容器，包含所有 UI 组件 */
    private JPanel mainPanel;
    /** Zookeeper 地址输入框，用户在此输入注册中心地址 */
    private JBTextField zookeeperAddressField;

    /**
     * 返回此配置页的唯一标识符。
     *
     * <p>IDEA 使用该 ID 在 Settings 搜索和内部跳转时定位本配置页。
     * 必须全局唯一，这里使用插件包名作为前缀确保不与其他插件冲突。</p>
     *
     * @return 配置页的唯一 ID 字符串
     */
    @Override
    public @NotNull String getId() {
        return "com.github.yanxianchao.dubboeasyinvoke.settings";
    }

    /**
     * 返回配置页在 Settings 左侧树中显示的名称。
     *
     * <p>这是用户在 IDEA 设置界面中看到的菜单项文字。</p>
     *
     * @return 显示名称
     */
    @Override
    public @Nls String getDisplayName() {
        return "Dubbo Easy Invoke";
    }

    /**
     * 创建并返回配置页面的 UI 组件。
     *
     * <p>当用户首次打开 Settings 页面时，IDEA 框架调用此方法创建 UI。
     * 使用 {@link FormBuilder} 构建表单布局，包含提示文字和输入框。</p>
     *
     * <p>创建完成后立即调用 {@link #reset()} 从持久化服务中加载当前值到输入框。</p>
     *
     * @return 配置页面的根 Swing 组件
     */
    @Override
    public @Nullable JComponent createComponent() {
        // 这里是插件 Settings 页面的输入框，用于保存 Zookeeper 地址。
        zookeeperAddressField = new JBTextField();
        zookeeperAddressField.putClientProperty("JTextField.placeholderText", "例如：127.0.0.1:2181 或 zk1:2181,zk2:2181");

        // 在输入框上方添加说明文字，帮助用户理解该配置项的用途
        JBLabel tipLabel = new JBLabel("配置 Dubbo 注册中心的 Zookeeper 地址（用于加载应用和接口列表）。");
        tipLabel.setBorder(JBUI.Borders.emptyBottom(8));

        // 使用 FormBuilder 构建标准的 IDEA 表单布局
        mainPanel = FormBuilder.createFormBuilder()
                .addComponent(tipLabel)
                .addLabeledComponent("Zookeeper 地址", zookeeperAddressField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)  // 底部填充空白，让表单靠上对齐
                .getPanel();

        // 从持久化服务加载当前保存的值到输入框
        reset();
        return mainPanel;
    }

    /**
     * 判断用户是否修改了设置。
     *
     * <p>IDEA 框架定期调用此方法来决定 "Apply" 和 "OK" 按钮的启用状态。
     * 只有返回 true（即有未保存的修改）时，"Apply" 按钮才可点击。</p>
     *
     * <p>比较逻辑：将输入框当前文本（trim 后）与持久化服务中保存的值进行对比。</p>
     *
     * @return true 表示用户修改了内容，需要保存；false 表示无变化
     */
    @Override
    public boolean isModified() {
        DubboInvokeSettingsService settings = DubboInvokeSettingsService.getInstance();
        String saved = settings.getZookeeperAddress();
        String current = zookeeperAddressField == null ? "" : zookeeperAddressField.getText().trim();
        return !saved.equals(current);
    }

    /**
     * 将用户在 UI 上的修改保存到持久化服务。
     *
     * <p>当用户点击 "Apply" 或 "OK" 按钮时，IDEA 框架调用此方法。
     * 将输入框的文本写入 {@link DubboInvokeSettingsService}，
     * 后者会自动将数据序列化到 XML 文件完成持久化。</p>
     */
    @Override
    public void apply() {
        DubboInvokeSettingsService.getInstance().setZookeeperAddress(zookeeperAddressField.getText());
    }

    /**
     * 将 UI 重置为持久化服务中保存的值。
     *
     * <p>当用户点击 "Reset" 按钮或首次打开页面时调用，
     * 确保输入框显示的是最后一次保存的值。</p>
     */
    @Override
    public void reset() {
        if (zookeeperAddressField != null) {
            zookeeperAddressField.setText(DubboInvokeSettingsService.getInstance().getZookeeperAddress());
        }
    }

    /**
     * 释放 UI 资源。
     *
     * <p>当用户关闭 Settings 对话框时，IDEA 框架调用此方法。
     * 将 Swing 组件引用置为 null，帮助 GC 回收，防止内存泄漏。</p>
     *
     * <p>注意：下次打开 Settings 时会重新调用 {@link #createComponent()} 创建新的 UI。</p>
     */
    @Override
    public void disposeUIResources() {
        mainPanel = null;
        zookeeperAddressField = null;
    }
}
