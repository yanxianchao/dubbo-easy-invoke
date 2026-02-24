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
 * 收藏夹弹窗：
 * 左侧按应用过滤，右侧展示收藏列表，并支持搜索、重命名、删除与确认回填。
 */
public final class FavoriteBrowserDialog extends DialogWrapper {

    private static final String ALL_APPLICATIONS = "全部";

    private final DubboInvokeSettingsService settingsService;

    private final javax.swing.DefaultListModel<String> applicationListModel = new javax.swing.DefaultListModel<>();
    private final JBList<String> applicationList = new JBList<>(applicationListModel);
    private final JBTextField keywordField = new JBTextField();
    private final FavoriteTableModel favoriteTableModel = new FavoriteTableModel();
    private final JBTable favoriteTable = new JBTable(favoriteTableModel);
    private final JButton deleteFavoriteButton = new JButton("删除");

    private List<InvokeFavorite> allFavorites = List.of();
    private InvokeFavorite selectedFavorite;
    private JPanel rootPanel;
    private boolean suppressApplicationListEvent;

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

    public @Nullable InvokeFavorite getSelectedFavorite() {
        return selectedFavorite;
    }

    @Override
    protected @Nullable JComponent createCenterPanel() {
        JPanel leftPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        leftPanel.add(new JBLabel("应用"), BorderLayout.NORTH);
        leftPanel.add(new JBScrollPane(applicationList), BorderLayout.CENTER);
        leftPanel.setPreferredSize(new Dimension(JBUI.scale(110), JBUI.scale(420)));
        leftPanel.setMinimumSize(new Dimension(JBUI.scale(96), JBUI.scale(300)));

        int keywordHeight = keywordField.getPreferredSize().height;
        keywordField.setPreferredSize(new Dimension(JBUI.scale(260), keywordHeight));
        keywordField.setMinimumSize(new Dimension(JBUI.scale(220), keywordHeight));
        keywordField.setMaximumSize(new Dimension(JBUI.scale(300), keywordHeight));

        JPanel topActionPanel = new JPanel();
        topActionPanel.setLayout(new javax.swing.BoxLayout(topActionPanel, javax.swing.BoxLayout.X_AXIS));
        // 顶部工具行增加左侧内边距：
        // 1) 避免“关键字”贴着中间分割线；
        // 2) 与下方表格表头文字（如“收藏名”）的起始位置更一致。
        topActionPanel.setBorder(JBUI.Borders.emptyLeft(8));
        topActionPanel.add(new JBLabel("关键字"));
        topActionPanel.add(Box.createHorizontalStrut(JBUI.scale(6)));
        topActionPanel.add(keywordField);
        topActionPanel.add(Box.createHorizontalStrut(JBUI.scale(10)));
        topActionPanel.add(deleteFavoriteButton);
        topActionPanel.add(Box.createHorizontalGlue());

        JPanel rightPanel = new JPanel(new BorderLayout(0, JBUI.scale(8)));
        rightPanel.add(topActionPanel, BorderLayout.NORTH);
        rightPanel.add(new JBScrollPane(favoriteTable), BorderLayout.CENTER);

        OnePixelSplitter splitPane = new OnePixelSplitter(false, 0.14f);
        splitPane.setFirstComponent(leftPanel);
        splitPane.setSecondComponent(rightPanel);
        splitPane.setBorder(JBUI.Borders.empty());

        rootPanel = new JPanel(new BorderLayout());
        rootPanel.setPreferredSize(new Dimension(JBUI.scale(900), JBUI.scale(480)));
        rootPanel.add(splitPane, BorderLayout.CENTER);
        return rootPanel;
    }

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

