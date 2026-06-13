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
 *
 * <h3>在项目中的角色</h3>
 * <p>本类是插件 Tool Window 的注册入口，在 {@code plugin.xml} 中通过
 * {@code <toolWindow>} 标签声明。IDE 启动并加载插件后，会在合适的时机
 * 调用 {@link #createToolWindowContent} 方法来初始化工具窗口的内容。</p>
 *
 * <h3>设计思路</h3>
 * <ul>
 *   <li>实现 {@link ToolWindowFactory} 接口，由 IDEA 平台负责工具窗口的创建和生命周期管理。</li>
 *   <li>同时实现 {@link DumbAware} 接口，表明本工具窗口在 IDEA 索引未完成（Dumb Mode）时
 *       也可以正常显示和使用。这对于用户体验很重要——不需要等待索引完成就能打开调用面板。</li>
 *   <li>将 {@link DubboInvokePanel} 注册为 {@link Content} 的 Disposer，
 *       当工具窗口关闭或项目关闭时，面板资源会被自动清理。</li>
 * </ul>
 *
 * @see DubboInvokePanel 实际的调用操作面板，承载所有 UI 交互逻辑
 */
public final class DubboInvokeToolWindowFactory implements ToolWindowFactory, DumbAware {

    /**
     * 创建工具窗口的内容。
     *
     * <p>由 IDEA 平台在工具窗口首次显示时调用。方法内部：
     * <ol>
     *   <li>创建 {@link DubboInvokePanel} 实例作为主面板；</li>
     *   <li>通过 {@link ContentFactory} 将面板包装为 IDEA 的 {@link Content} 对象；</li>
     *   <li>将面板设为 Content 的 Disposer，确保工具窗口关闭时面板资源能被正确释放；</li>
     *   <li>将 Content 添加到工具窗口的内容管理器中，完成挂载。</li>
     * </ol>
     *
     * @param project    当前打开的项目实例，面板可通过它访问项目级服务
     * @param toolWindow IDEA 分配的工具窗口实例，用于挂载内容
     */
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        // 创建插件的主操作面板
        DubboInvokePanel panel = new DubboInvokePanel();
        // 将面板包装为 IDEA Content 对象（第二个参数为标签页标题，空串表示不显示标题）
        Content content = ContentFactory.getInstance().createContent(panel.getComponent(), "", false);
        // 注册 Disposer：当 Content 被移除时，自动调用 panel 的 dispose 方法释放资源
        content.setDisposer(panel);
        // 将内容添加到工具窗口中
        toolWindow.getContentManager().addContent(content);
    }
}
