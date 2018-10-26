package org.wordpress.android.ui.posts

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.support.design.widget.Snackbar
import android.support.v4.app.Fragment
import android.support.v7.app.AlertDialog
import android.support.v7.util.DiffUtil
import android.support.v7.util.DiffUtil.DiffResult
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.LinearSmoothScroller
import android.support.v7.widget.RecyclerView
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.View.OnClickListener
import android.view.ViewGroup
import android.widget.ProgressBar
import de.greenrobot.event.EventBus
import kotlinx.coroutines.experimental.Dispatchers
import kotlinx.coroutines.experimental.GlobalScope
import kotlinx.coroutines.experimental.Job
import kotlinx.coroutines.experimental.android.Main
import kotlinx.coroutines.experimental.isActive
import kotlinx.coroutines.experimental.launch
import kotlinx.coroutines.experimental.withContext
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.wordpress.android.R
import org.wordpress.android.WordPress
import org.wordpress.android.analytics.AnalyticsTracker
import org.wordpress.android.fluxc.Dispatcher
import org.wordpress.android.fluxc.generated.PostActionBuilder
import org.wordpress.android.fluxc.model.CauseOfOnPostChanged
import org.wordpress.android.fluxc.model.PostModel
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.list.ListDescriptor
import org.wordpress.android.fluxc.model.list.ListItemDataSource
import org.wordpress.android.fluxc.model.list.ListManager
import org.wordpress.android.fluxc.model.list.PostListDescriptor
import org.wordpress.android.fluxc.model.list.PostListDescriptor.PostListDescriptorForRestSite
import org.wordpress.android.fluxc.model.list.PostListDescriptor.PostListDescriptorForXmlRpcSite
import org.wordpress.android.fluxc.store.ListStore
import org.wordpress.android.fluxc.store.ListStore.ListErrorType
import org.wordpress.android.fluxc.store.ListStore.OnListChanged
import org.wordpress.android.fluxc.store.ListStore.OnListChanged.CauseOfListChange.FIRST_PAGE_FETCHED
import org.wordpress.android.fluxc.store.ListStore.OnListItemsChanged
import org.wordpress.android.fluxc.store.MediaStore.OnMediaChanged
import org.wordpress.android.fluxc.store.MediaStore.OnMediaUploaded
import org.wordpress.android.fluxc.store.PostStore
import org.wordpress.android.fluxc.store.PostStore.FetchPostListPayload
import org.wordpress.android.fluxc.store.PostStore.OnPostChanged
import org.wordpress.android.fluxc.store.PostStore.OnPostUploaded
import org.wordpress.android.fluxc.store.PostStore.RemotePostPayload
import org.wordpress.android.fluxc.store.SiteStore
import org.wordpress.android.push.NativeNotificationsUtils
import org.wordpress.android.ui.ActionableEmptyView
import org.wordpress.android.ui.ActivityLauncher
import org.wordpress.android.ui.EmptyViewMessageType
import org.wordpress.android.ui.EmptyViewMessageType.GENERIC_ERROR
import org.wordpress.android.ui.EmptyViewMessageType.PERMISSION_ERROR
import org.wordpress.android.ui.ListManagerDiffCallback
import org.wordpress.android.ui.notifications.utils.PendingDraftsNotificationsUtils
import org.wordpress.android.ui.posts.adapters.PostListAdapter
import org.wordpress.android.ui.uploads.PostEvents
import org.wordpress.android.ui.uploads.UploadService
import org.wordpress.android.ui.uploads.UploadUtils
import org.wordpress.android.ui.uploads.VideoOptimizer
import org.wordpress.android.util.AccessibilityUtils
import org.wordpress.android.util.AniUtils
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.AppLog.T
import org.wordpress.android.util.NetworkUtils
import org.wordpress.android.util.ToastUtils
import org.wordpress.android.util.WPSwipeToRefreshHelper.buildSwipeToRefreshHelper
import org.wordpress.android.util.analytics.AnalyticsUtils
import org.wordpress.android.util.helpers.RecyclerViewScrollPositionManager
import org.wordpress.android.util.helpers.SwipeToRefreshHelper
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout
import org.wordpress.android.widgets.PostListButton
import org.wordpress.android.widgets.RecyclerItemDecoration
import java.util.ArrayList
import java.util.HashMap
import javax.inject.Inject

private const val KEY_TRASHED_POST_LOCAL_IDS = "KEY_TRASHED_POST_LOCAL_IDS"
private const val KEY_TRASHED_POST_REMOTE_IDS = "KEY_TRASHED_POST_REMOTE_IDS"
private const val KEY_UPLOADED_REMOTE_POST_IDS = "KEY_UPLOADED_REMOTE_POST_IDS"
private const val LIST_TYPE = "list-type-to-show"

