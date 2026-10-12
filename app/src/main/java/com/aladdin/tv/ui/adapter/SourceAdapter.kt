package com.aladdin.tv.ui.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
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
    private val onSourceClick: (StreamSource, Int) -> Unit
) : RecyclerView.Adapter<SourceAdapter.SourceViewHolder>() {

    private var sources: List<StreamSource> = emptyList()
    private var currentActiveIndex: Int = 0

    fun submitSources(newSources: List<StreamSource>, activeIndex: Int) {
        this.sources = newSources
        this.currentActiveIndex = activeIndex
        notifyDataSetChanged()
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
            binding.tvSourceIndex.text = String.format("%02d", position + 1)
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
                if (source.lastSuccessTime > 0L) {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(source.lastSuccessTime))
                    append("最近成功: $timeStr")
                } else if (source.failureCount > 0) {
                    append("失敗累計: ${source.failureCount}次")
                } else {
                    append("待測試")
                }
            }
            binding.tvSourceExtraInfo.text = extraDesc

            val isCurrent = position == currentActiveIndex
            binding.tvSourceSelectedBadge.isVisible = isCurrent

            binding.root.setOnClickListener {
                onSourceClick(source, position)
            }
        }
    }
}
