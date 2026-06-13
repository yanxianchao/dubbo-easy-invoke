package com.github.yanxianchao.dubboeasyinvoke.ui;

import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * "收藏当前接口"弹窗。
 *
 * <p>只保留一个必填项"收藏名"，减少用户操作步骤。</p>
 *
 * <h3>在项目中的角色</h3>
 * <p>当用户在工具窗口中点击"收藏"按钮时弹出此对话框，
 * 让用户为当前选择的 Dubbo 接口方法指定一个收藏名称。
 * 确认后由调用方将收藏信息写入 {@link com.github.yanxianchao.dubboeasyinvoke.settings.DubboInvokeSettingsService}。</p>
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>继承 {@link DialogWrapper}，复用 IDEA 平台提供的标准对话框能力
 *       （包括按钮布局、ESC 关闭、回车确认、表单验证等）。</li>
 *   <li>通过构造函数传入建议名称（{@code suggestedName}）并自动全选，
 *       方便用户直接保存或快速修改，减少操作步骤。</li>
 *   <li>通过覆写 {@link #doValidate()} 实现实时表单验证，
 *       在用户输入时就给出提示，避免提交空名称。</li>
 *   <li>覆写 {@link #getPreferredFocusedComponent()} 使弹窗打开后
 *       输入框自动获得焦点，用户可直接开始输入，体验更流畅。</li>
 * </ul>
 *
 * @see com.github.yanxianchao.dubboeasyinvoke.settings.DubboInvokeSettingsService#saveFavorite 收藏保存逻辑
 */
public final class FavoriteSaveDialog extends DialogWrapper {

    /** 收藏名称输入框，用户在此输入自定义的收藏名 */
    private final JBTextField favoriteNameField = new JBTextField();
    /** 对话框的主面板，包含表单布局 */
    private final JPanel panel;

    /**
     * 构造"收藏当前接口"对话框。
     *
     * <p>初始化流程：
     * <ol>
     *   <li>设置对话框标题和按钮文字（中文化）；</li>
     *   <li>将建议名称填入输入框并全选——用户可以直接回车保存，
     *       也可以修改后再保存，操作效率最高；</li>
     *   <li>使用 {@link FormBuilder} 构建表单布局；</li>
     *   <li>调用 {@link #init()} 完成 IDEA DialogWrapper 的内部初始化
     *       （创建按钮面板、注册快捷键等）。</li>
     * </ol>
     *
     * @param parentComponent 父组件，用于计算弹窗的居中位置；可以为 null
     * @param suggestedName   建议的收藏名称，通常使用"应用名.方法名"作为默认值
     */
    public FavoriteSaveDialog(@Nullable JComponent parentComponent, @NotNull String suggestedName) {
        // 第二个参数 canBeParent=true，表示该对话框可以作为其他子对话框的父窗口
        super(parentComponent, true);
        setTitle("收藏当前接口");
        setOKButtonText("保存");
        setCancelButtonText("取消");

        // 预填建议名称并全选，方便用户直接保存或快速修改
        favoriteNameField.setText(suggestedName);
        favoriteNameField.selectAll();

        // 构建简洁的单行表单布局
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("收藏名", favoriteNameField, 1, false)
                .getPanel();

        // 必须在最后调用 init()，DialogWrapper 要求子类构造器末尾调用此方法
        // 以完成按钮面板创建、快捷键注册等初始化工作
        init();
    }

    /**
     * 获取用户输入的收藏名称。
     *
     * <p>调用方在对话框关闭后（用户点击"保存"按钮），通过此方法获取最终的收藏名。
     * 返回值已去除首尾空格。</p>
     *
     * @return 去除首尾空格后的收藏名称字符串
     */
    public @NotNull String getFavoriteName() {
        return favoriteNameField.getText().trim();
    }

    /**
     * 实时表单验证逻辑。
     *
     * <p>IDEA 的 {@link DialogWrapper} 会在用户每次输入时调用此方法。
     * 如果返回非 null 的 {@link ValidationInfo}，"保存"按钮将被禁用，
     * 同时在对应的输入框旁显示错误提示。</p>
     *
     * <p>当前只校验一条规则：收藏名不能为空。</p>
     *
     * @return 验证失败时返回包含错误信息的 ValidationInfo；验证通过返回 null
     */
    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (getFavoriteName().isEmpty()) {
            return new ValidationInfo("收藏名不能为空", favoriteNameField);
        }
        return null;
    }

    /**
     * 返回对话框的中心面板内容。
     *
     * <p>由 {@link DialogWrapper} 在 {@link #init()} 时调用，
     * 将返回的面板放置到对话框的中央区域（按钮面板的上方）。</p>
     *
     * @return 包含收藏名输入表单的面板
     */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        return panel;
    }

    /**
     * 指定对话框打开后默认获得焦点的组件。
     *
     * <p>返回收藏名输入框，这样用户打开弹窗后可以直接开始输入或按回车保存，
     * 无需手动点击输入框，提升操作效率。</p>
     *
     * @return 收藏名输入框组件
     */
    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return favoriteNameField;
    }
}
