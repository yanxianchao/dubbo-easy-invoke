package com.github.yanxianchao.dubboeasyinvoke.ui;

import com.github.yanxianchao.dubboeasyinvoke.model.InvokeFavorite;
import com.github.yanxianchao.dubboeasyinvoke.settings.DubboInvokeSettingsService;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.OnePixelSplitter;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBList;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.ui.table.JBTable;
import com.intellij.util.ui.JBUI;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.Box;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 收藏夹浏览与管理对话框。
 *
 * <p><b>职责：</b>为用户提供一个可视化的收藏接口浏览器，支持按应用过滤、关键字搜索、
 * 收藏重命名、删除以及确认选择（回填到主面板）等操作。</p>
 *
 * <p><b>在项目中的角色：</b>作为 Dubbo Easy Invoke 插件的收藏管理入口，
 * 当用户点击"收藏夹"按钮时弹出此对话框。用户可在此浏览历史收藏的 Dubbo 接口调用配置，
 * 选择后点击"确认"即可将收藏内容回填到主调用面板。</p>
 *
 * <p><b>设计思路：</b></p>
 * <ul>
 *   <li>采用经典的"左侧导航 + 右侧内容"布局：左侧为应用列表（含"全部"选项），
 *       右侧为收藏条目表格；</li>
 *   <li>支持实时关键字搜索，匹配范围覆盖收藏名和接口名（service.method）；</li>
 *   <li>表格的"收藏名"列支持原地编辑（单击即可重命名），编辑完成后自动持久化；</li>
 *   <li>所有数据操作（删除、重命名）都通过 {@link DubboInvokeSettingsService} 持久化，
 *       操作后自动重新加载列表以保持 UI 与持久化数据的一致性；</li>
 *   <li>使用 {@link OnePixelSplitter} 实现可拖拽的分割面板，符合 IntelliJ 平台 UI 风格。</li>
 * </ul>
 *
 * <p><b>线程安全性：</b>本类继承自 {@link DialogWrapper}，所有操作在 EDT 上执行，
 * 无多线程并发问题。</p>
 */
public final class FavoriteBrowserDialog extends DialogWrapper {

    /** 左侧应用列表中代表"显示所有应用"的特殊选项文本 */
    private static final String ALL_APPLICATIONS = "全部";

    /** 插件设置服务，用于读取、删除、重命名收藏数据（持久化层） */
    private final DubboInvokeSettingsService settingsService;

    /** 左侧应用列表的数据模型 */
    private final javax.swing.DefaultListModel<String> applicationListModel = new javax.swing.DefaultListModel<>();

    /** 左侧应用列表组件，单选模式，用于按应用过滤右侧收藏表格 */
    private final JBList<String> applicationList = new JBList<>(applicationListModel);

    /** 右侧顶部的关键字搜索框，支持按收藏名或接口名实时过滤 */
    private final JBTextField keywordField = new JBTextField();

    /** 右侧收藏表格的自定义数据模型 */
    private final FavoriteTableModel favoriteTableModel = new FavoriteTableModel();

    /** 右侧收藏表格组件，展示过滤后的收藏条目 */
    private final JBTable favoriteTable = new JBTable(favoriteTableModel);

    /** 删除按钮，删除当前选中的收藏条目 */
    private final JButton deleteFavoriteButton = new JButton("删除");

    /** 全量收藏列表（从 settingsService 加载），作为过滤的数据源 */
    private List<InvokeFavorite> allFavorites = List.of();

    /** 用户最终确认选择的收藏对象，点击"确认"后赋值，供外部调用方读取 */
    private InvokeFavorite selectedFavorite;

    /** 对话框根面板引用，用于在 Messages 弹窗时指定父组件 */
    private JPanel rootPanel;

    /**
     * 抑制左侧应用列表选择事件的标志。
     * <p>在程序化刷新应用列表（如重新加载数据后恢复选中状态）时置为 true，
     * 防止选择变化事件触发不必要的过滤操作。</p>
     */
    private boolean suppressApplicationListEvent;

