package dev.minios.ocremote.ui.screens.chat.util

import dev.minios.ocremote.domain.model.Part

/** 探查类工具集合 —— 连续出现时聚合展示。对齐 opencode CONTEXT_GROUP_TOOLS。 */
val CONTEXT_GROUP_TOOLS: Set<String> = setOf("read", "glob", "grep", "list", "listDirectory", "write")

/** parts 列表的可渲染单元：单个 part 或一组连续探查工具。 */
sealed class PartRenderUnit {
    data class Single(val part: Part) : PartRenderUnit()
    data class ContextGroup(val tools: List<Part.Tool>) : PartRenderUnit()
}

/**
 * 把 parts 列表分组：连续的探查工具合并成 [PartRenderUnit.ContextGroup]，其余各自成 [PartRenderUnit.Single]。
 * 单个探查工具也成组。对应 opencode 的 groupParts + isContextGroupTool。
 */
fun groupContextTools(parts: List<Part>): List<PartRenderUnit> {
    val result = mutableListOf<PartRenderUnit>()
    var start = -1

    fun flush(end: Int) {
        if (start < 0) return
        val group = parts.subList(start, end + 1).filterIsInstance<Part.Tool>()
        if (group.isNotEmpty()) {
            result.add(PartRenderUnit.ContextGroup(group))
        }
        start = -1
    }

    for (i in parts.indices) {
        val part = parts[i]
        if (part is Part.Tool && part.tool in CONTEXT_GROUP_TOOLS) {
            if (start < 0) start = i
        } else {
            flush(i - 1)
            result.add(PartRenderUnit.Single(part))
        }
    }
    flush(parts.lastIndex)
    return result
}

/** 探查工具分类计数：read / search(glob+grep) / list / write。 */
data class ContextToolSummary(val read: Int, val search: Int, val list: Int, val write: Int)

/** 计算一组探查工具的分类计数。 */
fun contextToolSummary(tools: List<Part.Tool>): ContextToolSummary {
    var read = 0
    var search = 0
    var list = 0
    var write = 0
    for (t in tools) {
        when (t.tool) {
            "read" -> read++
            "glob", "grep" -> search++
            "list", "listDirectory" -> list++
            "write" -> write++
        }
    }
    return ContextToolSummary(read, search, list, write)
}
