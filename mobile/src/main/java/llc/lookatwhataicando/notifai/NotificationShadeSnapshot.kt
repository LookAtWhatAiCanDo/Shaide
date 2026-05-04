package llc.lookatwhataicando.notifai

import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.smartfoo.android.core.logging.FooLog

private val TAG = FooLog.TAG(NotificationShadeSnapshot::class)
private object NotificationShadeSnapshot

// ---------------------------------------------------------------------------
// Shared constants — used by this file, ShadeRowSearchQueue, and DebugShadeScan
// ---------------------------------------------------------------------------

internal const val COM_ANDROID_SYSTEMUI = "com.android.systemui"

/**
 * Known notification shade container view IDs, ordered by preference. First match wins.
 * When the heuristic fallback fires instead, it logs the actual ID found — paste that in here
 * to avoid the heuristic cost on future runs.
 *
 * To add a new OEM: run with DEBUG_DUMP_WINDOWS = true, find the scrollable container that
 * holds the notification rows, and add its full viewIdResourceName here.
 */
internal val NOTIFICATION_CONTAINER_IDS = listOf(
    "com.android.systemui:id/notification_stack_scroller", // AOSP / Pixel (confirmed)
    "com.android.systemui:id/notification_panel",          // unconfirmed OEM variant
    "com.android.systemui:id/notification_list",           // unconfirmed OEM variant
)

/**
 * Known notification row view ID suffixes, ordered by preference.
 * Suffix matching tolerates differing package prefixes across OEMs.
 * When the heuristic fallback fires, it logs the actual suffix found — add it here.
 *
 * To add a new OEM: run with DEBUG_DUMP_WINDOWS = true, find the direct children of the
 * container that represent individual notifications, and add their viewIdResourceName suffix.
 */
internal val NOTIFICATION_ROW_ID_SUFFIXES = listOf(
    "expandableNotificationRow", // AOSP / Pixel (confirmed)
)

/** Content-description on the chevron button of a collapsed notification row. */
internal const val EXPAND_BUTTON_DESC = "Expand"
/** Content-description on the chevron button of an already-expanded notification row. */
internal const val COLLAPSE_BUTTON_DESC = "Collapse"

private const val DEBUG_DUMP_WINDOWS = false
private const val VERBOSE_LOG_CONTAINER_NOT_FOUND = false

// ---------------------------------------------------------------------------
// ShadeRow
// ---------------------------------------------------------------------------

/**
 * Structured snapshot of a single notification row in the notification shade.
 *
 * Fields are extracted by view ID suffix — suffix matching tolerates OEM/android:id/ variations.
 *   appName  — from .../app_name_text  (e.g. "Discord", "Chat")
 *   title    — from .../title or .../notification_title  (channel, contact, or subject)
 *   sender   — from .../message_name  (individual sender in a group/messaging notification)
 *   messages — all .../message_text values in order  (one per bubble line)
 *   text     — from .../notification_text or .../big_text or .../text when messages is empty
 *   time     — from .../time (Android provides the long form "6 hours ago" vs the UI "6h")
 */
data class ShadeRow(
    val appName: String?,
    val title: String?,
    val sender: String?,
    val messages: List<String>,
    val text: String?,
    val time: String?,
) {
    val isEmpty: Boolean
        get() = appName == null && title == null && sender == null && messages.isEmpty() && text == null
}

// ---------------------------------------------------------------------------
// Snapshot
// ---------------------------------------------------------------------------

/**
 * Single DFS from [root] that simultaneously dumps (when [DEBUG_DUMP_WINDOWS] is true),
 * locates the notification shade container, and collects row nodes.
 *
 * Collecting rows during this traversal — rather than in a separate pass — is essential.
 * Each call to [AccessibilityNodeInfo.getChild] can return a new pooled wrapper; a second
 * call for the same child index may yield a stale wrapper with an incomplete childCount.
 * Row nodes obtained here (first access) have correct childCount, so [extractRowData] can
 * reach all siblings including app_name_text and time without needing refresh().
 *
 * Container search: Tier 1 = known IDs in [NOTIFICATION_CONTAINER_IDS].
 *                   Tier 2 = structural heuristic (scrollable, majority-clickable children).
 * Row collection:   Tier 1 = direct children matching [NOTIFICATION_ROW_ID_SUFFIXES].
 *                   Tier 2 = all clickable direct children of the container.
 */
