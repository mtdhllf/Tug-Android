package com.github.mtdhllf.tug.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.mtdhllf.tug.MainActivity
import com.github.mtdhllf.tug.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 主导航与新建任务校验的 UI 测试。
 *
 * 每次操作后停留 [STAGE_DELAY_MS]，便于开发者肉眼观察界面变化。
 */
@RunWith(AndroidJUnit4::class)
class MainUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    /** 操作间停顿，便于人眼审视。 */
    private fun stage(delayMs: Long = STAGE_DELAY_MS) {
        composeRule.waitForIdle()
        Thread.sleep(delayMs)
    }

    @Test
    fun bottomNavigation_switchesBetweenMainScreens() {
        composeRule.onNodeWithText("Tug").assertIsDisplayed()
        stage()

        composeRule.onNodeWithText(activity.getString(R.string.nav_tasks)).performClick()
        stage()
        composeRule.onNodeWithText(activity.getString(R.string.tasks_title)).assertIsDisplayed()
        stage()

        composeRule.onNodeWithText(activity.getString(R.string.nav_settings)).performClick()
        stage()
        composeRule.onNodeWithText(activity.getString(R.string.settings_download_network))
            .assertIsDisplayed()
        stage()

        composeRule.onNodeWithText(activity.getString(R.string.nav_home)).performClick()
        stage()
        composeRule.onNodeWithText(activity.getString(R.string.quick_add)).assertIsDisplayed()
        stage()
    }

    @Test
    fun addTaskSheet_acceptsMagnetLink() {
        composeRule.onNodeWithText(activity.getString(R.string.fab_new)).performClick()
        stage()
        composeRule.onNodeWithText(activity.getString(R.string.add_title)).assertIsDisplayed()
        stage()

        composeRule.onNodeWithTag("add_url").performTextInput("magnet:?xt=urn:btih:abc")
        stage()
    }

    @Test
    fun addTaskSheet_rejectsInvalidScheme() {
        composeRule.onNodeWithText(activity.getString(R.string.fab_new)).performClick()
        stage()
        composeRule.onNodeWithTag("add_url").performTextInput("ftp://example.com/file.bin")
        stage()
        composeRule.onNodeWithTag("add_submit").performClick()
        stage()

        composeRule.onNodeWithText(activity.getString(R.string.error_unsupported_url))
            .assertIsDisplayed()
        stage()
    }

    private val activity: MainActivity
        get() = composeRule.activity

    private companion object {
        const val STAGE_DELAY_MS = 700L
    }
}
