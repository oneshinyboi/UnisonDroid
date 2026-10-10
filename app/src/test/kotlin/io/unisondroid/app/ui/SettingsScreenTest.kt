package io.unisondroid.app.ui

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `mobile data off renders the switch off and toggling reports the new value`() {
        var toggled: Boolean? = null

        compose.setContent {
            UnisonDroidTheme {
                SettingsContent(syncOnMobileData = false, onToggle = { toggled = it })
            }
        }

        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).assertIsOff()
        compose.onNodeWithTag(SETTINGS_TOGGLE_TAG).performClick()

        assertEquals(true, toggled)
    }
}
