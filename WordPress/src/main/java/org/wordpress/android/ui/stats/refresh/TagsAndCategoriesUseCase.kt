package org.wordpress.android.ui.stats.refresh

import android.arch.lifecycle.LiveData
import android.arch.lifecycle.MutableLiveData
import kotlinx.coroutines.experimental.CoroutineDispatcher
import org.wordpress.android.R
import org.wordpress.android.R.drawable
import org.wordpress.android.fluxc.model.SiteModel
import org.wordpress.android.fluxc.model.stats.TagsModel
import org.wordpress.android.fluxc.model.stats.TagsModel.TagModel
import org.wordpress.android.fluxc.store.InsightsStore
import org.wordpress.android.fluxc.store.StatsStore.InsightsTypes.TAGS_AND_CATEGORIES
import org.wordpress.android.modules.UI_THREAD
import org.wordpress.android.ui.stats.refresh.BlockListItem.ExpandableItem
import org.wordpress.android.ui.stats.refresh.BlockListItem.Item
import org.wordpress.android.ui.stats.refresh.BlockListItem.Link
import org.wordpress.android.ui.stats.refresh.BlockListItem.Title
import org.wordpress.android.ui.stats.refresh.NavigationTarget.ViewTag
import org.wordpress.android.ui.stats.refresh.NavigationTarget.ViewTagsAndCategoriesStats
import org.wordpress.android.ui.stats.refresh.usecases.BaseInsightsUseCase
import org.wordpress.android.viewmodel.ResourceProvider
import javax.inject.Inject
import javax.inject.Named

class TagsAndCategoriesUseCase
@Inject constructor(
    @Named(UI_THREAD) private val mainDispatcher: CoroutineDispatcher,
    private val insightsStore: InsightsStore,
    private val resourceProvider: ResourceProvider
) : BaseInsightsUseCase(TAGS_AND_CATEGORIES, mainDispatcher) {
    private val mutableNavigationTarget = MutableLiveData<NavigationTarget>()
    val navigationTarget: LiveData<NavigationTarget> = mutableNavigationTarget
    override suspend fun fetchRemoteData(site: SiteModel, refresh: Boolean, forced: Boolean): InsightsItem {
        val response = insightsStore.fetchTags(site, forced)
        val model = response.model
        val error = response.error

        return when {
            error != null -> Failed(R.string.stats_view_tags_and_categories, error.message ?: error.type.name)
            model != null -> loadTagsAndCategories(site, model)
            else -> throw IllegalArgumentException("Unexpected empty body")
        }
    }

    override suspend fun loadCachedData(site: SiteModel): InsightsItem? {
        val model = insightsStore.getTags(site)
        return model?.let { loadTagsAndCategories(site, model) }
    }

    private fun loadTagsAndCategories(
        site: SiteModel,
        model: TagsModel
    ): ListInsightItem {
        val items = mutableListOf<BlockListItem>()
        items.add(Title(R.string.stats_view_tags_and_categories))
        items.addAll(model.tags.mapIndexed { index, tag ->
            when {
                tag.items.size == 1 -> mapTag(tag, index, model.tags.size)
                else -> ExpandableItem(
                        mapCategory(tag, index, model.tags.size),
                        tag.items.map { subTag -> mapItem(subTag) }
                )
            }
        })
        items.add(Link(text = R.string.stats_insights_view_more) {
            mutableNavigationTarget.value = ViewTagsAndCategoriesStats(site.siteId)
        })
        return ListInsightItem(items)
    }

    private fun mapTag(tag: TagsModel.TagModel, index: Int, listSize: Int): Item {
        val item = tag.items.first()
        return Item(
                getIcon(item.type),
                item.name,
                tag.views.toFormattedString(),
                index < listSize - 1,
                clickAction = { clickTag(item.link) }
        )
    }

    private fun mapCategory(tag: TagsModel.TagModel, index: Int, listSize: Int): Item {
        val text = tag.items.foldIndexed("") { itemIndex, acc, item ->
            when (itemIndex) {
                0 -> item.name
                else -> resourceProvider.getString(R.string.stats_category_folded_name, acc, item.name)
            }
        }
        return Item(
                R.drawable.ic_folder_multiple_grey_dark_24dp,
                text,
                tag.views.toFormattedString(),
                index < listSize - 1
        )
    }

    private fun mapItem(item: TagModel.Item): Item {
        return Item(
                getIcon(item.type),
                item.name,
                "",
                false,
                clickAction = { clickTag(item.link) }

        )
    }

    private fun getIcon(type: String) =
            if (type == "tag") drawable.ic_tag_grey_dark_24dp else drawable.ic_folder_grey_dark_24dp

    private fun clickTag(link: String) {
        mutableNavigationTarget.value = ViewTag(link)
    }
}