internal fun snapshotShade(eventTypeName: String, root: AccessibilityNodeInfo): List<ShadeRow> {
    if (DEBUG_DUMP_WINDOWS) {
        FooLog.v(TAG, "snapshotShade[$eventTypeName]: pkg=${root.packageName} id=${root.viewIdResourceName}")
    }

    val rowNodes = mutableListOf<AccessibilityNodeInfo>()

    fun logNode(node: AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val id = node.viewIdResourceName?.removePrefix("$COM_ANDROID_SYSTEMUI:id/") ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val text = node.text?.toString()?.replace('\n', '↵') ?: ""
        val desc = node.contentDescription?.toString()?.replace('\n', '↵') ?: ""
        val flags = buildString {
            if (node.isClickable)     append("click ")
            if (node.isScrollable)    append("scroll ")
            if (node.isCheckable)     append("check ")
            if (node.isEnabled)       append("enabled ")
            if (node.isVisibleToUser) append("visible ")
        }.trimEnd()
        FooLog.v(TAG, buildString {
            append("$indent[$cls] id=$id")
            if (text.isNotEmpty()) append(" text=\"$text\"")
            if (desc.isNotEmpty()) append(" desc=\"$desc\"")
            if (flags.isNotEmpty()) append(" [$flags]")
        })
    }

    // Returns true when the container is found and rows are collected, so the caller can
    // short-circuit when DEBUG_DUMP_WINDOWS is false.
    fun traverse(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (DEBUG_DUMP_WINDOWS) logNode(node, depth)

        if (node.viewIdResourceName in NOTIFICATION_CONTAINER_IDS) {
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    if (NOTIFICATION_ROW_ID_SUFFIXES.any { child.viewIdResourceName?.endsWith(it) == true }) {
                        rowNodes.add(child)
                    }
                    if (DEBUG_DUMP_WINDOWS) traverse(child, depth + 1)
                }
            }
            return true
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (traverse(child, depth + 1) && !DEBUG_DUMP_WINDOWS) return true
        }
        return false
    }

    traverse(root, 0)

    if (rowNodes.isEmpty()) {
        val heuristic = findNotificationContainerHeuristic(root)
        if (heuristic != null) {
            FooLog.w(TAG, "snapshotShade: heuristic container id=${heuristic.viewIdResourceName} — add to NOTIFICATION_CONTAINER_IDS")
            val matched = (0 until heuristic.childCount)
                .mapNotNull { heuristic.getChild(it) }
                .filter { it.isClickable }
            if (matched.isNotEmpty()) {
                val sampleSuffix = (matched.first().viewIdResourceName ?: "<no-id>").substringAfterLast('/')
                FooLog.w(TAG, "snapshotShade: heuristic rows sample suffix=$sampleSuffix — add to NOTIFICATION_ROW_ID_SUFFIXES")
                rowNodes.addAll(matched)
            }
        } else if (VERBOSE_LOG_CONTAINER_NOT_FOUND) {
            FooLog.w(TAG, "snapshotShade: no notification container found")
        }
    }

    return rowNodes.mapNotNull { extractRowData(it).takeUnless { row -> row.isEmpty } }
}

private fun findNotificationContainerHeuristic(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (node.isScrollable) {
        val childCount = node.childCount
        if (childCount > 0) {
            val clickable = (0 until childCount).count { node.getChild(it)?.isClickable == true }
            if (clickable * 2 >= childCount) return node
        }
    }
    for (i in 0 until node.childCount) {
        node.getChild(i)?.let { findNotificationContainerHeuristic(it) }?.let { return it }
    }
    return null
}

/**
 * Walks a notification row's subtree and extracts structured fields by view ID suffix.
 * Suffix matching tolerates both `android:id/` and `com.android.systemui:id/` prefixes.
 */
