package com.neoremote.android.core.receiver

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.neoremote.android.BuildConfig
import com.neoremote.android.core.model.DragState
import com.neoremote.android.core.model.RemoteCommand
import com.neoremote.android.core.model.SystemAction
import com.neoremote.android.core.model.VideoActionKind

class MobileControlAccessibilityService : AccessibilityService(), MobileCommandHandler {
    private var planner: MobileInputPlanner? = null
    private var receiverServer: MobileReceiverServer? = null
    private var discoveryPublisher: MobileReceiverDiscoveryPublisher? = null
    private var trailOverlayView: TouchTrailOverlayView? = null
    private var density: Float = 1f
    private val mainHandler = Handler(Looper.getMainLooper())
    private val actionQueue = MobileActionQueue(maxPendingActions = MAX_PENDING_ACTIONS)
    private val moveGestureAccumulator = MobileMoveGestureAccumulator()
    private var actionInFlight = false
    private val flushMoveGestureRunnable = Runnable {
        val flushedActions = moveGestureAccumulator.flush()
        if (flushedActions.isNotEmpty()) {
            actionQueue.enqueue(flushedActions, MobileActionQueuePolicy.APPEND)
            drainActionQueue()
        }
    }
    private val windowManager: WindowManager by lazy {
        getSystemService(WindowManager::class.java)
    }
    private var cachedLikeToggleNode: AccessibilityNodeInfo? = null
    private var cachedFavoriteToggleNode: AccessibilityNodeInfo? = null
    private var lastToggleCacheRefreshAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        val metrics = resources.displayMetrics
        density = metrics.density.takeIf { it > 0f } ?: 1f
        planner = MobileInputPlanner.fromPhysicalViewport(
            widthPixels = metrics.widthPixels,
            heightPixels = metrics.heightPixels,
            density = density,
        )
        Log.i(
            TAG,
            "Mobile receiver service connected viewport=${metrics.widthPixels}x${metrics.heightPixels} density=$density",
        )
        ensureTrailOverlay()
        receiverServer = MobileReceiverServer(commandHandler = this).also { it.start() }
        discoveryPublisher = MobileReceiverDiscoveryPublisher(this).also { it.start() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val activeEvent = event ?: return
        val packageName = activeEvent.packageName?.toString().orEmpty()
        if (!packageName.startsWith(DOUYIN_PACKAGE_PREFIX)) return
        val now = SystemClock.elapsedRealtime()
        if (!shouldRefreshToggleCache(activeEvent.eventType, now, lastToggleCacheRefreshAt)) return
        lastToggleCacheRefreshAt = now

        val source = rootInActiveWindow ?: activeEvent.source ?: return
        try {
            updateDouyinToggleCacheFrom(source)
        } finally {
            source.recycle()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        receiverServer?.closeScope()
        receiverServer = null
        discoveryPublisher?.closeScope()
        discoveryPublisher = null
        removeTrailOverlay()
        clearCachedDouyinToggles()
        planner = null
        super.onDestroy()
    }

    override fun handle(command: RemoteCommand): MobileCommandHandleResult {
        val activePlanner = planner ?: return MobileCommandHandleResult(handled = false)
        val actions = activePlanner.apply(command)
        debugLog { "Received command=$command actions=${actions.size}" }
        if (actions.isNotEmpty()) {
            mainHandler.post {
                val queuePolicy = mobileQueuePolicyFor(command)
                val queueableActions = actions.flatMap { action ->
                    if (action is MobileInputAction.MovePointer) {
                        mainHandler.removeCallbacks(flushMoveGestureRunnable)
                        moveGestureAccumulator.accept(action)
                    } else {
                        mainHandler.removeCallbacks(flushMoveGestureRunnable)
                        if (queuePolicy == MobileActionQueuePolicy.REPLACE_PENDING) {
                            moveGestureAccumulator.reset()
                            listOf(action)
                        } else {
                            moveGestureAccumulator.accept(action)
                        }
                    }
                }
                if (actions.any { it is MobileInputAction.MovePointer }) {
                    mainHandler.postDelayed(flushMoveGestureRunnable, MOVE_FLUSH_DELAY_MS)
                }
                if (queueableActions.isNotEmpty()) {
                    actionQueue.enqueue(queueableActions, queuePolicy)
                    drainActionQueue()
                }
            }
        }
        return MobileCommandHandleResult(
            handled = shouldAcknowledgeMobileCommand(command, actions),
        )
    }

    private fun clickDouyinToggle(kind: VideoToggleKind): MobileCommandHandleResult {
        val startedAt = SystemClock.elapsedRealtime()
        if (clickCachedDouyinToggle(kind)) {
            debugLog { "Douyin ${kind.logName} clicked path=cache elapsed=${SystemClock.elapsedRealtime() - startedAt}ms" }
            return MobileCommandHandleResult(handled = true)
        }

        val target = findDouyinToggleNode(kind)
            ?: return MobileCommandHandleResult(
                handled = false,
                statusMessage = kind.notFoundMessage,
            )

        val clicked = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (clicked) {
            replaceCachedToggleNode(kind, AccessibilityNodeInfo.obtain(target))
        }
        target.recycle()
        return if (clicked) {
            debugLog { "Douyin ${kind.logName} clicked path=scan elapsed=${SystemClock.elapsedRealtime() - startedAt}ms" }
            MobileCommandHandleResult(handled = true)
        } else {
            MobileCommandHandleResult(
                handled = false,
                statusMessage = kind.clickFailedMessage,
            )
        }
    }

    private fun findDouyinToggleNode(kind: VideoToggleKind): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        var match: AccessibilityNodeInfo? = null

        try {
            root.visitNodes { node ->
                val packageName = node.packageName?.toString().orEmpty()
                if (!packageName.startsWith(DOUYIN_PACKAGE_PREFIX)) return@visitNodes false

                val description = node.contentDescription?.toString().orEmpty()
                if (description.matchesDouyinToggle(kind)) {
                    match = node.closestClickableNode()
                }
                match != null
            }
        } finally {
            root.recycle()
        }

        return match
    }

