package org.wordpress.android.ui.main

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import android.support.v7.widget.AppCompatButton
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.TextView
import org.wordpress.android.R
import org.wordpress.android.WordPress
import org.wordpress.android.fluxc.store.QuickStartStore
import org.wordpress.android.fluxc.store.QuickStartStore.QuickStartTask
import org.wordpress.android.ui.posts.BasicFragmentDialog
import org.wordpress.android.ui.prefs.AppPrefs.getSelectedSite
import org.wordpress.android.util.LocaleManager
import javax.inject.Inject

class QuickStartActivity : AppCompatActivity(), BasicFragmentDialog.BasicDialogPositiveClickInterface {
    @Inject lateinit var quickStartStore: QuickStartStore

    private val site: Int = getSelectedSite()
    private val skipAllTasksDialogTag = "skip_all_tasks_dialog"

    companion object {
        const val ARG_QUICK_START_TASK = "quick_start_task"
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleManager.setLocale(newBase))
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as WordPress).component()?.inject(this)
        setContentView(R.layout.quick_start_activity)

        setTasksClickListeners()
        checkCompletedTasks()
    }

    // TODO to be used in future branches
    override fun onBackPressed() {
        setResult(Activity.RESULT_OK)
        finish()
    }

    // TODO to be used in future branches
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            setResult(Activity.RESULT_OK)
            finish()
        }

        return super.onOptionsItemSelected(item)
    }

    // TODO click listeners will lead to actual tutorials
    private fun setTasksClickListeners() {
        findViewById<RelativeLayout>(R.id.layout_view_site).setOnClickListener {
            startTask(QuickStartTask.VIEW_SITE)
        }

        findViewById<RelativeLayout>(R.id.layout_browse_themes).setOnClickListener {
            startTask(QuickStartTask.CHOOSE_THEME)
        }

        findViewById<RelativeLayout>(R.id.layout_customize_site).setOnClickListener {
            quickStartStore.setDoneTask(site.toLong(), QuickStartTask.CUSTOMIZE_SITE, true)
            checkCompletedTasks()
        }

        findViewById<RelativeLayout>(R.id.layout_share_site).setOnClickListener {
            quickStartStore.setDoneTask(site.toLong(), QuickStartTask.SHARE_SITE, true)
            checkCompletedTasks()
        }

        findViewById<RelativeLayout>(R.id.layout_publish_post).setOnClickListener {
            quickStartStore.setDoneTask(site.toLong(), QuickStartTask.PUBLISH_POST, true)
            checkCompletedTasks()
        }

        findViewById<RelativeLayout>(R.id.layout_follow_site).setOnClickListener {
            quickStartStore.setDoneTask(site.toLong(), QuickStartTask.FOLLOW_SITE, true)
            checkCompletedTasks()
        }

        findViewById<AppCompatButton>(R.id.button_skip_all).setOnClickListener {
            showSkipDialog()
        }
    }

    private fun startTask(task: QuickStartTask) {
        val intent = Intent()
        intent.putExtra(ARG_QUICK_START_TASK, task)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }

    private fun showSkipDialog() {
        val basicFragmentDialog = BasicFragmentDialog()
        basicFragmentDialog.initialize(skipAllTasksDialogTag,
                getString(R.string.quick_start_dialog_skip_title),
                getString(R.string.quick_start_dialog_skip_message),
                getString(R.string.quick_start_button_skip_positive),
                null, getString(R.string.quick_start_button_skip_negative))

        basicFragmentDialog.show(supportFragmentManager, skipAllTasksDialogTag)
    }

    override fun onPositiveClicked(instanceTag: String) {
        findViewById<ScrollView>(R.id.checklist_scrollview).smoothScrollTo(0, 0)
        skipAllTasks()
    }

    private fun skipAllTasks() {
        QuickStartTask.values().forEach { quickStartStore.setDoneTask(site.toLong(), it, true) }
        checkCompletedTasks()
    }

    private fun checkCompletedTasks() {
        crossOutCompletedTasks()

        if (areAllTasksCompleted()) {
            findViewById<LinearLayout>(R.id.layout_list_complete).visibility = View.VISIBLE
            findViewById<RelativeLayout>(R.id.layout_skip_all).visibility = View.GONE
        }
    }

    private fun areAllTasksCompleted(): Boolean {
        QuickStartTask.values().forEach {
            if (it != QuickStartTask.CREATE_SITE) { // CREATE_SITE is completed by default, regardless of DB flag
                if (!quickStartStore.hasDoneTask(site.toLong(), it)) {
                    return@areAllTasksCompleted false
                }
            }
        }
        return true
    }

    private fun crossOutCompletedTasks() {
        // Create Site task is completed by default
        visuallyMarkTaskAsCompleted(findViewById(R.id.title_create_site), findViewById(R.id.done_create_site))
        disableTaskContainer(findViewById<View>(R.id.layout_create_site))

        if (quickStartStore.hasDoneTask(site.toLong(), QuickStartTask.VIEW_SITE)) {
            visuallyMarkTaskAsCompleted(findViewById(R.id.title_view_site), findViewById(R.id.done_view_site))
            disableTaskContainer(findViewById<View>(R.id.layout_view_site))
        }

        if (quickStartStore.hasDoneTask(site.toLong(), QuickStartTask.CHOOSE_THEME)) {
            visuallyMarkTaskAsCompleted(findViewById(R.id.title_browse_themes), findViewById(R.id.done_browse_themes))
            disableTaskContainer(findViewById<View>(R.id.layout_browse_themes))
        }

        if (quickStartStore.hasDoneTask(site.toLong(), QuickStartTask.CUSTOMIZE_SITE)) {
            visuallyMarkTaskAsCompleted(findViewById(R.id.title_customize_site), findViewById(R.id.done_customize_site))
            disableTaskContainer(findViewById<View>(R.id.layout_customize_site))
        }

        if (quickStartStore.hasDoneTask(site.toLong(), QuickStartTask.SHARE_SITE)) {
            visuallyMarkTaskAsCompleted(findViewById(R.id.title_share_site), findViewById(R.id.done_share_site))
            disableTaskContainer(findViewById<View>(R.id.layout_share_site))
        }

        if (quickStartStore.hasDoneTask(site.toLong(), QuickStartTask.PUBLISH_POST)) {
            visuallyMarkTaskAsCompleted(findViewById(R.id.title_publish_post), findViewById(R.id.done_publish_post))
            disableTaskContainer(findViewById<View>(R.id.layout_publish_post))
        }

        if (quickStartStore.hasDoneTask(site.toLong(), QuickStartTask.FOLLOW_SITE)) {
            visuallyMarkTaskAsCompleted(findViewById(R.id.title_follow_site), findViewById(R.id.done_follow_site))
            disableTaskContainer(findViewById<View>(R.id.layout_follow_site))
        }
    }

    private fun visuallyMarkTaskAsCompleted(taskTitleTextView: TextView, taskDoneCheckMark: View) {
        taskTitleTextView.let { it.paintFlags = it.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG }
        taskDoneCheckMark.visibility = View.VISIBLE
    }

    private fun disableTaskContainer(container: View) {
        container.isClickable = false
    }
}
