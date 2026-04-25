package com.soul.autoreply.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.SharedPreferences
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.soul.autoreply.api.AutoReplyApi

class SoulAccessibilityService : AccessibilityService() {

    companion object {
        var instance: SoulAccessibilityService? = null
            private set
        var isRunning = false
            private set
        private const val TAG = "SoulDebug"
        private var dumpCounter = 0
    }

    private lateinit var prefs: SharedPreferences
    private var lastEventTime = 0L
    private var isProcessingReply = false
    private var processingStartTime = 0L
    private var lastProcessedText = ""

    // Known Soul package names
    private val soulPackages = listOf(
        "com.soulskill.app",
        "com.soulgame.soul",
        "com.soulapp",
        "cn.soulapp.android"
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = getSharedPreferences("soul_reply_prefs", MODE_PRIVATE)
    }

    override fun onDestroy() {
        instance = null
        isRunning = false
        super.onDestroy()
    }

    override fun onServiceConnected() {
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                        AccessibilityEvent.TYPE_VIEW_CLICKED or
                        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 300
            packageNames = soulPackages.toTypedArray()
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        serviceInfo = info
        isRunning = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 超时看门狗：防止 API 卡住导致 isProcessingReply 永久锁死
        if (isProcessingReply && processingStartTime > 0 &&
            System.currentTimeMillis() - processingStartTime > 90000) {
            isProcessingReply = false
            processingStartTime = 0
        }
        if (isProcessingReply) return

        try {
            val packageName = event?.packageName?.toString() ?: return
            val eventType = event.eventType

            // 只处理 Soul 相关包（包含soul关键词的所有包）
            val isSoulApp = packageName.contains("soul", ignoreCase = true) ||
                            packageName.contains("soulskill", ignoreCase = true) ||
                            packageName.contains("soulgame", ignoreCase = true)
            if (!isSoulApp) return

            val now = System.currentTimeMillis()

            // 事件频率限制：500ms内的重复事件直接跳过
            if (now - lastEventTime < 500) return
            lastEventTime = now

            // 监听多种事件类型，确保不漏掉
            if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {

                // 每次窗口变化都dump界面结构（限制频率）
                dumpUIStructure()
                handleWindowContentChanged()
            }
        } catch (e: Exception) {
            isProcessingReply = false
        }
    }

    /**
     * 把当前界面完整结构dump到文件，方便分析Soul的UI层次
     */
    private fun dumpUIStructure() {
        if (dumpCounter > 0) return // 只dump前3次，避免刷屏
        dumpCounter++

        val rootNode = rootInActiveWindow ?: return
        try {
            val sb = StringBuilder()
            sb.append("=== Soul UI Dump #${dumpCounter} ===\n")
            sb.append("Time: ${System.currentTimeMillis()}\n")
            appendNodeTree(rootNode, sb, 0)
            sb.append("\n=== Messages found ===\n")
            val messages = findChatMessages(rootNode)
            messages.forEach { sb.append("MSG: $it\n") }

            // 写入app内部存储
            val file = getFileStreamPath("soul_ui_dump_${dumpCounter}.txt")
            file.writeText(sb.toString())

            Log.d(TAG, "UI结构已dump到: ${file.absolutePath}")
            showToast("UI诊断: 已保存结构到文件")
        } catch (e: Exception) {
            Log.e(TAG, "dump失败: ${e.message}")
        } finally {
            rootNode.recycle()
        }
    }

    private fun appendNodeTree(node: AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 6) return // 限制深度避免过深
        val indent = "  ".repeat(depth)

        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""
        val viewId = node.viewIdResourceName ?: ""
        val clickable = node.isClickable
        val editable = node.isEditable