    /**
     * 构造收藏夹浏览对话框并完成初始化。
     *
     * <p>初始化流程：配置各 UI 组件 → 调用 {@link DialogWrapper#init()} 构建对话框布局 →
     * 加载全量收藏数据并刷新显示 → 更新按钮状态。</p>
     *
     * @param parentComponent 父组件引用，用于对话框定位（可为 null）
     * @param settingsService 插件设置服务实例，提供收藏数据的 CRUD 操作
     */
    public FavoriteBrowserDialog(@Nullable JComponent parentComponent, @NotNull DubboInvokeSettingsService settingsService) {
        super(parentComponent, true);
        this.settingsService = settingsService;
        setTitle("收藏夹");
        setOKButtonText("确认");
        setCancelButtonText("取消");

        configureComponents();
        init();
        reloadFavorites(ALL_APPLICATIONS, null);
        updateActionState();
    }

    /**
     * 获取用户最终确认选择的收藏对象。
     *
     * <p>仅在用户点击"确认"按钮且确实选中了一条收藏后才有值，
     * 其他情况（取消、关闭对话框）返回 null。</p>
     *
     * @return 用户确认的收藏对象，未选择时返回 null
     */
    public @Nullable InvokeFavorite getSelectedFavorite() {
        return selectedFavorite;
    }

