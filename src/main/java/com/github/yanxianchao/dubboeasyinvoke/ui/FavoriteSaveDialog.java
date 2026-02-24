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
 * “收藏当前接口”弹窗。
 *
 * <p>只保留一个必填项“收藏名”，减少用户操作步骤。</p>
 */
public final class FavoriteSaveDialog extends DialogWrapper {

    private final JBTextField favoriteNameField = new JBTextField();
    private final JPanel panel;

    public FavoriteSaveDialog(@Nullable JComponent parentComponent, @NotNull String suggestedName) {
        super(parentComponent, true);
        setTitle("收藏当前接口");
        setOKButtonText("保存");
        setCancelButtonText("取消");

        favoriteNameField.setText(suggestedName);
        favoriteNameField.selectAll();

        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("收藏名", favoriteNameField, 1, false)
                .getPanel();

        init();
    }

    public @NotNull String getFavoriteName() {
        return favoriteNameField.getText().trim();
    }

    @Override
    protected @Nullable ValidationInfo doValidate() {
        if (getFavoriteName().isEmpty()) {
            return new ValidationInfo("收藏名不能为空", favoriteNameField);
        }
        return null;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        return panel;
    }

    @Override
    public @Nullable JComponent getPreferredFocusedComponent() {
        return favoriteNameField;
    }
}
