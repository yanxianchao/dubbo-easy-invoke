package com.github.yanxianchao.dubboeasyinvoke.ui;

import com.github.yanxianchao.dubboeasyinvoke.invoke.DubboTelnetClient;
import com.github.yanxianchao.dubboeasyinvoke.model.DiscoverySnapshot;
import com.github.yanxianchao.dubboeasyinvoke.model.DubboMethodEndpoint;
import com.github.yanxianchao.dubboeasyinvoke.registry.ZooKeeperDubboRegistryClient;
import com.github.yanxianchao.dubboeasyinvoke.settings.DubboInvokeSettingsService;
import com.github.yanxianchao.dubboeasyinvoke.util.JsonFormatter;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.JBColor;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextArea;
import com.intellij.util.ui.FormBuilder;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.JTextField;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.Color;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.ItemEvent;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public final class DubboInvokePanel {

    private static final DateTimeFormatter CONSOLE_TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JPanel mainPanel;

    private final ComboBox<String> applicationComboBox = new ComboBox<>();
    private final ComboBox<DubboMethodEndpoint> interfaceComboBox = new ComboBox<>();
    private final SearchableComboBoxController<String> applicationSearchController;
    private final SearchableComboBoxController<DubboMethodEndpoint> interfaceSearchController;

    private final JButton refreshButton = new JButton("刷新");
    private final JButton pasteParamButton = new JButton("粘贴入参");
    private final JButton copyParamButton = new JButton("复制入参");
    private final JButton copyResultButton = new JButton("复制结果");
    private final JButton invokeButton = new JButton("调用接口");
    private final JButton resolveAddressButton = new JButton("解析地址");
    private final JButton resetButton = new JButton("重置");

    private final JBTextArea parameterTextArea = new JBTextArea();
    private final JBTextArea resultTextArea = new JBTextArea();
    private final JTextPane consoleTextPane = new JTextPane();
    private final JBLabel consoleLatestLabel = new JBLabel("准备就绪");
    private final javax.swing.Timer parameterAutoFormatTimer = new javax.swing.Timer(450, event -> formatParameterIfJson());
    private final SimpleAttributeSet consoleInfoStyle = createConsoleStyle(JBColor.foreground(), false);
    private final SimpleAttributeSet consoleRunningStyle = createConsoleStyle(
            new JBColor(new Color(0x0B57D0), new Color(0x89B4FF)),
            false
    );
    private final SimpleAttributeSet consoleSuccessStyle = createConsoleStyle(
            new JBColor(new Color(0x1B5E20), new Color(0x8BCF8C)),
            false
    );
    private final SimpleAttributeSet consoleWarningStyle = createConsoleStyle(
            new JBColor(new Color(0x9C640C), new Color(0xF0C674)),
            false
    );
    private final SimpleAttributeSet consoleErrorStyle = createConsoleStyle(
            new JBColor(new Color(0xB71C1C), new Color(0xFF8A80)),
            true
    );
    private final SimpleAttributeSet consoleEndpointStyle = createConsoleStyle(
            new JBColor(new Color(0x005A9E), new Color(0x9CDCFE)),
            true
    );

    private final ZooKeeperDubboRegistryClient registryClient = new ZooKeeperDubboRegistryClient();
    private final DubboTelnetClient telnetClient = new DubboTelnetClient();
    private final AtomicLong refreshRequestSeq = new AtomicLong(0);

    private volatile DiscoverySnapshot discoverySnapshot = DiscoverySnapshot.empty();

    public DubboInvokePanel() {
        this.applicationSearchController = new SearchableComboBoxController<>(applicationComboBox, value -> value);
        this.interfaceSearchController = new SearchableComboBoxController<>(interfaceComboBox, DubboMethodEndpoint::getDisplayName);

        initComboEditors();
        initTextAreas();

        mainPanel = buildLayout();
        bindActions();
        reloadApplications(false);
    }

    public @NotNull JComponent getComponent() {
        return mainPanel;
    }

    private void initComboEditors() {
        Object appEditor = applicationComboBox.getEditor().getEditorComponent();
        if (appEditor instanceof JTextField appField) {
            appField.putClientProperty("JTextField.placeholderText", "输入关键字筛选应用");
        }

        Object interfaceEditor = interfaceComboBox.getEditor().getEditorComponent();
        if (interfaceEditor instanceof JTextField interfaceField) {
            interfaceField.putClientProperty("JTextField.placeholderText", "输入关键字筛选接口");
        }
    }

    private void initTextAreas() {
        Font mono = new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(13));
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

        parameterTextArea.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                formatParameterIfJson();
            }
        });
        parameterTextArea.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                parameterAutoFormatTimer.restart();
            }
        });

        consoleTextPane.setFont(new Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(12)));
        consoleTextPane.setEditable(false);
        consoleLatestLabel.setHorizontalAlignment(SwingConstants.RIGHT);
        consoleLatestLabel.setForeground(JBColor.GRAY);
    }

    private @NotNull JPanel buildLayout() {
        int appComboHeight = applicationComboBox.getPreferredSize().height;
        Dimension appPreferredSize = new Dimension(JBUI.scale(170), appComboHeight);
        Dimension appMinimumSize = new Dimension(JBUI.scale(100), appComboHeight);
        Dimension appMaximumSize = new Dimension(JBUI.scale(170), appComboHeight);
        applicationComboBox.setPreferredSize(appPreferredSize);
        applicationComboBox.setMinimumSize(appMinimumSize);
        applicationComboBox.setMaximumSize(appMaximumSize);

        int interfaceComboHeight = interfaceComboBox.getPreferredSize().height;
        interfaceComboBox.setPreferredSize(new Dimension(JBUI.scale(420), interfaceComboHeight));
        interfaceComboBox.setMinimumSize(new Dimension(JBUI.scale(180), interfaceComboHeight));
        interfaceComboBox.setMaximumSize(new Dimension(Integer.MAX_VALUE, interfaceComboHeight));

        JPanel appActionPanel = new JPanel();
        appActionPanel.setLayout(new javax.swing.BoxLayout(appActionPanel, javax.swing.BoxLayout.X_AXIS));
        appActionPanel.add(refreshButton);
        appActionPanel.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)));
        appActionPanel.add(pasteParamButton);
        appActionPanel.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)));
        appActionPanel.add(copyParamButton);
        appActionPanel.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(6)));
        appActionPanel.add(copyResultButton);

        JPanel appRow = new JPanel();
        appRow.setLayout(new javax.swing.BoxLayout(appRow, javax.swing.BoxLayout.X_AXIS));
        appRow.add(applicationComboBox);
        appRow.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(8)));
        appRow.add(appActionPanel);
        appRow.add(javax.swing.Box.createHorizontalGlue());
        installAdaptiveAppComboWidth(appRow, appActionPanel, appComboHeight);

        JPanel interfaceRow = new JPanel(new BorderLayout());
        interfaceRow.add(interfaceComboBox, BorderLayout.CENTER);

        JPanel actionPanel = new JPanel();
        actionPanel.setLayout(new javax.swing.BoxLayout(actionPanel, javax.swing.BoxLayout.Y_AXIS));
        invokeButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        resolveAddressButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        resetButton.setAlignmentX(JComponent.CENTER_ALIGNMENT);
        actionPanel.add(invokeButton);
        actionPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
        actionPanel.add(resolveAddressButton);
        actionPanel.add(javax.swing.Box.createVerticalStrut(JBUI.scale(8)));
        actionPanel.add(resetButton);

        JBScrollPane parameterScroll = new JBScrollPane(parameterTextArea);
        parameterScroll.setPreferredSize(new Dimension(420, 320));
        parameterScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        parameterScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        JPanel parameterRow = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        parameterRow.add(parameterScroll, BorderLayout.CENTER);
        parameterRow.add(actionPanel, BorderLayout.EAST);

        JBScrollPane resultScroll = new JBScrollPane(resultTextArea);
        resultScroll.setPreferredSize(new Dimension(420, 210));
        resultScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        resultScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        JBLabel consoleTitleLabel = new JBLabel("Console");
        consoleTitleLabel.setFont(consoleTitleLabel.getFont().deriveFont(Font.BOLD));

        JPanel consoleHeader = new JPanel(new BorderLayout(JBUI.scale(8), 0));
        consoleHeader.add(consoleTitleLabel, BorderLayout.WEST);
        consoleHeader.add(consoleLatestLabel, BorderLayout.CENTER);

        JBScrollPane consoleScroll = new JBScrollPane(consoleTextPane);
        consoleScroll.setPreferredSize(new Dimension(420, 140));
        consoleScroll.setMinimumSize(new Dimension(50, JBUI.scale(110)));
        consoleScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        consoleScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED);

        JPanel consolePanel = new JPanel(new BorderLayout(JBUI.scale(8), JBUI.scale(6)));
        consolePanel.setBorder(JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)
        ));
        consolePanel.setMinimumSize(new Dimension(80, JBUI.scale(140)));
        consolePanel.add(consoleHeader, BorderLayout.NORTH);
        consolePanel.add(consoleScroll, BorderLayout.CENTER);

        JPanel topPanel = FormBuilder.createFormBuilder()
                .addLabeledComponent("应用", appRow, 1, false)
                .addLabeledComponent("接口", interfaceRow, 1, false)
                .addLabeledComponent("入参", parameterRow, 1, false)
                .addLabeledComponent("结果", resultScroll, 1, false)
                .getPanel();

        JPanel rootPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        rootPanel.setBorder(JBUI.Borders.empty(12, 12, 0, 12));
        rootPanel.add(topPanel, BorderLayout.CENTER);
        rootPanel.add(consolePanel, BorderLayout.SOUTH);
        return rootPanel;
    }

    private void installAdaptiveAppComboWidth(@NotNull JPanel appRow, @NotNull JPanel appActionPanel, int comboHeight) {
        final int minWidth = JBUI.scale(100);
        final int maxWidth = JBUI.scale(170);
        final int gapWidth = JBUI.scale(8);

        Runnable adjustWidth = () -> {
            int rowWidth = appRow.getWidth();
            if (rowWidth <= 0) {
                return;
            }

            int actionWidth = appActionPanel.getPreferredSize().width;
            int available = Math.max(minWidth, rowWidth - actionWidth - gapWidth);
            int targetWidth = available;
            targetWidth = Math.max(minWidth, Math.min(maxWidth, targetWidth));

            Dimension targetSize = new Dimension(targetWidth, comboHeight);
            if (!targetSize.equals(applicationComboBox.getPreferredSize())) {
                applicationComboBox.setPreferredSize(targetSize);
                applicationComboBox.setMinimumSize(new Dimension(minWidth, comboHeight));
                applicationComboBox.setMaximumSize(new Dimension(maxWidth, comboHeight));
                appRow.revalidate();
            }
        };

        appRow.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                adjustWidth.run();
            }
        });
        SwingUtilities.invokeLater(adjustWidth);
    }

    private void bindActions() {
        refreshButton.addActionListener(event -> reloadApplications(true));
        pasteParamButton.addActionListener(event -> pasteParameterFromClipboard());
        copyParamButton.addActionListener(event -> copyTextToClipboard(parameterTextArea.getText(), "入参"));
        copyResultButton.addActionListener(event -> copyTextToClipboard(resultTextArea.getText(), "结果"));
        invokeButton.addActionListener(event -> invokeSelectedInterface());
        resolveAddressButton.addActionListener(event -> resolveSelectedInterfaceAddress());
        resetButton.addActionListener(event -> resetInputs());

        applicationComboBox.addItemListener(event -> {
            if (event.getStateChange() != ItemEvent.SELECTED || !(event.getItem() instanceof String appName)) {
                return;
            }

            if (!discoverySnapshot.getApplications().contains(appName)) {
                return;
            }

            // 应用发生变化时，先清空接口列表与输入状态，再加载新应用的接口。
            interfaceSearchController.setSelectedItem(null);
            interfaceSearchController.setItems(List.of());
            syncInterfacesByApplication(appName);
        });
    }

    private void reloadApplications(boolean forceRefresh) {
        String zkAddress = DubboInvokeSettingsService.getInstance().getZookeeperAddress();
        if (zkAddress.isBlank()) {
            setStatus("请先在 Settings > Tools > Dubbo Easy Invoke 配置 Zookeeper 地址。");
            return;
        }

        long requestId = refreshRequestSeq.incrementAndGet();
        setBusyState(true, "正在加载应用和接口...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ZooKeeperDubboRegistryClient.DiscoverResult result = registryClient.discoverWithSource(zkAddress, forceRefresh);
                DiscoverySnapshot snapshot = result.getSnapshot();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isStaleRefreshRequest(requestId)) {
                        return;
                    }
                    applyDiscovery(snapshot);
                    setBusyState(false, initialLoadMessage(result.getSource()));
                    if (!forceRefresh) {
                        startBackgroundRefreshIfNeeded(requestId, zkAddress, snapshot, result.getSource());
                    }
                });
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isStaleRefreshRequest(requestId)) {
                        return;
                    }
                    setBusyState(false, "加载失败");
                    setStatus("加载失败: " + ex.getMessage());
                });
            }
        });
    }

    private void startBackgroundRefreshIfNeeded(
            long requestId,
            @NotNull String zkAddress,
            @NotNull DiscoverySnapshot baselineSnapshot,
            @NotNull ZooKeeperDubboRegistryClient.DataSource initialSource
    ) {
        if (initialSource == ZooKeeperDubboRegistryClient.DataSource.NETWORK) {
            return;
        }

        setStatus("已展示缓存，正在后台刷新最新应用和接口...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                ZooKeeperDubboRegistryClient.DiscoverResult latest = registryClient.discoverWithSource(zkAddress, true);
                DiscoverySnapshot latestSnapshot = latest.getSnapshot();
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isStaleRefreshRequest(requestId)) {
                        return;
                    }

                    if (latestSnapshot.equals(baselineSnapshot)) {
                        setStatus("后台刷新完成，当前数据已是最新");
                        return;
                    }

                    applyDiscovery(latestSnapshot);
                    setStatus("后台已刷新到最新应用和接口");
                });
            } catch (Exception ex) {
                ApplicationManager.getApplication().invokeLater(() -> {
                    if (isStaleRefreshRequest(requestId)) {
                        return;
                    }
                    setStatus("后台刷新失败: " + ex.getMessage());
                });
            }
        });
    }

    private boolean isStaleRefreshRequest(long requestId) {
        return requestId != refreshRequestSeq.get();
    }

    private @NotNull String initialLoadMessage(@NotNull ZooKeeperDubboRegistryClient.DataSource source) {
        return switch (source) {
            case MEMORY_CACHE -> "缓存命中（内存），加载完成";
            case DISK_CACHE -> "缓存命中（磁盘），加载完成";
            case NETWORK -> "已从注册中心加载最新数据";
            case FALLBACK_DISK -> "注册中心不可用，已回退到本地缓存";
        };
    }

    private void applyDiscovery(@NotNull DiscoverySnapshot snapshot) {
        this.discoverySnapshot = snapshot;

        List<String> apps = snapshot.getApplications();
        applicationSearchController.setItems(apps);
        if (apps.isEmpty()) {
            interfaceSearchController.setItems(List.of());
            applicationSearchController.setSelectedItem(null);
            interfaceSearchController.setSelectedItem(null);
            setStatus("未发现应用，请确认 Zookeeper 中存在 Dubbo provider 数据。");
            return;
        }

        String selectedApp = applicationSearchController.getSelectedItem();
        if (selectedApp == null || !apps.contains(selectedApp)) {
            selectedApp = apps.get(0);
            applicationSearchController.setSelectedItem(selectedApp);
        }

        syncInterfacesByApplication(selectedApp);
        setStatus("已加载 " + snapshot.getApplicationCount() + " 个应用");
    }

    private void syncInterfacesByApplication(@NotNull String appName) {
        List<DubboMethodEndpoint> interfaces = discoverySnapshot.getInterfacesForApp(appName);
        interfaceSearchController.setItems(interfaces);

        if (interfaces.isEmpty()) {
            interfaceSearchController.setSelectedItem(null);
            setStatus("应用 " + appName + " 下无可调用接口");
            return;
        }

        DubboMethodEndpoint selected = interfaceSearchController.getSelectedItem();
        if (selected == null || !interfaces.contains(selected)) {
            interfaceSearchController.setSelectedItem(interfaces.get(0));
        }

        setStatus("应用 " + appName + " 共 " + interfaces.size() + " 个接口");
    }

    private void invokeSelectedInterface() {
        DubboMethodEndpoint endpoint = interfaceSearchController.getSelectedItem();
        if (endpoint == null) {
            setStatus("请先选择接口");
            return;
        }

        formatParameterIfJson();
        String argumentExpression = JsonFormatter.toDubboArgumentExpression(parameterTextArea.getText());

        setBusyState(true, "正在调用接口...");
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            try {
                String rawResult = telnetClient.invoke(endpoint, argumentExpression);
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

    private void formatParameterIfJson() {
        String formatted = JsonFormatter.prettyIfJson(parameterTextArea.getText());
        if (!formatted.equals(parameterTextArea.getText())) {
            parameterTextArea.setText(formatted);
        }
    }

    private void resetInputs() {
        parameterTextArea.setText("");
        resultTextArea.setText("");
        setStatus("已重置入参与结果");
    }

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

    private void copyTextToClipboard(@NotNull String text, @NotNull String targetName) {
        if (text.isBlank()) {
            setStatus(targetName + "为空，未复制");
            return;
        }

        CopyPasteManager.getInstance().setContents(new StringSelection(text));
        setStatus("已复制" + targetName);
    }

    private void logInvocationEndpoint(@NotNull DubboMethodEndpoint endpoint) {
        if (!ApplicationManager.getApplication().isDispatchThread()) {
            ApplicationManager.getApplication().invokeLater(() -> logInvocationEndpoint(endpoint));
            return;
        }

        String timestamp = LocalTime.now().format(CONSOLE_TIME_FORMATTER);
        String line = "[" + timestamp + "] 调用地址: " + endpoint.getHost() + ":" + endpoint.getPort();
        appendConsoleLine(line, consoleEndpointStyle);
    }

    private void resolveSelectedInterfaceAddress() {
        DubboMethodEndpoint endpoint = interfaceSearchController.getSelectedItem();
        if (endpoint == null) {
            setStatus("请先选择接口");
            return;
        }
        logInvocationEndpoint(endpoint);
    }

    private void setBusyState(boolean busy, @NotNull String message) {
        refreshButton.setEnabled(!busy);
        invokeButton.setEnabled(!busy);
        resolveAddressButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);
        applicationComboBox.setEnabled(!busy);
        interfaceComboBox.setEnabled(!busy);
        setStatus(message);
    }

    private void setStatus(@NotNull String text) {
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
        consoleLatestLabel.setText(cleanText);
        consoleLatestLabel.setForeground(StyleConstants.getForeground(logStyle));
    }

    private void appendConsoleLine(@NotNull String logLine, @NotNull SimpleAttributeSet logStyle) {
        StyledDocument document = consoleTextPane.getStyledDocument();
        try {
            if (document.getLength() > 0) {
                document.insertString(document.getLength(), "\n", consoleInfoStyle);
            }
            document.insertString(document.getLength(), logLine, logStyle);
            consoleTextPane.setCaretPosition(document.getLength());
        } catch (BadLocationException ignored) {
            // Ignore invalid position errors in UI logging.
        }
    }

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

    private boolean containsAny(@NotNull String message, @NotNull String... tokens) {
        String normalized = message.toLowerCase();
        for (String token : tokens) {
            if (normalized.contains(token.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private SimpleAttributeSet createConsoleStyle(@NotNull Color color, boolean bold) {
        SimpleAttributeSet attributes = new SimpleAttributeSet();
        StyleConstants.setForeground(attributes, color);
        StyleConstants.setBold(attributes, bold);
        return attributes;
    }
}
