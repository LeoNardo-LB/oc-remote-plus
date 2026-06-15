package dev.minios.ocremote.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clipScrollableContainer
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.lazy.layout.LazyLayout
import androidx.compose.foundation.lazy.layout.LazyLayoutItemProvider
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasurePolicy
import androidx.compose.foundation.lazy.layout.LazyLayoutMeasureScope
import androidx.compose.foundation.lazy.layout.getDefaultLazyLayoutKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.Remeasurement
import androidx.compose.ui.layout.RemeasurementModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 一个基于 Compose [LazyLayout] 公共 API 的自定义垂直列表，核心能力是
 * **scroll anchoring**：在 reverseLayout 中当 item 增长时（如 SSE 流式输出），
 * 保持用户视窗稳定，避免被"拖着走"。
 *
 * 根因：标准 [androidx.compose.foundation.lazy.LazyColumn] 在 reverseLayout 中，
 * `measureLazyList` 保留 `firstVisibleItemScrollOffset` 不变，item 增长会向上推开
 * 上方 items。本实现 在自定义 measure policy 中，measure 完成后、placement 前，
 * 检测 item 增长并调整 scroll offset——在同一个 measure pass 内完成。
 */

// =====================================================================================
// DSL Scope
// =====================================================================================

/** 单个 item 的描述：key、contentType 与 composable 内容。 */
internal data class AnchoredItem(
    val key: Any,
    val contentType: Any?,
    val content: @Composable () -> Unit
)

/** DSL scope，用于在 [AnchoredLazyColumn] 的 content lambda 中声明 items。 */
class AnchoredLazyListScope {
    internal val items = mutableListOf<AnchoredItem>()

    fun item(key: Any? = null, contentType: Any? = null, content: @Composable () -> Unit) {
        items.add(
            AnchoredItem(
                key = key ?: getDefaultLazyLayoutKey(items.size),
                contentType = contentType,
                content = content
            )
        )
    }

    fun <T> items(
        list: List<T>,
        key: (T) -> Any,
        contentType: (T) -> Any? = { null },
        itemContent: @Composable (T) -> Unit
    ) {
        list.forEach { item ->
            items.add(
                AnchoredItem(
                    key = key(item),
                    contentType = contentType(item),
                    content = { itemContent(item) }
                )
            )
        }
    }

    fun <T> itemsIndexed(
        list: List<T>,
        key: (Int, T) -> Any,
        contentType: (Int, T) -> Any? = { _, _ -> null },
        itemContent: @Composable (Int, T) -> Unit
    ) {
        list.forEachIndexed { index, item ->
            items.add(
                AnchoredItem(
                    key = key(index, item),
                    contentType = contentType(index, item),
                    content = { itemContent(index, item) }
                )
            )
        }
    }
}

// =====================================================================================
// State
// =====================================================================================

/**
 * [AnchoredLazyColumn] 的状态。记录第一个可见 item 的 index / offset，
 * 并维护 item size 跟踪表用于 scroll anchoring。
 */