    private void configureComponents() {
        applicationList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        applicationList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !suppressApplicationListEvent) {
                applyFilters(null);
            }
        });

        keywordField.putClientProperty("JTextField.placeholderText", "匹配收藏名或接口名（service.method）");
        keywordField.getDocument().addDocumentListener(new DocumentAdapter() {
            @Override
            protected void textChanged(@NotNull DocumentEvent event) {
                applyFilters(getSelectedFavoriteId());
            }
        });

        favoriteTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        favoriteTable.setFillsViewportHeight(true);
        favoriteTable.setRowSelectionAllowed(true);
        favoriteTable.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        favoriteTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        favoriteTable.setSurrendersFocusOnKeystroke(true);
        favoriteTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) {
                updateActionState();
            }
        });
        favoriteTable.getColumnModel().getColumn(0).setMinWidth(JBUI.scale(90));
        favoriteTable.getColumnModel().getColumn(0).setPreferredWidth(JBUI.scale(140));
        favoriteTable.getColumnModel().getColumn(0).setMaxWidth(JBUI.scale(220));
        favoriteTable.getColumnModel().getColumn(1).setMinWidth(JBUI.scale(45));
        favoriteTable.getColumnModel().getColumn(1).setPreferredWidth(JBUI.scale(70));
        favoriteTable.getColumnModel().getColumn(1).setMaxWidth(JBUI.scale(120));
        favoriteTable.getColumnModel().getColumn(2).setPreferredWidth(JBUI.scale(420));
        alignTableHeaderToLeft();

        DefaultCellEditor nameEditor = new DefaultCellEditor(new JBTextField());
        nameEditor.setClickCountToStart(1);
        favoriteTable.getColumnModel().getColumn(0).setCellEditor(nameEditor);

        deleteFavoriteButton.addActionListener(event -> deleteSelectedFavorite());
    }

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

    private void reloadFavorites(@Nullable String preferredApplication, @Nullable String preferredFavoriteId) {
        allFavorites = settingsService.getFavorites();
        refreshApplicationList(preferredApplication);
        applyFilters(preferredFavoriteId);
    }

    private void refreshApplicationList(@Nullable String preferredApplication) {
        String keepSelected = preferredApplication;
        if (keepSelected == null || keepSelected.isBlank()) {
            keepSelected = getSelectedApplicationFilter();
        }
        if (keepSelected == null || keepSelected.isBlank()) {
            keepSelected = ALL_APPLICATIONS;
        }

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

        suppressApplicationListEvent = true;
        try {
            applicationListModel.clear();
            applicationListModel.addElement(ALL_APPLICATIONS);
            for (String application : applications) {
                applicationListModel.addElement(application);
            }

            if (!containsApplicationInView(keepSelected)) {
                keepSelected = ALL_APPLICATIONS;
            }
            applicationList.setSelectedValue(keepSelected, true);
        } finally {
            suppressApplicationListEvent = false;
        }
    }

    private boolean containsApplicationInView(@NotNull String application) {
        for (int i = 0; i < applicationListModel.size(); i++) {
            if (application.equals(applicationListModel.getElementAt(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean containsIgnoreCase(@NotNull List<String> values, @NotNull String candidate) {
        for (String value : values) {
            if (value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private void applyFilters(@Nullable String preferredFavoriteId) {
        String selectedApplication = getSelectedApplicationFilter();
        String keyword = normalizeKeyword(keywordField.getText());

        // 过滤顺序：先按应用，再按关键字。这样在收藏很多时性能更稳一些。
        List<InvokeFavorite> filtered = new ArrayList<>();
        for (InvokeFavorite favorite : allFavorites) {
            if (!ALL_APPLICATIONS.equals(selectedApplication)
                    && !selectedApplication.equals(favorite.getApplication())) {
                continue;
            }

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

        favoriteTable.setRowSelectionInterval(0, 0);
    }

    private @Nullable InvokeFavorite getSelectedFavoriteFromTable() {
        int row = favoriteTable.getSelectedRow();
        if (row < 0) {
            return null;
        }
        int modelRow = favoriteTable.convertRowIndexToModel(row);
        return favoriteTableModel.getFavoriteAt(modelRow);
    }

    private @Nullable String getSelectedFavoriteId() {
        InvokeFavorite selected = getSelectedFavoriteFromTable();
        return selected == null ? null : selected.getId();
    }

    private @NotNull String getSelectedApplicationFilter() {
        String selected = applicationList.getSelectedValue();
        if (selected == null || selected.isBlank()) {
            return ALL_APPLICATIONS;
        }
        return selected;
    }

    private void updateActionState() {
        boolean hasSelection = getSelectedFavoriteFromTable() != null;
        setOKActionEnabled(hasSelection);
        deleteFavoriteButton.setEnabled(hasSelection);
    }

    private void deleteSelectedFavorite() {
        stopEditingIfNeeded();
        InvokeFavorite selected = getSelectedFavoriteFromTable();
        if (selected == null) {
            Messages.showWarningDialog(rootPanel, "请先选择要删除的收藏", "提示");
            return;
        }

        int confirm = Messages.showYesNoDialog(
                rootPanel,
                "确认删除收藏“" + selected.getName() + "”？",
                "删除收藏",
                Messages.getWarningIcon()
        );
        if (confirm != Messages.YES) {
            return;
        }

        String selectedApplication = getSelectedApplicationFilter();
        try {
            settingsService.deleteFavorite(selected.getId());
            reloadFavorites(selectedApplication, null);
        } catch (IllegalArgumentException ex) {
            Messages.showErrorDialog(rootPanel, ex.getMessage(), "删除失败");
        }
    }

    private void handleFavoriteNameEdited(int rowIndex, @Nullable Object value) {
        InvokeFavorite target = favoriteTableModel.getFavoriteAt(rowIndex);
        if (target == null) {
            return;
        }

        String newName = value == null ? "" : value.toString().trim();
        if (newName.isEmpty()) {
            Messages.showErrorDialog(rootPanel, "收藏名不能为空", "修改失败");
            favoriteTableModel.fireTableRowsUpdated(rowIndex, rowIndex);
            return;
        }

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
            reloadFavorites(selectedApplication, target.getId());
        }
    }

    private void stopEditingIfNeeded() {
        if (favoriteTable.isEditing()) {
            favoriteTable.getCellEditor().stopCellEditing();
        }
    }

    private boolean containsKeyword(@NotNull String text, @NotNull String keyword) {
        return text.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private @NotNull String normalizeKeyword(@Nullable String text) {
        if (text == null) {
            return "";
        }
        return text.trim().toLowerCase(Locale.ROOT);
    }

    private final class FavoriteTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"收藏名", "应用", "接口"};

        private final List<InvokeFavorite> rows = new ArrayList<>();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public @NotNull String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public boolean isCellEditable(int rowIndex, int columnIndex) {
            return columnIndex == 0;
        }

        @Override
        public void setValueAt(Object aValue, int rowIndex, int columnIndex) {
            if (columnIndex != 0) {
                return;
            }
            handleFavoriteNameEdited(rowIndex, aValue);
        }

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

        private @Nullable InvokeFavorite getFavoriteAt(int rowIndex) {
            if (rowIndex < 0 || rowIndex >= rows.size()) {
                return null;
            }
            return rows.get(rowIndex);
        }

        private int findRowIndex(@NotNull String favoriteId) {
            for (int i = 0; i < rows.size(); i++) {
                if (favoriteId.equals(rows.get(i).getId())) {
                    return i;
                }
            }
            return -1;
        }

        private void setRows(@NotNull List<InvokeFavorite> favorites) {
            rows.clear();
            rows.addAll(favorites);
            fireTableDataChanged();
        }
    }
}
