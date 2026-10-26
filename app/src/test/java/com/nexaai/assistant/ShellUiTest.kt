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

    private fun navigate(destination: String) {
        compose.onNodeWithTag("nav_$destination").performClick()
        compose.waitForIdle()
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