class AnchoredLazyListState(
    initialIndex: Int = 0,
    initialOffset: Int = 0
) {
    /** 第一个可见 item 的索引（始终是 visibleItems 中最小的 index）。 */
    var firstVisibleItemIndex by mutableStateOf(initialIndex)
        internal set

    /** 第一个可见 item 已滚过（不可见）的像素数，非负。 */
    var firstVisibleItemScrollOffset by mutableStateOf(initialOffset)
        internal set

    /** 上一轮 measure 记录的 item（key → 含 spacing 的总高度），用于检测增长。 */
    internal val prevItemSizes = mutableMapOf<Any, Int>()

    /** 上次 measure 时 firstVisibleItem 的 key，用于检测 items 增删导致的 index 偏移。 */
    internal var lastKnownFirstItemKey: Any? = null

    /**
     * 待在下一轮 measure 中消费的 scroll 增量（px，Float 保留精度）。
     * 符号约定与官方 [androidx.compose.foundation.lazy.LazyListState] 的 onScroll 一致：
     * 正值 = 向 index 增大方向滚动。
     */
    internal var scrollToBeConsumed = 0f

    private var remeasurement: Remeasurement? = null

    /** 作为 Modifier.Element 提供给 LazyLayout，以获取 [Remeasurement] 句柄。 */
    internal val remeasurementModifierElement: Modifier.Element = object : RemeasurementModifier {
        override fun onRemeasurementAvailable(remeasurement: Remeasurement) {
            this@AnchoredLazyListState.remeasurement = remeasurement
        }
    }

    internal fun forceRemeasure() {
        remeasurement?.forceRemeasure()
    }

    /**
     * 手势 / fling 的 scroll 入口。
     *
     * delta 符号：`reverseDirection = false` 时，手指上滑 → delta > 0。
     * 我们用 `scrollToBeConsumed -= delta`：
     * - delta > 0 → scrollToBeConsumed < 0 → measure 中 currentOffset -= (负) = currentOffset+|delta| → offset 增大 → 向上滚 ✓
     * - delta < 0 → scrollToBeConsumed > 0 → measure 中 currentOffset -= (正) → offset 减小 → 向下滚 ✓
     *
     * 边界守卫：在 measure 更新 canScrollForward/Backward 后，拒绝过界滚动。
     */
    val scrollableState: ScrollableState = ScrollableState { delta ->
        // Fix 2: 边界守卫——拒绝过界 delta
        if (delta < 0f && !canScrollBackward) return@ScrollableState 0f
        if (delta > 0f && !canScrollForward && totalItemsCount > 0) return@ScrollableState 0f

        scrollToBeConsumed -= delta
        if (abs(scrollToBeConsumed) > 0.5f) {
            forceRemeasure()
        }
        delta
    }

    var canScrollForward by mutableStateOf(false)
        internal set

    var canScrollBackward by mutableStateOf(false)
        internal set

    /** 上次 measure pass 的 item 总数，供外部查询（替代 LazyListState.layoutInfo.totalItemsCount）。 */
    var totalItemsCount by mutableStateOf(0)
        internal set

    /** 当前是否有 scroll 手势 / 动画正在进行。 */
    val isScrollInProgress: Boolean get() = scrollableState.isScrollInProgress

    /**
     * 直接设置滚动位置（非动画），下次 measure pass 生效。
     * 类似 [androidx.compose.foundation.lazy.LazyListState.scrollToItem] 的非 suspend 版本。
     */
    fun scrollToItem(index: Int, scrollOffset: Int = 0) {
        firstVisibleItemIndex = index
        firstVisibleItemScrollOffset = scrollOffset
        forceRemeasure()
    }

    /**
     * 跳转到底部（item 0，offset 0）。用于 auto-scroll。
     * 对应原 LazyListState.smoothScrollToBottom() 的简化版——直接设置而非动画。
     */
    fun scrollToBottom() {
        firstVisibleItemIndex = 0
        firstVisibleItemScrollOffset = 0
        forceRemeasure()
    }

    /**
     * 在 scroll 互斥锁内执行 block，用于动画 scroll。
     * block 内可用 ScrollScope.scrollBy() 注入增量。
     */
    suspend fun scroll(block: suspend ScrollScope.() -> Unit) {
        scrollableState.scroll { block() }
    }
}

// =====================================================================================
// Composable
// =====================================================================================

