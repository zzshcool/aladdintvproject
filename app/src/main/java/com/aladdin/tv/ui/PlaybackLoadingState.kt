package com.aladdin.tv.ui

/** Attempt-scoped loading feedback; playback verification remains with the players. */
class PlaybackLoadingState {
    enum class Phase { CONNECTING, RESOLVING, BUFFERING }

    data class Feedback(val phase: Phase, val visible: Boolean, val slow: Boolean)

    var attemptId = -1L
        private set
    var finished = true
        private set
    private var phase: Phase? = null
    private var startedAt = 0L
    private var hasPlayback = false
    private var dismissed = false
    private var adPlaying = false
    val awaitingFeedback: Boolean
        get() = phase != null && !dismissed && !adPlaying && !finished

    fun begin(attemptId: Long, now: Long, resolving: Boolean = false) {
        this.attemptId = attemptId
        phase = if (resolving) Phase.RESOLVING else Phase.CONNECTING
        startedAt = now
        hasPlayback = false
        dismissed = false
        adPlaying = false
        finished = false
    }

    fun connecting(attemptId: Long) {
        if (accepts(attemptId) && phase == Phase.RESOLVING) phase = Phase.CONNECTING
    }

    fun buffering(attemptId: Long, now: Long) {
        if (!accepts(attemptId) || adPlaying || phase != null) return
        phase = Phase.BUFFERING
        startedAt = now
    }

    fun playing(attemptId: Long) {
        if (!accepts(attemptId)) return
        phase = null
        hasPlayback = true
    }

    fun ad(attemptId: Long, playing: Boolean, now: Long) {
        if (!accepts(attemptId)) return
        adPlaying = playing
        phase = if (playing) null else Phase.CONNECTING
        startedAt = now
    }

    fun dismiss(attemptId: Long) {
        if (this.attemptId == attemptId) dismissed = true
    }

    fun finish(attemptId: Long) {
        if (this.attemptId != attemptId) return
        phase = null
        finished = true
    }

    fun feedback(now: Long): Feedback? {
        val current = phase ?: return null
        val elapsed = (now - startedAt).coerceAtLeast(0L)
        return Feedback(current, !dismissed && !adPlaying && (!hasPlayback || elapsed >= BUFFER_DELAY_MS), elapsed >= SLOW_AFTER_MS)
    }

    private fun accepts(attemptId: Long) = this.attemptId == attemptId && !finished

    companion object {
        const val BUFFER_DELAY_MS = 750L
        const val SLOW_AFTER_MS = 10_000L
    }
}