    /**
     * 创建对话框的中心面板，构建完整的"左右分栏"布局。
     *
     * <p>布局结构：</p>
     * <pre>
     * ┌─────────────────────────────────────────────────┐
     * │  应用     │  关键字 [________]  [删除]           │
     * │ ┌──────┐  │ ┌─────────────────────────────────┐ │
     * │ │ 全部 │  │ │ 收藏名 │ 应用 │ 接口             │ │
     * │ │ app1 │  │ │ ...    │ ...  │ ...              │ │
     * │ │ app2 │  │ │        │      │                  │ │
     * │ └──────┘  │ └─────────────────────────────────┘ │
     * └─────────────────────────────────────────────────┘
     * </pre>
     *
     * @return 构建完成的中心面板组件
     */
    @Override
    protected @Nullable JComponent createCenterPanel() {
        // --- 左侧面板：应用列表 ---
        JPanel leftPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        leftPanel.add(new JBLabel("应用"), BorderLayout.NORTH);
        leftPanel.add(new JBScrollPane(applicationList), BorderLayout.CENTER);
        leftPanel.setPreferredSize(new Dimension(JBUI.scale(110), JBUI.scale(420)));
        leftPanel.setMinimumSize(new Dimension(JBUI.scale(96), JBUI.scale(300)));

        // --- 右侧顶部：关键字搜索框 + 删除按钮 ---
        int keywordHeight = keywordField.getPreferredSize().height;
        keywordField.setPreferredSize(new Dimension(JBUI.scale(260), keywordHeight));
        keywordField.setMinimumSize(new Dimension(JBUI.scale(220), keywordHeight));
        keywordField.setMaximumSize(new Dimension(JBUI.scale(300), keywordHeight));

        JPanel topActionPanel = new JPanel();
        topActionPanel.setLayout(new javax.swing.BoxLayout(topActionPanel, javax.swing.BoxLayout.X_AXIS));
        // 顶部工具行增加左侧内边距：
        // 1) 避免"关键字"贴着中间分割线；
        // 2) 与下方表格表头文字（如"收藏名"）的起始位置更一致。
        topActionPanel.setBorder(JBUI.Borders.emptyLeft(8));
        topActionPanel.add(new JBLabel("关键字"));
        topActionPanel.add(Box.createHorizontalStrut(JBUI.scale(6)));
        topActionPanel.add(keywordField);
        topActionPanel.add(Box.createHorizontalStrut(JBUI.scale(10)));
        topActionPanel.add(deleteFavoriteButton);
        topActionPanel.add(Box.createHorizontalGlue());

        // --- 右侧面板：工具栏 + 收藏表格 ---
        JPanel rightPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        rightPanel.add(topActionPanel, BorderLayout.NORTH);
        rightPanel.add(new JBScrollPane(favoriteTable), BorderLayout.CENTER);

        // --- 左右分割面板 ---
        OnePixelSplitter splitPane = new OnePixelSplitter(false, 0.14f);
        splitPane.setFirstComponent(leftPanel);
        splitPane.setSecondComponent(rightPanel);
        splitPane.setBorder(JBUI.Borders.empty());

        rootPanel = new JPanel(new BorderLayout());
        rootPanel.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(480)));
        rootPanel.add(splitPane, BorderLayout.CENTER);
        return rootPanel;
    }

    /**
     * 处理"确认"按钮点击事件。
     *
     * <p>在关闭对话框前，先停止可能正在进行的表格编辑，然后校验是否有选中项。
     * 若未选中则弹出提示，不关闭对话框；若已选中则记录选择结果并关闭。</p>
     */
    @Override
    protected void doOKAction() {
        stopEditingIfNeeded();
        InvokeFavorite selected = getSelectedFavoriteFromTable();
        if (selected == null) {
            Messages.showWarningDialog(rootPanel, "请先选择一个收藏接口", "提示");
            return;
        }
        selectedFavorite = selected;
        super.doOKAction();
    }

    /**
     * 配置对话框中各 UI 组件的属性、事件监听器和交互行为。
     *
     * <p>主要配置内容：</p>
     * <ul>
     *   <li>左侧应用列表：单选模式，选择变化时触发过滤；</li>
     *   <li>关键字搜索框：设置占位提示文本，文本变化时触发过滤；</li>
     *   <li>收藏表格：单选模式、列宽设置、表头左对齐、
     *       "收藏名"列支持单击编辑（通过 {@link DefaultCellEditor}）；</li>
     *   <li>删除按钮：绑定删除事件处理。</li>
     * </ul>
     */
    private void configureComponents() {
        // --- 左侧应用列表配置 ---
        applicationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        applicationList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !suppressApplicationListEvent) {
                applyFilters(null);
            }
        });

        // --- 关键字搜索框配置 ---
        keywordField.putClientProperty("JTextField.placeholderText", "匹配收藏名或接口名（service.method）");
        keywordField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                applyFilters(getSelectedFavoriteId());
            }
        });

        // --- 收藏表格配置 ---
        favoriteTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        favoriteTable.setFillsViewportHeight(true);
        favoriteTable.setRowSelectionAllowed(true);
        favoriteTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        // 焦点离开时自动提交编辑，防止编辑内容因切换焦点而丢失
        favoriteTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        favoriteTable.setSurrendersFocusOnKeystroke(true);
        favoriteTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActionState();
            }
        });

        // 列宽设置：收藏名列固定宽度范围，接口列自适应剩余空间
        favoriteTable.getColumnModel().getColumn(0).setMinWidth(JBUI.scale(90));
        favoriteTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(140));
        favoriteTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(220));
        favoriteTable.getColumnModel().getColumn(1).setMinWidth(JBUI.scale(45));
        favoriteTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(70));
        favoriteTable.getColumnModel().getColumn(1).setMaxWidth(JBUI.scale(120));
        favoriteTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(420));
        alignTableHeaderToLeft();

        // "收藏名"列启用单击编辑，降低重命名操作的交互成本
        DefaultCellEditor nameEditor = new DefaultCellEditor(new JBTextField());
        nameEditor.setClickCountToStart(1);
        favoriteTable.getColumnModel().getColumn(0).setCellEditor(nameEditor);

        // --- 删除按钮配置 ---
        deleteFavoriteButton.addActionListener(event -> deleteSelectedFavorite());
    }

    /**
     * 将表格表头的文字对齐方式设置为左对齐。
     *
     * <p>IntelliJ 默认的表头渲染器可能使用居中对齐，
     * 左对齐更符合本对话框的整体布局风格，且与表格内容对齐一致。</p>
     */
    private void alignTableHeaderToLeft() {
        TableCellRenderer defaultHeaderRenderer = favoriteTable.getTableHeader().getDefaultRenderer();
        favoriteTable.getTableHeader().setDefaultRenderer((table, value, isSelected, hasFocus, row, column) -> {
            java.awt.Component component = defaultHeaderRenderer.getTableCellRendererComponent(
                    table,
                    value,
                    isSelected,
                    hasFocus,
                    row,
                    column
            );
            if (component instanceof JLabel label) {
                label.setHorizontalAlignment(SwingConstants.LEFT);
            }
            return component;
        });
    }

    /**
     * 从持久化层重新加载全量收藏数据，并刷新左侧应用列表和右侧收藏表格。
     *
     * <p>该方法在初始化、删除收藏、重命名收藏等场景下被调用，
     * 确保 UI 始终与持久化数据保持一致。</p>
     *
     * @param preferredApplication 希望在左侧应用列表中保持选中的应用名（可为 null）
     * @param preferredFavoriteId  希望在右侧表格中保持选中的收藏 ID（可为 null）
     */
    private void reloadFavorites(@Nullable String preferredApplication, @Nullable String preferredFavoriteId) {
        allFavorites = settingsService.getFavorites();
        refreshApplicationList(preferredApplication);
        applyFilters(preferredFavoriteId);
    }

    /**
     * 刷新左侧应用列表的内容，并恢复之前的选中状态。
     *
     * <p>从全量收藏数据中提取去重后的应用名列表，按字母排序后填充到列表模型中。
     * 列表首项固定为"全部"，表示不按应用过滤。</p>
     *
     * <p>刷新过程中会临时抑制选择事件（{@link #suppressApplicationListEvent}），
     * 防止程序化的选择变化触发不必要的过滤操作。</p>
     *
     * @param preferredApplication 希望恢复选中的应用名，为 null 或空白时沿用当前选中
     */
    private void refreshApplicationList(@Nullable String preferredApplication) {
        // 确定要恢复选中的应用名：优先使用参数指定的，否则沿用当前选中
        String keepSelected = preferredApplication;
        if (keepSelected == null || keepSelected.isBlank()) {
            keepSelected = getSelectedApplicationFilter();
        }
        if (keepSelected == null || keepSelected.isBlank()) {
            keepSelected = ALL_APPLICATIONS;
        }

        // 从全量收藏中提取去重的应用名列表
        List<String> applications = new ArrayList<>();
        for (InvokeFavorite favorite : allFavorites) {
            String application = favorite.getApplication();
            if (application == null || application.isBlank()) {
                continue;
            }
            if (!containsIgnoreCase(applications, application)) {
                applications.add(application);
            }
        }
        applications.sort(String.CASE_INSENSITIVE_ORDER);

        // 抑制选择事件，避免程序化刷新时触发过滤
        suppressApplicationListEvent = true;
        try {
            applicationListModel.clear();
            applicationListModel.addElement(ALL_APPLICATIONS);
            for (String application : applications) {
                applicationListModel.addElement(application);
            }

            // 若之前选中的应用已不存在（如被删除导致），回退到"全部"
            if (!containsApplicationInView(keepSelected)) {
                keepSelected = ALL_APPLICATIONS;
            }
            applicationList.setSelectedValue(keepSelected, true);
        } finally {
            suppressApplicationListEvent = false;
        }
    }

    /**
     * 检查当前应用列表视图中是否包含指定的应用名。
     *
     * @param application 要检查的应用名
     * @return 列表中存在该应用名则返回 true
     */
    private boolean containsApplicationInView(@NotNull String application) {
        for (int i = 0; i < applicationListModel.size(); i++) {
            if (application.equals(applicationListModel.getElementAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断字符串列表中是否包含指定值（大小写不敏感比较）。
     *
     * @param values    待检查的字符串列表
     * @param candidate 要查找的值
     * @return 存在大小写不敏感匹配时返回 true
     */
    private boolean containsIgnoreCase(@NotNull List<String> values, @NotNull String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 应用当前的过滤条件（应用筛选 + 关键字搜索），更新右侧收藏表格的显示内容。
     *
     * <p>过滤顺序：先按左侧选中的应用过滤，再按关键字在收藏名和接口名中匹配。
     * 这样在收藏数量较多时可以尽早排除不相关条目，提高过滤性能。</p>
     *
     * @param preferredFavoriteId 过滤完成后希望恢复选中的收藏 ID（可为 null）
     */
    private void applyFilters(@Nullable String preferredFavoriteId) {
        String selectedApplication = getSelectedApplicationFilter();
        String keyword = normalizeKeyword(keywordField.getText());

        // 过滤顺序：先按应用，再按关键字。这样在收藏很多时性能更稳一些。
        List<InvokeFavorite> filtered = new ArrayList<>();
        for (InvokeFavorite favorite : allFavorites) {
            // 第一层过滤：应用名匹配
            if (!ALL_APPLICATIONS.equals(selectedApplication)
                    && !selectedApplication.equals(favorite.getApplication())) {
                continue;
            }

            // 第二层过滤：关键字匹配（收藏名或接口名）
            if (!keyword.isEmpty()) {
                boolean matchedByFavoriteName = containsKeyword(favorite.getName(), keyword);
                boolean matchedByInterfaceName = containsKeyword(favorite.getInterfaceDisplayName(), keyword);
                if (!matchedByFavoriteName && !matchedByInterfaceName) {
                    continue;
                }
            }

            filtered.add(favorite);
        }

        favoriteTableModel.setRows(filtered);
        restoreTableSelection(preferredFavoriteId);
        updateActionState();
    }

    /**
     * 在过滤后恢复表格的选中行。
     *
     * <p>优先按指定的收藏 ID 定位并选中对应行；
     * 若 ID 为 null 或未找到，则默认选中第一行。</p>
     *
     * @param preferredFavoriteId 希望选中的收藏 ID（可为 null）
     */
    private void restoreTableSelection(@Nullable String preferredFavoriteId) {
        if (favoriteTableModel.getRowCount() == 0) {
            return;
        }

        if (preferredFavoriteId != null) {
            int index = favoriteTableModel.findRowIndex(preferredFavoriteId);
            if (index >= 0) {
                favoriteTable.setRowSelectionInterval(index, index);
                return;
            }
        }

        // 默认选中第一行，确保表格始终有选中项（方便用户快速操作）
        favoriteTable.setRowSelectionInterval(0, 0);
    }

    /**
     * 从表格当前选中行获取对应的收藏对象。
     *
     * <p>会通过 {@link JBTable#convertRowIndexToModel} 将视图行索引转换为模型行索引，
     * 以正确处理表格排序/过滤后行号不一致的情况。</p>
     *
     * @return 当前选中行对应的收藏对象，无选中行时返回 null
     */
    private @Nullable InvokeFavorite getSelectedFavoriteFromTable() {
        int row = favoriteTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = favoriteTable.convertRowIndexToModel(row);
        return favoriteTableModel.getFavoriteAt(modelRow);
    }

    /**
     * 获取当前表格选中行对应的收藏 ID。
     *
     * @return 选中收藏的 ID，无选中行时返回 null
     */
    private @Nullable String getSelectedFavoriteId() {
        InvokeFavorite selected = getSelectedFavoriteFromTable();
        return selected == null ? null : selected.getId();
    }

    /**
     * 获取左侧应用列表当前选中的应用名。
     *
     * @return 当前选中的应用名，无选中或为空时返回 {@link #ALL_APPLICATIONS}
     */
    private @NotNull String getSelectedApplicationFilter() {
        String selected = applicationList.getSelectedValue();
        if (selected == null || selected.isBlank()) {
            return ALL_APPLICATIONS;
        }
        return selected;
    }

    /**
     * 根据表格选中状态更新"确认"按钮和"删除"按钮的可用性。
     *
     * <p>仅当有选中行时按钮才可用，防止用户在无选中项时执行无效操作。</p>
     */
    private void updateActionState() {
        boolean hasSelection = getSelectedFavoriteFromTable() != null;
        setOKActionEnabled(hasSelection);
        deleteFavoriteButton.setEnabled(hasSelection);
    }

    /**
     * 删除当前选中的收藏条目。
     *
     * <p>执行流程：停止编辑 → 校验选中项 → 弹出二次确认对话框 →
     * 调用 settingsService 删除 → 重新加载列表。</p>
     *
     * <p>删除失败时会弹出错误提示框，不会静默吞掉异常。</p>
     */
    private void deleteSelectedFavorite() {
        stopEditingIfNeeded();
        InvokeFavorite selected = getSelectedFavoriteFromTable();
        if (selected == null) {
            Messages.showWarningDialog(rootPanel, "请先选择要删除的收藏", "提示");
            return;
        }

        // 二次确认，防止误删
        int confirm = Messages.showYesNoDialog(
                rootPanel,
                "确认删除收藏“" + selected.getName() + "”？",
                "删除收藏",
                Messages.getWarningIcon()
        );
        if (confirm != Messages.YES) {
            return;
        }

        // 记录当前应用过滤状态，删除后恢复
        String selectedApplication = getSelectedApplicationFilter();
        try {
            settingsService.deleteFavorite(selected.getId());
            reloadFavorites(selectedApplication, null);
        } catch (IllegalArgumentException ex) {
            Messages.showErrorDialog(rootPanel, ex.getMessage(), "删除失败");
        }
    }

    /**
     * 处理收藏名编辑完成事件（表格单元格编辑提交时触发）。
     *
     * <p>校验新名称的有效性（非空、是否有变化），然后通过 settingsService 持久化重命名。
     * 重命名成功后重新加载整个列表，确保排序、过滤、左侧应用列表等保持一致。</p>
     *
     * @param rowIndex 被编辑的行索引（模型层）
     * @param value    编辑后的新值（来自表格编辑器，可能为 null）
     */
    private void handleFavoriteNameEdited(int rowIndex, @Nullable Object value) {
        InvokeFavorite target = favoriteTableModel.getFavoriteAt(rowIndex);
        if (target == null) {
            return;
        }

        String newName = value == null ? "" : value.toString().trim();
        if (newName.isEmpty()) {
            Messages.showErrorDialog(rootPanel, "收藏名不能为空", "修改失败");
            // 刷新该行显示，恢复原始名称
            favoriteTableModel.fireTableRowsUpdated(rowIndex, rowIndex);
            return;
        }

        // 名称无变化时无需操作
        if (newName.equals(target.getName())) {
            return;
        }

        String selectedApplication = getSelectedApplicationFilter();
        try {
            // 重命名后整表重载，确保排序/过滤/左侧应用列表保持一致。
            InvokeFavorite updated = settingsService.renameFavoriteName(target.getId(), newName);
            reloadFavorites(selectedApplication, updated.getId());
        } catch (IllegalArgumentException ex) {
            Messages.showErrorDialog(rootPanel, ex.getMessage(), "修改失败");
            // 重命名失败时也需重载，以恢复原始状态
            reloadFavorites(selectedApplication, target.getId());
        }
    }

    /**
     * 如果表格正在编辑状态，则提交编辑并停止。
     *
     * <p>在执行删除、确认等操作前调用，确保未提交的编辑内容先被保存。</p>
     */
    private void stopEditingIfNeeded() {
        if (favoriteTable.isEditing()) {
            favoriteTable.getCellEditor().stopCellEditing();
        }
    }

    /**
     * 判断文本中是否包含指定关键字（大小写不敏感）。
     *
     * @param text    被搜索的文本
     * @param keyword 已归一化的小写关键字（由 {@link #normalizeKeyword} 处理后传入）
     * @return 包含关键字时返回 true
     */
    private boolean containsKeyword(@NotNull String text, @NotNull String keyword) {
        return text.toLowerCase(Locale.ROOT).contains(keyword);
    }

    /**
     * 归一化关键字：去除首尾空白并转为小写，方便后续的大小写不敏感匹配。
     *
     * @param text 原始关键字文本（可能为 null）
     * @return 归一化后的关键字，null 输入返回空字符串
     */
    private @NotNull String normalizeKeyword(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 收藏表格的自定义数据模型。
     *
     * <p><b>职责：</b>管理收藏表格的行数据，提供表格渲染和编辑所需的接口。</p>
     *
     * <p><b>列定义：</b></p>
     * <ul>
     *   <li>第 0 列 —— "收藏名"：可编辑，单击即进入编辑模式，编辑完成后触发重命名逻辑；</li>
     *   <li>第 1 列 —— "应用"：只读，显示收藏所属的应用名；</li>
     *   <li>第 2 列 —— "接口"：只读，显示接口的完整名称（service.method 格式）。</li>
     * </ul>
     *
     * <p><b>线程安全性：</b>仅在 EDT 上操作，无需额外同步。</p>
     */
    private final class FavoriteTableModel extends AbstractTableModel {

        /** 表格列名定义 */
        private static final String[] COLUMNS = {"收藏名", "应用", "接口"};

        /** 当前过滤后的收藏行数据 */
        private final List<InvokeFavorite> rows = new ArrayList<>();

        /**
         * 返回当前过滤后的行数。
         *
         * @return 行数
         */
        @Override
        public int getRowCount() {
            return rows.size();
        }

        /**
         * 返回表格列数（固定 3 列：收藏名、应用、接口）。
         *
         * @return 列数，固定为 3
         */
        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        /**
         * 返回指定列的列名。
         *
         * @param column 列索引
         * @return 列名
         */
        @Override
        public @NotNull String getColumnName(int column) {
            return COLUMNS[column];
        }

        /**
         * 判断指定单元格是否可编辑。
         *
         * <p>仅第 0 列（收藏名）可编辑，其他列为只读。</p>
         *
         * @param rowIndex    行索引
         * @param columnIndex 列索引
         * @return 第 0 列返回 true，其余列返回 false
         */
        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        /**
         * 处理单元格值被编辑后的提交操作。
         *
         * <p>仅处理第 0 列（收藏名）的编辑，
         * 委托给 {@link FavoriteBrowserDialog#handleFavoriteNameEdited} 执行实际的重命名逻辑。</p>
         *
         * @param aValue      编辑后的新值
         * @param rowIndex    行索引
         * @param columnIndex 列索引
         */
        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 0) {
                return;
            }
            handleFavoriteNameEdited(rowIndex, aValue);
        }

        /**
         * 获取指定单元格的显示值。
         *
         * @param rowIndex    行索引
         * @param columnIndex 列索引（0=收藏名，1=应用，2=接口）
         * @return 对应单元格的文本值，无效索引返回空字符串
         */
        @Override
        public @Nullable Object getValueAt(int rowIndex, int columnIndex) {
            InvokeFavorite favorite = getFavoriteAt(rowIndex);
            if (favorite == null) {
                return "";
            }
            return switch (columnIndex) {
                case 0 -> favorite.getName();
                case 1 -> favorite.getApplication();
                case 2 -> favorite.getInterfaceDisplayName();
                default -> "";
            };
        }

        /**
         * 安全地获取指定行索引对应的收藏对象。
         *
         * @param rowIndex 行索引
         * @return 对应的收藏对象，索引越界时返回 null
         */
        private @Nullable InvokeFavorite getFavoriteAt(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                return null;
            }
            return rows.get(rowIndex);
        }

        /**
         * 在当前行数据中查找指定收藏 ID 对应的行索引。
         *
         * @param favoriteId 要查找的收藏 ID
         * @return 行索引（从 0 开始），未找到时返回 -1
         */
        private int findRowIndex(@NotNull String favoriteId) {
            for (int i = 0; i < rows.size(); i++) {
                if (favoriteId.equals(rows.get(i).getId())) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 替换表格的全部行数据并通知表格刷新。
         *
         * <p>该方法在每次过滤操作后被调用，用过滤结果替换当前显示的行数据。</p>
         *
         * @param favorites 新的行数据列表
         */
        private void setRows(@NotNull List<InvokeFavorite> favorites) {
            rows.clear();
            rows.addAll(favorites);
            fireTableDataChanged();
        }
    }
}
