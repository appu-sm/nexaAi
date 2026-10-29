package com.nexaai.assistant.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

internal class OverlaySpeechBubble(private val context: Context) {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null
    private var textView: TextView? = null
    private var scroll: ScrollView? = null
    private var tail: View? = null
    private var content: String? = null
    private val expire = Runnable { dismiss() }
    private val bubbleColor = Color.rgb(66, 66, 66)
    private val params = WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.LEFT }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    fun render(speech: OverlaySpeech?, anchor: WindowManager.LayoutParams, opacity: Float) {
        val text = speech?.text().orEmpty()
        if (text.isBlank()) { clear(); return }
        val changed = content != text
        if (!changed && root == null) return
        if (changed) {
            content = text
            handler.removeCallbacks(expire)
            handler.postDelayed(expire, 12_000)
        }
        val layout = root ?: FrameLayout(context).also { frame ->
            val label = TextView(context).apply {
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { dismiss() }
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            val scroller = ScrollView(context).apply {
                isFillViewport = false
                background = GradientDrawable().apply { setColor(bubbleColor); cornerRadius = dp(8).toFloat() }
                addView(label)
            }
            val pointer = View(context).apply {
                background = GradientDrawable().apply { setColor(bubbleColor) }
                rotation = 45f
            }
            frame.addView(pointer)
            frame.addView(scroller)
            textView = label; scroll = scroller; tail = pointer
        }
        textView?.text = text
        if (changed) scroll?.scrollTo(0, 0)
        layout.alpha = opacity
        val metrics = context.resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val iconX = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
            screenWidth - anchor.x - anchor.width else anchor.x
        val leftSpace = (iconX - dp(12)).coerceAtLeast(0)
        val rightSpace = (screenWidth - iconX - anchor.width - dp(12)).coerceAtLeast(0)
        val onLeft = leftSpace >= rightSpace
        val width = minOf(dp(320), if (onLeft) leftSpace else rightSpace).coerceAtLeast(1)
        val pointerSize = minOf(dp(12), width)
        scroll?.layoutParams = FrameLayout.LayoutParams((width - pointerSize / 2).coerceAtLeast(1),
            FrameLayout.LayoutParams.WRAP_CONTENT, if (onLeft) Gravity.LEFT else Gravity.RIGHT)
        tail?.layoutParams = FrameLayout.LayoutParams(pointerSize, pointerSize,
            Gravity.TOP or if (onLeft) Gravity.RIGHT else Gravity.LEFT)
        val maxHeight = minOf(dp(220), (screenHeight - dp(48)).coerceAtLeast(1))
        layout.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        params.width = width
        params.height = layout.measuredHeight.coerceAtLeast(1)
        params.x = (if (onLeft) iconX - dp(4) - width else iconX + anchor.width + dp(4))
            .coerceIn(0, (screenWidth - width).coerceAtLeast(0))
        params.y = (anchor.y + (anchor.height - params.height) / 2)
            .coerceIn(0, (screenHeight - params.height - dp(24)).coerceAtLeast(0))
        tail?.let { pointer ->
            pointer.layoutParams = (pointer.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = (anchor.y + anchor.height / 2 - params.y - pointerSize / 2)
                    .coerceIn(0, (params.height - pointerSize).coerceAtLeast(0))
            }
        }
        if (root == null) { manager.addView(layout, params); root = layout }
        else manager.updateViewLayout(layout, params)
    }

    private fun dismiss() {
        handler.removeCallbacks(expire)
        val layout = root ?: return
        root = null; textView = null; scroll = null; tail = null
        try { manager.removeViewImmediate(layout) }
        catch (_: IllegalArgumentException) { }
        catch (_: SecurityException) { }
    }

    fun clear() { content = null; dismiss() }
}