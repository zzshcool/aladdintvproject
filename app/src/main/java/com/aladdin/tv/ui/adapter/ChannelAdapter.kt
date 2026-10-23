package com.aladdin.tv.ui.adapter

import android.annotation.SuppressLint
import android.graphics.PorterDuff
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.AsyncListDiffer
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

    private val differ = AsyncListDiffer(this, ChannelDiffCallback())
    private val channels: List<Channel> get() = differ.currentList
    private var submittedChannels = emptyList<Channel>()
    private var playingChannelId: String? = null
    private var positions = emptyMap<String, Int>()

    init {
        differ.addListListener { _, current ->
            positions = current.mapIndexed { index, channel -> channel.id to index }.toMap()
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newChannels: List<Channel>) {
        submittedChannels = newChannels.toList()
        differ.submitList(submittedChannels)
    }

    fun setPlayingIndex(newIndex: Int) {
        val nextId = submittedChannels.getOrNull(newIndex)?.id
        if (nextId == playingChannelId) return
        val oldId = playingChannelId
        playingChannelId = nextId
        oldId?.let { positions[it]?.let { index -> notifyItemChanged(index) } }
        nextId?.let { positions[it]?.let { index -> notifyItemChanged(index) } }
    }

    fun notifyHealthChanged(channelId: String) {
        positions[channelId]?.let { notifyItemChanged(it, "health") }
    }

    fun getPlayingIndex(): Int = playingChannelId?.let { positions[it] } ?: -1

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
        holder.bind(channel, channel.id == playingChannelId, health)
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

            binding.tvChannelNum.text = String.format(java.util.Locale.ROOT, "%02d", channel.number)
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
            val displayText = if (!health.message.isNullOrBlank() && health.status != ChannelHealthStatus.NORMAL) {
                health.message
            } else {
                context.getString(textRes)
            }
            binding.tvStatusText.text = displayText
            binding.tvStatusText.setTextColor(color)
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean = oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean =
            oldItem.id == newItem.id && oldItem.number == newItem.number &&
                oldItem.name == newItem.name && oldItem.group == newItem.group &&
                oldItem.isGeoBlocked == newItem.isGeoBlocked && oldItem.isPartTime == newItem.isPartTime &&
                oldItem.streamSources.size == newItem.streamSources.size
    }
}