    private fun updateDouyinToggleCacheFrom(root: AccessibilityNodeInfo) {
        var foundLike = false
        var foundFavorite = false
        root.visitNodes { node ->
            val packageName = node.packageName?.toString().orEmpty()
            if (!packageName.startsWith(DOUYIN_PACKAGE_PREFIX)) return@visitNodes false

            val description = node.contentDescription?.toString().orEmpty()
            when {
                description.matchesDouyinToggle(VideoToggleKind.LIKE) ->
                    node.closestClickableNode()?.let {
                        replaceCachedToggleNode(VideoToggleKind.LIKE, it)
                        foundLike = true
                    }

                description.matchesDouyinToggle(VideoToggleKind.FAVORITE) ->
                    node.closestClickableNode()?.let {
                        replaceCachedToggleNode(VideoToggleKind.FAVORITE, it)
                        foundFavorite = true
                    }
            }
            foundLike && foundFavorite
        }
    }

    private fun clickCachedDouyinToggle(kind: VideoToggleKind): Boolean {
        val node = cachedToggleNode(kind) ?: return false
        val isFresh = node.refresh()
        if (!isFresh || !node.isEnabled || !node.isClickable) {
            clearCachedToggleNode(kind)
            return false
        }

        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            clearCachedToggleNode(kind)
        }
        return clicked
    }

    private fun cachedToggleNode(kind: VideoToggleKind): AccessibilityNodeInfo? =
        when (kind) {
            VideoToggleKind.LIKE -> cachedLikeToggleNode
            VideoToggleKind.FAVORITE -> cachedFavoriteToggleNode
        }

    private fun replaceCachedToggleNode(kind: VideoToggleKind, node: AccessibilityNodeInfo) {
        clearCachedToggleNode(kind)
        when (kind) {
            VideoToggleKind.LIKE -> cachedLikeToggleNode = node
            VideoToggleKind.FAVORITE -> cachedFavoriteToggleNode = node
        }
    }

    private fun clearCachedToggleNode(kind: VideoToggleKind) {
        when (kind) {
            VideoToggleKind.LIKE -> {
                cachedLikeToggleNode?.recycle()
                cachedLikeToggleNode = null
            }

            VideoToggleKind.FAVORITE -> {
                cachedFavoriteToggleNode?.recycle()
                cachedFavoriteToggleNode = null
            }
        }
    }

    private fun clearCachedDouyinToggles() {
        clearCachedToggleNode(VideoToggleKind.LIKE)
        clearCachedToggleNode(VideoToggleKind.FAVORITE)
    }

    private fun drainActionQueue() {
        if (actionInFlight) return
        val nextAction = actionQueue.removeFirstOrNull() ?: return
        actionInFlight = true
        performAction(nextAction) {
            mainHandler.postDelayed(
                {
                    actionInFlight = false
                    drainActionQueue()
                },
                ACTION_SEQUENCE_DELAY_MS,
            )
        }
    }

    private fun performAction(action: MobileInputAction, onFinished: () -> Unit) {
        when (action) {
            is MobileInputAction.MovePointer -> dispatchMove(action.from, action.to, onFinished)
            is MobileInputAction.TapAt -> dispatchTap(
                position = action.position.toPhysicalPixels(density),
                showTrail = action.showTrail,
                onFinished = onFinished,
            )
            is MobileInputAction.LongPressAt -> dispatchPress(
                position = action.position.toPhysicalPixels(density),
                durationMs = action.durationMs,
                showTrail = action.showTrail,
                onFinished = onFinished,
            )
            is MobileInputAction.Swipe -> dispatchSwipe(
                from = action.from,
                to = action.to,
                durationMs = action.durationMs,
                showTrail = action.showTrail,
                onFinished = onFinished,
            )
            is MobileInputAction.VideoToggle -> {
                clickDouyinToggle(action.kind)
                onFinished()
            }
            is MobileInputAction.Global -> {
                performGlobal(action.action)
                onFinished()
            }
        }
    }

    private fun dispatchMove(from: PointerPosition, to: PointerPosition, onFinished: () -> Unit) {
        if (from == to) {
            onFinished()
            return
        }
        dispatchSwipe(
            from = from,
            to = to,
            durationMs = MOVE_DURATION_MS,
            showTrail = true,
            onFinished = onFinished,
        )
    }

    private fun dispatchTap(
        position: PointerPosition,
        showTrail: Boolean,
        onFinished: () -> Unit,
    ) {
        dispatchPress(
            position = position,
            durationMs = TAP_DURATION_MS,
            showTrail = showTrail,
            onFinished = onFinished,
        )
    }

    private fun dispatchPress(
        position: PointerPosition,
        durationMs: Long,
        showTrail: Boolean,
        onFinished: () -> Unit,
    ) {
        if (showTrail) {
            trailOverlayView?.recordTap(position)
        }
        val path = Path().apply { moveTo(position.x, position.y) }
        val dispatched = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            GestureLogCallback("tap", position, position, onFinished),
            null,
        )
        if (!dispatched) onFinished()
    }

    private fun dispatchSwipe(
        from: PointerPosition,
        to: PointerPosition,
        durationMs: Long,
        showTrail: Boolean = true,
        onFinished: () -> Unit,
    ) {
        val physicalFrom = from.toPhysicalPixels(density)
        val physicalTo = to.toPhysicalPixels(density)
        if (showTrail) {
            trailOverlayView?.recordSegment(physicalFrom, physicalTo)
        }
        val path = Path().apply {
            moveTo(physicalFrom.x, physicalFrom.y)
            lineTo(physicalTo.x, physicalTo.y)
        }
        val dispatched = dispatchGesture(
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build(),
            GestureLogCallback("swipe", physicalFrom, physicalTo, onFinished),
            null,
        )
        if (!dispatched) onFinished()
    }

    private fun performGlobal(action: SystemAction) {
        val globalAction = when (action) {
            SystemAction.BACK -> GLOBAL_ACTION_BACK
            SystemAction.HOME -> GLOBAL_ACTION_HOME
            SystemAction.RECENTS -> GLOBAL_ACTION_RECENTS
        }
        performGlobalAction(globalAction)
    }

    private fun ensureTrailOverlay() {
        if (trailOverlayView != null) return
        val overlay = TouchTrailOverlayView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        trailOverlayView = overlay
        windowManager.addView(overlay, params)
    }

    private fun removeTrailOverlay() {
        trailOverlayView?.let { overlay ->
            runCatching { windowManager.removeView(overlay) }
        }
        trailOverlayView = null
    }

    companion object {
        private const val MOVE_DURATION_MS = 45L
        private const val MOVE_FLUSH_DELAY_MS = 70L
        private const val TAP_DURATION_MS = 35L
        private const val ACTION_SEQUENCE_DELAY_MS = 16L
        private const val MAX_PENDING_ACTIONS = 24
        private const val TAG = "NeoRemoteReceiver"
        private const val DOUYIN_PACKAGE_PREFIX = "com.ss.android.ugc.aweme"

        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, MobileControlAccessibilityService::class.java)
                .flattenToString()
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ).orEmpty()
            return enabledServices
                .split(':')
                .any { it.equals(expected, ignoreCase = true) }
        }
    }

    private class GestureLogCallback(
        private val kind: String,
        private val from: PointerPosition,
        private val to: PointerPosition,
        private val onFinished: () -> Unit,
    ) : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            debugLog { "$kind gesture completed from=$from to=$to" }
            onFinished()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "$kind gesture cancelled from=$from to=$to")
            } else {
                Log.w(TAG, "$kind gesture cancelled")
            }
            onFinished()
        }
    }
}