        if (text.isNotBlank() || desc.isNotBlank() || viewId.isNotBlank()) {
            sb.append("$indent[class=$className")
            if (viewId.isNotBlank()) sb.append(", id=$viewId")
            if (text.isNotBlank()) sb.append(", text=\"${text.take(50)}\"")
            if (desc.isNotBlank()) sb.append(", desc=\"${desc.take(50)}\"")
            if (clickable) sb.append(", clickable=true")
            if (editable) sb.append(", editable=true")
            sb.append("]\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                appendNodeTree(child, sb, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun handleWindowContentChanged() {
        val rootNode = rootInActiveWindow ?: return
        try {
            val chatMessages = findChatMessages(rootNode)
            if (chatMessages.isNotEmpty()) {
                val latest = chatMessages.last()
                val trimmed = latest.trim()

                // 调试：显示找到的消息
                showToast("[调试] 找到消息: ${trimmed.take(30)}")

                // 防重复 + 字数过滤 + 内容验证
                if (trimmed.isNotEmpty() &&
                    trimmed != lastProcessedText &&
                    trimmed.length < 1000 &&
                    !trimmed.all { it.isDigit() || it == ':' || it == '-' || it == '.' || it == ' ' }) {

                    lastProcessedText = trimmed

                    if (!isLikelyOwnMessage(trimmed, rootNode)) {
                        triggerAutoReply(trimmed)
                    }
                }
            }
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Find chat message texts from the current screen.
     * We look for text nodes that are inside chat bubble containers.
     */
    private fun findChatMessages(root: AccessibilityNodeInfo): List<String> {
        val messages = mutableListOf<String>()
        findMessageNodesDeep(root, messages, 0)
        return messages
    }

    private fun findMessageNodesDeep(node: AccessibilityNodeInfo, results: MutableList<String>, depth: Int) {
        if (depth > 40) return // prevent infinite recursion

        val text = node.text?.toString()?.trim() ?: ""
        val className = node.className?.toString() ?: ""
        val contentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val viewId = node.viewIdResourceName ?: ""

        // 收集所有非空文本节点（放宽过滤条件）
        val hasMeaningfulText = text.isNotBlank() && text.length >= 2

        if (hasMeaningfulText) {
            // 跳过纯UI标签和时间戳
            val isPureTimestamp = text.all { it.isDigit() || it == ':' || it == '-' || it == '.' }
            val isButtonLabel = text.length < 8 && listOf("发送", "取消", "确定", "保存", "关闭", "删除", "编辑", "回复", "转发", "返回", "更多", "分享").contains(text)
            val isInputHint = text.contains("输入") || text.contains("说说") || text.contains("这一刻") || text.contains("正在输入")

            if (!isPureTimestamp && !isButtonLabel && !isInputHint) {
                results.add(text)
            }
        }

        // 递归遍历子节点
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                findMessageNodesDeep(child, results, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun isUIContainment(className: String, text: String): Boolean {
        val chromeLabels = setOf(
            "Soul", "soul", "soul", "发送", "发送", "取消", "确定", "保存",
            "输入", "搜索", "设置", "个人中心", "消息", "聊天", "联系人",
            "发现", "我的", "返回", "关闭", "更多", "编辑", "删除"
        )
        if (text.length < 8 && chromeLabels.any { text.startsWith(it) }) return true

        val uiClasses = setOf(
            "Toolbar", "ActionBar", "TabLayout", "BottomNavigation",
            "LinearLayout", "RelativeLayout", "FrameLayout", "ConstraintLayout"
        )
        // Only skip pure layout containers with no meaningful text
        if (uiClasses.any { className.contains(it) } && text.isEmpty()) return true

        return false
    }

    private fun looksLikeChatMessage(text: String, className: String): Boolean {
        // Real messages tend to be longer than UI labels
        if (text.length < 4) return false
        // Real messages don't look like button labels
        val buttonLabels = listOf("发送", "取消", "确定", "保存", "关闭", "删除", "编辑", "回复", "转发")
        if (buttonLabels.any { text == it || text.startsWith(it) && text.length < 6 }) return false
        // Real messages don't look like input hints
        if (text.contains("输入") || text.contains("说说") || text.contains("这一刻")) return false
        // Real messages are not pure numbers ( Timestamps)
        if (text.all { it.isDigit() || it == ':' || it == '-' }) return false
        return true
    }

    private fun isLikelyOwnMessage(text: String, root: AccessibilityNodeInfo): Boolean {
        // Simple heuristic: if there's a send button nearby and the text is short-ish
        // or if we just sent something similar, assume it's our own
        // For now, we let the debounce handle duplicates
        return false
    }

    private fun triggerAutoReply(incomingMessage: String) {
        isProcessingReply = true
        processingStartTime = System.currentTimeMillis()
        showToast("收到: $incomingMessage")

        AutoReplyApi.sendReply(this, incomingMessage) { reply ->
            android.os.Handler(mainLooper).post {
                if (reply.startsWith("API错误") || reply.startsWith("网络错误") ||
                    reply == "请先配置API Key" || reply.startsWith("API错误")) {
                    showToast("自动回复失败: $reply")
                    isProcessingReply = false
                } else {
                    pasteAndSendReply(reply)
                    showToast("已回复: $reply")
                    isProcessingReply = false
                }
            }
        }
    }

    private fun pasteAndSendReply(text: String) {
        val rootNode = rootInActiveWindow ?: return
        try {
            // Strategy 1: Find the input EditText and set text, then find send button
            val inputNode = findInputEditText(rootNode)
            if (inputNode != null) {
                // Set the reply text
                val args = android.os.Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                inputNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                inputNode.recycle()

                // Try to click send button after a short delay
                android.os.Handler(mainLooper).postDelayed({
                    clickSendButton()
                }, 200)
                rootNode.recycle()
                return
            }

            // Strategy 2: Find send button first, then find adjacent input
            clickSendButton()
        } finally {
            rootNode.recycle()
        }
    }

    private fun findInputEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val className = node.className?.toString() ?: ""

        if (node.isEditable && (className.contains("EditText", ignoreCase = true) ||
                    className.contains("TextInput", ignoreCase = true) ||
                    className.contains("Edit", ignoreCase = true))) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findInputEditText(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun clickSendButton() {
        val rootNode = rootInActiveWindow ?: return
        try {
            val button = findSendButtonNode(rootNode)
            if (button != null) {
                button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                button.recycle()
            }
        } finally {
            rootNode.recycle()
        }
    }

    private fun findSendButtonNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val className = node.className?.toString() ?: ""

        // Match by text "发送" (Send)
        if (text == "发送" || desc == "发送" || desc == "send" || desc == "Send") {
            if (node.isClickable) return node
        }

        // Match by content description containing "send" or Chinese send
        if ((desc.contains("发送") || desc.lowercase().contains("send")) && node.isClickable) {
            return node
        }

        // Match ImageButton with send description
        if (className.contains("ImageButton", ignoreCase = true) && node.isClickable) {
            if (desc.isNotEmpty() && (desc.contains("send", ignoreCase = true) || desc == "发送")) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findSendButtonNode(child)
            if (result != null) {
                child.recycle()
                return result
            }
            child.recycle()
        }
        return null
    }

    private fun showToast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) { /* ignore */ }
    }

    private fun runOnUiThread(block: () -> Unit) {
        android.os.Handler(mainLooper).post(block)
    }

    override fun onInterrupt() {
        isProcessingReply = false
    }
}
