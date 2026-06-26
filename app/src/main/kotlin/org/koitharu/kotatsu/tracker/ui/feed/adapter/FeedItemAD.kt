package org.koitharu.kotatsu.tracker.ui.feed.adapter

import androidx.core.content.ContextCompat
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.databinding.ItemFeedBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem

fun feedItemAD(
	clickListener: OnListItemClickListener<FeedItem>,
) = adapterDelegateViewBinding<FeedItem, ListModel, ItemFeedBinding>(
	{ inflater, parent -> ItemFeedBinding.inflate(inflater, parent, false) },
) {
	val indicatorNew = ContextCompat.getDrawable(context, R.drawable.ic_new)
	val expandedIds = HashSet<Long>()

	itemView.setOnClickListener {
		clickListener.onItemClick(item, it)
	}

	binding.imageViewExpand.setOnClickListener {
		val id = item.id
		if (expandedIds.contains(id)) {
			expandedIds.remove(id)
		} else {
			expandedIds.add(id)
		}
		bindingAdapter?.notifyItemChanged(bindingAdapterPosition)
	}

	bind {
		binding.imageViewCover.setImageAsync(item.imageUrl, item.manga.source)
		binding.textViewTitle.text = item.title
		binding.textViewSummary.text = context.resources.getQuantityStringSafe(
			R.plurals.new_chapters,
			item.count,
			item.count,
		)
		binding.textViewSummary.drawableStart = if (item.isNew) {
			indicatorNew
		} else {
			null
		}

		if (item.chapters.isNotEmpty()) {
			binding.imageViewExpand.visibility = android.view.View.VISIBLE
			val isExpanded = expandedIds.contains(item.id)
			if (isExpanded) {
				binding.textViewChapters.visibility = android.view.View.VISIBLE
				binding.textViewChapters.text = item.chapters.joinToString("\n")
				binding.imageViewExpand.rotation = 180f
			} else {
				binding.textViewChapters.visibility = android.view.View.GONE
				binding.imageViewExpand.rotation = 0f
			}
		} else {
			binding.imageViewExpand.visibility = android.view.View.GONE
			binding.textViewChapters.visibility = android.view.View.GONE
		}
	}
}