/**
 * 支持 scroll anchoring 的垂直 LazyColumn。
 *
 * @param isAtBottom 调用方传入的"是否锚定在底部"状态。为 true 时不做 anchoring
 *                   （让 auto-scroll 自然跟随）；为 false 时启用 anchoring，保持视窗稳定。
 * @param reverseLayout 默认 true：index 0 渲染在底部（适合聊天消息流）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AnchoredLazyColumn(
    state: AnchoredLazyListState,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = true,
    userScrollEnabled: Boolean = true,
    isAtBottom: Boolean = true,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(0.dp),
    content: AnchoredLazyListScope.() -> Unit
) {
    // 1. 每次 recompose 重新收集 items（不 remember：content lambda 捕获了变化的
    //    displayItems 等变量，每次都是新引用，remember(content) 必然失效）。
    val scope = AnchoredLazyListScope().apply(content)

    // 2. itemProvider 实例保持 stable（remember 无 key）。
    //    关键：LazyLayout 检测到 provider 实例变化时会清除内部 composition cache，
    //    导致所有可见 item 从头 compose → 闪屏。
    //    通过 mutable property 更新 items，provider 实例不变 → cache 保留 →
    //    LazyLayout 通过 key 匹配复用 composition slot，只有内容真正变化的 item 才 recompose。
    val itemProvider = remember { AnchoredItemProvider() }
    itemProvider.updateItems(scope.items)
    // items 变了 → 强制 LazyLayout 重新 measure（provider 实例 stable，但内容需要刷新）
    SideEffect { state.forceRemeasure() }

    // 3. measure policy 不依赖 itemProvider 实例变化（它通过 provider 方法读取最新数据）。
    val measurePolicy: LazyLayoutMeasurePolicy =
        remember(state, contentPadding, reverseLayout, verticalArrangement, isAtBottom) {
            anchoredMeasurePolicy(
                state = state,
                itemProvider = itemProvider,
                contentPadding = contentPadding,
                reverseLayout = reverseLayout,
                isAtBottom = isAtBottom,
                spacingProvider = { verticalArrangement.spacing }
            )
        }

    val interactionSource = remember { MutableInteractionSource() }

    LazyLayout(
        itemProvider = { itemProvider },
        modifier = modifier
            .then(state.remeasurementModifierElement)
            .clipScrollableContainer(Orientation.Vertical) // Fix #1: 裁剪主轴方向
            .scrollable(
                state = state.scrollableState,
                orientation = Orientation.Vertical,
                reverseDirection = false, // Fix 1: 不反转——measure policy 的 offset 逻辑已处理方向
                enabled = userScrollEnabled,
                flingBehavior = ScrollableDefaults.flingBehavior(),
                interactionSource = interactionSource
            ),
        prefetchState = null,
        measurePolicy = measurePolicy
    )
}

// =====================================================================================
// ItemProvider
// =====================================================================================

@OptIn(ExperimentalFoundationApi::class)
private class AnchoredItemProvider : LazyLayoutItemProvider {

    private var items by mutableStateOf<List<AnchoredItem>>(emptyList())

    fun updateItems(newItems: List<AnchoredItem>) {
        items = newItems
    }

    override val itemCount: Int get() = items.size

    override fun getContentType(index: Int): Any? = items[index].contentType

    override fun getKey(index: Int): Any = items[index].key

    @Composable
    override fun Item(index: Int, key: Any) {
        items[index].content()
    }
}

// =====================================================================================
// Measure Policy — 逐行翻译自 androidx.compose.foundation.lazy.measureLazyList
// 剥离了 animation / prefetch / lookahead / stickyHeaders / beyondBounds
// 新增了 scroll anchoring（item 增长时同帧补偿 offset）
// =====================================================================================

/**
 * measure 阶段记录的单个 item 测量结果。
 * 对应标准 LazyListMeasuredItem，简化版（无 alignment / animation / graphicsLayer）。
 */
private class MeasureItem(
    val index: Int,
    val placeables: List<Placeable>,
    val key: Any,
    /** 该 item 之后的 spacing（最后一个 item 为 0）。 */
    val spacing: Int
) {
    /** 主轴尺寸（所有 placeable 高度之和）。 */
    val height: Int = if (placeables.isEmpty()) 0 else placeables.sumOf { it.height }

    /** 交叉轴尺寸（最宽 placeable 的宽度）。 */
    val crossAxisSize: Int = if (placeables.isEmpty()) 0 else placeables.maxOf { it.width }

    /** height + spacing，对应标准 mainAxisSizeWithSpacings。 */
    val mainAxisSizeWithSpacings: Int = (height + spacing).coerceAtLeast(0)

    /** 内部坐标系中的偏移（由 [position] 设置，由 placement 读取）。 */
    var offset: Int = 0
        private set

    /** 设置内部偏移。 */
    fun position(mainAxisOffset: Int) {
        offset = mainAxisOffset
    }
}

