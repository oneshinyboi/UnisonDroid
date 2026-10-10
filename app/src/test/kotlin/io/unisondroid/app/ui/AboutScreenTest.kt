package io.unisondroid.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import io.unisondroid.app.sync.UnisonInfo
import io.unisondroid.app.ui.theme.UnisonDroidTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AboutScreenTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `lists every bundled unison version`() {
        compose.setContent { UnisonDroidTheme { AboutScreen() } }

        compose.onNodeWithTag(ABOUT_VERSION_TAG).assertExists()
        UnisonInfo.BUNDLED.forEach { bundled ->
            compose.onNodeWithText(bundled.version, substring = true).assertExists()
            compose.onNodeWithText(bundled.fileName, substring = true).assertExists()
        }
    }
}
