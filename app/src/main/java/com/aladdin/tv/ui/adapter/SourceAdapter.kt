package com.aladdin.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aladdin.tv.data.model.StreamSource
import com.aladdin.tv.data.model.StreamType
import com.aladdin.tv.databinding.ItemSourceBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 同頻道多線路選擇面板專用適配器
 * 支援電視盒純遙控器上下焦點選取、目前使用中標記與附加條件預覽
 */
class SourceAdapter(
    private val getSourceFailure: (String) -> Pair<Long, String?>,
    private val onSourceClick: (StreamSource, Int) -> Unit
) : RecyclerView.Adapter<SourceAdapter.SourceViewHolder>() {

    private var sources: List<StreamSource> = emptyList()
    private var currentActiveIndex: Int = 0

    fun submitSources(newSources: List<StreamSource>, activeIndex: Int) {
        val oldSources = sources
        val oldActiveIndex = currentActiveIndex
        val updatedSources = newSources.toList()
        val changes = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldSources.size
            override fun getNewListSize() = updatedSources.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldSources[oldItemPosition].sourceId == updatedSources[newItemPosition].sourceId
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItemPosition == newItemPosition &&
                    oldSources[oldItemPosition] == updatedSources[newItemPosition] &&
                    (oldItemPosition == oldActiveIndex) == (newItemPosition == activeIndex)
        })
        sources = updatedSources
        currentActiveIndex = activeIndex
        changes.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
        val binding = ItemSourceBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return SourceViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
        holder.bind(sources[position], position)
    }

    override fun getItemCount(): Int = sources.size

    inner class SourceViewHolder(
        private val binding: ItemSourceBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(source: StreamSource, position: Int) {
            binding.tvSourceIndex.text = String.format(Locale.ROOT, "%02d", position + 1)
            binding.tvSourceName.text = source.label ?: "線路 ${position + 1}"

            val typeStr = when (source.type) {
                StreamType.YOUTUBE -> "YOUTUBE"
                StreamType.WEB_VIEW -> "WEB_WATCH"
                else -> "HLS"
            }
            binding.tvSourceType.text = typeStr

            val extraDesc = buildString {
                if (source.isGeoBlocked) append("限台灣IP · ")
                if (source.isPartTime) append("非全天 · ")
                if (source.type == StreamType.WEB_VIEW) append("含前貼片廣告 · ")
                val (failureTime, reason) = getSourceFailure(source.url)
                val latestFailure = failureTime > source.lastSuccessTime || source.failureCount > 0
                val format = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
                if (latestFailure) {
                    append("最近失敗")
                    if (failureTime > 0) append(": ${format.format(Date(failureTime))}")
                    if (!reason.isNullOrBlank()) append(" · $reason")
                } else if (source.lastSuccessTime > 0L) {
                    append("最近成功: ${format.format(Date(source.lastSuccessTime))}")
                } else {
                    append("待測試")
                }
            }
            binding.tvSourceExtraInfo.text = extraDesc

            val isCurrent = position == currentActiveIndex
            binding.tvSourceSelectedBadge.isVisible = isCurrent

            binding.root.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) onSourceClick(sources[index], index)
            }
        }
    }
}
