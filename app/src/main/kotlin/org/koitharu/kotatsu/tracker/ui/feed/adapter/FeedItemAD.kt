package org.koitharu.kotatsu.tracker.ui.feed.adapter

import androidx.core.content.ContextCompat
import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.util.ext.drawableStart
import org.koitharu.kotatsu.core.util.ext.getQuantityStringSafe
import org.koitharu.kotatsu.databinding.ItemFeedBinding
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.tracker.ui.feed.model.FeedItem

interface FeedListener {
	fun onItemClick(item: FeedItem)
	fun onDownloadClick(item: FeedItem)
	fun onDownloadChapterClick(item: FeedItem, chapterId: Long)
	fun onDeleteChapterClick(item: FeedItem, chapterId: Long)
}

fun feedItemAD(
	listener: FeedListener,
) = adapterDelegateViewBinding<FeedItem, ListModel, ItemFeedBinding>(
	{ inflater, parent -> ItemFeedBinding.inflate(inflater, parent, false) },
) {
	val indicatorNew = ContextCompat.getDrawable(context, R.drawable.ic_new)
	val expandedIds = HashSet<Long>()

	itemView.setOnClickListener {
		listener.onItemClick(item)
	}

	binding.imageViewDownload.setOnClickListener {
		listener.onDownloadClick(item)
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
				binding.linearLayoutChapters.visibility = android.view.View.VISIBLE
				binding.linearLayoutChapters.removeAllViews()
				val inflater = android.view.LayoutInflater.from(context)
				item.chapters.forEach { ch ->
					val view = inflater.inflate(R.layout.item_feed_chapter, binding.linearLayoutChapters, false)
					val titleView = view.findViewById<android.widget.TextView>(R.id.textView_chapter_title)
					val downloadView = view.findViewById<android.widget.ImageView>(R.id.imageView_download_chapter)
					titleView.text = ch.title
					if (ch.isDownloaded) {
						downloadView.setImageResource(R.drawable.ic_storage)
						downloadView.setOnClickListener {
							listener.onDeleteChapterClick(item, ch.id)
						}
					} else {
						downloadView.setImageResource(R.drawable.ic_save)
						downloadView.setOnClickListener {
							listener.onDownloadChapterClick(item, ch.id)
						}
					}
					binding.linearLayoutChapters.addView(view)
				}
				binding.imageViewExpand.rotation = 180f
			} else {
				binding.linearLayoutChapters.visibility = android.view.View.GONE
				binding.imageViewExpand.rotation = 0f
			}
		} else {
			binding.imageViewExpand.visibility = android.view.View.GONE
			binding.linearLayoutChapters.visibility = android.view.View.GONE
		}
	}
}
