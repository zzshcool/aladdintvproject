package com.aladdin.tv.ui.adapter

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.aladdin.tv.R
import com.aladdin.tv.data.model.Channel
import com.aladdin.tv.data.model.ChannelHealth
import com.aladdin.tv.data.model.ChannelHealthStatus
import com.aladdin.tv.databinding.ItemChannelBinding

/**
 * 左側抽屜頻道列表適配器
 * 專為 Android 電視盒遙控器 D-pad 與手機觸控設計，具備焦點放大回饋、健康狀態燈與播放指示
 */
class ChannelAdapter(
    private val getChannelHealth: (Channel) -> ChannelHealth,
    private val onChannelClick: (channel: Channel, position: Int) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ChannelViewHolder>() {

    private val channels = mutableListOf<Channel>()
    private var playingIndex: Int = -1

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newChannels: List<Channel>) {
        val diffCallback = ChannelDiffCallback(this.channels, newChannels)
        val diffResult = DiffUtil.calculateDiff(diffCallback)
        this.channels.clear()
        this.channels.addAll(newChannels)
        diffResult.dispatchUpdatesTo(this)
    }

    fun setPlayingIndex(newIndex: Int) {
        val oldIndex = playingIndex
        playingIndex = newIndex
        if (oldIndex in channels.indices) {
            notifyItemChanged(oldIndex)
        }
        if (newIndex in channels.indices) {
            notifyItemChanged(newIndex)
        }
    }

    fun notifyHealthChanged(channelId: String) {
        val index = channels.indexOfFirst { it.id == channelId }
        if (index != -1) {
            notifyItemChanged(index)
        }
    }

    fun getPlayingIndex(): Int = playingIndex

    fun getItem(position: Int): Channel? = channels.getOrNull(position)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val binding = ItemChannelBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ChannelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = channels[position]
        val health = getChannelHealth(channel)
        holder.bind(channel, position == playingIndex, health)
    }

    override fun getItemCount(): Int = channels.size

    inner class ChannelViewHolder(
        private val binding: ItemChannelBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            itemView.isFocusable = true
            itemView.isFocusableInTouchMode = true

            itemView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    onChannelClick(channels[pos], pos)
                }
            }

            itemView.setOnFocusChangeListener { view, hasFocus ->
                if (hasFocus) {
                    view.animate()
                        .scaleX(1.03f)
                        .scaleY(1.03f)
                        .translationZ(6f)
                        .setDuration(120)
                        .start()
                } else {
                    view.animate()
                        .scaleX(1.0f)
                        .scaleY(1.0f)
                        .translationZ(0f)
                        .setDuration(120)
                        .start()
                }
            }
        }

        fun bind(channel: Channel, isPlaying: Boolean, health: ChannelHealth) {
            val context = itemView.context

            binding.tvChannelNum.text = String.format("%02d", channel.number)
            binding.tvChannelName.text = channel.name.ifBlank { "頻道 ${channel.number}" }

            val groupText = buildString {
                append(channel.group)
                if (channel.streamSources.size > 1) {
                    append(" · ${channel.streamSources.size}線路")
                }
                if (channel.isGeoBlocked) append(" · 限制")
                if (channel.isPartTime) append(" · 時段")
            }
            binding.tvChannelGroup.text = groupText
            binding.tvPlayingIndicator.visibility = if (isPlaying) View.VISIBLE else View.GONE
            itemView.isSelected = isPlaying

            // 綁定狀態燈顏色與文字
            val (colorRes, textRes) = when (health.status) {
                ChannelHealthStatus.NORMAL -> Pair(R.color.status_green, R.string.status_normal)
                ChannelHealthStatus.CONNECTING -> Pair(R.color.status_yellow, R.string.status_connecting)
                ChannelHealthStatus.FAILED -> Pair(R.color.status_red, R.string.status_failed)
                ChannelHealthStatus.UNKNOWN -> Pair(R.color.status_gray, R.string.status_unknown)
                ChannelHealthStatus.EXTERNAL -> Pair(R.color.status_external, R.string.status_external)
            }

            val color = ContextCompat.getColor(context, colorRes)
            binding.viewStatusDot.background?.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            val displayText = if (!health.message.isNullOrBlank() && (health.status == ChannelHealthStatus.CONNECTING || health.status == ChannelHealthStatus.FAILED)) {
                health.message
            } else {
                context.getString(textRes)
            }
            binding.tvStatusText.text = displayText
            binding.tvStatusText.setTextColor(color)
        }
    }

    private class ChannelDiffCallback(
        private val oldList: List<Channel>,
        private val newList: List<Channel>
    ) : DiffUtil.Callback() {
        override fun getOldListSize(): Int = oldList.size
        override fun getNewListSize(): Int = newList.size

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return oldList[oldItemPosition].id == newList[newItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            val oldItem = oldList[oldItemPosition]
            val newItem = newList[newItemPosition]
            return oldItem == newItem && oldItem.currentSourceIndex == newItem.currentSourceIndex
        }
    }
}
