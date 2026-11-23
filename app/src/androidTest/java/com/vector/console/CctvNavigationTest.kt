package com.vector.console

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class CctvNavigationTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Test
    fun ac004_brand_is_oversight() {
        rule.onNodeWithText("OVERSIGHT").assertIsDisplayed()
    }

    @Test
    fun ac005_all_eight_routes_are_visible_in_the_rail() {
        listOf("00", "01", "02", "03", "04", "05", "06", "07").forEach {
            org.junit.Assert.assertTrue(
                rule.onAllNodesWithText(it).fetchSemanticsNodes().isNotEmpty()
            )
        }
    }

    @Test
    fun ac003_breadcrumb_and_three_filter_levels_render() {
        rule.onAllNodesWithText("CCTV").onLast().performClick()
        rule.onNodeWithText("Indonesia").assertIsDisplayed()
        rule.onNodeWithText("REGION").assertIsDisplayed()
        rule.onNodeWithText("PROVINCE").assertIsDisplayed()
        rule.onNodeWithText("OPERATOR").assertIsDisplayed()
    }
}
