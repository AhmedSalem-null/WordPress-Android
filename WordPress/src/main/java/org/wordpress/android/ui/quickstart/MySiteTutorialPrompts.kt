package org.wordpress.android.ui.quickstart

import org.wordpress.android.R
import org.wordpress.android.fluxc.store.QuickStartStore.QuickStartTask

enum class MySiteTutorialPrompts constructor(
    val task: QuickStartTask,
    val mySiteRowId: Int,
    val shortMessagePrompt: Int,
    val iconId: Int
) {
    VIEW_SITE_TUTORIAL(
            QuickStartTask.VIEW_SITE,
            R.id.row_view_site,
            R.string.quick_start_dialog_view_site_message_short,
            R.drawable.ic_globe_grey_24dp),
    CHOSE_THEME_TUTORIAL(
            QuickStartTask.CHOOSE_THEME,
            R.id.row_themes,
            R.string.quick_start_dialog_choose_theme_message_short,
            R.drawable.ic_themes_grey_24dp),
    CUSTOMIZE_SITE_TUTORIAL(
            QuickStartTask.CUSTOMIZE_SITE,
            R.id.row_themes,
            R.string.quick_start_dialog_customize_site_message_short_themes,
            R.drawable.ic_themes_grey_24dp),
    SHARE_SITE_TUTORIAL(
            QuickStartTask.SHARE_SITE,
            R.id.row_sharing,
            R.string.quick_start_dialog_share_site_message_short_sharing,
            R.drawable.ic_share_24dp),
    PUBLISH_POST_TUTORIAL(
            QuickStartTask.PUBLISH_POST,
            -1,
            R.string.quick_start_dialog_publish_post_message_short,
            R.drawable.ic_create_white_24dp),
    FOLLOW_SITES_TUTORIAL(
            QuickStartTask.FOLLOW_SITE,
            -1,
            R.string.quick_start_dialog_follow_sites_message_short_reader,
            R.drawable.ic_reader_white_32dp);

    companion object {
        @JvmStatic
        fun getTutorialForTask(task: QuickStartTask): MySiteTutorialPrompts? {
            MySiteTutorialPrompts.values().forEach {
                if (it.task == task) return it
            }
            return null
        }

        const val KEY = "my_site_tutorial_prompts"
    }
}