private const val TOGGLE_CACHE_REFRESH_INTERVAL_MS = 300L
private const val MAX_ACCESSIBILITY_SCAN_NODES = 160

private class TouchTrailOverlayView(context: Context) : View(context) {
    private data class TrailSegment(
        val from: PointerPosition,
        val to: PointerPosition,
        val createdAt: Long,
    )

    private data class TrailTap(
        val position: PointerPosition,
        val createdAt: Long,
    )

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 8f
    }
    private val tapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val segments = ArrayDeque<TrailSegment>()
    private val taps = ArrayDeque<TrailTap>()
    private val fadeRunnable = object : Runnable {
        override fun run() {
            pruneExpired()
            invalidate()
            if (segments.isNotEmpty() || taps.isNotEmpty()) {
                postDelayed(this, FRAME_DELAY_MS)
            }
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun recordSegment(from: PointerPosition, to: PointerPosition) {
        val now = System.currentTimeMillis()
        segments += TrailSegment(from = from, to = to, createdAt = now)
        while (segments.size > MAX_SEGMENTS) {
            segments.removeFirst()
        }
        scheduleFade()
    }

    fun recordTap(position: PointerPosition) {
        val now = System.currentTimeMillis()
        taps += TrailTap(position = position, createdAt = now)
        while (taps.size > MAX_TAPS) {
            taps.removeFirst()
        }
        scheduleFade()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.currentTimeMillis()
        pruneExpired(now)

        segments.forEach { segment ->
            val alpha = alphaFor(now - segment.createdAt)
            paint.color = Color.argb(alpha, 50, 145, 255)
            canvas.drawLine(segment.from.x, segment.from.y, segment.to.x, segment.to.y, paint)
        }

        taps.forEach { tap ->
            val age = now - tap.createdAt
            val alpha = alphaFor(age)
            val radius = 18f + (age.coerceAtMost(TTL_MS).toFloat() / TTL_MS.toFloat()) * 26f
            tapPaint.color = Color.argb(alpha, 50, 145, 255)
            canvas.drawCircle(tap.position.x, tap.position.y, radius, tapPaint)
        }
    }

    private fun scheduleFade() {
        pruneExpired()
        invalidate()
        removeCallbacks(fadeRunnable)
        postDelayed(fadeRunnable, FRAME_DELAY_MS)
    }

    private fun pruneExpired(now: Long = System.currentTimeMillis()) {
        while (segments.firstOrNull()?.let { now - it.createdAt > TTL_MS } == true) {
            segments.removeFirst()
        }
        while (taps.firstOrNull()?.let { now - it.createdAt > TTL_MS } == true) {
            taps.removeFirst()
        }
    }

    private fun alphaFor(ageMs: Long): Int {
        val progress = (ageMs.toFloat() / TTL_MS.toFloat()).coerceIn(0f, 1f)
        return (220f * (1f - progress)).toInt().coerceIn(0, 220)
    }

    private companion object {
        const val TTL_MS = 650L
        const val FRAME_DELAY_MS = 16L
        const val MAX_SEGMENTS = 36
        const val MAX_TAPS = 8
    }
}

private val VideoToggleKind.notFoundMessage: String
    get() = when (this) {
        VideoToggleKind.LIKE -> "未找到抖音点赞按钮"
        VideoToggleKind.FAVORITE -> "未找到抖音收藏按钮"
    }

private val VideoToggleKind.clickFailedMessage: String
    get() = when (this) {
        VideoToggleKind.LIKE -> "抖音点赞按钮点击失败"
        VideoToggleKind.FAVORITE -> "抖音收藏按钮点击失败"
    }

private val VideoToggleKind.logName: String
    get() = when (this) {
        VideoToggleKind.LIKE -> "like"
        VideoToggleKind.FAVORITE -> "favorite"
    }

private fun String.matchesDouyinToggle(kind: VideoToggleKind): Boolean =
    when (kind) {
        VideoToggleKind.LIKE -> "已点赞" in this || "未点赞" in this ||
            ("喜欢" in this && "按钮" in this && "评论" !in this && "分享" !in this)

        VideoToggleKind.FAVORITE -> "收藏" in this && "按钮" in this
    }

private fun AccessibilityNodeInfo.visitNodes(visitor: (AccessibilityNodeInfo) -> Boolean) {
    var visited = 0

    fun visit(node: AccessibilityNodeInfo): Boolean {
        if (visited >= MAX_ACCESSIBILITY_SCAN_NODES) return true
        visited += 1
        if (visitor(node)) return true

        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                if (visit(child)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    visit(this)
}

internal fun shouldRefreshToggleCache(
    eventType: Int,
    nowMs: Long,
    lastRefreshAtMs: Long,
): Boolean {
    val isRelevantEvent = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
    return isRelevantEvent && nowMs - lastRefreshAtMs >= TOGGLE_CACHE_REFRESH_INTERVAL_MS
}

private fun AccessibilityNodeInfo.closestClickableNode(): AccessibilityNodeInfo? {
    var current: AccessibilityNodeInfo? = this
    var ownsCurrent = false
    while (current != null) {
        if (current.isClickable && current.isEnabled) {
            val result = AccessibilityNodeInfo.obtain(current)
            if (ownsCurrent) current.recycle()
            return result
        }
        val parent = current.parent
        if (ownsCurrent) current.recycle()
        current = parent
        ownsCurrent = parent != null
    }
    return null
}

private inline fun debugLog(message: () -> String) {
    if (BuildConfig.DEBUG) {
        Log.d("NeoRemoteReceiver", message())
    }
}

fun shouldAcknowledgeMobileCommand(
    command: RemoteCommand,
    actions: List<MobileInputAction>,
): Boolean =
    command is RemoteCommand.ClientHello ||
        command is RemoteCommand.Heartbeat ||
        (command is RemoteCommand.Drag && command.state == DragState.STARTED) ||
        actions.isNotEmpty()

internal fun mobileQueuePolicyFor(command: RemoteCommand): MobileActionQueuePolicy =
    when (command) {
        is RemoteCommand.SystemActionCommand -> MobileActionQueuePolicy.REPLACE_PENDING
        is RemoteCommand.VideoAction -> when (command.action) {
            VideoActionKind.BACK -> MobileActionQueuePolicy.REPLACE_PENDING
            else -> MobileActionQueuePolicy.APPEND
        }
        else -> MobileActionQueuePolicy.APPEND
    }