class PostListFragment : Fragment(),
        PostListAdapter.OnPostSelectedListener,
        PostListAdapter.OnPostButtonClickListener {
    private val rvScrollPositionSaver = RecyclerViewScrollPositionManager()
    private var swipeToRefreshHelper: SwipeToRefreshHelper? = null
    private var fabView: View? = null

    private var swipeRefreshLayout: CustomSwipeRefreshLayout? = null
    private var recyclerView: RecyclerView? = null
    private var actionableEmptyView: ActionableEmptyView? = null
    private var progressLoadMore: ProgressBar? = null

    private var targetPost: PostModel? = null
    private var shouldCancelPendingDraftNotification = false
    private var postIdForPostToBeDeleted = 0

    private val uploadedPostRemoteIds = ArrayList<Long>()
    private val trashedPostIds = ArrayList<Pair<Int, Long>>()

    private lateinit var nonNullActivity: Activity
    private lateinit var site: SiteModel
    private lateinit var listDescriptor: PostListDescriptor

    @Inject internal lateinit var siteStore: SiteStore
    @Inject internal lateinit var postStore: PostStore
    @Inject internal lateinit var listStore: ListStore
    @Inject internal lateinit var dispatcher: Dispatcher

    private var listManager: ListManager<PostModel>? = null
    private var refreshListDataJob: Job? = null
    private val postListAdapter: PostListAdapter by lazy {
        val postListAdapter = PostListAdapter(nonNullActivity, site)
        postListAdapter.setOnPostSelectedListener(this)
        postListAdapter.setOnPostButtonClickListener(this)
        postListAdapter
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nonNullActivity = checkNotNull(activity)
        (nonNullActivity.application as WordPress).component().inject(this)

        val site: SiteModel?
        if (savedInstanceState == null) {
            val nonNullIntent = checkNotNull(nonNullActivity.intent)
            site = nonNullIntent.getSerializableExtra(WordPress.SITE) as SiteModel?
            targetPost = postStore.getPostByLocalPostId(
                    nonNullIntent.getIntExtra(PostsListActivity.EXTRA_TARGET_POST_LOCAL_ID, 0)
            )
        } else {
            rvScrollPositionSaver.onRestoreInstanceState(savedInstanceState)
            site = savedInstanceState.getSerializable(WordPress.SITE) as SiteModel?
            savedInstanceState.getLongArray(KEY_UPLOADED_REMOTE_POST_IDS)?.let {
                uploadedPostRemoteIds.addAll(it.asList())
            }
            savedInstanceState.getIntArray(KEY_TRASHED_POST_LOCAL_IDS)?.let { trashedLocalIds ->
                savedInstanceState.getLongArray(KEY_TRASHED_POST_REMOTE_IDS)?.let { trashedRemoteIds ->
                    trashedPostIds.addAll(trashedLocalIds.toList().zip(trashedRemoteIds.toList()))
                }
            }
            targetPost = postStore.getPostByLocalPostId(
                    savedInstanceState.getInt(PostsListActivity.EXTRA_TARGET_POST_LOCAL_ID)
            )
        }

        if (site == null) {
            ToastUtils.showToast(nonNullActivity, R.string.blog_not_found, ToastUtils.Duration.SHORT)
            nonNullActivity.finish()
        } else {
            this.site = site
        }

        EventBus.getDefault().register(this)
        dispatcher.register(this)

        val listType: PostListType = requireNotNull(arguments?.getSerializable(LIST_TYPE) as PostListType?)
        listDescriptor = if (this.site.isUsingWpComRestApi) {
            PostListDescriptorForRestSite(this.site, statusList = listType.postStatusList)
        } else {
            PostListDescriptorForXmlRpcSite(this.site, statusList = listType.postStatusList)
        }
        refreshListManagerFromStore(listDescriptor, shouldRefreshFirstPageAfterLoading = (savedInstanceState == null))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(WordPress.SITE, site)
        rvScrollPositionSaver.onSaveInstanceState(outState, recyclerView)
        outState.putIntArray(KEY_TRASHED_POST_LOCAL_IDS, trashedPostIds.map { it.first }.toIntArray())
        outState.putLongArray(KEY_TRASHED_POST_REMOTE_IDS, trashedPostIds.map { it.second }.toLongArray())
        outState.putLongArray(KEY_UPLOADED_REMOTE_POST_IDS, uploadedPostRemoteIds.toLongArray())
    }

    override fun onDestroy() {
        EventBus.getDefault().unregister(this)
        dispatcher.unregister(this)

        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()

        // scale in the fab after a brief delay if it's not already showing
        if (fabView?.visibility != View.VISIBLE) {
            val delayMs = resources.getInteger(R.integer.fab_animation_delay).toLong()
            Handler().postDelayed({
                if (isAdded) {
                    AniUtils.scaleIn(fabView, AniUtils.Duration.MEDIUM)
                }
            }, delayMs)
        }
    }

    override fun onDetach() {
        if (shouldCancelPendingDraftNotification) {
            // delete the pending draft notification if available
            val pushId = PendingDraftsNotificationsUtils.makePendingDraftNotificationId(postIdForPostToBeDeleted)
            NativeNotificationsUtils.dismissNotification(pushId, nonNullActivity)
            shouldCancelPendingDraftNotification = false
        }
        super.onDetach()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.post_list_fragment, container, false)

        swipeRefreshLayout = view.findViewById(R.id.ptr_layout)
        recyclerView = view.findViewById(R.id.recycler_view)
        progressLoadMore = view.findViewById(R.id.progress)
        fabView = view.findViewById(R.id.fab_button)

        actionableEmptyView = view.findViewById(R.id.actionable_empty_view)

        val context = nonNullActivity
        val spacingVertical = context.resources.getDimensionPixelSize(R.dimen.card_gutters)
        val spacingHorizontal = context.resources.getDimensionPixelSize(R.dimen.content_margin)
        recyclerView?.layoutManager = LinearLayoutManager(context)
        recyclerView?.addItemDecoration(RecyclerItemDecoration(spacingHorizontal, spacingVertical))
        recyclerView?.adapter = postListAdapter

        // hide the fab so we can animate it
        fabView?.visibility = View.GONE
        fabView?.setOnClickListener { newPost() }

        swipeToRefreshHelper = buildSwipeToRefreshHelper(swipeRefreshLayout) {
            refreshPostList()
        }
        return view
    }

    private fun refreshPostList() {
        if (!isAdded) {
            return
        }
        if (!NetworkUtils.isNetworkAvailable(nonNullActivity)) {
            swipeRefreshLayout?.isRefreshing = false
            // If network is not available, we can refresh the items from the DB in case an update is not reflected
            // It really shouldn't be necessary, but wouldn't hurt to have it here either
            refreshListManagerFromStore(listDescriptor, shouldRefreshFirstPageAfterLoading = false)
        } else {
            listManager?.refresh()
        }
    }

    private fun newPost() {
        if (!isAdded) {
            return
        }
        ActivityLauncher.addNewPostForResult(nonNullActivity, site, false)
    }

    private fun updateEmptyViewForListManagerChange(listManager: ListManager<PostModel>) {
        if (!listManager.isFetchingFirstPage) {
            if (listManager.size == 0) {
                val messageType = if (NetworkUtils.isNetworkAvailable(nonNullActivity)) {
                    EmptyViewMessageType.NO_CONTENT
                } else {
                    EmptyViewMessageType.NETWORK_ERROR
                }
                updateEmptyView(messageType)
            } else {
                hideEmptyView()
            }
        } else {
            updateEmptyView(EmptyViewMessageType.LOADING)
        }
    }

    private fun updateEmptyView(emptyViewMessageType: EmptyViewMessageType) {
        if (!isAdded) {
            return
        }
        val stringId: Int = when (emptyViewMessageType) {
            EmptyViewMessageType.LOADING -> R.string.posts_fetching
            EmptyViewMessageType.NO_CONTENT -> R.string.posts_empty_list
            EmptyViewMessageType.NETWORK_ERROR -> R.string.no_network_message
            EmptyViewMessageType.PERMISSION_ERROR -> R.string.error_refresh_unauthorized_posts
            EmptyViewMessageType.GENERIC_ERROR -> R.string.error_refresh_posts
        }

        val hasNoContent = emptyViewMessageType == EmptyViewMessageType.NO_CONTENT
        actionableEmptyView?.let {
            it.image.setImageResource(R.drawable.img_illustration_posts_75dp)
            it.image.visibility = if (hasNoContent) View.VISIBLE else View.GONE
            it.title.setText(stringId)
            it.button.setText(R.string.posts_empty_list_button)
            it.button.visibility = if (hasNoContent) View.VISIBLE else View.GONE
            it.button.setOnClickListener { _ ->
                ActivityLauncher.addNewPostForResult(nonNullActivity, site, false)
            }
            it.visibility = if (postListAdapter.itemCount == 0) View.VISIBLE else View.GONE
        }
    }

    private fun hideEmptyView() {
        if (isAdded) {
            actionableEmptyView?.visibility = View.GONE
        }
    }

    private fun showTargetPostIfNecessary() {
        if (!isAdded) {
            return
        }
        // If the activity was given a target post, and this is the first time posts are loaded, scroll to that post
        targetPost?.let { targetPost ->
            postListAdapter.getPositionForPost(targetPost)?.let { position ->
                val smoothScroller = object : LinearSmoothScroller(nonNullActivity) {
                    private val SCROLL_OFFSET_DP = 23

                    override fun getVerticalSnapPreference(): Int {
                        return LinearSmoothScroller.SNAP_TO_START
                    }

                    override fun calculateDtToFit(
                        viewStart: Int,
                        viewEnd: Int,
                        boxStart: Int,
                        boxEnd: Int,
                        snapPreference: Int
                    ): Int {
                        // Assume SNAP_TO_START, and offset the scroll, so the bottom of the above post shows
                        val offsetPx = TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, SCROLL_OFFSET_DP.toFloat(), resources.displayMetrics
                        ).toInt()
                        return boxStart - viewStart + offsetPx
                    }
                }

                smoothScroller.targetPosition = position
                recyclerView?.layoutManager?.startSmoothScroll(smoothScroller)
            }
            this.targetPost = null
        }
    }

    /*
     * send the passed post to the trash with undo
     */
    private fun trashPost(post: PostModel) {
        // only check if network is available in case this is not a local draft - local drafts have not yet
        // been posted to the server so they can be trashed w/o further care
        if (!isAdded || !post.isLocalDraft && !NetworkUtils.checkConnection(nonNullActivity)) {
            return
        }

        // remove post from the list and add it to the list of trashed posts
        val postIdPair = Pair(post.id, post.remotePostId)
        trashedPostIds.add(postIdPair)
        refreshListManagerFromStore(listDescriptor, shouldRefreshFirstPageAfterLoading = false)

        val undoListener = OnClickListener {
            // user undid the trash, so un-hide the post and remove it from the list of trashed posts
            trashedPostIds.remove(postIdPair)
            refreshListManagerFromStore(listDescriptor, shouldRefreshFirstPageAfterLoading = false)
        }

        // different undo text if this is a local draft since it will be deleted rather than trashed
        val text = if (post.isLocalDraft) getString(R.string.post_deleted) else getString(R.string.post_trashed)
        val snackbar = Snackbar.make(
                nonNullActivity.findViewById(R.id.root_view),
                text,
                AccessibilityUtils.getSnackbarDuration(nonNullActivity)
        ).setAction(R.string.undo, undoListener)
        // wait for the undo snackbar to disappear before actually deleting the post
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onDismissed(snackbar: Snackbar?, event: Int) {
                super.onDismissed(snackbar, event)

                // if the post no longer exists in the list of trashed posts it's because the
                // user undid the trash, so don't perform the deletion
                if (!trashedPostIds.contains(postIdPair)) {
                    return
                }

                // remove from the list of trashed posts in case onDismissed is called multiple
                // times - this way the above check prevents us making the call to delete it twice
                // https://code.google.com/p/android/issues/detail?id=190529
                trashedPostIds.remove(postIdPair)

                // here cancel all media uploads related to this Post
                UploadService.cancelQueuedPostUploadAndRelatedMedia(WordPress.getContext(), post)

                if (post.isLocalDraft) {
                    dispatcher.dispatch(PostActionBuilder.newRemovePostAction(post))

                    // delete the pending draft notification if available
                    shouldCancelPendingDraftNotification = false
                    val pushId = PendingDraftsNotificationsUtils.makePendingDraftNotificationId(post.id)
                    NativeNotificationsUtils.dismissNotification(pushId, WordPress.getContext())
                } else {
                    dispatcher.dispatch(PostActionBuilder.newDeletePostAction(RemotePostPayload(post, site)))
                }
            }
        })

        postIdForPostToBeDeleted = post.id
        shouldCancelPendingDraftNotification = true
        snackbar.show()
    }

    private fun showPublishConfirmationDialog(post: PostModel) {
        if (!isAdded) {
            return
        }
        val builder = AlertDialog.Builder(
                ContextThemeWrapper(nonNullActivity, R.style.Calypso_Dialog_Alert)
        )
        builder.setTitle(resources.getText(R.string.dialog_confirm_publish_title))
                .setMessage(getString(R.string.dialog_confirm_publish_message_post))
                .setPositiveButton(R.string.dialog_confirm_publish_yes) { _, _ ->
                    UploadUtils.publishPost(
                            nonNullActivity,
                            post,
                            site,
                            dispatcher
                    )
                }
                .setNegativeButton(R.string.cancel, null)
                .setCancelable(true)
        builder.create().show()
    }

    // PostListAdapter listeners

    /*
     * called by the adapter when the user clicks a post
     */
    override fun onPostSelected(post: PostModel) {
        onPostButtonClicked(PostListButton.BUTTON_EDIT, post)
    }

    /*
     * called by the adapter when the user clicks the edit/view/stats/trash button for a post
     */
    override fun onPostButtonClicked(buttonType: Int, postClicked: PostModel) {
        if (!isAdded) {
            return
        }

        // Get the latest version of the post, in case it's changed since the last time we refreshed the post list
        val post = postStore.getPostByLocalPostId(postClicked.id)
        if (post == null) {
            // This is mostly a sanity check and the list should never go out of sync, but if there is an edge case, we
            // should refresh the list
            refreshPostList()
            return
        }

        when (buttonType) {
            PostListButton.BUTTON_EDIT -> {
                // track event
                val properties = HashMap<String, Any>()
                properties["button"] = "edit"
                if (!post.isLocalDraft) {
                    properties["post_id"] = post.remotePostId
                }
                properties[AnalyticsUtils.HAS_GUTENBERG_BLOCKS_KEY] =
                        PostUtils.contentContainsGutenbergBlocks(post.content)
                AnalyticsUtils.trackWithSiteDetails(AnalyticsTracker.Stat.POST_LIST_BUTTON_PRESSED, site, properties)

                if (UploadService.isPostUploadingOrQueued(post)) {
                    // If the post is uploading media, allow the media to continue uploading, but don't upload the
                    // post itself when they finish (since we're about to edit it again)
                    UploadService.cancelQueuedPostUpload(post)
                }
                ActivityLauncher.editPostOrPageForResult(nonNullActivity, site, post)
            }
            PostListButton.BUTTON_RETRY -> {
                // restart the UploadService with retry parameters
                val intent = UploadService.getUploadPostServiceIntent(
                        nonNullActivity,
                        post,
                        PostUtils.isFirstTimePublish(post),
                        false,
                        true
                )
                nonNullActivity.startService(intent)
            }
            PostListButton.BUTTON_SUBMIT, PostListButton.BUTTON_SYNC, PostListButton.BUTTON_PUBLISH -> {
                showPublishConfirmationDialog(post)
            }
            PostListButton.BUTTON_VIEW -> ActivityLauncher.browsePostOrPage(nonNullActivity, site, post)
            PostListButton.BUTTON_PREVIEW -> ActivityLauncher.viewPostPreviewForResult(nonNullActivity, site, post)
            PostListButton.BUTTON_STATS -> ActivityLauncher.viewStatsSinglePostDetails(
                    nonNullActivity,
                    site,
                    post
            )
            PostListButton.BUTTON_TRASH, PostListButton.BUTTON_DELETE -> {
                if (!UploadService.isPostUploadingOrQueued(post)) {
                    var message = getString(R.string.dialog_confirm_delete_post)

                    if (post.isLocalDraft) {
                        message = getString(R.string.dialog_confirm_delete_permanently_post)
                    }

                    val builder = AlertDialog.Builder(
                            ContextThemeWrapper(nonNullActivity, R.style.Calypso_Dialog_Alert)
                    )
                    builder.setTitle(getString(R.string.delete_post))
                            .setMessage(message)
                            .setPositiveButton(R.string.delete) { _, _ -> trashPost(post) }
                            .setNegativeButton(R.string.cancel, null)
                            .setCancelable(true)
                    builder.create().show()
                } else {
                    val builder = AlertDialog.Builder(
                            ContextThemeWrapper(nonNullActivity, R.style.Calypso_Dialog_Alert)
                    )
                    builder.setTitle(getText(R.string.delete_post))
                            .setMessage(R.string.dialog_confirm_cancel_post_media_uploading)
                            .setPositiveButton(R.string.delete) { _, _ -> trashPost(post) }
                            .setNegativeButton(R.string.cancel, null)
                            .setCancelable(true)
                    builder.create().show()
                }
            }
        }
    }

    // FluxC events

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onPostChanged(event: OnPostChanged) {
        when (event.causeOfChange) {
            // Fetched post list event will be handled by OnListChanged
            is CauseOfOnPostChanged.UpdatePost -> {
                if (event.isError) {
                    AppLog.e(T.POSTS, "Error updating the post with type: " + event.error.type +
                            " and message: " + event.error.message)
                }
            }
            is CauseOfOnPostChanged.DeletePost -> {
                if (event.isError) {
                    GlobalScope.launch(Dispatchers.Main) {
                        val message = getString(R.string.error_deleting_post)
                        ToastUtils.showToast(nonNullActivity, message, ToastUtils.Duration.SHORT)
                    }
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    @Suppress("unused")
    fun onListChanged(event: OnListChanged) {
        if (!event.listDescriptors.contains(listDescriptor)) {
            return
        }
        if (event.isError) {
            GlobalScope.launch(Dispatchers.Main) {
                val emptyViewMessageType = if (event.error.type == ListErrorType.PERMISSION_ERROR) {
                    PERMISSION_ERROR
                } else GENERIC_ERROR
                updateEmptyView(emptyViewMessageType)
            }
        } else {
            if (event.causeOfChange == FIRST_PAGE_FETCHED) {
                // `uploadedPostRemoteIds` is kept as a workaround when the local drafts are uploaded and the list
                // has not yet been updated yet. Since we just fetched the first page, we can safely clear it.
                // Please check out `onPostUploaded` for more context.
                uploadedPostRemoteIds.clear()
            }
            refreshListManagerFromStore(listDescriptor, false)
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    @Suppress("unused")
    fun onListItemsChanged(event: OnListItemsChanged) {
        if (listDescriptor.typeIdentifier != event.type) {
            return
        }
        refreshListManagerFromStore(listDescriptor, false)
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onPostUploaded(event: OnPostUploaded) {
        if (isAdded && event.post != null && event.post.localSiteId == site.id) {
            UploadUtils.onPostUploadedSnackbarHandler(
                    nonNullActivity,
                    nonNullActivity.findViewById(R.id.coordinator),
                    event.isError, event.post, null, site, dispatcher
            )
            // When a local draft is uploaded, it'll no longer be considered a local item by `ListManager` and it won't
            // be in the remote item list until the next refresh, which means it'll briefly disappear from the list.
            // This is not the behavior we want and to get around it, we'll keep the remote id of the post until the
            // next refresh and pass it to `ListStore` so it'll be included in the list.
            // Although the issue is related to local drafts, we can't check if uploaded post is local draft reliably
            // as the current `ListManager` might not have been updated yet since it's a bg action.
            uploadedPostRemoteIds.add(event.post.remotePostId)
            refreshPostList()
        }
    }

    /*
     * Media info for a post's featured image has been downloaded, tell
     * the adapter so it can show the featured image now that we have its URL
     */
    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onMediaChanged(event: OnMediaChanged) {
        if (!event.isError && event.mediaList != null && event.mediaList.size > 0) {
            val mediaModel = event.mediaList[0]
            listManager?.findWithIndex { post ->
                post.featuredImageId == mediaModel.mediaId
            }?.forEach { (position, _) ->
                GlobalScope.launch(Dispatchers.Main) {
                    if (isAdded) {
                        postListAdapter.notifyItemChanged(position)
                    }
                }
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    fun onMediaUploaded(event: OnMediaUploaded) {
        if (event.isError || event.canceled) {
            return
        }

        if (event.media == null || event.media.localPostId == 0 || site.id != event.media.localSiteId) {
            // Not interested in media not attached to posts or not belonging to the current site
            return
        }

        postStore.getPostByLocalPostId(event.media.localPostId)?.let { post ->
            var shouldRefresh = false
            if (event.media.isError || event.canceled) {
                // if a media is cancelled or ends in error, and the post is not uploading nor queued,
                // (meaning there is no other pending media to be uploaded for this post)
                // then we should refresh it to show its new state
                if (!UploadService.isPostUploadingOrQueued(post)) {
                    shouldRefresh = true
                }
            } else {
                shouldRefresh = true
            }
            if (shouldRefresh) {
                GlobalScope.launch(Dispatchers.Main) {
                    if (isAdded) {
                        postListAdapter.updateProgressForPost(post)
                    }
                }
            }
        }
    }

    /*
     * Upload started, reload so correct status on uploading post appears
     */
    fun onEventMainThread(event: PostEvents.PostUploadStarted) {
        if (isAdded && site.id == event.post.localSiteId) {
            postListAdapter.refreshRowForPost(event.post)
        }
    }

    /*
     * Upload cancelled (probably due to failed media), reload so correct status on uploading post appears
     */
    fun onEventMainThread(event: PostEvents.PostUploadCanceled) {
        if (isAdded && site.id == event.post.localSiteId) {
            postListAdapter.refreshRowForPost(event.post)
        }
    }

    fun onEventMainThread(event: VideoOptimizer.ProgressEvent) {
        if (isAdded) {
            postStore.getPostByLocalPostId(event.media.localPostId)?.let { post ->
                postListAdapter.updateProgressForPost(post)
            }
        }
    }

    fun onEventMainThread(event: UploadService.UploadErrorEvent) {
        EventBus.getDefault().removeStickyEvent(event)
        if (event.post != null) {
            UploadUtils.onPostUploadedSnackbarHandler(
                    nonNullActivity,
                    nonNullActivity.findViewById(R.id.coordinator), true, event.post,
                    event.errorMessage, site, dispatcher
            )
        } else if (event.mediaModelList != null && !event.mediaModelList.isEmpty()) {
            UploadUtils.onMediaUploadedSnackbarHandler(
                    nonNullActivity,
                    nonNullActivity.findViewById(R.id.coordinator), true,
                    event.mediaModelList, site, event.errorMessage
            )
        }
    }

    fun onEventMainThread(event: UploadService.UploadMediaSuccessEvent) {
        EventBus.getDefault().removeStickyEvent(event)
        if (event.mediaModelList != null && !event.mediaModelList.isEmpty()) {
            UploadUtils.onMediaUploadedSnackbarHandler(
                    nonNullActivity,
                    nonNullActivity.findViewById(R.id.coordinator), false,
                    event.mediaModelList, site, event.successMessage
            )
        }
    }

    fun onEventMainThread(event: UploadService.UploadMediaRetryEvent) {
        if (!isAdded) {
            return
        }

        if (event.mediaModelList != null && !event.mediaModelList.isEmpty()) {
            // if there' a Post to which the retried media belongs, clear their status
            val postsToRefresh = PostUtils.getPostsThatIncludeAnyOfTheseMedia(postStore, event.mediaModelList)
            // now that we know which Posts to refresh, let's do it
            for (post in postsToRefresh) {
                postListAdapter.refreshRowForPost(post)
            }
        }
    }

    // ListManager

    /**
     * A helper function to load the current [ListManager] from [ListStore].
     *
     * @param listDescriptor The descriptor for which the [ListManager] to be loaded for
     * @param shouldRefreshFirstPageAfterLoading Whether the first page of the list should be fetched after its loaded
     *
     * A background [Job] will be used to refresh the list. If this function is triggered again before the job is
     * complete, it'll be canceled and a new one will be started. This is important to do, because we always want to
     * last version of [ListManager] to be used and we don't want block the UI thread unnecessarily.
     */
    private fun refreshListManagerFromStore(
        listDescriptor: ListDescriptor,
        shouldRefreshFirstPageAfterLoading: Boolean
    ) {
        refreshListDataJob?.cancel()
        refreshListDataJob = GlobalScope.launch(Dispatchers.Default) {
            val listManager = getListDataFromStore(listDescriptor)
            if (isActive && this@PostListFragment.listDescriptor == listDescriptor) {
                val diffResult = calculateDiff(this@PostListFragment.listManager, listManager)
                if (isActive && this@PostListFragment.listDescriptor == listDescriptor) {
                    updateListManager(listManager, diffResult, shouldRefreshFirstPageAfterLoading)
                }
            }
        }
    }

    /**
     * A helper function to load the [ListManager] for the given [ListDescriptor] from [ListStore].
     *
     * [ListStore] requires an instance of [ListItemDataSource] which is a way for us to tell [ListStore] and
     * [ListManager] how to take certain actions or how to access certain data.
     */
    private suspend fun getListDataFromStore(listDescriptor: ListDescriptor): ListManager<PostModel> =
            listStore.getListManager(listDescriptor, object : ListItemDataSource<PostModel> {
                /**
                 * Tells [ListStore] how to fetch a post from remote for the given list descriptor and remote post id
                 */
                override fun fetchItem(listDescriptor: ListDescriptor, remoteItemId: Long) {
                    val postToFetch = PostModel()
                    postToFetch.remotePostId = remoteItemId
                    val payload = RemotePostPayload(postToFetch, site)
                    dispatcher.dispatch(PostActionBuilder.newFetchPostAction(payload))
                }

                /**
                 * Tells [ListStore] how to fetch a list from remote for the given list descriptor and offset
                 */
                override fun fetchList(listDescriptor: ListDescriptor, offset: Int) {
                    if (listDescriptor is PostListDescriptor) {
                        val fetchPostListPayload = FetchPostListPayload(listDescriptor, offset)
                        dispatcher.dispatch(PostActionBuilder.newFetchPostListAction(fetchPostListPayload))
                    }
                }

                /**
                 * Tells [ListStore] how to get posts from [PostStore] for the given list descriptor and remote post ids
                 */
                override fun getItems(listDescriptor: ListDescriptor, remoteItemIds: List<Long>): Map<Long, PostModel> {
                    return postStore.getPostsByRemotePostIds(remoteItemIds, site)
                }

                /**
                 * Tells [ListStore] which local drafts should be included in the list. Since [ListStore] deals with
                 * remote items, it needs our help to show local data.
                 */
                override fun localItems(listDescriptor: ListDescriptor): List<PostModel>? {
                    if (listDescriptor is PostListDescriptor) {
                        // We should filter out the trashed posts from local drafts since they should be hidden
                        val trashedLocalPostIds = trashedPostIds.map { it.first }
                        return postStore.getLocalPostsForDescriptor(listDescriptor)
                                .filter { !trashedLocalPostIds.contains(it.id) }
                    }
                    return null
                }

                /**
                 * Tells [ListStore] which remote post ids must be included in the list. This is to workaround a case
                 * where the local draft is uploaded to remote but the list has not been refreshed yet. If we don't
                 * tell about this to [ListStore] that post will disappear until the next refresh.
                 *
                 * Please check out [OnPostUploaded] and [OnListChanged] for where [uploadedPostRemoteIds] is managed.
                 */
                override fun remoteItemIdsToInclude(listDescriptor: ListDescriptor): List<Long>? {
                    return uploadedPostRemoteIds
                }

                /**
                 * Tells [ListStore] which remote post ids must be hidden from the list. In order to show an undo
                 * snackbar when a post is trashed, we don't immediately delete/trash a post which means [ListStore]
                 * doesn't know about this action and needs our help to determine which posts should be hidden until
                 * delete/trash action is completed.
                 *
                 * Please check out [trashPost] for more details.
                 */
                override fun remoteItemsToHide(listDescriptor: ListDescriptor): List<Long>? {
                    return trashedPostIds.map { it.second }
                }
            })

    /**
     * A helper function to update the current [ListManager] with the given [listManager].
     *
     * @param listManager [ListManager] to be used to change with the current one
     * @param diffResult Pre-calculated [DiffResult] to be applied in the [PostListAdapter]
     * @param shouldRefreshFirstPageAfterUpdate Whether the first page of the list should be fetched after the update
     *
     * This function deals with all the UI actions that needs to be taken after a [ListManager] change, including but
     * not limited to, updating the swipe to refresh layout, loading progress bar and updating the empty views.
     */
    private suspend fun updateListManager(
        listManager: ListManager<PostModel>,
        diffResult: DiffResult,
        shouldRefreshFirstPageAfterUpdate: Boolean
    ) = withContext(Dispatchers.Main) {
        if (!isAdded) {
            return@withContext
        }
        this@PostListFragment.listManager = listManager
        swipeRefreshLayout?.isRefreshing = listManager.isFetchingFirstPage
        progressLoadMore?.visibility = if (listManager.isLoadingMore) View.VISIBLE else View.GONE
        // Save and restore the visible view. Without this, for example, if a new row is inserted, it does not show up.
        val recyclerViewState = recyclerView?.layoutManager?.onSaveInstanceState()
        postListAdapter.setListManager(listManager, diffResult)
        recyclerViewState?.let {
            recyclerView?.layoutManager?.onRestoreInstanceState(it)
        }

        // If offset is saved, restore it here. This is for when we save the scroll position in the bundle.
        recyclerView?.let {
            rvScrollPositionSaver.restoreScrollOffset(it)
        }
        showTargetPostIfNecessary()
        if (shouldRefreshFirstPageAfterUpdate) {
            refreshPostList()
        } else {
            // If we update the empty view just before a fetch, it will show "No Content" message only to update it
            // immediately after when we start a fetch. This makes it a smoother experience for empty post lists.
            updateEmptyViewForListManagerChange(listManager)
        }
    }

    /**
     * A helper function that calculates the [DiffResult] to be applied in [PostListAdapter] for the given
     * two [ListManager]s.
     */
    private suspend fun calculateDiff(
        oldListManager: ListManager<PostModel>?,
        newListManager: ListManager<PostModel>
    ): DiffResult = withContext(Dispatchers.Default) {
        val callback = ListManagerDiffCallback(
                oldListManager = oldListManager,
                newListManager = newListManager,
                areItemsTheSame = { oldPost, newPost ->
                    // If the local ids of two posts are the same, they are referring to the same post
                    oldPost.id == newPost.id
                },
                areContentsTheSame = { oldPost, newPost ->
                    if (oldPost.isLocalDraft && newPost.isLocalDraft) {
                        // If both posts are local drafts, checking their locally changed date will be enough
                        oldPost.dateLocallyChanged == newPost.dateLocallyChanged
                    } else if (oldPost.isLocalDraft || newPost.isLocalDraft) {
                        // If a post is a local draft and the other is not, the contents are considered to be changed
                        false
                    } else if (oldPost.isLocallyChanged && newPost.isLocallyChanged) {
                        // Neither post is a local draft due to previous checks. For remote posts, if both of them are
                        // locally changed, we can rely on their locally changed date
                        oldPost.dateLocallyChanged == newPost.dateLocallyChanged
                    } else if (oldPost.isLocallyChanged || newPost.isLocallyChanged) {
                        // If a post is locally changed and the other is not, the contents are considered to be changed
                        false
                    } else {
                        // Both posts are remote posts due to previous checks. In this case we can simply rely on their
                        // last modified date on remote
                        oldPost.lastModified == newPost.lastModified
                    }
                })
        DiffUtil.calculateDiff(callback)
    }

    companion object {
        const val TAG = "post_list_fragment_tag"

        @JvmStatic
        fun newInstance(site: SiteModel, listType: PostListType, targetPost: PostModel?): PostListFragment {
            val fragment = PostListFragment()
            val bundle = Bundle()
            bundle.putSerializable(WordPress.SITE, site)
            bundle.putSerializable(LIST_TYPE, listType)
            targetPost?.let {
                bundle.putInt(PostsListActivity.EXTRA_TARGET_POST_LOCAL_ID, it.id)
            }
            fragment.arguments = bundle
            return fragment
        }
    }
}
