package com.example.offlinedocumentcomposer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opens both tools without modifying the user's scan session or exported files. */
@RunWith(AndroidJUnit4::class)
class ToolNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun quickToolsOpenAndReturnWithoutCrashing() {
        compose.onNodeWithText("Image to PDF").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Gallery").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("PDF Resizer").performScrollTo().performClick()
        compose.onNodeWithText("Select PDF").assertIsDisplayed()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Quick Tools").assertExists()
    }
}
