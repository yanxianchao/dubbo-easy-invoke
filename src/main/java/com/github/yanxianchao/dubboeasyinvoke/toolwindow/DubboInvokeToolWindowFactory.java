package com.github.yanxianchao.dubboeasyinvoke.toolwindow;

import com.github.yanxianchao.dubboeasyinvoke.ui.DubboInvokePanel;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

/**
 * IDEA Tool Window 入口工厂。
 *
 * <p>IDE 启动后由平台回调此类，把 {@link DubboInvokePanel} 挂到右侧工具窗口。</p>
 */
public final class DubboInvokeToolWindowFactory implements ToolWindowFactory, DumbAware {

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        DubboInvokePanel panel = new DubboInvokePanel();
        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);
    }
}
