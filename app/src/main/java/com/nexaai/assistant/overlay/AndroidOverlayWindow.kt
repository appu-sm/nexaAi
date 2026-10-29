package com.nexaai.assistant.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import androidx.core.content.ContextCompat
import com.nexaai.assistant.R
import com.nexaai.assistant.data.AssistantSettings
import kotlin.math.abs
import kotlin.math.roundToInt

private class OverlayIconButton(context: Context) : ImageButton(context) {
    override fun performClick(): Boolean = super.performClick()
}

internal class OverlayArtwork(private val context: Context) {
    private val listening = mutableMapOf<Int, Drawable>()

    fun drawable(resource: Int, microphoneActive: Boolean): Drawable {
        if (!microphoneActive) return requireNotNull(ContextCompat.getDrawable(context, resource))
        return listening.getOrPut(resource) {
            val source = BitmapFactory.decodeResource(context.resources, resource)
            val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
            val hsv = FloatArray(3)
            for (vertical in bitmap.height * 65 / 100 until bitmap.height * 77 / 100) {
                for (horizontal in bitmap.width * 44 / 100 until bitmap.width * 57 / 100) {
                    val circleX = (horizontal.toFloat() / bitmap.width - 0.5f) / 0.055f
                    val circleY = (vertical.toFloat() / bitmap.height - 0.705f) / 0.055f
                    if (circleX * circleX + circleY * circleY > 1f) continue
                    val pixel = bitmap.getPixel(horizontal, vertical)
                    Color.colorToHSV(pixel, hsv)
                    if (Color.alpha(pixel) > 0 && hsv[1] > 0.05f) {
                        hsv[0] = 125f
                        hsv[1] = 0.9f
                        hsv[2] = hsv[2].coerceAtMost(0.78f)
                        bitmap.setPixel(horizontal, vertical, Color.HSVToColor(Color.alpha(pixel), hsv))
                    }
                }
            }
            BitmapDrawable(context.resources, bitmap)
        }
    }
}

class AndroidOverlayWindow(
    private val context: Context,
    private val actions: OverlayActions,
    private val savePosition: (Int, Int) -> Unit,
    private val onFailure: () -> Unit,
) : OverlayWindow {
    private val manager = context.getSystemService(WindowManager::class.java)
    private val artwork = OverlayArtwork(context)
    private val bubble = OverlaySpeechBubble(context)
    private var root: OverlayIconButton? = null
    private var settings = AssistantSettings()
    private var presentation = OverlayPresentation("Hands-free off")
    private var dragging = false
    private val params = WindowManager.LayoutParams(0, 0, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    override fun add(settings: AssistantSettings, presentation: OverlayPresentation) {
        check(root == null)
        val icon = OverlayIconButton(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            minimumWidth = 0
            minimumHeight = 0
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                val current = this@AndroidOverlayWindow.presentation
                if (current.canStop && !current.wakeActive) actions.stop() else actions.listen()
            }
            setOnLongClickListener { actions.open(); true }
        }
        this.settings = settings; this.presentation = presentation
        render(icon)
        installDrag(icon)
        try {
            manager.addView(icon, params)
            root = icon
            bubble.render(presentation.speech, params, icon.alpha)
        } catch (failure: RuntimeException) {
            remove()
            throw failure
        }
    }

    override fun update(settings: AssistantSettings, presentation: OverlayPresentation) {
        this.settings = settings; this.presentation = presentation
        root?.let {
            render(it)
            manager.updateViewLayout(it, params)
            bubble.render(presentation.speech, params, settings.overlayOpacityPercent / 100f)
        }
    }

    private fun bounds(): Pair<Int, Int> {
        val metrics = context.resources.displayMetrics
        return (metrics.widthPixels - params.width - dp(16)).coerceAtLeast(0) to
            (metrics.heightPixels - params.height - dp(48)).coerceAtLeast(0)
    }

    private fun render(icon: OverlayIconButton) {
        val size = dp(settings.overlaySizeDp)
        params.width = size
        params.height = size
        icon.alpha = settings.overlayOpacityPercent / 100f
        icon.contentDescription = "NexaAI: ${presentation.text}. ${if (presentation.canStop && !presentation.wakeActive) "Stop listening" else "Listen for command"}"
        icon.tooltipText = icon.contentDescription
        icon.setImageDrawable(StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), artwork.drawable(
                if (presentation.wakeActive) R.drawable.wakeup_press else R.drawable.offline_press, presentation.microphoneActive))
            addState(intArrayOf(), artwork.drawable(
                if (presentation.wakeActive) R.drawable.wakeup_normal else R.drawable.offline_normal, presentation.microphoneActive))
        })
        val (maxX, maxY) = bounds()
        if (!dragging) {
            params.x = maxX * settings.overlayXPercent / 100
            params.y = maxY * settings.overlayYPercent / 100
        }
        params.x = params.x.coerceIn(0, maxX)
        params.y = params.y.coerceIn(0, maxY)
    }

    private fun installDrag(icon: OverlayIconButton) {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        icon.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY; startX = params.x; startY = params.y
                    dragging = false
                    icon.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = if (context.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                        downX - event.rawX else event.rawX - downX
                    val deltaY = event.rawY - downY
                    if (!dragging && (abs(deltaX) > slop || abs(deltaY) > slop)) {
                        dragging = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        try { icon.onTouchEvent(cancel) } finally { cancel.recycle() }
                    }
                    if (dragging) {
                        val (maxX, maxY) = bounds()
                        params.x = (startX + deltaX.roundToInt()).coerceIn(0, maxX)
                        params.y = (startY + deltaY.roundToInt()).coerceIn(0, maxY)
                        try {
                            root?.let {
                                manager.updateViewLayout(it, params)
                                bubble.render(presentation.speech, params, settings.overlayOpacityPercent / 100f)
                            }
                        }
                        catch (_: RuntimeException) { onFailure() }
                    } else icon.onTouchEvent(event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val moved = dragging
                    dragging = false
                    if (!moved) icon.onTouchEvent(event)
                    if (moved && event.actionMasked == MotionEvent.ACTION_UP) {
                        val (maxX, maxY) = bounds()
                        val percentX = if (maxX == 0) 0 else params.x * 100 / maxX
                        val percentY = if (maxY == 0) 0 else params.y * 100 / maxY
                        settings = settings.copy(overlayXPercent = percentX, overlayYPercent = percentY)
                        savePosition(percentX, percentY)
                    }
                }
                else -> return@setOnTouchListener false
            }
            true
        }
    }

    override fun remove() {
        bubble.clear()
        val view = root ?: return
        root = null
        try { manager.removeViewImmediate(view) }
        catch (_: IllegalArgumentException) { }
        catch (_: SecurityException) { }
    }
}