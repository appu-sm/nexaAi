package com.nexaai.assistant

import android.content.Context
import android.widget.ImageButton
import android.view.MotionEvent
import android.view.WindowManager
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import com.nexaai.assistant.data.*
import com.nexaai.assistant.overlay.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class OverlayRuntimeTest {
    @Test fun deniedPermissionNeverStartsServiceOrOpensSettingsAndRevocationStopsOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("overlay", ".preferences_pb").apply { delete() }
        val repository = ConfigurationRepository(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        var permission = false
        var starts = 0
        val control = OverlayControl(context, repository, backgroundScope, { permission }, { starts++ })
        repeat(2) { control.requestStart() }
        assertEquals(0, starts)
        assertFalse(control.state.value.running)
        assertTrue(control.state.value.message.contains("permission"))
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
        permission = true
        repeat(2) { control.requestStart() }
        assertEquals(1, starts)
        control.started(true)
        assertTrue(control.state.value.visible)
        control.requestStart()
        assertEquals(1, starts)
        permission = false
        control.refreshPermission()
        assertFalse(control.state.value.running)
        assertFalse(control.state.value.visible)
        assertFalse(control.state.value.permission)
        advanceTimeBy(60_000)
        assertEquals(1, starts)
        file.delete()
    }

    @Test fun startFailureAndWatchdogNeverLoopAndManualRetryIsPossible() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("overlay-failure", ".preferences_pb").apply { delete() }
        val repository = ConfigurationRepository(PreferenceDataStoreFactory.create(scope = backgroundScope, produceFile = { file }))
        var starts = 0
        var throwFailure = true
        val control = OverlayControl(context, repository, backgroundScope, { true }, {
            starts++
            if (throwFailure) throw SecurityException("Denied")
        })
        control.requestStart()
        advanceTimeBy(60_000)
        assertEquals(1, starts)
        assertFalse(control.state.value.running)
        assertTrue(control.state.value.message.contains("denied"))
        throwFailure = false
        control.requestStart()
        runCurrent()
        advanceTimeBy(10_001)
        runCurrent()
        assertTrue(control.state.value.message.contains("did not start"))
        assertFalse(control.state.value.running)
        advanceTimeBy(60_000)
        assertEquals(2, starts)
        file.delete()
    }

    @Test fun bootRestoreCallsOnlyOverlayInterfaceOnceWhenSupported() {
        var requests = 0
        val configured = ConfigurationSnapshot(Configuration(settings = AssistantSettings(
            overlayEnabled = true, autoStartAfterBoot = true)), ready = true)
        val request = { requests++; Unit }
        assertFalse(StartupPolicy.restore(ConfigurationSnapshot(ready = true), true, true, request))
        assertFalse(StartupPolicy.restore(configured, false, true, request))
        assertFalse(StartupPolicy.restore(configured, true, false, request))
        assertEquals(0, requests)
        assertTrue(StartupPolicy.restore(configured, true, true, request))
        assertEquals(1, requests)
        assertFalse(configured.configuration.settings.wakeWordEnabled)
    }

    @Test fun realWindowUsesArtworkAndInterfaceCallbacksAndClosesIdempotently() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var opens = 0
        var stops = 0
        var commands = 0
        val window = AndroidOverlayWindow(context, OverlayActions({ opens++ }, { stops++ }, { commands++ }), { _, _ -> }, { fail("Window failed") })
        val controller = OverlayController(window)
        controller.reconcile(AssistantSettings(overlayEnabled = true), true, OverlayPresentation("Hands-free off"))
        val manager = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(
            context.getSystemService(WindowManager::class.java))
        val view = manager.views.single() as ImageButton
        val params = view.layoutParams as WindowManager.LayoutParams
        assertEquals((64 * context.resources.displayMetrics.density).toInt(), params.width)
        assertEquals(params.width, params.height)
        assertEquals(0, view.paddingLeft)
        assertTrue(view.contentDescription.toString().contains("Hands-free off"))
        view.performClick()
        assertEquals(0, opens)
        assertEquals(1, commands)
        assertEquals(0, stops)
        controller.reconcile(AssistantSettings(overlayEnabled = true), true, OverlayPresentation("Wake active", true, true))
        assertEquals(1, manager.views.size)
        assertTrue(view.contentDescription.toString().contains("Wake active"))
        view.performClick()
        assertEquals(2, commands)
        assertEquals(0, stops)
        controller.reconcile(AssistantSettings(overlayEnabled = true), true, OverlayPresentation("Listening", canStop = true, microphoneActive = true))
        view.performClick()
        assertEquals(1, stops)
        view.performLongClick()
        assertEquals(1, opens)
        controller.close()
        controller.close()
        assertTrue(manager.views.isEmpty())
    }

    @Test fun draggingOnlyMovesTheIconAndPersistsClampedPosition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var saved: Pair<Int, Int>? = null
        val window = AndroidOverlayWindow(context,
            OverlayActions({ fail("Drag must not open the app") }, { fail("Drag must not stop listening") }, { fail("Drag must not listen") }),
            { x, y -> saved = x to y }, { fail("Window failed") })
        window.add(AssistantSettings(overlayXPercent = 0, overlayYPercent = 0), OverlayPresentation("Listening", canStop = true))
        val manager = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(
            context.getSystemService(WindowManager::class.java))
        val icon = manager.views.single() as ImageButton
        fun touch(action: Int, coordinate: Float, time: Long) {
            val event = MotionEvent.obtain(0, time, action, coordinate, coordinate, 0)
            try { icon.dispatchTouchEvent(event) } finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 10f, 0)
        touch(MotionEvent.ACTION_MOVE, 10_000f, 20)
        assertNull(saved)
        touch(MotionEvent.ACTION_UP, 10_000f, 40)
        assertNotNull(saved)
        assertTrue(saved!!.first in 0..100 && saved!!.second in 0..100)
        assertFalse(icon.isPressed)
        window.remove()
        window.remove()
        assertTrue(manager.views.isEmpty())
    }

    @Test
    @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
    fun listeningArtworkChangesOnlyTheBlueBodyIndicatorToGreen() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val artwork = OverlayArtwork(context)
        for (resource in listOf(R.drawable.offline_normal, R.drawable.offline_press, R.drawable.wakeup_normal, R.drawable.wakeup_press)) {
            val original = android.graphics.BitmapFactory.decodeResource(context.resources, resource)
            val green = (artwork.drawable(resource, true) as android.graphics.drawable.BitmapDrawable).bitmap
            var changed = 0
            val hsv = FloatArray(3)
            for (vertical in 0 until original.height) for (horizontal in 0 until original.width) {
                val before = original.getPixel(horizontal, vertical)
                val after = green.getPixel(horizontal, vertical)
                if (before != after) {
                    changed++
                    val distanceX = horizontal - original.width * 0.5
                    val distanceY = vertical - original.height * 0.705
                    val radius = original.width * 0.055
                    assertTrue("Only chest-circle pixels may change at ($horizontal, $vertical)",
                        distanceX * distanceX + distanceY * distanceY <= radius * radius + 0.001)
                    android.graphics.Color.colorToHSV(after, hsv)
                    if (android.graphics.Color.alpha(after) >= 128) {
                        assertEquals(125f, hsv[0], 3f)
                        assertTrue(hsv[1] >= 0.85f)
                        assertTrue(hsv[2] <= 0.8f)
                    }
                    assertEquals(android.graphics.Color.alpha(before), android.graphics.Color.alpha(after))
                }
            }
            assertTrue("Body indicator must change for resource $resource", changed > 0)
            assertNotEquals("The center of the chest light must turn green",
                original.getPixel(original.width / 2, original.height * 70 / 100),
                green.getPixel(original.width / 2, original.height * 70 / 100))
            assertSame(artwork.drawable(resource, true), artwork.drawable(resource, true))
        }
    }

    @Test fun speechBubbleShowsTranscriptAndOutputWithoutChangingIconAndExpiresOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val window = AndroidOverlayWindow(context, OverlayActions({}, {}, {}), { _, _ -> }, { fail("Window failed") })
        val settings = AssistantSettings(overlayEnabled = true, overlayXPercent = 100)
        window.add(settings, OverlayPresentation("Listening", speech = OverlaySpeech(transcript = "volume up")))
        val manager = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(
            context.getSystemService(WindowManager::class.java))
        assertEquals(2, manager.views.size)
        val icon = manager.views.filterIsInstance<ImageButton>().single()
        val iconParams = icon.layoutParams as WindowManager.LayoutParams
        val iconX = iconParams.x
        val iconWidth = iconParams.width
        val response = OverlayPresentation("Hands-free off", speech = OverlaySpeech("volume up", "Volume increased"))
        window.update(settings, response)
        assertEquals(2, manager.views.size)
        val frame = manager.views.filterIsInstance<android.widget.FrameLayout>().single()
        val scroll = frame.getChildAt(1) as android.widget.ScrollView
        val grey = android.graphics.Color.rgb(66, 66, 66)
        assertEquals(grey, (scroll.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertEquals(grey, (frame.getChildAt(0).background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
        assertEquals("You: volume up\nNexaAI: Volume increased", (scroll.getChildAt(0) as android.widget.TextView).text.toString())
        val bubbleParams = frame.layoutParams as WindowManager.LayoutParams
        assertTrue(bubbleParams.x >= 0 && bubbleParams.y >= 0)
        assertTrue(bubbleParams.x + bubbleParams.width <= iconX)
        assertEquals(iconX, iconParams.x)
        assertEquals(iconWidth, iconParams.width)
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(13))
        assertEquals(1, manager.views.size)
        window.update(settings, response)
        assertEquals(1, manager.views.size)
        window.remove()
        assertTrue(manager.views.isEmpty())
    }

    @Test
    @Config(sdk = [28], qualifiers = "w360dp-h640dp-port")
    fun bubbleUsesRightSideAtLeftEdgeAndClearsOnDisable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val window = AndroidOverlayWindow(context, OverlayActions({}, {}, {}), { _, _ -> }, { fail("Window failed") })
        val settings = AssistantSettings(overlayEnabled = true, overlayXPercent = 0, overlayYPercent = 100)
        window.add(settings, OverlayPresentation("Listening", speech = OverlaySpeech("hello", "A long response ".repeat(100))))
        val manager = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(
            context.getSystemService(WindowManager::class.java))
        val icon = manager.views.filterIsInstance<ImageButton>().single()
        val iconParams = icon.layoutParams as WindowManager.LayoutParams
        val frame = manager.views.filterIsInstance<android.widget.FrameLayout>().single()
        val bubbleParams = frame.layoutParams as WindowManager.LayoutParams
        assertTrue(bubbleParams.x >= iconParams.x + iconParams.width)
        assertTrue(bubbleParams.x + bubbleParams.width <= context.resources.displayMetrics.widthPixels)
        assertTrue(bubbleParams.y + bubbleParams.height <= context.resources.displayMetrics.heightPixels)
        assertTrue(bubbleParams.height >= 40 * context.resources.displayMetrics.density)
        window.remove()
        shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(13))
        assertTrue(manager.views.isEmpty())
    }
}