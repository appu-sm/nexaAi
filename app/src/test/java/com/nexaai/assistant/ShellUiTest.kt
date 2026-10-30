package com.nexaai.assistant

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w1024dp-h768dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @Config(sdk = [28], qualifiers = "w360dp-h640dp-port")
    fun moonshineSelectionPersistsAndModelsShowsRequiredAssets() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("microphone").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        navigate("SETTINGS")
        compose.onNodeWithTag("asr_engine").performScrollTo().performClick()
        compose.onNodeWithText("Moonshine Base English").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Speech engine saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("grammar_enabled").performScrollTo().assertIsNotEnabled()
        navigate("HOME")
        navigate("SETTINGS")
        compose.onNodeWithTag("asr_engine").performScrollTo().assertTextContains("Moonshine Base English", substring = true)
        navigate("MODELS")
        compose.onNodeWithText("No installed speech model").assertExists()
        compose.onNodeWithText("Moonshine Base English: quantized", substring = true).assertExists()
        compose.onNodeWithTag("hands_free_enabled").assertIsOff()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w360dp-h640dp-port")
    fun commandMicrophoneSourceAndGainAreSavedInCompactSettings() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("microphone").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        navigate("SETTINGS")
        compose.onNodeWithTag("command_audio_source").performScrollTo().performClick()
        compose.onNodeWithText("Standard microphone").performClick()
        compose.onNodeWithTag("command_input_gain").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { action -> action(200f) }
        compose.onNodeWithTag("save_command_audio").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Command microphone saved").fetchSemanticsNodes().isNotEmpty() }
        navigate("HOME")
        navigate("SETTINGS")
        compose.onNodeWithTag("command_audio_source").performScrollTo().assertTextContains("Standard microphone", substring = true)
        compose.onNodeWithText("Input gain: 200%").assertExists()
        compose.onNodeWithTag("hands_free_enabled").assertIsOff()
    }

    private fun navigate(destination: String) {
        compose.onNodeWithTag("nav_$destination").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w360dp-h640dp-port")
    fun compactDiagnosticsCanEnableInspectAndResetWithoutStartingListening() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("microphone").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        navigate("DIAGNOSTICS")
        compose.onNodeWithTag("voice_diagnostics").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("voice_diagnostics").assertIsOn()
        compose.onNodeWithTag("diagnostics_snapshot").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("diagnostics_report").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diagnostics_report").assertTextContains("application-observed", substring = true)
        compose.onNodeWithTag("diagnostics_reset").performScrollTo().performClick()
        compose.onNodeWithTag("diagnostics_report").assertDoesNotExist()
        compose.onNodeWithTag("hands_free_enabled").assertIsOff()
        compose.onNodeWithTag("voice_diagnostics").performScrollTo().performClick()
        navigate("HOME")
        compose.onNodeWithTag("microphone").assertIsEnabled()
    }

    @Test fun sixScreensAndUnavailableMicrophone() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("microphone").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("microphone").assertIsEnabled()
        navigate("COMMANDS")
        compose.onNodeWithTag("command_search").assertExists()
        navigate("SETTINGS")
        compose.onNodeWithTag("display_name").assertExists()
        navigate("MODELS")
        compose.onNodeWithText("No installed speech model").assertExists()
        navigate("DIAGNOSTICS")
        compose.onNodeWithText("This device").assertExists()
        navigate("HELP")
        compose.onNodeWithText("Help and privacy").assertExists()
    }

    @Test fun savesNameAndRejectsCollidingAlias() {
        navigate("SETTINGS")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("save_name").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("display_name").performTextReplacement("Cabin")
        compose.onNodeWithTag("save_name").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Name saved").fetchSemanticsNodes().isNotEmpty() }
        navigate("HOME")
        compose.onNodeWithText("Cabin").assertExists()
        navigate("COMMANDS")
        compose.onNodeWithTag("command_search").performTextReplacement("pause")
        compose.onNodeWithTag("edit_PAUSE").performScrollTo().performClick()
        compose.onNodeWithTag("alias_0").performTextReplacement("volume up")
        compose.onNodeWithTag("save_aliases").performClick()
        compose.onNodeWithTag("alias_error").assertTextContains("Alias collision", substring = true)
    }

    @Test fun overlayPermissionIsExplicitAndIndependentOfStartupAndMicrophone() {
        navigate("SETTINGS")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("overlay_enabled").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("overlay_enabled").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Overlay preference saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("overlay_status").performScrollTo().assertTextContains("permission: missing", substring = true)
        assertNoSettingsLaunched()
        compose.onNodeWithTag("auto_start").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Startup preference saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("hands_free_enabled").assertIsOff()
        navigate("HOME")
        compose.onNodeWithTag("assistant_state").assertTextContains("Idle", substring = true)
        compose.onNodeWithTag("microphone").assertIsEnabled()
        navigate("SETTINGS")
        compose.onNodeWithTag("overlay_permission").performScrollTo().performClick()
        compose.runOnIdle {
            val intent = org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity
            org.junit.Assert.assertEquals(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, intent.action)
            org.junit.Assert.assertEquals("package:com.nexaai.assistant", intent.data.toString())
        }
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitForIdle()
        assertNoSettingsLaunched()
        compose.onNodeWithTag("overlay_status").performScrollTo().assertTextContains("permission: missing", substring = true)
        compose.onNodeWithTag("overlay_enabled").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("overlay_enabled").fetchSemanticsNodes().all { node ->
            node.config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.Off
        } }
    }

    private fun assertNoSettingsLaunched() {
        compose.runOnIdle { org.junit.Assert.assertNull(org.robolectric.Shadows.shadowOf(compose.activity).nextStartedActivity) }
    }

    @Test fun savesCustomWakePhraseAndRejectsEmptyPhrase() {
        navigate("SETTINGS")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("save_wake_phrase").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("wake_phrase").performScrollTo().performTextReplacement("")
        compose.onNodeWithTag("save_wake_phrase").assertIsNotEnabled()
        compose.onNodeWithTag("wake_phrase").performTextReplacement("Hey Radio")
        compose.onNodeWithTag("save_wake_phrase").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Wake phrase saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Hands-free: Hey Radio").assertExists()
        navigate("HOME")
        navigate("SETTINGS")
        compose.onNodeWithTag("wake_phrase").assertTextContains("Hey Radio")
    }

    @Test fun grammarModeAndCustomWordsCanBeSavedAndDisabledIndependently() {
        navigate("SETTINGS")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("grammar_enabled").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("grammar_enabled").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Grammar mode saved").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("grammar_enabled").assertIsOn()
        compose.onNodeWithTag("grammar_words").performScrollTo().performTextReplacement("[unk]")
        compose.onNodeWithTag("save_grammar_words").assertIsNotEnabled()
        compose.onNodeWithTag("grammar_words").performTextReplacement("Navigation\ncabin music\nnavigation")
        compose.onNodeWithTag("save_grammar_words").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Custom grammar words saved").fetchSemanticsNodes().isNotEmpty() }
        navigate("HOME")
        navigate("SETTINGS")
        compose.onNodeWithTag("grammar_words").assertTextContains("navigation\ncabin music")
        compose.onNodeWithTag("grammar_enabled").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("grammar_enabled").fetchSemanticsNodes().all { node ->
                node.config[androidx.compose.ui.semantics.SemanticsProperties.ToggleableState] == androidx.compose.ui.state.ToggleableState.Off
            }
        }
        compose.onNodeWithTag("grammar_words").assertTextContains("navigation\ncabin music")
    }

    @Test fun developmentCommandsPreviewCancelAndValidateRadioFrequencies() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("command_input").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("command_input").performScrollTo().performTextReplacement("help")
        compose.onNodeWithTag("interpret_command").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("execute_command").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("cancel_command").performScrollTo().performClick()
        compose.onNodeWithTag("execute_command").assertIsNotEnabled()
        compose.onNodeWithTag("command_result").assertTextContains("Cancelled", substring = true)
        compose.onNodeWithTag("interpret_command").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("execute_command").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("execute_command").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("command_result").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("command_result").assertTextContains("Success:", substring = true)
        compose.onNodeWithTag("execute_command").assertIsNotEnabled()
        compose.onNodeWithTag("command_input").performScrollTo().performTextReplacement("tune fm to 98.1")
        compose.onNodeWithTag("interpret_command").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("execute_command").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("execute_command").assertIsEnabled()
        compose.onNodeWithTag("cancel_command").performScrollTo().performClick()
        compose.onNodeWithTag("command_input").performScrollTo().performTextReplacement("tune fm to 120")
        compose.onNodeWithTag("interpret_command").performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("command_result").fetchSemanticsNodes().any { node ->
                node.config[androidx.compose.ui.semantics.SemanticsProperties.Text].any { it.text.startsWith("InvalidParameters:") }
            }
        }
        compose.onNodeWithTag("execute_command").assertIsNotEnabled()
        compose.onNodeWithTag("microphone").assertIsEnabled()
    }

    @Test
    @Config(sdk = [28], qualifiers = "w360dp-h640dp-port")
    fun compactNavigationDisableAndResetConfirmation() {
        navigate("SETTINGS")
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("assistant_enabled").fetchSemanticsNodes().any { node ->
                !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
            }
        }
        compose.onNodeWithTag("assistant_enabled").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Assistant preference saved").fetchSemanticsNodes().isNotEmpty() }
        navigate("HOME")
        compose.onNodeWithTag("assistant_state").assertTextEquals("Assistant disabled")
        navigate("SETTINGS")
        compose.onNodeWithText("Reset settings").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        navigate("HOME")
        compose.onNodeWithTag("assistant_state").assertTextEquals("Assistant disabled")
        navigate("SETTINGS")
        compose.onNodeWithText("Reset settings").performScrollTo().performClick()
        compose.onNodeWithText("Reset", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Settings reset").fetchSemanticsNodes().isNotEmpty() }
        navigate("HOME")
        compose.onNodeWithTag("assistant_state").assertTextEquals("Idle - offline model not installed")
    }
}