private fun extractRowData(row: AccessibilityNodeInfo): ShadeRow {
    var appName: String? = null
    var title: String? = null
    var sender: String? = null
    val messages = mutableListOf<String>()
    var text: String? = null
    var time: String? = null

    fun walk(node: AccessibilityNodeInfo) {
        val id = node.viewIdResourceName
        val nodeText = node.text?.toString()
        if (!nodeText.isNullOrBlank()) {
            when {
                id?.endsWith("/app_name_text")     == true -> appName = nodeText
                id?.endsWith("/title")              == true -> if (title == null) title = nodeText
                id?.endsWith("/notification_title") == true -> if (title == null) title = nodeText
                id?.endsWith("/message_name")       == true -> sender = nodeText
                id?.endsWith("/message_text")       == true -> messages.add(nodeText)
                id?.endsWith("/big_text")           == true -> if (text == null) text = nodeText
                id?.endsWith("/notification_text")  == true -> if (text == null) text = nodeText
                id?.endsWith("/text")               == true -> if (text == null) text = nodeText
                id?.endsWith("/time")               == true -> if (time == null) time = nodeText
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { walk(it) }
        }
    }

    walk(row)

    return ShadeRow(
        appName  = appName,
        title    = title,
        sender   = sender,
        messages = messages,
        text     = text,
        time     = time,
    )
}

// ---------------------------------------------------------------------------
// Tree traversal utilities — used by ShadeRowSearchQueue and DebugShadeScan
// ---------------------------------------------------------------------------

internal fun getLiveRowNodes(windows: List<AccessibilityWindowInfo>?): List<AccessibilityNodeInfo> {
    windows ?: return emptyList()
    for (window in windows) {
        val root = window.root ?: continue
        if (root.packageName?.toString() != COM_ANDROID_SYSTEMUI) continue
        val rows = mutableListOf<AccessibilityNodeInfo>()
        if (collectRowNodes(root, rows) && rows.isNotEmpty()) return rows
    }
    return emptyList()
}

internal fun collectRowNodes(node: AccessibilityNodeInfo, rows: MutableList<AccessibilityNodeInfo>): Boolean {
    if (node.viewIdResourceName in NOTIFICATION_CONTAINER_IDS) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                if (NOTIFICATION_ROW_ID_SUFFIXES.any { child.viewIdResourceName?.endsWith(it) == true }) {
                    rows.add(child)
                }
            }
        }
        return true
    }
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        if (collectRowNodes(child, rows)) return true
    }
    return false
}

internal fun findContainerNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    if (root.viewIdResourceName in NOTIFICATION_CONTAINER_IDS) return root
    for (i in 0 until root.childCount) {
        val child = root.getChild(i) ?: continue
        findContainerNode(child)?.let { return it }
    }
    return null
}

internal fun getLiveContainerNode(windows: List<AccessibilityWindowInfo>?): AccessibilityNodeInfo? {
    windows ?: return null
    for (window in windows) {
        val root = window.root ?: continue
        if (root.packageName?.toString() != COM_ANDROID_SYSTEMUI) continue
        findContainerNode(root)?.let { return it }
    }
    return null
}

internal fun findRawRowWithAppName(
    appLabel: String,
    windows: List<AccessibilityWindowInfo>?,
): AccessibilityNodeInfo? =
    getLiveRowNodes(windows).firstOrNull { hasAppNameInSubtree(it, appLabel) }

internal fun hasAppNameInSubtree(node: AccessibilityNodeInfo, appLabel: String): Boolean {
    if (node.viewIdResourceName?.endsWith("/app_name_text") == true &&
        node.text?.toString()?.equals(appLabel, ignoreCase = true) == true) return true
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        if (hasAppNameInSubtree(child, appLabel)) return true
    }
    return false
}

/**
 * Finds the expand/collapse chevron button that belongs **directly** to this notification
 * row — i.e. the button whose [contentDescription] matches [desc] — without descending into
 * nested [expandableNotificationRow] children, which have their own independent chevrons.
 *
 * This prevents falsely detecting a child row's "Expand" button as belonging to an
 * already-expanded parent GROUP_SUMMARY, which would cause ACTION_CLICK on the parent to
 * toggle it back to collapsed (expand→collapse→expand… loop).
 *
 * @param isRoot  true only for the top-level call; child expandableNotificationRow nodes
 *                stop recursion immediately.
 */
internal fun findDirectRowButton(
    node: AccessibilityNodeInfo,
    desc: String,
    isRoot: Boolean = true,
): AccessibilityNodeInfo? {
    if (!isRoot && NOTIFICATION_ROW_ID_SUFFIXES.any { node.viewIdResourceName?.endsWith(it) == true }) return null
    if (node.contentDescription?.toString() == desc) return node
    for (i in 0 until node.childCount) {
        val child = node.getChild(i) ?: continue
        findDirectRowButton(child, desc, isRoot = false)?.let { return it }
    }
    return null
}
