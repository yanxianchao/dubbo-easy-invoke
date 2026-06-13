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
 * <p><b>职责：</b>为 IntelliJ 平台的 {@link ComboBox} 组件增加"边输入边过滤"的能力，
 * 使用户在大量候选项中能通过关键字快速定位目标条目。</p>
 *
 * <p><b>在项目中的角色：</b>作为 Dubbo Easy Invoke 插件 UI 层的通用组件控制器，
 * 被服务接口选择、方法选择等场景复用，为下拉框统一提供搜索过滤体验。</p>
 *
 * <p><b>设计思路：</b></p>
 * <ul>
 *   <li>将 ComboBox 设为可编辑（editable），监听编辑器中的文本变化事件来触发过滤；</li>
 *   <li>过滤时重建 {@link DefaultComboBoxModel}，只保留匹配项，并自动展开下拉列表；</li>
 *   <li>使用 {@link SwingUtilities#invokeLater} 合并同一事件循环中的多次输入，
 *       避免频繁重建 model 导致的性能问题和 UI 闪烁；</li>
 *   <li>特别处理了中文输入法（IME）的组合输入阶段：在候选字未提交前不触发过滤、
 *       不回写编辑器文本，避免拼音与汉字异常拼接的问题；</li>
 *   <li>鼠标点击时展示全部候选项，方便用户浏览完整列表。</li>
 * </ul>
 *
 * <p><b>线程安全性：</b>本类仅在 EDT（Event Dispatch Thread）上操作 Swing 组件，
 * 不涉及多线程并发访问，无需额外同步。外部调用方应确保在 EDT 上调用本类的公开方法。</p>
 *
 * @param <T> 下拉框中候选项的数据类型
 */
public final class SearchableComboBoxController<T> {

    /** 被控制的 IntelliJ ComboBox 实例 */
    private final ComboBox<T> comboBox;

    /**
     * 将候选项对象转换为可显示文本的函数。
     * <p>用于列表渲染、关键字匹配和编辑器文本回写，是整个搜索过滤逻辑的核心映射。</p>
     */
    private final Function<T, String> textProvider;

    /** 完整的候选项列表（未过滤），作为每次过滤的数据源 */
    private final List<T> allItems = new ArrayList<>();

    /**
     * 内部更新标志。
     * <p>当控制器自身在修改 ComboBox 的 model/选中项/编辑器文本时置为 true，
     * 用于阻止由此产生的事件（如 ActionListener、DocumentListener）触发二次过滤，
     * 防止递归循环和不必要的 UI 抖动。</p>
     */
    private boolean internalUpdate;

    /**
     * 过滤请求合并标志。
     * <p>当已有一个 {@link SwingUtilities#invokeLater} 任务排队等待执行时置为 true，
     * 后续的输入事件只更新 {@link #pendingKeyword} 而不再排新任务，
     * 从而将同一事件循环内的多次输入合并为一次过滤操作。</p>
     */
    private boolean filterUpdateScheduled;

    /**
     * 输入法组合状态标志。
     * <p>当用户正在使用中文等输入法进行组合输入（如拼音尚未选词提交）时为 true。
     * 此阶段不触发过滤、不回写编辑器文本，避免输入法候选被打断。</p>
     */
    private boolean imeComposing;

    /**
     * 抑制自动弹出下拉列表的标志。
     * <p>当用户从展开的下拉列表中选择了一个条目后置为 true，
     * 防止紧随其后的过滤操作再次弹出下拉框（因为用户已经做出了选择）。
     * 该标志在下一次过滤执行后自动复位。</p>
     */
    private boolean suppressAutoPopup;

    /**
     * 待处理的过滤关键字。
     * <p>在过滤请求合并机制中，记录最新一次输入的关键字，
     * 确保合并后的过滤操作使用的是最终的用户输入。</p>
     */
    private String pendingKeyword;

    /** ComboBox 编辑器中的文本输入框引用，用于读写编辑器文本和监听输入事件 */
    private JTextField editorField;

    /**
     * 构造可搜索下拉框控制器并完成所有事件绑定。
     *
     * <p>构造过程中会：</p>
     * <ol>
     *   <li>将 ComboBox 设为可编辑模式，并配置自定义渲染器；</li>
     *   <li>绑定 ActionListener —— 检测用户从下拉列表中选择条目的动作；</li>
     *   <li>绑定 MouseListener —— 点击时展示全部候选项；</li>
     *   <li>绑定编辑器的 DocumentListener —— 文本变化时触发过滤；</li>
     *   <li>绑定编辑器的 InputMethodListener —— 处理中文输入法的组合/提交状态切换。</li>
     * </ol>
     *
     * @param comboBox     需要增加搜索能力的下拉框实例，不能为 null
     * @param textProvider 将候选项对象转换为显示文本的函数，不能为 null
     */
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
                /**
                 * 响应输入法文本变化事件。
                 *
                 * <p>通过分析 {@link InputMethodEvent} 中已提交字符数（committedCharacterCount）
                 * 与总字符数的差值来判断当前是否处于组合输入状态：</p>
                 * <ul>
                 *   <li>差值 > 0：仍有未提交的组合字符（如拼音），标记 imeComposing = true，不触发过滤；</li>
                 *   <li>差值 = 0：候选词已提交，标记 imeComposing = false，立即触发过滤。</li>
                 * </ul>
                 *
                 * @param event 输入法文本变化事件
                 */
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

    /**
     * 设置完整的候选项列表，并立即根据当前编辑器中的关键字触发一次过滤。
     *
     * <p>调用此方法后，内部会用新列表替换原有数据源（{@link #allItems}），
     * 然后基于编辑器当前的输入文本重新执行过滤，更新下拉列表的内容。</p>
     *
     * @param items 新的完整候选项列表，不能为 null（可以为空列表）
     */
    public void setItems(@NotNull List<T> items) {
        allItems.clear();
        allItems.addAll(items);
        requestFilter(getEditorTextRaw());
    }

    /**
     * 获取当前选中的候选项对象。
     *
     * <p>优先返回 ComboBox model 中直接匹配的选中对象；
     * 若选中的是文本（用户手动输入的情况），则尝试在全量列表中按文本精确匹配查找。</p>
     *
     * @return 选中的候选项对象，无法匹配时返回 null
     */
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

        // 兼容"编辑器里是文本但 model 里还没选中对象"的场景。
        T textMatched = findExactTextMatch(selected.toString());
        if (textMatched != null) {
            return textMatched;
        }
        return null;
    }

    /**
     * 获取编辑器中的文本内容（已去除首尾空白）。
     *
     * @return 编辑器文本的 trim 结果，编辑器不可用时返回空字符串
     */
    public @NotNull String getEditorText() {
        return getEditorTextRaw().trim();
    }

    /**
     * 获取编辑器中的原始文本内容（不做 trim 处理）。
     *
     * <p>在过滤逻辑中需要保留原始文本用于回写，因此提供此内部方法。</p>
     *
     * @return 编辑器原始文本，编辑器不可用时返回空字符串
     */
    private @NotNull String getEditorTextRaw() {
        if (editorField == null) {
            return "";
        }
        return editorField.getText();
    }

    /**
     * 以编程方式设置当前选中项，并同步更新编辑器文本。
     *
     * <p>操作期间会临时标记 {@link #internalUpdate} 为 true，
     * 以防止由此触发的事件导致不必要的过滤操作。</p>
     *
     * @param item 要选中的候选项对象，传 null 表示清空选择
     */
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

    /**
     * 根据关键字执行实际的过滤操作，更新 ComboBox 的 model 和下拉弹出状态。
     *
     * <p>核心过滤流程：</p>
     * <ol>
     *   <li>将关键字转为小写，遍历 {@link #allItems} 进行大小写不敏感的包含匹配；</li>
     *   <li>用过滤结果重建 {@link DefaultComboBoxModel}，替换 ComboBox 的 model；</li>
     *   <li>尝试保留或恢复之前的选中状态，避免用户体验中的跳变；</li>
     *   <li>回写编辑器文本和光标位置（IME 组合期间跳过）；</li>
     *   <li>根据过滤结果决定是否展开/收起下拉列表。</li>
     * </ol>
     *
     * @param keyword 用户输入的过滤关键字
     */
    private void filterByKeyword(@NotNull String keyword) {
        String normalized = keyword.toLowerCase(Locale.ROOT);
        List<T> filtered = new ArrayList<>();
        for (T item : allItems) {
            String text = textProvider.apply(item);
            if (normalized.isEmpty() || text.toLowerCase(Locale.ROOT).contains(normalized)) {
                filtered.add(item);
            }
        }

        // 判断是否应保持当前选中项不变：选中项的文本与关键字完全一致且在过滤结果中
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

            // 回写编辑器文本：确保 model 替换后编辑器中仍显示用户输入的关键字
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

        // 以下逻辑控制下拉弹出框的显示/隐藏
        boolean editorFocused = editorField != null && editorField.hasFocus();
        if (!editorFocused) {
            return;
        }

        if (filtered.isEmpty()) {
            // 没有匹配结果时收起下拉框
            if (comboBox.isPopupVisible()) {
                comboBox.hidePopup();
            }
            suppressAutoPopup = false;
            return;
        }

        if (suppressAutoPopup) {
            // 用户刚从列表中选择了条目，本轮不再自动弹出
            suppressAutoPopup = false;
            return;
        }

        // 有匹配结果且编辑器有焦点时，确保下拉框展开
        if (!comboBox.isPopupVisible()) {
            SwingUtilities.invokeLater(() -> {
                if (!comboBox.isPopupVisible()) {
                    comboBox.showPopup();
                }
            });
        }
    }

    /**
     * 请求一次过滤操作（带合并去抖）。
     *
     * <p>采用"挂起 + 合并"策略：如果已有一个 invokeLater 任务排队，
     * 仅更新 {@link #pendingKeyword} 而不重复排队。当 invokeLater 任务执行时，
     * 使用最新的关键字进行过滤。这样可以有效避免快速输入时频繁重建 model 的性能开销。</p>
     *
     * @param keyword 当前的过滤关键字
     */
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

    /**
     * 处理用户点击 ComboBox 或编辑器时的"展开全部"逻辑。
     *
     * <p>点击行为的分支策略：</p>
     * <ul>
     *   <li>编辑器为空或文本与当前选中项完全一致 → 展示全部候选项（方便浏览）；</li>
     *   <li>编辑器中有自定义关键字 → 保留筛选逻辑，但确保下拉弹出可见。</li>
     * </ul>
     */
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

    /**
     * 展示全部候选项的下拉弹出框。
     *
     * <p>该方法用于用户点击 ComboBox 区域时，无论当前过滤状态如何，
     * 都重建包含全部候选项的 model 并展开下拉框，方便用户浏览所有选项。</p>
     *
     * <p>展示前会尝试恢复之前的选中状态或通过文本精确匹配来定位选中项。</p>
     *
     * @param selectedItem 之前选中的候选项，用于恢复选中状态（可能为 null）
     */
    private void showAllItemsPopup(@Nullable T selectedItem) {
        // 仅在 ComboBox 或编辑器拥有焦点且组件可用时才展示
        boolean shouldShowPopup = comboBox.isEnabled()
                && (comboBox.hasFocus() || (editorField != null && editorField.hasFocus()));
        if (!shouldShowPopup) {
            return;
        }

        // 先隐藏再重建，确保下拉框刷新内容
        if (comboBox.isPopupVisible()) {
            comboBox.hidePopup();
        }

        internalUpdate = true;
        try {
            // 用全量数据重建 model
            DefaultComboBoxModel<T> model = new DefaultComboBoxModel<>();
            for (T item : allItems) {
                model.addElement(item);
            }
            comboBox.setModel(model);

            if (selectedItem != null && allItems.contains(selectedItem)) {
                // 恢复之前的选中项
                comboBox.setSelectedItem(selectedItem);
                if (editorField != null) {
                    String selectedText = textProvider.apply(selectedItem);
                    if (!selectedText.equals(editorField.getText())) {
                        editorField.setText(selectedText);
                    }
                    editorField.setCaretPosition(selectedText.length());
                }
            } else {
                // 尝试通过编辑器文本精确匹配一个候选项
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
                    // 没有匹配项时保持编辑器文本不变
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

    /**
     * 在全量候选项列表中按文本精确匹配查找对应的对象。
     *
     * <p>匹配策略：优先进行大小写敏感的精确匹配（{@code equals}），
     * 若未命中则退而求其次进行大小写不敏感匹配（{@code equalsIgnoreCase}），
     * 始终返回第一个匹配的结果。</p>
     *
     * @param text 要匹配的文本（通常来自编辑器输入），为 null 或空白时返回 null
     * @return 精确匹配到的候选项对象，未找到时返回 null
     */
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
            // 优先精确匹配（大小写敏感），找到即返回
            if (itemText.equals(keyword)) {
                return item;
            }
            // 记录第一个大小写不敏感匹配作为候补
            if (ignoreCaseMatched == null && itemText.equalsIgnoreCase(keyword)) {
                ignoreCaseMatched = item;
            }
        }
        return ignoreCaseMatched;
    }
}
