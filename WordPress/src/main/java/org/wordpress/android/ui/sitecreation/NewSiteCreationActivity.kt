package org.wordpress.android.ui.sitecreation

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AppCompatActivity
import android.view.MenuItem
import org.wordpress.android.R
import org.wordpress.android.WordPress
import org.wordpress.android.analytics.AnalyticsTracker
import org.wordpress.android.ui.sitecreation.SiteCreationStep.DOMAINS
import org.wordpress.android.ui.sitecreation.SiteCreationStep.SEGMENTS
import org.wordpress.android.ui.sitecreation.SiteCreationStep.VERTICALS
import org.wordpress.android.ui.sitecreation.segments.NewSiteCreationSegmentsFragment
import org.wordpress.android.ui.sitecreation.segments.NewSiteCreationSegmentsResultObservable
import org.wordpress.android.ui.sitecreation.verticals.NewSiteCreationVerticalsFragment
import org.wordpress.android.ui.sitecreation.verticals.NewSiteCreationVerticalsResultObservable
import org.wordpress.android.util.observeEvent
import org.wordpress.android.util.wizard.WizardNavigationTarget
import javax.inject.Inject

class NewSiteCreationActivity : AppCompatActivity() {
    @Inject protected lateinit var mViewModelFactory: ViewModelProvider.Factory
    @Inject protected lateinit var segmentsResultObservable: NewSiteCreationSegmentsResultObservable
    @Inject protected lateinit var verticalsResultObservable: NewSiteCreationVerticalsResultObservable
    private lateinit var mMainViewModel: NewSiteCreationMainVM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as WordPress).component().inject(this)

        setContentView(R.layout.site_creation_activity)
        mMainViewModel = ViewModelProviders.of(this, mViewModelFactory).get(NewSiteCreationMainVM::class.java)
        mMainViewModel.start()

        if (savedInstanceState == null) {
            AnalyticsTracker.track(AnalyticsTracker.Stat.SITE_CREATION_ACCESSED)
        }
        observeVMState()
    }

    private fun observeVMState() {
        mMainViewModel.navigationTargetObservable
                .observeEvent(this) { target ->
                    showStep(target)
                }
        segmentsResultObservable.selectedSegment.observe(
                this,
                Observer { segmentId -> segmentId?.let { mMainViewModel.onSegmentSelected(segmentId) } }
        )

        verticalsResultObservable.selectedVertical.observe(
                this,
                Observer { verticalId -> mMainViewModel.onVerticalsScreenFinished(verticalId) }
        )
    }

    private fun showStep(target: WizardNavigationTarget<SiteCreationStep, SiteCreationState>): Boolean {
        val fragment = when (target.wizardStepIdentifier) {
            SEGMENTS -> NewSiteCreationSegmentsFragment.newInstance()
            VERTICALS ->
                NewSiteCreationVerticalsFragment.newInstance(target.wizardState.segmentId!!)
            DOMAINS -> NewSiteCreationDomainFragment.newInstance("Test title")
        }
        slideInFragment(fragment, target.wizardStepIdentifier.toString())
        return true
    }

    private fun slideInFragment(fragment: Fragment?, tag: String) {
        val fragmentTransaction = supportFragmentManager.beginTransaction()
        fragmentTransaction.setCustomAnimations(
                R.anim.activity_slide_in_from_right, R.anim.activity_slide_out_to_left,
                R.anim.activity_slide_in_from_left, R.anim.activity_slide_out_to_right
        )
        fragmentTransaction.replace(R.id.fragment_container, fragment, tag)
        if (supportFragmentManager.findFragmentById(R.id.fragment_container) != null) {
            fragmentTransaction.addToBackStack(null)
        }
        fragmentTransaction.commit()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            onBackPressed()
            return true
        }

        return false
    }

    override fun onBackPressed() {
        mMainViewModel.onBackPressed()
        super.onBackPressed()
    }
}