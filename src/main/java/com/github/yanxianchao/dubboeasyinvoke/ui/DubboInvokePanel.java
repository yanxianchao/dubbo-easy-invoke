package com.github.yanxianchao.dubboeasyinvoke.ui;

import com.github.yanxianchao.dubboeasyinvoke.invoke.DubboTelnetClient;
import com.github.yanxianchao.dubboeasyinvoke.model.DiscoverySnapshot;
import com.github.yanxianchao.dubboeasyinvoke.model.DubboMethodEndpoint;
import com.github.yanxianchao.dubboeasyinvoke.model.InvokeFavorite;
import com.github.yanxianchao.dubboeasyinvoke.registry.ZooKeeperDubboRegistryClient;
import com.github.yanxianchao.dubboeasyinvoke.settings.DubboInvokeSettingsService;
import com.github.yanxianchao.dubboeasyinvoke.util.JsonFormatter;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dubbo Easy Invoke 插件的主界面面板（Tool Window 内容）。
 *
 * <h3>职责概述</h3>
 * <ol>
 *   <li>构建并管理插件的完整 UI 布局：应用选择、接口选择、入参编辑、结果展示、控制台日志。</li>
 *   <li>协调 ZooKeeper 注册中心的应用/接口发现流程（加载、缓存回退、后台刷新、实时订阅）。</li>
 *   <li>触发 Dubbo Telnet 调用并将结果回显到界面。</li>
 *   <li>维护收藏夹的保存与恢复。</li>
 * </ol>
 *
 * <h3>设计思路</h3>
 * <p>本类定位为"UI 编排层"，自身不包含网络通信或数据序列化逻辑，
 * 真正的数据获取和网络调用分别委托给 {@link ZooKeeperDubboRegistryClient}
 * 与 {@link DubboTelnetClient}。</p>
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>所有 UI 更新（组件状态修改、控制台追加日志）均在 <b>EDT（Event Dispatch Thread）</b> 上执行。</li>
 *   <li>网络请求（ZooKeeper 发现、Dubbo 调用）通过
 *       {@code ApplicationManager.getApplication().executeOnPooledThread()} 在后台线程池中执行，
 *       完成后通过 {@code invokeLater()} 切回 EDT。</li>
 *   <li>使用 {@link #refreshRequestSeq} 和 {@link #subscriptionSeq} 两个原子序号
 *       保证"先发后到"的过期请求/订阅不会覆盖最新数据。</li>
 * </ul>
 *
 * <p>这个类偏"编排层"，真正的数据获取和网络调用分别交给
 * {@link ZooKeeperDubboRegistryClient} 与 {@link DubboTelnetClient}。</p>
 */
public final class DubboInvokePanel implements Disposable {

    /** 控制台日志行前缀使用的时间格式，精确到秒 */
    private static final DateTimeFormatter CONSOLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 组件间的紧凑间距（像素），统一用于 BoxLayout strut 与 BorderLayout gap */
    private static final int COMPACT_GAP = 4;

    /**
     * "解析接口"面板中需要对齐的字段标签列表。
     * 用于 {@link #buildResolvedFieldPrefix(String)} 计算最大像素宽度，
     * 从而让所有字段值在冒号后对齐。
     */
    private static final String[] RESOLVE_DETAIL_LABELS = {
            "接口名称",
            "服务地址",
            "消费应用",
            "超时时间",
            "二方包版本",
            "DUBBO版本"
    };

    // ========== UI 组件 ==========

    /** 最外层根面板，由 {@link #buildLayout()} 构建 */
    private final JPanel mainPanel;

    /** 应用名称下拉框，通过 {@link #applicationSearchController} 提供搜索/筛选能力 */
    private final ComboBox<String> applicationComboBox = new ComboBox<>();

    /** 接口（服务方法）下拉框，通过 {@link #interfaceSearchController} 提供搜索/筛选能力 */
    private final ComboBox<DubboMethodEndpoint> interfaceComboBox = new ComboBox<>();

    /** 应用下拉框的搜索控制器，支持关键字过滤应用列表 */
    private final SearchableComboBoxController<String> applicationSearchController;

    /** 接口下拉框的搜索控制器，支持关键字过滤接口列表 */
    private final SearchableComboBoxController<DubboMethodEndpoint> interfaceSearchController;

    /** "刷新"按钮 —— 强制从注册中心重新拉取应用/接口数据 */
    private final JButton refreshButton = new JButton("刷新");

    /** "粘贴入参"按钮 —— 将系统剪贴板内容粘贴到入参文本区 */
    private final JButton pasteParamButton = new JButton("粘贴入参");

    /** "复制入参"按钮 —— 将入参文本区内容复制到系统剪贴板 */
    private final JButton copyParamButton = new JButton("复制入参");

    /** "复制结果"按钮 —— 将调用结果文本区内容复制到系统剪贴板 */
    private final JButton copyResultButton = new JButton("复制结果");

    /** "调用接口"按钮 —— 发起 Dubbo Telnet 调用 */
    private final JButton invokeButton = new JButton("调用接口");

    /** "解析接口"按钮 —— 在控制台展示接口地址、消费者、超时等详细信息 */
    private final JButton resolveAddressButton = new JButton("解析接口");

    /** "收藏当前"按钮 —— 将当前选中的接口+入参保存为收藏 */
    private final JButton favoriteCurrentButton = new JButton("收藏当前");

    /** "收藏夹"按钮 —— 打开收藏夹浏览对话框 */
    private final JButton favoritesButton = new JButton("收藏夹");

    /** "重置"按钮 —— 清空入参和结果文本区 */
    private final JButton resetButton = new JButton("重置");

    /** 入参编辑区，支持 JSON 自动格式化 */
    private final JBTextArea parameterTextArea = new JBTextArea();

    /** 调用结果展示区，只读 */
    private final JBTextArea resultTextArea = new JBTextArea();

    /** 控制台文本面板，使用 StyledDocument 支持彩色日志输出 */
    private final JTextPane consoleTextPane = new JTextPane();

    /** 控制台最新状态标签，显示在控制台标题行右侧，作为当前状态的快速提示 */
    private final JBLabel consoleLatestLabel = new JBLabel("准备就绪");

    /**
     * 入参文本自动格式化定时器。
     * <p>为什么使用定时器：用户每次按键都会触发 DocumentEvent，如果每次都格式化会造成光标跳动。
     * 这里采用"防抖（debounce）"策略，在用户停止输入 450ms 后才执行格式化。</p>
     */
    private final javax.swing.Timer parameterAutoFormatTimer = new javax.swing.Timer(450, event -> formatParameterIfJson());

    // ========== 控制台日志样式 ==========

    /** 普通信息样式 —— 默认前景色 */
    private final SimpleAttributeSet consoleInfoStyle = createConsoleStyle(JBColor.foreground(), false);

    /** 运行中样式 —— 蓝色，用于"正在加载""处理中"等进行态日志 */
    private final SimpleAttributeSet consoleRunningStyle = createConsoleStyle(
            new JBColor(new Color(0x0B57D0), new Color(0x89B4FF)),
            false
    );

    /** 成功样式 —— 绿色，用于"已完成""成功"等正向结果日志 */
    private final SimpleAttributeSet consoleSuccessStyle = createConsoleStyle(
            new JBColor(new Color(0x1B5E20), new Color(0x8BCF8C)),
            false
    );

    /** 警告样式 —— 黄色/橙色，用于"请先配置""未发现"等提示性日志 */
    private final SimpleAttributeSet consoleWarningStyle = createConsoleStyle(
            new JBColor(new Color(0x9C640C), new Color(0xF0C674)),
            false
    );

    /** 错误样式 —— 红色加粗，用于"失败""异常"等错误日志 */
    private final SimpleAttributeSet consoleErrorStyle = createConsoleStyle(
            new JBColor(new Color(0xB71C1C), new Color(0xFF8A80)),
            true
    );

    /** 端点地址样式 —— 蓝色加粗，用于突出显示调用/解析的 host:port 信息 */
    private final SimpleAttributeSet consoleEndpointStyle = createConsoleStyle(
            new JBColor(new Color(0x005A9E), new Color(0x9CDCFE)),
            true
    );

    // ========== 核心服务与并发控制 ==========

    /** ZooKeeper 注册中心客户端，负责应用/接口发现与订阅 */
    private final ZooKeeperDubboRegistryClient registryClient = new ZooKeeperDubboRegistryClient();

    /** Dubbo Telnet 调用客户端，负责通过 Telnet 协议执行 invoke 命令 */
    private final DubboTelnetClient telnetClient = new DubboTelnetClient();

    /**
     * 刷新请求序号（单调递增）。
     * <p>每次发起 {@link #reloadApplications(boolean)} 时递增，
     * 后台回调通过比对当前值判断自身是否已过期，从而防止并发刷新导致"后到先覆盖"的问题。</p>
     */
    private final AtomicLong refreshRequestSeq = new AtomicLong(0);

    /**
     * 订阅序号（单调递增）。
     * <p>每次创建或停止注册中心订阅时递增，
     * 订阅回调通过比对当前值判断自身是否已失效，避免旧订阅事件污染最新数据。</p>
     */
    private final AtomicLong subscriptionSeq = new AtomicLong(0);

    // ========== 运行时状态（volatile 保证多线程可见性） ==========

    /** 当前的注册中心发现快照，包含所有应用及其接口列表 */
    private volatile DiscoverySnapshot discoverySnapshot = DiscoverySnapshot.empty();

    /** 当前活跃的注册中心订阅（为 null 表示未订阅） */
    private volatile @Nullable ZooKeeperDubboRegistryClient.DiscoverySubscription registrySubscription;

    /** 当前选中的应用名称（用于判断应用是否发生切换） */
    private volatile @Nullable String activeApplicationName;

    /**
     * 是否静默应用选择变更的状态通知。
     * <p>在程序自动切换应用（如恢复收藏、刷新后回填）时设为 true，
     * 避免触发不必要的状态消息刷新。</p>
     */
    private volatile boolean muteApplicationSelectionStatus;

    /** 面板是否已被销毁，用于防止异步回调在面板关闭后继续操作 UI */
    private volatile boolean disposed;

    /**
     * 构造主面板并完成全部初始化。
     *
     * <p>初始化流程：
     * <ol>
     *   <li>创建下拉框的搜索控制器</li>
     *   <li>初始化下拉框编辑器（设置占位文字）</li>
     *   <li>初始化文本区（字体、行数、自动格式化）</li>
     *   <li>设置紧凑组件样式</li>
     *   <li>构建 UI 布局</li>
     *   <li>绑定按钮事件</li>
     *   <li>首次加载应用列表（使用缓存优先策略）</li>
     * </ol>
     */
    public DubboInvokePanel() {
        this.applicationSearchController = new SearchableComboBoxController<>(applicationComboBox, value -> value);
        this.interfaceSearchController = new SearchableComboBoxController<>(interfaceComboBox, DubboMethodEndpoint::getDisplayName);

        initComboEditors();
        initTextAreas();
        initCompactComponentStyles();

        mainPanel = buildLayout();
        bindActions();
        // 首次加载使用非强制模式，优先读取缓存以加速启动
        reloadApplications(false);
    }

    /**
     * 获取面板的根 Swing 组件，供 Tool Window 容器嵌入。
     *
     * @return 最外层 JPanel
     */
    public @NotNull JComponent getComponent() {
        return mainPanel;
    }

    /**
     * 销毁面板，释放资源。
     * <p>由 IntelliJ 平台在 Tool Window 关闭时调用。
     * 主要职责是停止注册中心订阅、标记 disposed 状态以终止所有异步回调。</p>
     */
    @Override
    public void dispose() {
        disposed = true;
        stopRegistrySubscription();
    }

    /**
     * 初始化应用和接口下拉框的编辑器组件。
     * <p>为编辑器文本框设置占位提示文字，引导用户输入关键字进行筛选。
     * 同时应用 SMALL 样式使组件更紧凑。</p>
     */
    private void initComboEditors() {
        Object appEditor = applicationComboBox.getEditor().getEditorComponent();
        if (appEditor instanceof JTextField appField) {
            appField.putClientProperty("JTextField.placeholderText", "输入关键字筛选应用");
            UIUtil.applyStyle(UIUtil.ComponentStyle.SMALL, appField);
        }

        Object interfaceEditor = interfaceComboBox.getEditor().getEditorComponent();
        if (interfaceEditor instanceof JTextField interfaceField) {
            interfaceField.putClientProperty("JTextField.placeholderText", "输入关键字筛选接口");
            UIUtil.applyStyle(UIUtil.ComponentStyle.SMALL, interfaceField);
        }
    }

    /**
     * 初始化入参、结果、控制台三个文本区域。
     *
     * <p>关键设计：
     * <ul>
     *   <li>入参区支持自动 JSON 格式化：失去焦点时立即格式化 + 停止输入 450ms 后防抖格式化。</li>
     *   <li>结果区设为只读，防止用户误编辑。</li>
     *   <li>所有文本区使用等宽字体，便于 JSON 对齐阅读。</li>
     * </ul>
     */
    private void initTextAreas() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(11));
        // 设为不重复触发，仅在最后一次 restart() 后 450ms 触发一次
        parameterAutoFormatTimer.setRepeats(false);

        parameterTextArea.setFont(mono);
        parameterTextArea.setRows(18);
        parameterTextArea.setLineWrap(true);
        parameterTextArea.setWrapStyleWord(true);

        resultTextArea.setFont(mono);
        resultTextArea.setRows(12);
        resultTextArea.setEditable(false);
        resultTextArea.setLineWrap(true);
        resultTextArea.setWrapStyleWord(true);

        // 失去焦点时立即格式化，确保用户点击"调用"前入参已是标准 JSON
        parameterTextArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                formatParameterIfJson();
            }
        });
        // 每次文本变化重置防抖定时器，实现"停止输入 450ms 后自动格式化"
        parameterTextArea.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                parameterAutoFormatTimer.restart();
            }
        });

        consoleTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(10)));
        consoleTextPane.setEditable(false);
        consoleLatestLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        consoleLatestLabel.setForeground(JBColor.GRAY);
    }

    /**
     * 为所有交互按钮和下拉框应用紧凑（SMALL）样式。
     * <p>统一缩小边距，使整体界面在 Tool Window 中更节省空间。</p>
     */
    private void initCompactComponentStyles() {
        UIUtil.ComponentStyle compactStyle = UIUtil.ComponentStyle.SMALL;
        UIUtil.applyStyle(compactStyle, applicationComboBox);
        UIUtil.applyStyle(compactStyle, interfaceComboBox);
        UIUtil.applyStyle(compactStyle, consoleLatestLabel);

        List<JButton> buttons = List.of(
                refreshButton,
                pasteParamButton,
                copyParamButton,
                copyResultButton,
                invokeButton,
                resolveAddressButton,
                favoriteCurrentButton,
                favoritesButton,
                resetButton
        );
        for (JButton button : buttons) {
            UIUtil.applyStyle(compactStyle, button);
            button.setMargin(JBUI.insets(1, 8));
        }
    }

    /**
     * 构建完整的 UI 布局并返回根面板。
     *
     * <p>布局结构（从上到下）：
     * <pre>
     *   [应用下拉框] [刷新] [收藏当前] [收藏夹]        ← appRow
     *   [接口下拉框（自动拉伸）]                        ← interfaceRow
     *   [入参文本区]                [操作按钮栏]         ← parameterRow
     *   [结果文本区]                                    ← resultScroll
     *   ┌─ Console ──────────────────────────────────┐
     *   │ [控制台标题] [最新状态标签]                    │ ← consolePanel
     *   │ [带样式的日志文本]                            │
     *   └────────────────────────────────────────────┘
     * </pre>
     *
     * @return 构建好的根面板
     */
    private @NotNull JPanel buildLayout() {
        // --- 应用下拉框尺寸约束 ---
        int appComboHeight = applicationComboBox.getPreferredSize().height;
        Dimension appPreferredSize = new Dimension(JBUI.scale(170), appComboHeight);
        Dimension appMinimumSize = new Dimension(JBUI.scale(100), appComboHeight);
        Dimension appMaximumSize = new Dimension(JBUI.scale(170), appComboHeight);
        applicationComboBox.setPreferredSize(appPreferredSize);
        applicationComboBox.setMinimumSize(appMinimumSize);
        applicationComboBox.setMaximumSize(appMaximumSize);

        // --- 接口下拉框尺寸约束（允许水平拉伸到最大宽度） ---
        int interfaceComboHeight = interfaceComboBox.getPreferredSize().height;
        interfaceComboBox.setPreferredSize(new Dimension(JBUI.scale(420), interfaceComboHeight));
        interfaceComboBox.setMinimumSize(new Dimension(JBUI.scale(180), interfaceComboHeight));
        interfaceComboBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, interfaceComboHeight));

        // --- 应用行右侧操作按钮组 ---
        JPanel appActionPanel = new JPanel();
        appActionPanel.setLayout(new javax.swing.BoxLayout(appActionPanel, javax.swing.BoxLayout.X_AXIS));
        appActionPanel.add(refreshButton);
        appActionPanel.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(COMPACT_GAP)));
        appActionPanel.add(favoriteCurrentButton);
        appActionPanel.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(COMPACT_GAP)));
        appActionPanel.add(favoritesButton);

        // --- 应用行：下拉框 + 操作按钮 + 右侧弹性空间 ---
        JPanel appRow = new JPanel();
        appRow.setLayout(new javax.swing.BoxLayout(appRow, javax.swing.BoxLayout.X_AXIS));
        appRow.add(applicationComboBox);
        appRow.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(COMPACT_GAP)));
        appRow.add(appActionPanel);
        appRow.add(javax.swing.Box.createHorizontalGlue());
        // 安装自适应宽度监听，使应用下拉框随窗口宽度变化动态调整
        installAdaptiveAppComboWidth(appRow, appActionPanel, appComboHeight);

        // --- 接口行：下拉框居中自动拉伸 ---
        JPanel interfaceRow = new JPanel(new BorderLayout(JBUI.scale(COMPACT_GAP), 0));
        interfaceRow.add(interfaceComboBox, BorderLayout.CENTER);

        // --- 右侧操作按钮栏（纵向排列） ---
        JPanel actionPanel = new JPanel();
        actionPanel.setLayout(new javax.swing.BoxLayout(actionPanel, javax.swing.BoxLayout.Y_AXIS));
        invokeButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        resolveAddressButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        pasteParamButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        copyParamButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        copyResultButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        resetButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        actionPanel.add(invokeButton);
        actionPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(COMPACT_GAP)));
        actionPanel.add(resolveAddressButton);
        actionPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(COMPACT_GAP)));
        actionPanel.add(pasteParamButton);
        actionPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(COMPACT_GAP)));
        actionPanel.add(copyParamButton);
        actionPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(COMPACT_GAP)));
        actionPanel.add(copyResultButton);
        actionPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(COMPACT_GAP)));
        actionPanel.add(resetButton);

        // --- 入参文本区滚动面板 ---
        JBScrollPane parameterScroll = new JBScrollPane(parameterTextArea);
        parameterScroll.setPreferredSize(new Dimension(420, 320));
        parameterScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        parameterScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // --- 入参行：文本区（中央） + 操作按钮栏（东侧） ---
        JPanel parameterRow = new JPanel(new BorderLayout(JBUI.scale(COMPACT_GAP), 0));
        parameterRow.add(parameterScroll, BorderLayout.CENTER);
        parameterRow.add(actionPanel, BorderLayout.EAST);

        // --- 结果文本区滚动面板 ---
        JBScrollPane resultScroll = new JBScrollPane(resultTextArea);
        resultScroll.setPreferredSize(new Dimension(420, 210));
        resultScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resultScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // --- 控制台标题栏 ---
        JBLabel consoleTitleLabel = new JBLabel("Console");
        consoleTitleLabel.setFont(consoleTitleLabel.getFont().deriveFont(Font.BOLD));

        JPanel consoleHeader = new JPanel(new BorderLayout(JBUI.scale(COMPACT_GAP), 0));
        consoleHeader.add(consoleTitleLabel, BorderLayout.WEST);
        consoleHeader.add(consoleLatestLabel, BorderLayout.CENTER);

        // --- 控制台日志滚动面板 ---
        JBScrollPane consoleScroll = new JBScrollPane(consoleTextPane);
        consoleScroll.setPreferredSize(new Dimension(420, 140));
        consoleScroll.setMinimumSize(new Dimension(50, JBUI.scale(110)));
        consoleScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        consoleScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        // --- 控制台整体面板（带边框） ---
        JPanel consolePanel = new JPanel(new BorderLayout(JBUI.scale(COMPACT_GAP), JBUI.scale(COMPACT_GAP)));
        consolePanel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)
        ));
        consolePanel.setMinimumSize(new Dimension(80, JBUI.scale(140)));
        consolePanel.add(consoleHeader, BorderLayout.NORTH);
        consolePanel.add(consoleScroll, BorderLayout.CENTER);

        // --- 使用 FormBuilder 构建上半部分表单布局 ---
        JPanel topPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("应用", appRow, 1, false)
                .addLabeledComponent("接口", interfaceRow, 1, false)
                .addLabeledComponent("入参", parameterRow, 1, false)
                .addLabeledComponent("结果", resultScroll, 1, false)
                .getPanel();
        // 将"结果"标签的锚点设为左上角，使标签在文本区高度增大时不会居中
        adjustFormLabelAnchor(topPanel, resultScroll, GridBagConstraints.WEST);
        applySmallStyleToFormLabels(topPanel);

        // --- 最终根面板：上方表单 + 下方控制台 ---
        JPanel rootPanel = new JPanel(new BorderLayout(0, JBUI.scale(COMPACT_GAP)));
        rootPanel.setBorder(JBUI.Borders.empty(2, 12, 0, 12));
        rootPanel.add(topPanel, BorderLayout.CENTER);
        rootPanel.add(consolePanel, BorderLayout.SOUTH);
        return rootPanel;
    }

    /**
     * 递归遍历容器中的所有 JLabel，对其应用 SMALL 字体样式。
     * <p>用于将 FormBuilder 自动生成的表单标签统一缩小。</p>
     *
     * @param container 待遍历的容器
     */
    private void applySmallStyleToFormLabels(@NotNull Container container) {
        for (java.awt.Component component : container.getComponents()) {
            if (component instanceof JLabel label) {
                UIUtil.applyStyle(UIUtil.ComponentStyle.SMALL, label);
            }
            if (component instanceof Container nestedContainer) {
                applySmallStyleToFormLabels(nestedContainer);
            }
        }
    }

    /**
     * 调整 FormBuilder 表单中指定组件对应标签的 GridBag 锚点。
     * <p>默认情况下 FormBuilder 生成的标签在垂直方向居中，
     * 对于高度较大的组件（如结果文本区），需要将标签锚定在左上角。</p>
     *
     * @param formPanel       FormBuilder 生成的面板
     * @param labeledComponent 目标组件（通过 JLabel.labelFor 匹配）
     * @param anchor           GridBagConstraints 锚点常量，如 {@link GridBagConstraints#WEST}
     */
    private void adjustFormLabelAnchor(@NotNull JPanel formPanel, @NotNull JComponent labeledComponent, int anchor) {
        if (!(formPanel.getLayout() instanceof GridBagLayout gridBagLayout)) {
            return;
        }

        for (java.awt.Component component : formPanel.getComponents()) {
            if (!(component instanceof JLabel label) || label.getLabelFor() != labeledComponent) {
                continue;
            }

            GridBagConstraints constraints = gridBagLayout.getConstraints(component);
            constraints.anchor = anchor;
            gridBagLayout.setConstraints(component, constraints);
            return;
        }
    }

    /**
     * 为应用下拉框安装自适应宽度监听器。
     *
     * <p>为什么需要自适应：
     * Tool Window 的宽度由用户拖拽决定，应用下拉框需要在按钮组不被挤压的前提下
     * 尽量利用剩余水平空间，但又不超过 {@code maxWidth} 避免过度拉伸。</p>
     *
     * @param appRow         应用行面板
     * @param appActionPanel 应用行右侧按钮组
     * @param comboHeight    下拉框高度
     */
    private void installAdaptiveAppComboWidth(@NotNull JPanel appRow, @NotNull JPanel appActionPanel, int comboHeight) {
        final int minWidth = JBUI.scale(100);
        final int maxWidth = JBUI.scale(170);
        final int gapWidth = JBUI.scale(COMPACT_GAP);

        Runnable adjustWidth = () -> {
            int rowWidth = appRow.getWidth();
            if (rowWidth <= 0) {
                return;
            }

            // 计算按钮组占用的宽度，剩余空间分配给下拉框
            int actionWidth = appActionPanel.getPreferredSize().width;
            int available = Math.max(minWidth, rowWidth - actionWidth - gapWidth);
            int targetWidth = available;
            // 限制在 [minWidth, maxWidth] 范围内
            targetWidth = Math.max(minWidth, Math.min(maxWidth, targetWidth));

            Dimension targetSize = new Dimension(targetWidth, comboHeight);
            if (!targetSize.equals(applicationComboBox.getPreferredSize())) {
                applicationComboBox.setPreferredSize(targetSize);
                applicationComboBox.setMinimumSize(new Dimension(minWidth, comboHeight));
                applicationComboBox.setMaximumSize(new Dimension(maxWidth, comboHeight));
                appRow.revalidate();
            }
        };

        // 窗口大小变化时重新计算
        appRow.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                adjustWidth.run();
            }
        });
        // 首次布局完成后立即执行一次
        SwingUtilities.invokeLater(adjustWidth);
    }

    /**
     * 绑定所有按钮和下拉框的事件监听器。
     *
     * <p>应用下拉框的 ItemListener 特别关键：
     * 当用户切换应用时，先清空接口列表和输入状态，再异步加载新应用的接口。
     * 通过 {@link #muteApplicationSelectionStatus} 控制是否在控制台输出状态消息。</p>
     */
    private void bindActions() {
        refreshButton.addActionListener(event -> reloadApplications(true));
        pasteParamButton.addActionListener(event -> pasteParameterFromClipboard());
        copyParamButton.addActionListener(event -> copyTextToClipboard(parameterTextArea.getText(), "入参"));
        copyResultButton.addActionListener(event -> copyTextToClipboard(resultTextArea.getText(), "结果"));
        invokeButton.addActionListener(event -> invokeSelectedInterface());
        resolveAddressButton.addActionListener(event -> resolveSelectedInterfaceAddress());
        favoriteCurrentButton.addActionListener(event -> saveCurrentFavorite());
        favoritesButton.addActionListener(event -> openFavoritesDialog());
        resetButton.addActionListener(event -> resetInputs());

        applicationComboBox.addItemListener(event -> {
            if (event.getStateChange() != ItemEvent.SELECTED || !(event.getItem() instanceof String appName)) {
                return;
            }

            // 过滤掉不在快照中的无效应用名（可能是搜索框中的临时输入）
            if (!discoverySnapshot.getApplications().contains(appName)) {
                return;
            }

            // 如果选中的应用与当前活跃应用相同，不需要重新加载接口
            if (appName.equals(activeApplicationName)) {
                return;
            }

            // 应用发生变化时，先清空接口列表与输入状态，再加载新应用的接口。
            interfaceSearchController.setSelectedItem(null);
            interfaceSearchController.setItems(List.of());
            syncInterfacesByApplication(appName, !muteApplicationSelectionStatus);
        });
    }

    /**
     * 从注册中心加载应用和接口列表。
     *
     * <p>加载策略：
     * <ul>
     *   <li>{@code forceRefresh = false}：优先使用缓存（内存 → 磁盘 → 网络），
     *       如果命中缓存则在后台静默刷新最新数据。</li>
     *   <li>{@code forceRefresh = true}：强制从 ZooKeeper 拉取最新数据。</li>
     * </ul>
     *
     * <p>并发安全：通过 {@link #refreshRequestSeq} 保证只有最新请求的回调生效。</p>
     *
     * @param forceRefresh 是否强制跳过缓存
     */
    private void reloadApplications(boolean forceRefresh) {
        if (disposed) {
            return;
        }

        String zkAddress = DubboInvokeSettingsService.getInstance().getZookeeperAddress();
        if (zkAddress.isBlank()) {
            setStatus("请先在 Settings > Tools > Dubbo Easy Invoke 配置 Zookeeper 地址。");
            return;
        }

        // 通过递增请求号防止并发请求"后到先覆盖"的问题。
        long requestId = refreshRequestSeq.incrementAndGet();
        // 旧的订阅已失效，先停止
        stopRegistrySubscription();
        setBusyState(true, "正在加载应用和接口...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ZooKeeperDubboRegistryClient.DiscoverResult result = registryClient.discoverWithSource(zkAddress, forceRefresh);
                DiscoverySnapshot snapshot = result.getSnapshot();
                ApplicationManager.getApplication().invokeLater(() -> {
                    // 双重检查：面板是否已关闭、请求是否已过期
                    if (disposed || isStaleRefreshRequest(requestId)) {
                        return;
                    }
                    applyDiscovery(snapshot);
                    restartRegistrySubscription(requestId, zkAddress, snapshot);
                    setBusyState(false, initialLoadMessage(result.getSource()));
                    // 非强制刷新时，如果数据来自缓存，则在后台静默拉取最新数据
                    if (!forceRefresh) {
                        startBackgroundRefreshIfNeeded(requestId, zkAddress, snapshot, result.getSource());
                    }
                });
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || isStaleRefreshRequest(requestId)) {
                        return;
                    }
                    setBusyState(false, "加载失败");
                    setStatus("加载失败: " + ex.getMessage());
                });
            }
        });
    }

    /**
     * 当首次加载命中缓存时，在后台静默刷新最新数据。
     *
     * <p>设计目的：
     * 首次打开面板时用缓存快速展示内容，同时异步拉取最新数据。
     * 如果最新数据与缓存一致则不做任何更新；如果有变化则静默替换界面数据。</p>
     *
     * @param requestId         当前刷新请求序号，用于过期检查
     * @param zkAddress         ZooKeeper 地址
     * @param baselineSnapshot  用于比对的缓存快照
     * @param initialSource     首次加载的数据来源（只在非 NETWORK 时才需要后台刷新）
     */
    private void startBackgroundRefreshIfNeeded(
            long requestId,
            @NotNull String zkAddress,
            @NotNull DiscoverySnapshot baselineSnapshot,
            @NotNull ZooKeeperDubboRegistryClient.DataSource initialSource
    ) {
        // 如果首次就是从网络加载的，无需再刷新
        if (initialSource == ZooKeeperDubboRegistryClient.DataSource.NETWORK) {
            return;
        }

        setStatus("已展示缓存，正在后台刷新最新应用和接口...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                // 强制从网络拉取
                ZooKeeperDubboRegistryClient.DiscoverResult latest = registryClient.discoverWithSource(zkAddress, true);
                DiscoverySnapshot latestSnapshot = latest.getSnapshot();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || isStaleRefreshRequest(requestId)) {
                        return;
                    }

                    // 如果数据没有变化，只更新状态消息
                    if (latestSnapshot.equals(baselineSnapshot)) {
                        setStatus("后台刷新完成，当前数据已是最新");
                        return;
                    }

                    // 数据有变化，更新界面并重建订阅
                    applyDiscovery(latestSnapshot);
                    restartRegistrySubscription(requestId, zkAddress, latestSnapshot);
                    setStatus("后台已刷新到最新应用和接口");
                });
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed || isStaleRefreshRequest(requestId)) {
                        return;
                    }
                    setStatus("后台刷新失败: " + ex.getMessage());
                });
            }
        });
    }

    /**
     * 判断给定的请求序号是否已过期（即在此之后又发起了新的刷新请求）。
     *
     * @param requestId 待检查的请求序号
     * @return true 表示已过期，应忽略该请求的回调结果
     */
    private boolean isStaleRefreshRequest(long requestId) {
        return requestId != refreshRequestSeq.get();
    }

    /**
     * 重新建立注册中心订阅，监听应用地址变更。
     *
     * <p>订阅生命周期管理：
     * <ol>
     *   <li>先停止旧订阅，递增 {@link #subscriptionSeq}</li>
     *   <li>创建新订阅，绑定回调</li>
     *   <li>回调中通过比对 subscriptionId 和 requestId 判断是否过期</li>
     *   <li>创建完成后再次检查是否过期，如过期则立即关闭</li>
     * </ol>
     *
     * @param requestId 当前刷新请求序号
     * @param zkAddress ZooKeeper 地址
     * @param snapshot  当前快照，作为订阅的基准
     */
    private void restartRegistrySubscription(
            long requestId,
            @NotNull String zkAddress,
            @NotNull DiscoverySnapshot snapshot
    ) {
        if (disposed || isStaleRefreshRequest(requestId)) {
            return;
        }

        stopRegistrySubscription();
        long subscriptionId = subscriptionSeq.incrementAndGet();
        try {
            ZooKeeperDubboRegistryClient.DiscoverySubscription subscription = registryClient.subscribe(
                    zkAddress,
                    snapshot,
                    new ZooKeeperDubboRegistryClient.DiscoverySubscriptionListener() {
                        /**
                         * 注册中心检测到服务地址变更时回调。
                         * <p>在 EDT 上应用最新快照，并在控制台输出变更摘要。</p>
                         */
                        @Override
                        public void onSnapshotUpdated(@NotNull DiscoverySnapshot latestSnapshot) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                // 三重过期检查：面板已销毁、订阅已更替、刷新请求已更替
                                if (disposed
                                        || subscriptionId != subscriptionSeq.get()
                                        || isStaleRefreshRequest(requestId)) {
                                    return;
                                }
                                String selectedApplication = applicationSearchController.getSelectedItem();
                                // 构建针对当前选中应用的地址变更描述
                                String changeMessage = buildApplicationAddressChangeStatusMessage(
                                        discoverySnapshot,
                                        latestSnapshot,
                                        selectedApplication
                                );
                                // 静默更新数据，不触发状态消息
                                applyDiscovery(latestSnapshot, false);
                                if (changeMessage != null) {
                                    setStatus(changeMessage);
                                }
                            });
                        }

                        /**
                         * 订阅过程发生错误时回调。
                         */
                        @Override
                        public void onError(@NotNull String message, @Nullable Throwable error) {
                            ApplicationManager.getApplication().invokeLater(() -> {
                                if (disposed || subscriptionId != subscriptionSeq.get()) {
                                    return;
                                }
                                setStatus(message);
                            });
                        }
                    }
            );

            // 订阅创建完成后再次检查是否过期，防止在创建过程中发生了新的刷新
            if (disposed || subscriptionId != subscriptionSeq.get() || isStaleRefreshRequest(requestId)) {
                subscription.close();
                return;
            }

            registrySubscription = subscription;
            int serviceCount = subscription.getServiceCount();
            if (serviceCount > 0) {
                setStatus("已订阅 " + serviceCount + " 个服务，地址变化将自动同步");
            } else {
                setStatus("当前无可订阅服务，等待手动刷新");
            }
        } catch (Exception ex) {
            if (!disposed && subscriptionId == subscriptionSeq.get() && !isStaleRefreshRequest(requestId)) {
                setStatus("订阅服务失败: " + ex.getMessage());
            }
        }
    }

    /**
     * 停止当前活跃的注册中心订阅。
     * <p>通过递增 {@link #subscriptionSeq} 使旧订阅回调自动失效，
     * 然后关闭订阅连接释放资源。</p>
     */
    private void stopRegistrySubscription() {
        // 先递增序号，使所有旧回调立即失效
        subscriptionSeq.incrementAndGet();
        ZooKeeperDubboRegistryClient.DiscoverySubscription current = registrySubscription;
        registrySubscription = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (Exception ignored) {
            // ignore close failures
        }
    }

    /**
     * 构建当前选中应用的地址变更状态消息。
     *
     * <p>对比前后两个快照，如果当前选中应用存在地址变更，
     * 生成人类可读的变更描述（如"检测到应用 xxx 地址变更，涉及 n 个接口"）。</p>
     *
     * @param previousSnapshot   变更前的快照
     * @param latestSnapshot     变更后的快照
     * @param selectedApplication 当前选中的应用名称
     * @return 变更描述消息；如果无变更或未选中应用，返回 null
     */
    private @Nullable String buildApplicationAddressChangeStatusMessage(
            @NotNull DiscoverySnapshot previousSnapshot,
            @NotNull DiscoverySnapshot latestSnapshot,
            @Nullable String selectedApplication
    ) {
        if (selectedApplication == null || selectedApplication.isBlank()) {
            return null;
        }

        ApplicationAddressChangeSummary summary = collectApplicationAddressChanges(previousSnapshot, latestSnapshot)
                .get(selectedApplication);
        if (summary == null) {
            return null;
        }

        String baseMessage = "检测到应用 "
                + summary.getApplication()
                + " 地址变更，涉及 "
                + summary.getChangedInterfaceCount()
                + " 个接口";
        // 如果新旧地址都只有一个，使用更简洁的"由 A 切换到 B"格式
        if (summary.getOldAddresses().size() == 1 && summary.getNewAddresses().size() == 1) {
            return baseMessage
                    + "，地址由 "
                    + summary.getOldAddresses().iterator().next()
                    + " 切换到 "
                    + summary.getNewAddresses().iterator().next();
        }
        return baseMessage
                + "，旧地址 "
                + formatAddressSet(summary.getOldAddresses())
                + "，新地址 "
                + formatAddressSet(summary.getNewAddresses());
    }

    /**
     * 收集前后两个快照之间所有应用的地址变更信息。
     *
     * <p>遍历所有端点，对比 host:port 是否发生变化，
     * 按应用名聚合变更信息。</p>
     *
     * @param previousSnapshot 变更前的快照
     * @param latestSnapshot   变更后的快照
     * @return 应用名 → 变更摘要的映射
     */
    private @NotNull Map<String, ApplicationAddressChangeSummary> collectApplicationAddressChanges(
            @NotNull DiscoverySnapshot previousSnapshot,
            @NotNull DiscoverySnapshot latestSnapshot
    ) {
        Map<EndpointKey, DubboMethodEndpoint> previousEndpoints = indexEndpoints(previousSnapshot);
        Map<EndpointKey, DubboMethodEndpoint> latestEndpoints = indexEndpoints(latestSnapshot);
        if (previousEndpoints.isEmpty() || latestEndpoints.isEmpty()) {
            return Map.of();
        }

        Map<String, ApplicationAddressChangeSummary> changes = new TreeMap<>();
        for (Map.Entry<EndpointKey, DubboMethodEndpoint> entry : previousEndpoints.entrySet()) {
            EndpointKey key = entry.getKey();
            DubboMethodEndpoint latest = latestEndpoints.get(key);
            // 如果新快照中没有该端点，说明服务下线，不属于"地址变更"
            if (latest == null) {
                continue;
            }

            DubboMethodEndpoint previous = entry.getValue();
            // 只有 host 或 port 发生变化才记录
            if (previous.getHost().equals(latest.getHost()) && previous.getPort() == latest.getPort()) {
                continue;
            }

            changes.computeIfAbsent(key.application(), ApplicationAddressChangeSummary::new)
                    .addChange(
                            key.serviceName(),
                            formatAddress(previous),
                            formatAddress(latest)
                    );
        }
        return changes;
    }

    /**
     * 将快照中的所有端点按 (应用, 服务名, 方法名) 索引为 Map，便于快速查找。
     *
     * @param snapshot 注册中心发现快照
     * @return 端点索引
     */
    private @NotNull Map<EndpointKey, DubboMethodEndpoint> indexEndpoints(@NotNull DiscoverySnapshot snapshot) {
        Map<EndpointKey, DubboMethodEndpoint> indexed = new HashMap<>();
        for (String appName : snapshot.getApplications()) {
            for (DubboMethodEndpoint endpoint : snapshot.getInterfacesForApp(appName)) {
                EndpointKey key = new EndpointKey(appName, endpoint.getServiceName(), endpoint.getMethodName());
                indexed.put(key, endpoint);
            }
        }
        return indexed;
    }

    /**
     * 将端点的 host 和 port 格式化为 "host:port" 字符串。
     *
     * @param endpoint Dubbo 方法端点
     * @return 格式化后的地址字符串
     */
    private @NotNull String formatAddress(@NotNull DubboMethodEndpoint endpoint) {
        return endpoint.getHost() + ":" + endpoint.getPort();
    }

    /**
     * 将地址集合格式化为可读字符串。
     * <p>当地址超过 2 个时使用省略格式（如"addr1 / addr2 等 5 个"），
     * 避免状态消息过长。</p>
     *
     * @param addresses 地址集合
     * @return 格式化后的地址描述
     */
    private @NotNull String formatAddressSet(@NotNull Set<String> addresses) {
        if (addresses.isEmpty()) {
            return "未发现";
        }
        if (addresses.size() <= 2) {
            return String.join(" / ", addresses);
        }

        Iterator<String> iterator = addresses.iterator();
        String first = iterator.next();
        String second = iterator.next();
        return first + " / " + second + " 等 " + addresses.size() + " 个";
    }

    /**
     * 根据数据来源生成首次加载的状态消息。
     *
     * @param source 数据来源枚举
     * @return 对应的中文状态描述
     */
    private @NotNull String initialLoadMessage(@NotNull ZooKeeperDubboRegistryClient.DataSource source) {
        return switch (source) {
            case MEMORY_CACHE -> "缓存命中（内存），加载完成";
            case DISK_CACHE -> "缓存命中（磁盘），加载完成";
            case NETWORK -> "已从注册中心加载最新数据";
            case FALLBACK_DISK -> "注册中心不可用，已回退到本地缓存";
        };
    }

    /**
     * 将发现快照应用到界面（默认输出状态消息）。
     *
     * @param snapshot 新的发现快照
     * @see #applyDiscovery(DiscoverySnapshot, boolean)
     */
    private void applyDiscovery(@NotNull DiscoverySnapshot snapshot) {
        applyDiscovery(snapshot, true);
    }

    /**
     * 将发现快照应用到界面，更新应用和接口下拉框。
     *
     * <p>核心逻辑：
     * <ol>
     *   <li>替换内存中的快照引用</li>
     *   <li>更新应用下拉框的选项列表</li>
     *   <li>尝试保持原有选中项；如果原选中项已失效，自动切换到第一个应用</li>
     *   <li>同步选中应用下的接口列表</li>
     * </ol>
     *
     * @param snapshot   新的发现快照
     * @param emitStatus 是否在控制台输出状态消息
     */
    private void applyDiscovery(@NotNull DiscoverySnapshot snapshot, boolean emitStatus) {
        if (disposed) {
            return;
        }
        this.discoverySnapshot = snapshot;

        // 临时保存并覆盖静默标志，确保在此方法内部使用正确的 emitStatus
        boolean previousMuteState = muteApplicationSelectionStatus;
        muteApplicationSelectionStatus = !emitStatus;
        List<String> apps = snapshot.getApplications();
        try {
            applicationSearchController.setItems(apps);
            if (apps.isEmpty()) {
                interfaceSearchController.setItems(List.of());
                applicationSearchController.setSelectedItem(null);
                interfaceSearchController.setSelectedItem(null);
                activeApplicationName = null;
                if (emitStatus) {
                    setStatus("未发现应用，请确认 Zookeeper 中存在 Dubbo provider 数据。");
                }
                return;
            }

            String selectedApp = applicationSearchController.getSelectedItem();
            if (selectedApp == null || !apps.contains(selectedApp)) {
                // 当历史选中值已失效（比如应用下线）时，自动回到第一个可用应用。
                selectedApp = apps.get(0);
                applicationSearchController.setSelectedItem(selectedApp);
            }

            syncInterfacesByApplication(selectedApp, emitStatus);
            if (emitStatus) {
                setStatus("已加载 " + snapshot.getApplicationCount() + " 个应用");
            }
        } finally {
            // 无论如何都恢复之前的静默状态
            muteApplicationSelectionStatus = previousMuteState;
        }
    }

    /**
     * 根据应用名同步接口列表到界面（默认输出状态消息）。
     *
     * @param appName 应用名称
     * @see #syncInterfacesByApplication(String, boolean)
     */
    private void syncInterfacesByApplication(@NotNull String appName) {
        syncInterfacesByApplication(appName, true);
    }

    /**
     * 根据应用名同步接口列表到界面。
     *
     * <p>从当前快照中获取指定应用的所有接口，更新接口下拉框，
     * 并尝试保持原有选中项（优先精确匹配，其次按 service+method 匹配）。</p>
     *
     * @param appName    应用名称
     * @param emitStatus 是否在控制台输出状态消息
     */
    private void syncInterfacesByApplication(@NotNull String appName, boolean emitStatus) {
        List<DubboMethodEndpoint> interfaces = discoverySnapshot.getInterfacesForApp(appName);
        DubboMethodEndpoint previousSelection = interfaceSearchController.getSelectedItem();
        interfaceSearchController.setItems(interfaces);
        activeApplicationName = appName;

        if (interfaces.isEmpty()) {
            interfaceSearchController.setSelectedItem(null);
            if (emitStatus) {
                setStatus("应用 " + appName + " 下无可调用接口");
            }
            return;
        }

        // 智能选择：优先保持之前的选中项，保证切换应用后用户体验的连贯性
        DubboMethodEndpoint selected = findBestSelection(interfaces, previousSelection);
        interfaceSearchController.setSelectedItem(selected);

        if (emitStatus) {
            setStatus("应用 " + appName + "（" + selected.getHost() + ":" + selected.getPort() + "） 共 " + interfaces.size()
                    + " 个接口");
        }
    }

    /**
     * 在新的接口列表中为用户选择"最佳"的默认选中项。
     *
     * <p>选择策略（优先级从高到低）：
     * <ol>
     *   <li>精确匹配之前选中的对象（equals）</li>
     *   <li>按 serviceName + methodName 模糊匹配（地址可能变了但接口相同）</li>
     *   <li>兜底选择列表第一项</li>
     * </ol>
     *
     * @param interfaces         新的接口列表
     * @param previousSelection  之前选中的接口（可为 null）
     * @return 最佳选中项
     */
    private @NotNull DubboMethodEndpoint findBestSelection(
            @NotNull List<DubboMethodEndpoint> interfaces,
            @Nullable DubboMethodEndpoint previousSelection
    ) {
        if (previousSelection == null) {
            return interfaces.get(0);
        }

        // 策略 1：精确匹配
        for (DubboMethodEndpoint endpoint : interfaces) {
            if (endpoint.equals(previousSelection)) {
                return endpoint;
            }
        }

        // 策略 2：serviceName + methodName 模糊匹配
        for (DubboMethodEndpoint endpoint : interfaces) {
            if (endpoint.getServiceName().equals(previousSelection.getServiceName())
                    && endpoint.getMethodName().equals(previousSelection.getMethodName())) {
                return endpoint;
            }
        }
        // 策略 3：兜底选择第一项
        return interfaces.get(0);
    }

    /**
     * 调用当前选中的 Dubbo 接口。
     *
     * <p>执行流程：
     * <ol>
     *   <li>获取选中的接口端点</li>
     *   <li>格式化入参文本为 Dubbo invoke 命令的参数表达式</li>
     *   <li>在后台线程执行 Telnet 调用</li>
     *   <li>回到 EDT 展示结果或错误信息</li>
     *   <li>在控制台记录调用地址</li>
     * </ol>
     */
    private void invokeSelectedInterface() {
        DubboMethodEndpoint endpoint = interfaceSearchController.getSelectedItem();
        if (endpoint == null) {
            setStatus("请先选择接口");
            return;
        }

        // 调用前先格式化一次入参，确保发送的 JSON 是规范的
        formatParameterIfJson();
        String argumentExpression = JsonFormatter.toDubboArgumentExpression(parameterTextArea.getText());

        setBusyState(true, "正在调用接口...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String rawResult = telnetClient.invoke(endpoint, argumentExpression);
                // 美化调用结果的 JSON 格式
                String prettyResult = JsonFormatter.prettyInvokeResult(rawResult);

                ApplicationManager.getApplication().invokeLater(() -> {
                    resultTextArea.setText(prettyResult);
                    resultTextArea.setCaretPosition(0);
                    setBusyState(false, "调用成功");
                    logInvocationEndpoint(endpoint);
                });
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    setBusyState(false, "调用失败");
                    resultTextArea.setText(ex.getMessage());
                    resultTextArea.setCaretPosition(0);
                    logInvocationEndpoint(endpoint);
                    setStatus("调用失败: " + ex.getMessage());
                });
            }
        });
    }

    /**
     * 尝试将入参文本区的内容格式化为美化的 JSON。
     * <p>如果内容不是合法 JSON，则保持原样不变。</p>
     */
    private void formatParameterIfJson() {
        String formatted = JsonFormatter.prettyIfJson(parameterTextArea.getText());
        if (!formatted.equals(parameterTextArea.getText())) {
            parameterTextArea.setText(formatted);
        }
    }

    /**
     * 重置入参和结果文本区的内容。
     */
    private void resetInputs() {
        parameterTextArea.setText("");
        resultTextArea.setText("");
        setStatus("已重置入参与结果");
    }

    /**
     * 将当前选中的接口和入参保存为收藏。
     *
     * <p>弹出命名对话框让用户输入收藏名称，
     * 如果名称已存在则更新，否则创建新收藏。</p>
     */
    private void saveCurrentFavorite() {
        DubboMethodEndpoint endpoint = interfaceSearchController.getSelectedItem();
        if (endpoint == null) {
            setStatus("请先选择接口");
            return;
        }

        FavoriteSaveDialog dialog = new FavoriteSaveDialog(
                mainPanel,
                endpoint.getDisplayName()
        );
        if (!dialog.showAndGet()) {
            return;
        }

        try {
            DubboInvokeSettingsService.FavoriteSaveResult result = DubboInvokeSettingsService.getInstance().saveFavorite(
                    dialog.getFavoriteName(),
                    endpoint,
                    parameterTextArea.getText()
            );
            if (result.getAction() == DubboInvokeSettingsService.FavoriteSaveAction.CREATED) {
                setStatus("已收藏: " + result.getFavorite().getName());
                return;
            }
            setStatus("已更新收藏: " + result.getFavorite().getName());
        } catch (IllegalArgumentException ex) {
            setStatus("收藏失败: " + ex.getMessage());
        }
    }

    /**
     * 打开收藏夹浏览对话框，让用户选择一个收藏并应用。
     */
    private void openFavoritesDialog() {
        FavoriteBrowserDialog dialog = new FavoriteBrowserDialog(mainPanel, DubboInvokeSettingsService.getInstance());
        if (!dialog.showAndGet()) {
            return;
        }

        InvokeFavorite favorite = dialog.getSelectedFavorite();
        if (favorite == null) {
            setStatus("未选择收藏接口");
            return;
        }
        applyFavorite(favorite);
    }

    /**
     * 将收藏内容应用到界面：回填入参、切换应用和接口。
     *
     * <p>恢复流程：
     * <ol>
     *   <li>回填入参文本</li>
     *   <li>如果收藏的应用存在于当前快照中，切换到该应用</li>
     *   <li>使用 {@link #applyFavoriteInterfaceSelectionLater(InvokeFavorite)} 延迟匹配接口</li>
     * </ol>
     *
     * @param favorite 要应用的收藏对象
     */
    private void applyFavorite(@NotNull InvokeFavorite favorite) {
        // 先回填入参，再尝试按"应用 + service + method"恢复接口选中状态。
        parameterTextArea.setText(favorite.getParameterText());
        parameterTextArea.setCaretPosition(0);
        formatParameterIfJson();

        List<String> applications = discoverySnapshot.getApplications();
        if (!applications.contains(favorite.getApplication())) {
            setStatus("已回填入参，但未找到应用 " + favorite.getApplication() + "，请刷新后重试");
            return;
        }

        String currentSelectedApp = applicationSearchController.getSelectedItem();
        if (!favorite.getApplication().equals(currentSelectedApp)) {
            // 切换应用会触发 ItemListener，进而重新加载接口列表
            applicationSearchController.setSelectedItem(favorite.getApplication());
        } else {
            // 应用未变化，手动同步接口列表
            syncInterfacesByApplication(favorite.getApplication());
        }
        applyFavoriteInterfaceSelectionLater(favorite);
    }

    /**
     * 延迟匹配并选中收藏对应的接口。
     *
     * <p>为什么使用 invokeLater：
     * 应用切换触发的 ItemListener 会异步刷新接口列表，
     * 如果立即匹配接口，可能接口列表还没更新完成。
     * 使用 invokeLater 确保在当前 EDT 事件全部处理完后再执行匹配。</p>
     *
     * @param favorite 要匹配的收藏对象
     */
    private void applyFavoriteInterfaceSelectionLater(@NotNull InvokeFavorite favorite) {
        // 使用 invokeLater 等待应用切换引起的接口列表刷新完成，再做目标接口匹配。
        SwingUtilities.invokeLater(() -> {
            DubboMethodEndpoint endpoint = findEndpointForFavorite(favorite);
            if (endpoint == null) {
                setStatus("已回填入参，但未找到接口 " + favorite.getInterfaceDisplayName() + "，请刷新后重试");
                return;
            }

            interfaceSearchController.setSelectedItem(endpoint);
            setStatus("已应用收藏: " + favorite.getName());
        });
    }

    /**
     * 在当前快照中查找与收藏匹配的接口端点。
     * <p>按 serviceName + methodName 匹配。</p>
     *
     * @param favorite 收藏对象
     * @return 匹配到的端点；未找到返回 null
     */
    private @Nullable DubboMethodEndpoint findEndpointForFavorite(@NotNull InvokeFavorite favorite) {
        List<DubboMethodEndpoint> endpoints = discoverySnapshot.getInterfacesForApp(favorite.getApplication());
        for (DubboMethodEndpoint endpoint : endpoints) {
            if (favorite.getServiceName().equals(endpoint.getServiceName())
                    && favorite.getMethodName().equals(endpoint.getMethodName())) {
                return endpoint;
            }
        }
        return null;
    }

    /**
     * 从系统剪贴板粘贴文本到入参文本区，并自动格式化。
     */
    private void pasteParameterFromClipboard() {
        String text = CopyPasteManager.getInstance().getContents(DataFlavor.stringFlavor);
        if (text == null || text.isBlank()) {
            setStatus("剪贴板没有可粘贴的文本");
            return;
        }

        parameterTextArea.setText(text);
        parameterTextArea.setCaretPosition(0);
        formatParameterIfJson();
        setStatus("已粘贴入参");
    }

    /**
     * 将指定文本复制到系统剪贴板。
     *
     * @param text       要复制的文本
     * @param targetName 文本来源名称（"入参"或"结果"），用于状态消息提示
     */
    private void copyTextToClipboard(@NotNull String text, @NotNull String targetName) {
        if (text.isBlank()) {
            setStatus(targetName + "为空，未复制");
            return;
        }

        CopyPasteManager.getInstance().setContents(new StringSelection(text));
        setStatus("已复制" + targetName);
    }

    /**
     * 在控制台记录调用的目标地址（host:port），使用端点样式突出显示。
     * <p>如果不在 EDT 上调用，会自动切换到 EDT。</p>
     *
     * @param endpoint 被调用的接口端点
     */
    private void logInvocationEndpoint(@NotNull DubboMethodEndpoint endpoint) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> logInvocationEndpoint(endpoint));
            return;
        }

        String timestamp = LocalTime.now().format(CONSOLE_TIME_FORMATTER);
        String line = "[" + timestamp + "] 调用地址: " + endpoint.getHost() + ":" + endpoint.getPort();
        appendConsoleLine(line, consoleEndpointStyle);
    }

    /**
     * 解析当前选中接口的详细信息（地址、消费者、超时、版本等），并在控制台展示。
     */
    private void resolveSelectedInterfaceAddress() {
        DubboMethodEndpoint endpoint = interfaceSearchController.getSelectedItem();
        if (endpoint == null) {
            setStatus("请先选择接口");
            return;
        }
        logResolvedInterfaceDetail(endpoint);
        updateLatestStatus("接口解析完成", consoleSuccessStyle);
    }

    /**
     * 在控制台输出接口的详细解析信息，包括接口名、地址、消费应用、超时、版本等。
     *
     * <p>每个字段使用 {@link #appendResolvedFieldLine(String, String, SimpleAttributeSet)}
     * 输出，字段标签左对齐，值使用不同样式高亮。</p>
     *
     * @param endpoint 要解析的接口端点
     */
    private void logResolvedInterfaceDetail(@NotNull DubboMethodEndpoint endpoint) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> logResolvedInterfaceDetail(endpoint));
            return;
        }

        String startTimestamp = LocalTime.now().format(CONSOLE_TIME_FORMATTER);
        appendConsoleLine("[" + startTimestamp + "] -------- 解析接口 --------", consoleInfoStyle);
        appendResolvedFieldLine("接口名称", endpoint.getDisplayName(), consoleInfoStyle);
        appendResolvedFieldLine("服务地址", endpoint.getHost() + ":" + endpoint.getPort(), consoleEndpointStyle);

        // 消费应用列表：如果为空则以警告样式提示
        List<String> consumerApplications = endpoint.getConsumerApplications();
        if (consumerApplications.isEmpty()) {
            appendResolvedFieldLine("消费应用", "未发现", consoleWarningStyle);
        } else {
            appendResolvedFieldLine("消费应用", String.join("|", consumerApplications), consoleInfoStyle);
        }

        // 超时时间：如果未配置则以警告样式提示
        Integer timeoutMillis = endpoint.getTimeoutMillis();
        if (timeoutMillis == null) {
            appendResolvedFieldLine("超时时间", "未配置", consoleWarningStyle);
        } else {
            appendResolvedFieldLine("超时时间", timeoutMillis + " ms", consoleInfoStyle);
        }

        // 二方包版本
        String serviceVersion = endpoint.getServiceVersion();
        if (serviceVersion.isBlank()) {
            appendResolvedFieldLine("二方包版本", "未配置", consoleWarningStyle);
        } else {
            appendResolvedFieldLine("二方包版本", serviceVersion, consoleInfoStyle);
        }

        // Dubbo 框架版本
        String dubboVersion = endpoint.getDubboVersion();
        if (dubboVersion.isBlank()) {
            appendResolvedFieldLine("DUBBO版本", "未配置", consoleWarningStyle);
        } else {
            appendResolvedFieldLine("DUBBO版本", dubboVersion, consoleInfoStyle);
        }

        String endTimestamp = LocalTime.now().format(CONSOLE_TIME_FORMATTER);
        appendConsoleLine("[" + endTimestamp + "] -------- 解析结束 --------", consoleSuccessStyle);
    }

    /**
     * 在控制台追加一行"标签: 值"格式的解析字段。
     *
     * <p>标签部分使用信息样式，值部分使用传入的自定义样式。
     * 标签按 {@link #RESOLVE_DETAIL_LABELS} 中最宽标签的像素宽度右补齐，
     * 确保所有行的冒号和值列对齐。</p>
     *
     * @param label      字段标签（如"接口名称"）
     * @param value      字段值
     * @param valueStyle 值的显示样式
     */
    private void appendResolvedFieldLine(
            @NotNull String label,
            @NotNull String value,
            @NotNull SimpleAttributeSet valueStyle
    ) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> appendResolvedFieldLine(label, value, valueStyle));
            return;
        }

        StyledDocument document = consoleTextPane.getStyledDocument();
        try {
            int lineStartOffset = document.getLength();
            if (document.getLength() > 0) {
                document.insertString(document.getLength(), "\n", consoleInfoStyle);
                lineStartOffset = document.getLength();
            }
            String prefix = buildResolvedFieldPrefix(label);
            document.insertString(document.getLength(), prefix, consoleInfoStyle);
            document.insertString(document.getLength(), value, valueStyle);
            // 让超长内容换行后"从值列开始"，保证可读性。
            applyResolvedFieldWrapIndent(document, lineStartOffset, prefix);
            consoleTextPane.setCaretPosition(document.getLength());
        } catch (BadLocationException ignored) {
            // Ignore invalid position errors in UI logging.
        }
    }

    /**
     * 构建解析字段的前缀字符串（"标签  : "），通过空格补齐使冒号对齐。
     *
     * <p>为什么按像素宽度计算：
     * 中文字符与英文字符的等宽字体宽度不同，按字符数对齐会导致错位。
     * 这里使用 FontMetrics 计算实际像素宽度来确定需要补多少空格。</p>
     *
     * @param label 字段标签
     * @return 格式化后的前缀字符串
     */
    private @NotNull String buildResolvedFieldPrefix(@NotNull String label) {
        // 这里按像素宽度计算前缀对齐，不依赖字符数，避免中英文混排造成错位。
        FontMetrics metrics = consoleTextPane.getFontMetrics(consoleTextPane.getFont());
        int targetLabelWidth = 0;
        // 找出所有标签中最宽的像素宽度
        for (String candidate : RESOLVE_DETAIL_LABELS) {
            targetLabelWidth = Math.max(targetLabelWidth, metrics.stringWidth(candidate));
        }

        int labelWidth = metrics.stringWidth(label);
        int spaceWidth = Math.max(1, metrics.charWidth(' '));
        // 计算需要补齐的空格数（至少 1 个）
        int paddingWidth = Math.max(0, targetLabelWidth - labelWidth) + (spaceWidth * 2);
        int spaceCount = Math.max(1, (paddingWidth + spaceWidth - 1) / spaceWidth);
        return label + " ".repeat(spaceCount) + ": ";
    }

    /**
     * 为解析字段行设置段落缩进，使长内容自动换行后从值列起始位置开始。
     *
     * <p>通过设置 {@code leftIndent} 和负的 {@code firstLineIndent}，
     * 实现第一行从头开始，后续换行从前缀宽度处开始的"悬挂缩进"效果。</p>
     *
     * @param document        控制台的 StyledDocument
     * @param lineStartOffset 行起始偏移量
     * @param prefix          前缀字符串（用于计算缩进宽度）
     */
    private void applyResolvedFieldWrapIndent(
            @NotNull StyledDocument document,
            int lineStartOffset,
            @NotNull String prefix
    ) {
        if (lineStartOffset < 0 || lineStartOffset >= document.getLength() || prefix.isEmpty()) {
            return;
        }

        FontMetrics metrics = consoleTextPane.getFontMetrics(consoleTextPane.getFont());
        float prefixWidth = Math.max(0, metrics.stringWidth(prefix));
        if (prefixWidth <= 0) {
            return;
        }

        // 悬挂缩进：leftIndent = 前缀宽度，firstLineIndent = -前缀宽度
        SimpleAttributeSet paragraphStyle = new SimpleAttributeSet();
        StyleConstants.setLeftIndent(paragraphStyle, prefixWidth);
        StyleConstants.setFirstLineIndent(paragraphStyle, -prefixWidth);
        document.setParagraphAttributes(lineStartOffset, document.getLength() - lineStartOffset, paragraphStyle, false);
    }

    /**
     * 切换所有交互控件的启用/禁用状态，并更新状态消息。
     *
     * <p>在执行网络操作（加载、调用）期间禁用所有按钮和下拉框，
     * 防止用户并发操作引起状态混乱。</p>
     *
     * @param busy    true 表示进入忙碌状态（禁用控件），false 表示恢复
     * @param message 状态消息
     */
    private void setBusyState(boolean busy, @NotNull String message) {
        if (disposed) {
            return;
        }
        // 一次性切换所有交互控件，避免某个按钮遗漏导致并发操作。
        refreshButton.setEnabled(!busy);
        invokeButton.setEnabled(!busy);
        resolveAddressButton.setEnabled(!busy);
        favoriteCurrentButton.setEnabled(!busy);
        favoritesButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);
        applicationComboBox.setEnabled(!busy);
        interfaceComboBox.setEnabled(!busy);
        setStatus(message);
    }

    /**
     * 设置控制台状态消息，同时追加到控制台日志。
     *
     * <p>自动完成以下处理：
     * <ul>
     *   <li>将换行符替换为空格（避免破坏控制台布局）</li>
     *   <li>添加时间戳前缀</li>
     *   <li>根据消息内容自动选择日志样式（成功/失败/警告/信息）</li>
     *   <li>更新最新状态标签</li>
     * </ul>
     *
     * <p>线程安全：如果不在 EDT 上调用，会自动切换。</p>
     *
     * @param text 状态消息文本
     */
    private void setStatus(@NotNull String text) {
        if (disposed) {
            return;
        }
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> setStatus(text));
            return;
        }

        String cleanText = text.replace('\n', ' ').trim();
        if (cleanText.isEmpty()) {
            return;
        }

        String timestamp = LocalTime.now().format(CONSOLE_TIME_FORMATTER);
        String logLine = "[" + timestamp + "] " + cleanText;
        SimpleAttributeSet logStyle = resolveConsoleStyle(cleanText);
        appendConsoleLine(logLine, logStyle);
        // latest 区只显示最后一条状态，作为"当前态提示"。
        updateLatestStatus(cleanText, logStyle);
    }

    /**
     * 更新控制台最新状态标签的文本和颜色。
     * <p>该标签位于控制台标题行右侧，提供当前状态的快速一瞥。</p>
     *
     * @param text  状态文本
     * @param style 样式（取其前景色用于标签颜色）
     */
    private void updateLatestStatus(@NotNull String text, @NotNull SimpleAttributeSet style) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> updateLatestStatus(text, style));
            return;
        }
        consoleLatestLabel.setText(text);
        consoleLatestLabel.setForeground(StyleConstants.getForeground(style));
    }

    /**
     * 向控制台 StyledDocument 追加一行日志，并自动滚动到底部。
     *
     * @param logLine  完整的日志行文本（含时间戳）
     * @param logStyle 日志行的显示样式
     */
    private void appendConsoleLine(@NotNull String logLine, @NotNull SimpleAttributeSet logStyle) {
        StyledDocument document = consoleTextPane.getStyledDocument();
        try {
            if (document.getLength() > 0) {
                document.insertString(document.getLength(), "\n", consoleInfoStyle);
            }
            document.insertString(document.getLength(), logLine, logStyle);
            // 将光标移到文档末尾，触发自动滚动
            consoleTextPane.setCaretPosition(document.getLength());
        } catch (BadLocationException ignored) {
            // Ignore invalid position errors in UI logging.
        }
    }

    /**
     * 根据消息内容中的关键词自动推断控制台日志样式。
     *
     * <p>匹配规则（按优先级）：
     * <ol>
     *   <li>包含"失败""错误""异常"等 → 错误样式（红色加粗）</li>
     *   <li>包含"正在""处理中"等 → 运行中样式（蓝色）</li>
     *   <li>包含"成功""完成"等 → 成功样式（绿色）</li>
     *   <li>包含"请先""未发现"等 → 警告样式（黄色）</li>
     *   <li>其他 → 普通信息样式</li>
     * </ol>
     *
     * @param message 消息文本
     * @return 匹配的样式
     */
    private @NotNull SimpleAttributeSet resolveConsoleStyle(@NotNull String message) {
        if (containsAny(message, "失败", "错误", "异常", "error", "exception")) {
            return consoleErrorStyle;
        }
        if (containsAny(message, "正在", "处理中", "loading", "running")) {
            return consoleRunningStyle;
        }
        if (containsAny(message, "成功", "完成", "已加载", "已重置", "success", "completed")) {
            return consoleSuccessStyle;
        }
        if (containsAny(message, "请先", "未发现", "无可", "warning", "注意")) {
            return consoleWarningStyle;
        }
        return consoleInfoStyle;
    }

    /**
     * 判断消息中是否包含任一关键词（大小写不敏感）。
     *
     * @param message 消息文本
     * @param tokens  关键词数组
     * @return true 表示至少匹配到一个关键词
     */
    private boolean containsAny(@NotNull String message, @NotNull String... tokens) {
        String normalized = message.toLowerCase(Locale.ROOT);
        for (String token : tokens) {
            if (normalized.contains(token.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 创建控制台日志样式。
     *
     * @param color 前景色
     * @param bold  是否加粗
     * @return 配置好的 SimpleAttributeSet
     */
    private SimpleAttributeSet createConsoleStyle(@NotNull Color color, boolean bold) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        StyleConstants.setBold(attributes, bold);
        return attributes;
    }

    // ========== 内部数据结构 ==========

    /**
     * 端点唯一标识 record，用于在前后两个快照之间对比同一接口方法。
     * <p>以 (应用名, 服务名, 方法名) 三元组作为唯一键。</p>
     *
     * @param application 应用名称
     * @param serviceName 服务名称（如 com.example.UserService）
     * @param methodName  方法名称（如 getUserById）
     */
    private record EndpointKey(
            @NotNull String application,
            @NotNull String serviceName,
            @NotNull String methodName
    ) {
    }

    /**
     * 单个应用的地址变更摘要。
     *
     * <p>聚合该应用下所有发生地址变更的接口信息，
     * 用于构建面向用户的变更通知消息。</p>
     */
    private static final class ApplicationAddressChangeSummary {

        /** 应用名称 */
        private final String application;

        /** 发生地址变更的接口服务名集合（大小写不敏感排序） */
        private final Set<String> changedInterfaces = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        /** 变更前的地址集合 */
        private final Set<String> oldAddresses = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        /** 变更后的地址集合 */
        private final Set<String> newAddresses = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);

        /**
         * 构造变更摘要。
         *
         * @param application 应用名称
         */
        private ApplicationAddressChangeSummary(@NotNull String application) {
            this.application = application;
        }

        /**
         * 记录一个接口的地址变更。
         *
         * @param serviceName 接口服务名
         * @param oldAddress  变更前地址（host:port）
         * @param newAddress  变更后地址（host:port）
         */
        private void addChange(
                @NotNull String serviceName,
                @NotNull String oldAddress,
                @NotNull String newAddress
        ) {
            changedInterfaces.add(serviceName);
            oldAddresses.add(oldAddress);
            newAddresses.add(newAddress);
        }

        /**
         * 获取应用名称。
         *
         * @return 应用名称
         */
        private @NotNull String getApplication() {
            return application;
        }

        /**
         * 获取发生地址变更的接口数量。
         *
         * @return 变更接口数量
         */
        private int getChangedInterfaceCount() {
            return changedInterfaces.size();
        }

        /**
         * 获取变更前的地址集合。
         *
         * @return 旧地址集合
         */
        private @NotNull Set<String> getOldAddresses() {
            return oldAddresses;
        }

        /**
         * 获取变更后的地址集合。
         *
         * @return 新地址集合
         */
        private @NotNull Set<String> getNewAddresses() {
            return newAddresses;
        }
    }
}
