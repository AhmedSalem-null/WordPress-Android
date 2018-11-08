package org.wordpress.android.ui.sitecreation.segments

import android.arch.lifecycle.Observer
import android.arch.lifecycle.ViewModelProvider
import android.arch.lifecycle.ViewModelProviders
import android.os.Bundle
import android.os.Parcelable
import android.support.annotation.LayoutRes
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.wordpress.android.R
import org.wordpress.android.WordPress
import org.wordpress.android.fluxc.model.vertical.VerticalSegmentModel
import org.wordpress.android.ui.sitecreation.NewSiteCreationBaseFormFragment
import org.wordpress.android.ui.sitecreation.NewSiteCreationListener
import org.wordpress.android.util.image.ImageManager
import javax.inject.Inject

private const val keyListState = "list_state"

class NewSiteCreationSegmentsFragment : NewSiteCreationBaseFormFragment<NewSiteCreationListener>() {
    private lateinit var linearLayoutManager: LinearLayoutManager
    private lateinit var recyclerView: RecyclerView
    private lateinit var viewModel: NewSiteCreationSegmentsViewModel

    private lateinit var progressLayout: ViewGroup
    private lateinit var errorLayout: ViewGroup
    private lateinit var headerLayout: ViewGroup

    @Inject protected lateinit var imageManager: ImageManager
    @Inject protected lateinit var viewModelFactory: ViewModelProvider.Factory

    @LayoutRes
    override fun getContentLayout(): Int {
        return R.layout.new_site_creation_segments_screen
    }

    override fun setupContent(rootView: ViewGroup) {
        // important for accessibility - talkback
        activity!!.setTitle(R.string.new_site_creation_segments_title)
        headerLayout = rootView.findViewById(R.id.header_layout)
        progressLayout = rootView.findViewById(R.id.progress_layout)
        errorLayout = rootView.findViewById(R.id.error_layout)
        initRecyclerView(rootView)
        initViewModel()
        initRetryButton(rootView)
    }

    private fun initRecyclerView(rootView: ViewGroup) {
        recyclerView = rootView.findViewById(R.id.recycler_view)
        val layoutManager = LinearLayoutManager(activity, LinearLayoutManager.VERTICAL, false)
        linearLayoutManager = layoutManager
        recyclerView.layoutManager = linearLayoutManager
        initAdapter()
    }

    private fun initAdapter() {
        val adapter = NewSiteCreationSegmentsAdapter(
                onItemTapped = { segment -> viewModel.onSegmentSelected(segment.segmentId) },
                imageManager = imageManager
        )
        recyclerView.adapter = adapter
    }

    private fun initViewModel() {
        viewModel = ViewModelProviders.of(activity!!, viewModelFactory)
                .get(NewSiteCreationSegmentsViewModel::class.java)

        viewModel.uiState.observe(this, Observer { state ->
            state?.let {
                progressLayout.visibility = if (state.showProgress) View.VISIBLE else View.GONE
                headerLayout.visibility = if (state.showHeader) View.VISIBLE else View.GONE
                recyclerView.visibility = if (state.showList) View.VISIBLE else View.GONE
                errorLayout.visibility = if (state.showError) View.VISIBLE else View.GONE
                updateSegments(state.data)
            }
        })

        viewModel.start()
    }

    private fun initRetryButton(rootView: ViewGroup) {
        val retryBtn = rootView.findViewById<Button>(R.id.error_retry)
        retryBtn.setOnClickListener { view -> viewModel.onRetryClicked() }
    }

    override fun onHelp() {
        if (mSiteCreationListener != null) {
            mSiteCreationListener.helpCategoryScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (activity!!.application as WordPress).component().inject(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putParcelable(keyListState, linearLayoutManager.onSaveInstanceState())
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        savedInstanceState?.getParcelable<Parcelable>(keyListState)?.let {
            linearLayoutManager.onRestoreInstanceState(it)
        }
    }

    private fun updateSegments(segments: List<VerticalSegmentModel>) {
        (recyclerView.adapter as NewSiteCreationSegmentsAdapter).update(segments)
    }

    companion object {
        val TAG = "site_creation_segment_fragment_tag"
    }
}