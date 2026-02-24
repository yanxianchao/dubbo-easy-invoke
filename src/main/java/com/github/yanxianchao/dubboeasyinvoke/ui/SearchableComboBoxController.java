package com.github.yanxianchao.dubboeasyinvoke.ui;

import com.intellij.openapi.ui.ComboBox;
import com.intellij.ui.DocumentAdapter;
import com.intellij.ui.SimpleListCellRenderer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.DefaultComboBoxModel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.InputMethodListener;
import java.text.AttributedCharacterIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * 可搜索下拉框控制器（支持实时过滤 + 中文输入法友好处理）。
 *
 * <p>核心目标是：用户输入关键字时不断过滤列表，同时尽量不打断输入体验
 * （例如 IME 组合输入阶段不做回写）。</p>
 */
public final class SearchableComboBoxController<T> {

    private final ComboBox<T> comboBox;
    private final Function<T, String> textProvider;
    private final List<T> allItems = new ArrayList<>();

    private boolean internalUpdate;
    private boolean filterUpdateScheduled;
    private boolean imeComposing;
    private boolean suppressAutoPopup;
    private String pendingKeyword;
    private JTextField editorField;

    public SearchableComboBoxController(@NotNull ComboBox<T> comboBox, @NotNull Function<T, String> textProvider) {
        this.comboBox = comboBox;
        this.textProvider = textProvider;
        this.comboBox.setEditable(true);
        this.comboBox.setRenderer(SimpleListCellRenderer.create((label, value, index) ->
                label.setText(value == null ? "" : textProvider.apply(value))));
        this.comboBox.addActionListener(event -> {
            // 鼠标/键盘从展开列表中选中后，下一轮过滤不再强制重新展开。
            if (!internalUpdate && comboBox.isPopupVisible()) {
                suppressAutoPopup = true;
            }
        });
        this.comboBox.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                SwingUtilities.invokeLater(() -> handleClickToExpandAll());
            }
        });

        Component editorComponent = this.comboBox.getEditor().getEditorComponent();
        if (editorComponent instanceof JTextField textField) {
            this.editorField = textField;
            textField.getDocument().addDocumentListener(new DocumentAdapter() {
                @Override
                protected void textChanged(@NotNull DocumentEvent event) {
                    if (internalUpdate || imeComposing) {
                        return;
                    }
                    requestFilter(getEditorTextRaw());
                }
            });

            textField.addInputMethodListener(new InputMethodListener() {
                @Override
                public void inputMethodTextChanged(InputMethodEvent event) {
                    AttributedCharacterIterator iterator = event.getText();
                    if (iterator == null) {
                        imeComposing = false;
                        return;
                    }

                    int totalLength = iterator.getEndIndex() - iterator.getBeginIndex();
                    int composingLength = totalLength - event.getCommittedCharacterCount();
                    imeComposing = composingLength > 0;

                    // 中文输入法候选提交后再触发筛选，避免组合输入期间回写导致文本异常拼接。
                    if (!imeComposing) {
                        requestFilter(getEditorTextRaw());
                    }
                }

                @Override
                public void caretPositionChanged(InputMethodEvent event) {
                    // no-op
                }
            });
            textField.addMouseListener(new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    SwingUtilities.invokeLater(() -> handleClickToExpandAll());
                }
            });
        }
    }

    public void setItems(@NotNull List<T> items) {
        allItems.clear();
        allItems.addAll(items);
        requestFilter(getEditorTextRaw());
    }

    public @Nullable T getSelectedItem() {
        Object selected = comboBox.getSelectedItem();
        for (T item : allItems) {
            if (item.equals(selected)) {
                return item;
            }
        }
        if (selected == null) {
            return null;
        }

        // 兼容“编辑器里是文本但 model 里还没选中对象”的场景。
        T textMatched = findExactTextMatch(selected.toString());
        if (textMatched != null) {
            return textMatched;
        }
        return null;
    }

    public @NotNull String getEditorText() {
        return getEditorTextRaw().trim();
    }

    private @NotNull String getEditorTextRaw() {
        if (editorField == null) {
            return "";
        }
        return editorField.getText();
    }

    public void setSelectedItem(@Nullable T item) {
        internalUpdate = true;
        try {
            if (item == null) {
                comboBox.setSelectedItem(null);
                if (editorField != null) {
                    editorField.setText("");
                }
            } else {
                comboBox.setSelectedItem(item);
                if (editorField != null) {
                    editorField.setText(textProvider.apply(item));
                }
            }
        } finally {
            internalUpdate = false;
        }
    }

    private void filterByKeyword(@NotNull String keyword) {
        String normalized = keyword.toLowerCase(Locale.ROOT);
        List<T> filtered = new ArrayList<>();
        for (T item : allItems) {
            String text = textProvider.apply(item);
            if (normalized.isEmpty() || text.toLowerCase(Locale.ROOT).contains(normalized)) {
                filtered.add(item);
            }
        }

        T selected = getSelectedItem();
        String selectedText = selected == null ? null : textProvider.apply(selected);
        boolean keepSelection = selected != null
                && selectedText != null
                && selectedText.equals(keyword)
                && filtered.contains(selected);

        internalUpdate = true;
        try {
            DefaultComboBoxModel<T> model = new DefaultComboBoxModel<>();
            for (T item : filtered) {
                model.addElement(item);
            }
            comboBox.setModel(model);

            if (keepSelection) {
                comboBox.setSelectedItem(selected);
            } else {
                T exactMatched = findExactTextMatch(keyword);
                if (exactMatched != null && filtered.contains(exactMatched)) {
                    comboBox.setSelectedItem(exactMatched);
                } else {
                    // 让编辑器持有当前关键字，避免在展开下拉时被 Swing 以 null 选中值清空。
                    comboBox.setSelectedItem(keyword);
                }
            }

            if (editorField != null && !imeComposing) {
                if (!keyword.equals(editorField.getText())) {
                    editorField.setText(keyword);
                }
                int caret = Math.min(keyword.length(), editorField.getText().length());
                editorField.setCaretPosition(caret);
            }
        } finally {
            internalUpdate = false;
        }

        boolean editorFocused = editorField != null && editorField.hasFocus();
        if (!editorFocused) {
            return;
        }

        if (filtered.isEmpty()) {
            if (comboBox.isPopupVisible()) {
                comboBox.hidePopup();
            }
            suppressAutoPopup = false;
            return;
        }

        if (suppressAutoPopup) {
            suppressAutoPopup = false;
            return;
        }

        if (!comboBox.isPopupVisible()) {
            SwingUtilities.invokeLater(() -> {
                if (!comboBox.isPopupVisible()) {
                    comboBox.showPopup();
                }
            });
        }
    }

    private void requestFilter(@NotNull String keyword) {
        pendingKeyword = keyword;
        if (filterUpdateScheduled) {
            return;
        }
        // 合并同一事件循环中的多次输入，避免频繁重建 model。
        filterUpdateScheduled = true;
        SwingUtilities.invokeLater(() -> {
            filterUpdateScheduled = false;
            String text = pendingKeyword == null ? getEditorText() : pendingKeyword;
            pendingKeyword = null;
            filterByKeyword(text);
        });
    }

    private void handleClickToExpandAll() {
        if (internalUpdate) {
            return;
        }

        T selected = getSelectedItem();
        String selectedText = selected == null ? "" : textProvider.apply(selected);
        String currentText = getEditorTextRaw().trim();

        if (currentText.isEmpty() || (!selectedText.isEmpty() && selectedText.equals(currentText))) {
            showAllItemsPopup(selected);
            return;
        }

        // 用户正在输入关键字时，保留筛选逻辑，但确保下拉展开可见。
        requestFilter(getEditorTextRaw());
    }

    private void showAllItemsPopup(@Nullable T selectedItem) {
        boolean shouldShowPopup = comboBox.isEnabled()
                && (comboBox.hasFocus() || (editorField != null && editorField.hasFocus()));
        if (!shouldShowPopup) {
            return;
        }

        if (comboBox.isPopupVisible()) {
            comboBox.hidePopup();
        }

        internalUpdate = true;
        try {
            DefaultComboBoxModel<T> model = new DefaultComboBoxModel<>();
            for (T item : allItems) {
                model.addElement(item);
            }
            comboBox.setModel(model);

            if (selectedItem != null && allItems.contains(selectedItem)) {
                comboBox.setSelectedItem(selectedItem);
                if (editorField != null) {
                    String selectedText = textProvider.apply(selectedItem);
                    if (!selectedText.equals(editorField.getText())) {
                        editorField.setText(selectedText);
                    }
                    editorField.setCaretPosition(selectedText.length());
                }
            } else {
                T exactMatched = findExactTextMatch(getEditorTextRaw());
                if (exactMatched != null) {
                    comboBox.setSelectedItem(exactMatched);
                    if (editorField != null) {
                        String selectedText = textProvider.apply(exactMatched);
                        if (!selectedText.equals(editorField.getText())) {
                            editorField.setText(selectedText);
                        }
                        editorField.setCaretPosition(selectedText.length());
                    }
                } else {
                    comboBox.setSelectedItem(getEditorTextRaw());
                }
            }
        } finally {
            internalUpdate = false;
        }

        suppressAutoPopup = false;
        SwingUtilities.invokeLater(() -> {
            if (!comboBox.isPopupVisible()) {
                comboBox.showPopup();
            }
        });
    }

    private @Nullable T findExactTextMatch(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String keyword = text.trim();
        if (keyword.isEmpty()) {
            return null;
        }

        T ignoreCaseMatched = null;
        for (T item : allItems) {
            String itemText = textProvider.apply(item);
            if (itemText.equals(keyword)) {
                return item;
            }
            if (ignoreCaseMatched == null && itemText.equalsIgnoreCase(keyword)) {
                ignoreCaseMatched = item;
            }
        }
        return ignoreCaseMatched;
    }
}
