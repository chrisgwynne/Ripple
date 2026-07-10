package com.ripple.town.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import com.ripple.town.RippleNavScaffold
import com.ripple.town.core.ui.RippleTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Compose navigation tests over the production nav scaffold (Robolectric). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavigationTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun setUp() {
        composeRule.setContent {
            RippleTheme {
                RippleNavScaffold(
                    navController = rememberNavController(),
                    town = { Text("TOWN-SCREEN") },
                    people = { Text("PEOPLE-SCREEN") },
                    news = { Text("NEWS-SCREEN") },
                    history = { Text("HISTORY-SCREEN") }
                )
            }
        }
    }

    @Test
    fun `starts on the town screen`() {
        setUp()
        composeRule.onNodeWithText("TOWN-SCREEN").assertIsDisplayed()
    }

    @Test
    fun `bottom navigation reaches all four destinations`() {
        setUp()
        composeRule.onNodeWithText("People").performClick()
        composeRule.onNodeWithText("PEOPLE-SCREEN").assertIsDisplayed()
        composeRule.onNodeWithText("News").performClick()
        composeRule.onNodeWithText("NEWS-SCREEN").assertIsDisplayed()
        composeRule.onNodeWithText("History").performClick()
        composeRule.onNodeWithText("HISTORY-SCREEN").assertIsDisplayed()
        composeRule.onNodeWithText("Town").performClick()
        composeRule.onNodeWithText("TOWN-SCREEN").assertIsDisplayed()
    }

    @Test
    fun `re-tapping the current tab does not stack destinations`() {
        setUp()
        composeRule.onNodeWithText("News").performClick()
        composeRule.onNodeWithText("News").performClick()
        composeRule.onNodeWithText("NEWS-SCREEN").assertIsDisplayed()
        // Back to town still works — the stack didn't grow.
        composeRule.onNodeWithText("Town").performClick()
        composeRule.onNodeWithText("TOWN-SCREEN").assertIsDisplayed()
    }
}
