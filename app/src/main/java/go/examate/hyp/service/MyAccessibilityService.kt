package go.examate.hyp.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class MyAccessibilityService : AccessibilityService() {
    // 窗口
    private var root: AccessibilityNodeInfo? = null

    // 搜索关键字
    private var keyword: String? = null

    // 被更新UI的View(用 WeakReference 防止 Activity 泄漏)
    private var textViewRef: java.lang.ref.WeakReference<TextView>? = null

    init {
        myAService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        //只使用界面变化的监听，避免点击事件监听进入死循环
        if (AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED != event.eventType) {
            return
        }

        if (keyword != null && textViewRef?.get() != null) getFullTextFromPart()
    }

    override fun onInterrupt() {
        myAService = null
    }

    override fun onDestroy() {
        super.onDestroy()
        textViewRef = null
        myAService = null
    }

    private fun traverseNodeText(nodeInfo: AccessibilityNodeInfo?) {
        if (nodeInfo == null) return

        val kw = keyword ?: return
        val tv = textViewRef?.get() ?: return
        // 当前节点的文本内容
        val text = nodeInfo.text
        if (text != null && !TextUtils.isEmpty(text) && text.toString().contains(kw)) {
            tv.text = text
            return
        }

        // 遍历子节点
        for (i in 0 until nodeInfo.childCount) {
            val childNodeInfo = nodeInfo.getChild(i)
            if (childNodeInfo != null) {
                traverseNodeText(childNodeInfo)
            }
        }
    }

    private fun traverseNodeRect(
        nodeInfo: AccessibilityNodeInfo?,
        selectZoneOnScreen: Rect,
        rectListToDraw: MutableList<Rect>,
        rectListToHint: MutableList<Rect>,
        resText: StringBuilder
    ) {
        if (nodeInfo == null) return

        // 当前节点的屏幕空间矩形
        val nodeRect = Rect()
        nodeInfo.getBoundsInScreen(nodeRect)

        // 比对是否包括在选区内
        if (selectZoneOnScreen.contains(nodeRect)) {
            val text = nodeInfo.text
            if (text != null && text.toString().isNotEmpty()) {
                Log.d("拾取选区内文本", text.toString())
                rectListToDraw.add(nodeRect)
                if (resText.isNotEmpty()) resText.append('\n')
                resText.append(text)
            }
        } else if (Rect.intersects(selectZoneOnScreen, nodeRect)) { //交叉
            val text = nodeInfo.text
            if (text != null && text.toString().isNotEmpty()) {
                rectListToHint.add(nodeRect)
            }
        }

        // 遍历子节点
        for (i in 0 until nodeInfo.childCount) {
            val childNodeInfo = nodeInfo.getChild(i)
            if (childNodeInfo != null) {
                traverseNodeRect(childNodeInfo, selectZoneOnScreen, rectListToDraw, rectListToHint, resText)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════
    // 自动答题模式:题目提取、选项点击、答案填入、下一题
    // ═══════════════════════════════════════════════════════════

    /**
     * 收集当前屏幕所有可见文本(排除本应用自身的窗口)
     * 同时取 text 和 contentDescription,并去重
     * @return 屏幕文本拼接结果,按节点顺序换行
     */
    fun collectAllScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        // 排除本应用包名,避免抓到悬浮窗自身
        if (rootNode.packageName == "go.examate.hyp") return ""
        val sb = StringBuilder()
        val seen = HashSet<String>()
        collectTextRecursive(rootNode, sb, seen)
        return sb.toString().trim()
    }

    /**
     * 带重试的文本提取:页面可能正在加载,延迟重试以提高命中率
     * @param maxRetries 最大重试次数(每次间隔 400ms)
     * @return 提取到的文本,失败返回空串
     */
    fun collectAllScreenTextWithRetry(maxRetries: Int = 5): String {
        var lastText = ""
        repeat(maxRetries) { attempt ->
            val text = collectAllScreenText()
            if (text.length >= 15) {
                // 内容已稳定:连续两次读取结果一致则直接返回
                if (text == lastText) return text
                lastText = text
            }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return lastText
            }
        }
        return lastText
    }

    private fun collectTextRecursive(node: AccessibilityNodeInfo, sb: StringBuilder, seen: HashSet<String>) {
        // 同时取 text 和 contentDescription(部分应用把内容放在 desc 里)
        val text = node.text
        if (!TextUtils.isEmpty(text)) {
            val s = text.toString().trim()
            if (s.isNotEmpty() && seen.add(s)) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(s)
            }
        }
        val desc = node.contentDescription
        if (!TextUtils.isEmpty(desc)) {
            val s = desc.toString().trim()
            // contentDescription 可能和 text 重复,用 seen 去重
            if (s.isNotEmpty() && seen.add(s)) {
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(s)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextRecursive(child, sb, seen)
        }
    }

    /**
     * 查找所有可点击且含文本的节点(用于选项定位)
     */
    private fun findAllClickableTextNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        // 可点击或可检查的节点,且自身或子节点有文本
        if (node.isClickable || node.isCheckable) {
            val hasText = !TextUtils.isEmpty(node.text) || hasTextInChildren(node)
            if (hasText) {
                result.add(node)
            }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findAllClickableTextNodes(child, result)
        }
    }

    private fun hasTextInChildren(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (!TextUtils.isEmpty(child.text)) return true
            if (hasTextInChildren(child)) return true
        }
        return false
    }

    private fun nodeFullText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        if (!TextUtils.isEmpty(node.text)) sb.append(node.text)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val t = nodeFullText(child)
            if (t.isNotEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(t)
            }
        }
        return sb.toString()
    }

    /**
     * 根据答案文本点击对应选项
     * 支持的答案格式:A / B / C / D 或 选项内容文本
     * @param answer AI 返回的答案(如 "A" 或 "B. 某选项内容")
     * @return true 表示成功点击了某个选项
     */
    fun clickOptionByAnswer(answer: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        if (rootNode.packageName == "go.examate.hyp") return false

        // 提取答案首字母(如 "A" / "B")
        val answerLetter = answer.trim().firstOrNull()?.uppercaseChar() ?: return false

        val clickables = mutableListOf<AccessibilityNodeInfo>()
        findAllClickableTextNodes(rootNode, clickables)

        // 策略1: 找文本以 "A." / "A、" / "A " / "A）" 等开头的可点击节点
        for (node in clickables) {
            val text = nodeFullText(node).trim()
            if (text.isEmpty()) continue
            // 匹配 "A.xxx" / "A、xxx" / "A xxx" / "A)xxx" / "A）xxx"
            if (text.length >= 2 && text[0].uppercaseChar() == answerLetter) {
                val second = text[1]
                if (second == '.' || second == '、' || second == ' ' || second == ')' || second == '）' || second == '\t') {
                    return performClickSafe(node)
                }
            }
        }

        // 策略2: 选项可能是 RadioButton/CheckBox,按顺序匹配(第1个=A,第2个=B...)
        val optionLikeNodes = clickables.filter {
            it.isCheckable || it.className?.contains("RadioButton") == true ||
            it.className?.contains("CheckBox") == true
        }
        val index = answerLetter - 'A'
        if (index in optionLikeNodes.indices) {
            return performClickSafe(optionLikeNodes[index])
        }

        // 策略3: 按可点击节点顺序匹配(前4个通常是ABCD)
        if (clickables.size >= 2 && index in clickables.indices) {
            return performClickSafe(clickables[index])
        }

        return false
    }

    /**
     * 安全点击:优先 ACTION_CLICK,失败回退到 dispatchGesture 模拟真实触摸
     * WebView 中 ACTION_CLICK 无法触发 JS onclick,必须用 dispatchGesture
     */
    private fun performClickSafe(node: AccessibilityNodeInfo): Boolean {
        var target: AccessibilityNodeInfo? = node
        // 向上找到真正可点击的祖先节点
        while (target != null && !target.isClickable) {
            target = target.parent
        }
        // 先尝试 ACTION_CLICK
        if (target != null && target.isClickable) {
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        // 回退到 dispatchGesture 模拟真实触摸(WebView 必需)
        return clickNodeByGesture(target ?: node)
    }

    /**
     * 用 dispatchGesture 在节点中心发送真实触摸手势
     * WebView 中的 JavaScript onclick 只响应真实触摸,不响应 ACTION_CLICK
     * @return true 表示手势已派发(不保证目标已响应)
     */
    fun clickNodeByGesture(node: AccessibilityNodeInfo): Boolean {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val x = rect.exactCenterX()
        val y = rect.exactCenterY()
        return clickPointByGesture(x, y)
    }

    /**
     * 用 dispatchGesture 在指定坐标发送点击手势
     */
    fun clickPointByGesture(x: Float, y: Float): Boolean {
        return try {
            val path = Path()
            path.moveTo(x, y)
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()
            dispatchGesture(gesture, null, null)
        } catch (e: Exception) {
            Log.e("MyA11y", "dispatchGesture failed", e)
            false
        }
    }

    /**
     * 找到可编辑的输入框并填入答案文字
     * @param answer 要输入的答案文本
     * @return true 表示成功填入
     */
    fun fillAnswerToInputField(answer: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        if (rootNode.packageName == "go.examate.hyp") return false

        val editFields = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(rootNode, editFields)

        if (editFields.isEmpty()) return false

        // 优先填入第一个可编辑字段(通常答题框只有一个)
        val target = editFields.first()
        // 先聚焦
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        // 设置文本
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, answer)
        return target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditableNodes(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isEditable) {
            result.add(node)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findEditableNodes(child, result)
        }
    }

    /**
     * 点击"下一题"按钮
     * 查找文本包含"下一题"/"下一页"/"下一步"的可点击节点
     * @return true 表示成功点击
     */
    fun clickNextButton(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        if (rootNode.packageName == "go.examate.hyp") return false

        val keywords = listOf("下一题", "下一页", "下一步", "继续")
        for (kw in keywords) {
            val nodes = rootNode.findAccessibilityNodeInfosByText(kw)
            for (node in nodes) {
                if (performClickSafe(node)) return true
            }
        }
        // 回退:查找所有可点击节点中包含箭头图标的(有些"下一题"是图标按钮无文本)
        // 在 WebView 中,可能"下一题"是按钮但没有可访问文本,尝试用已知位置点击
        return false
    }

    /**
     * 检测当前是否处于"整卷浏览"状态(可获取完整题目)
     * 通过查找包含"整卷"/"全部"/"浏览全卷"等关键字判断
     */
    fun isOverviewMode(): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        if (rootNode.packageName == "go.examate.hyp") return false
        val keywords = listOf("整卷", "全卷", "全部试题", "答题卡")
        val allText = StringBuilder()
        collectTextRecursive(rootNode, allText, HashSet())
        return keywords.any { allText.contains(it) }
    }

    companion object {
        // 单例
        @JvmStatic
        private var myAService: MyAccessibilityService? = null

        @JvmStatic
        fun SetKeyword(text: String?): Boolean {
            val service = myAService ?: return false
            service.keyword = text
            getFullTextFromPart()
            return true
        }

        @JvmStatic
        fun SetTextView(v: TextView?) {
            myAService?.textViewRef = v?.let { java.lang.ref.WeakReference(it) }
        }

        @JvmStatic
        fun getMyAccessibilityServiceInstance(): MyAccessibilityService? {
            return myAService
        }

        @JvmStatic
        fun getFullTextFromPart() {
            val service = myAService ?: return
            val activeWindow = service.rootInActiveWindow
            if (activeWindow != null && activeWindow.packageName != "go.examate.hyp") {
                service.root = activeWindow
            }

            service.traverseNodeText(service.root)
        }

        @JvmStatic
        fun getRectanglesZoneContains(
            rectZoneOnScreen: Rect,
            rectListToDraw: MutableList<Rect>,
            rectListToHint: MutableList<Rect>,
            resText: StringBuilder
        ) {
            val service = myAService
            if (service == null) {
                resText.append("服务未运行，请检查无障碍权限是否开启")
                return
            }

            service.root = service.rootInActiveWindow
            rectListToDraw.clear()

            //修正区域
            val offset = 30
            rectZoneOnScreen.left -= offset
            rectZoneOnScreen.top -= offset
            rectZoneOnScreen.right += offset
            rectZoneOnScreen.bottom += offset

            val root = service.root
            if (root != null) {
                //遍历活动窗口所有节点
                Log.d("当前root", root.packageName.toString())
                Log.d("搜索区域", rectZoneOnScreen.toString())
                service.traverseNodeRect(
                    root, rectZoneOnScreen,
                    rectListToDraw, rectListToHint, resText
                )

                Log.d("遍历结束", "=== End ===")
            }
        }
    }
}