/**
 * 构造 measure policy。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun anchoredMeasurePolicy(
    state: AnchoredLazyListState,
    itemProvider: AnchoredItemProvider,
    contentPadding: PaddingValues,
    reverseLayout: Boolean,
    isAtBottom: Boolean,
    spacingProvider: () -> androidx.compose.ui.unit.Dp
): LazyLayoutMeasurePolicy = LazyLayoutMeasurePolicy { constraints ->
    val topPadding = contentPadding.calculateTopPadding().roundToPx()
    val bottomPadding = contentPadding.calculateBottomPadding().roundToPx()
    val startPadding = contentPadding.calculateStartPadding(layoutDirection).roundToPx()
    val endPadding = contentPadding.calculateEndPadding(layoutDirection).roundToPx()
    val spacing = spacingProvider().roundToPx()

    val beforeContentPadding = if (reverseLayout) bottomPadding else topPadding
    val afterContentPadding = if (reverseLayout) topPadding else bottomPadding
    val totalMainAxisPadding = beforeContentPadding + afterContentPadding

    val mainAxisAvailableSize = (constraints.maxHeight - topPadding - bottomPadding).coerceAtLeast(0)
    val childConstraints = Constraints(
        minWidth = 0,
        maxWidth = if (constraints.maxWidth == Constraints.Infinity)
            Constraints.Infinity
        else
            (constraints.maxWidth - startPadding - endPadding).coerceAtLeast(0),
        minHeight = 0,
        maxHeight = Constraints.Infinity
    )

    val itemCount = itemProvider.itemCount

    if (itemCount == 0) {
        state.canScrollForward = false
        state.canScrollBackward = false
        state.prevItemSizes.clear()
        layout(constraints.constrainWidth(constraints.maxWidth), constraints.maxHeight) {}
    } else {
        measureAnchoredItems(
            constraints = constraints,
            itemCount = itemCount,
            topPadding = topPadding,
            bottomPadding = bottomPadding,
            startPadding = startPadding,
            endPadding = endPadding,
            beforeContentPadding = beforeContentPadding,
            afterContentPadding = afterContentPadding,
            spacing = spacing,
            mainAxisAvailableSize = mainAxisAvailableSize,
            childConstraints = childConstraints,
            state = state,
            itemProvider = itemProvider,
            reverseLayout = reverseLayout,
            isAtBottom = isAtBottom
        )
    }
}

/**
 * 核心算法——逐行翻译自 measureLazyList。
 *
 * 剥离项：animation、prefetch、lookahead pass、stickyHeaders、beyondBounds。
 * 新增项：scroll anchoring（step 7）。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun LazyLayoutMeasureScope.measureAnchoredItems(
    constraints: Constraints,
    itemCount: Int,
    topPadding: Int,
    bottomPadding: Int,
    startPadding: Int,
    endPadding: Int,
    beforeContentPadding: Int,
    afterContentPadding: Int,
    spacing: Int,
    mainAxisAvailableSize: Int,
    childConstraints: Constraints,
    state: AnchoredLazyListState,
    itemProvider: AnchoredItemProvider,
    reverseLayout: Boolean,
    isAtBottom: Boolean
): MeasureResult {
    // === helper: measure + wrap ===
    fun getAndMeasure(index: Int): MeasureItem {
        val key = itemProvider.getKey(index)
        val placeables = measure(index, childConstraints)
        val itemSpacing = if (index == itemCount - 1) 0 else spacing
        return MeasureItem(index, placeables, key, itemSpacing)
    }

    // --- 1. 初始位置（key-based 追踪）---
    var currentFirstItemIndex = state.firstVisibleItemIndex.coerceIn(0, itemCount - 1)
    val savedKey = state.lastKnownFirstItemKey
    if (savedKey != null && currentFirstItemIndex < itemCount &&
        itemProvider.getKey(currentFirstItemIndex) != savedKey
    ) {
        val newIndex = itemProvider.getIndex(savedKey)
        if (newIndex >= 0) currentFirstItemIndex = newIndex
    }
    var currentFirstItemScrollOffset = state.firstVisibleItemScrollOffset

    // --- 2. 应用 scroll delta ---
    var scrollDelta = state.scrollToBeConsumed.roundToInt()
    state.scrollToBeConsumed -= scrollDelta
    currentFirstItemScrollOffset -= scrollDelta
    if (currentFirstItemIndex == 0 && currentFirstItemScrollOffset < 0) {
        scrollDelta += currentFirstItemScrollOffset
        currentFirstItemScrollOffset = 0
    }

    val visibleItems = ArrayDeque<MeasureItem>()
    val minOffset = -beforeContentPadding + minOf(0, spacing)
    val maxOffset = mainAxisAvailableSize

    // --- 3. 临时加入 beforeContentPadding（测量 padding 区域的 items）---
    currentFirstItemScrollOffset += minOffset

    // --- 4. Backward scroll（offset < 0 → 补充更小 index 的 items）---
    while (currentFirstItemScrollOffset < 0 && currentFirstItemIndex > 0) {
        val previous = currentFirstItemIndex - 1
        val measuredItem = getAndMeasure(previous)
        visibleItems.add(0, measuredItem)
        currentFirstItemScrollOffset += measuredItem.mainAxisSizeWithSpacings
        currentFirstItemIndex = previous
    }
    if (currentFirstItemScrollOffset < minOffset) {
        scrollDelta += currentFirstItemScrollOffset
        currentFirstItemScrollOffset = minOffset
    }

    // --- 5. 移除临时 padding 调整 ---
    currentFirstItemScrollOffset -= minOffset

    // --- 6. Forward measure + inline carry ---
    var index = currentFirstItemIndex
    val maxMainAxis = (maxOffset + afterContentPadding).coerceAtLeast(0)
    var currentMainAxisOffset = -currentFirstItemScrollOffset

    // 6a. 跳过 backward 已测量的 items
    var indexInVisibleItems = 0
    while (indexInVisibleItems < visibleItems.size) {
        if (currentMainAxisOffset >= maxMainAxis) {
            visibleItems.removeAt(indexInVisibleItems)
        } else {
            index++
            currentMainAxisOffset += visibleItems[indexInVisibleItems].mainAxisSizeWithSpacings
            indexInVisibleItems++
        }
    }

    // 6b. Forward fill（inline carry：item 在视口上方时不加入 visibleItems，推进 index）
    while (index < itemCount &&
        (currentMainAxisOffset < maxMainAxis ||
            currentMainAxisOffset <= 0 ||
            visibleItems.isEmpty())
    ) {
        val measuredItem = getAndMeasure(index)
        currentMainAxisOffset += measuredItem.mainAxisSizeWithSpacings

        if (currentMainAxisOffset <= minOffset && index != itemCount - 1) {
            // item 完全在视口上方 → 推进 firstVisibleItemIndex
            currentFirstItemIndex = index + 1
            currentFirstItemScrollOffset -= measuredItem.mainAxisSizeWithSpacings
        } else {
            visibleItems.add(measuredItem)
        }
        index++
    }

    // --- 7. Scroll-back（内容不足时填补视口）---
    val preScrollBackScrollDelta = scrollDelta
    if (currentMainAxisOffset < maxOffset) {
        val toScrollBack = maxOffset - currentMainAxisOffset
        currentFirstItemScrollOffset -= toScrollBack
        currentMainAxisOffset += toScrollBack
        while (currentFirstItemScrollOffset < beforeContentPadding &&
            currentFirstItemIndex > 0
        ) {
            val previousIndex = currentFirstItemIndex - 1
            val measuredItem = getAndMeasure(previousIndex)
            visibleItems.add(0, measuredItem)
            currentFirstItemScrollOffset += measuredItem.mainAxisSizeWithSpacings
            currentFirstItemIndex = previousIndex
        }
        scrollDelta += toScrollBack
        if (currentFirstItemScrollOffset < 0) {
            scrollDelta += currentFirstItemScrollOffset
            currentMainAxisOffset += currentFirstItemScrollOffset
            currentFirstItemScrollOffset = 0
        }
    }

    // --- 8. 过滤 padding 区域内的 items（确定真正的 firstVisibleItem）---
    val visibleItemsScrollOffset = -currentFirstItemScrollOffset
    var firstItem = visibleItems.first()
    if (beforeContentPadding > 0 || spacing < 0) {
        for (i in visibleItems.indices) {
            val size = visibleItems[i].mainAxisSizeWithSpacings
            if (currentFirstItemScrollOffset != 0 && size <= currentFirstItemScrollOffset &&
                i != visibleItems.lastIndex
            ) {
                currentFirstItemScrollOffset -= size
                firstItem = visibleItems[i + 1]
            } else {
                break
            }
        }
    }

    // === 8.5. Scroll anchoring（核心新增：同帧 item 增长补偿）===
    // 检测可见 items 的纯 height 增长（不含 spacing），在同一 measure pass 内补偿 offset。
    // 用 height 而非 mainAxisSizeWithSpacings：避免 items 增删时 spacing 变化被误判为增长。
    // 阈值 >1px：过滤 Markdown 异步渲染产生的 frame-to-frame 亚像素级尺寸波动。
    //   这是噪声过滤而非补丁——SSE 增长通常 ≥10px/frame，而布局抖动 <2px/frame。
    if (!isAtBottom && !state.isScrollInProgress && visibleItems.isNotEmpty()) {
        var totalGrowth = 0
        for (vi in visibleItems) {
            val prevSize = state.prevItemSizes[vi.key]
            if (prevSize != null && vi.height > prevSize + 1) {
                totalGrowth += vi.height - prevSize
            }
        }
        if (totalGrowth > 0) {
            currentFirstItemScrollOffset += totalGrowth
        }
    }

    // --- 9. 记录 sizes（用纯 height，不含 spacing）---
    val newSizes = HashMap<Any, Int>(visibleItems.size)
    for (vi in visibleItems) {
        newSizes[vi.key] = vi.height
    }
    state.prevItemSizes.clear()
    state.prevItemSizes.putAll(newSizes)

    // --- 10. 更新 state ---
    state.firstVisibleItemIndex = currentFirstItemIndex
    state.firstVisibleItemScrollOffset = currentFirstItemScrollOffset
    state.lastKnownFirstItemKey = firstItem.key
    state.totalItemsCount = itemCount
    // 精确照搬标准: canScrollForward = index < itemsCount || currentMainAxisOffset > maxOffset
    // index 是 forward fill 循环结束后的值（指向下一个未测量的 item）
    // 第二个条件覆盖"所有 items 已测量但内容超过视口"的场景（如 2 条消息但 AI 文章很长）
    state.canScrollForward = index < itemCount || currentMainAxisOffset > maxOffset
    state.canScrollBackward = currentFirstItemIndex > 0 || currentFirstItemScrollOffset > 0

    // --- 11. Position items（对应标准 calculateItemsOffsets 的非 spareSpace 分支）---
    var currentMainAxis = visibleItemsScrollOffset
    for (vi in visibleItems) {
        vi.position(currentMainAxis)
        currentMainAxis += vi.mainAxisSizeWithSpacings
    }

    // hasSpareSpace 判定（对应标准 calculateItemsOffsets 的分支选择）
    val contentHeight = currentMainAxisOffset.coerceAtLeast(0)

    // --- 12. Layout + placement ---
    // 先算 layoutHeight（被 constraints 钳制后的实际高度）
    val rawLayoutHeight = contentHeight.coerceAtLeast(mainAxisAvailableSize) + topPadding + bottomPadding
    val layoutHeight = constraints.constrainHeight(rawLayoutHeight)
    val layoutWidth = constraints.constrainWidth(
        (visibleItems.maxOfOrNull { it.crossAxisSize } ?: 0) + startPadding + endPadding
    )
    // Fix: placement 用实际 content 区域高度，不是 mainAxisLayoutSize
    // 否则当 contentHeight > viewport 时，items 会被定位到视口外 → 黑屏
    val effectiveContentHeight = layoutHeight - topPadding - bottomPadding
    val hasSpareSpace = contentHeight < minOf(effectiveContentHeight, maxOffset)

    return layout(layoutWidth, layoutHeight) {
        if (hasSpareSpace) {
            // 内容不足以填满视口：用 arrangement 从顶部排列
            // 对应标准 calculateItemsOffsets 的 hasSpareSpace 分支
            val itemsCount = visibleItems.size
            // 简化 arrangement：spacedBy(spacing) 等价于 Top + spacing
            val sizes = IntArray(itemsCount) { visibleItems[it].height }
            val offsets = IntArray(itemsCount) { 0 }
            // 手动 arrange(Top)：累积 offset
            var acc = 0
            for (i in 0 until itemsCount) {
                offsets[i] = acc
                acc += sizes[i] + spacing
            }
            // reverseLayout: 内部 index 反转 + offset 反转
            val ordered = if (reverseLayout) visibleItems.reversed() else visibleItems
            val orderedOffsets = if (reverseLayout) offsets.reversedArray() else offsets
            var yCursor = topPadding
            for (i in ordered.indices) {
                val item = ordered[i]
                var placeableY = yCursor
                for (placeable in item.placeables) {
                    placeable.placeRelative(startPadding, placeableY)
                    placeableY += placeable.height
                }
                yCursor += item.height + spacing
            }
        } else {
            // 正常 placement：用 position() 设置的内部 offset
            for (vi in visibleItems) {
                var placeableY = vi.offset
                for (placeable in vi.placeables) {
                    var y = placeableY
                    if (reverseLayout) {
                        y = effectiveContentHeight - y - placeable.height
                    }
                    y += topPadding
                    placeable.placeRelative(startPadding, y)
                    placeableY += placeable.height
                }
            }
        }
    }
}
