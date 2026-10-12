package com.aladdin.tv.util

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager

/**
 * 裝置硬體解碼與螢幕能力檢測工具
 * 負責探測真實輸出螢幕與硬體 MediaCodec 是否真正具備 4K 解碼與顯示能力
 */
object DeviceCapabilityUtils {

    data class CapabilityResult(
        val isSupported: Boolean,
        val downgradeReason: String
    )

    /**
     * 綜合判定裝置是否具備 4K 播放能力 (區分當前實際輸出模式與硬體解碼)
     */
    fun check4kCapability(context: Context): CapabilityResult {
        val displayResult = checkDisplayOutput(context)
        if (!displayResult.isSupported) {
            return displayResult
        }
        val codecResult = checkCodec4kSupport()
        if (!codecResult.isSupported) {
            return codecResult
        }
        return CapabilityResult(true, "裝置完全支援 4K 輸出與硬體解碼")
    }

    fun is4kPlaybackSupported(context: Context): Boolean {
        return check4kCapability(context).isSupported
    }

    /**
     * 檢測當前螢幕輸出模式 (區分面板支援模式與當前實際輸出模式)
     */
    fun checkDisplayOutput(context: Context): CapabilityResult {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                ?: return CapabilityResult(false, "無法取得視窗管理服務")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val display = context.display ?: return CapabilityResult(false, "無有效顯示器資訊")
                val currentMode = display.mode
                // 優先檢測當前實際生效之顯示模式 (例如電視盒當前 HDMI 解析度)
                if (currentMode.physicalWidth < 3840 && currentMode.physicalHeight < 2160) {
                    val hasSupported4k = display.supportedModes.any { it.physicalWidth >= 3840 || it.physicalHeight >= 2160 }
                    val note = if (hasSupported4k) "面板支援 4K，但目前 HDMI/系統實際輸出為 ${currentMode.physicalWidth}x${currentMode.physicalHeight}" else "顯示器硬體未達 4K"
                    return CapabilityResult(false, note)
                }
                CapabilityResult(true, "目前螢幕輸出為 4K")
            } else {
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                if (metrics.widthPixels >= 3840 || metrics.heightPixels >= 2160) {
                    CapabilityResult(true, "目前螢幕輸出為 4K")
                } else {
                    CapabilityResult(false, "目前輸出解析度為 ${metrics.widthPixels}x${metrics.heightPixels} (未達 4K)")
                }
            }
        } catch (e: Exception) {
            CapabilityResult(false, "檢測螢幕解析度異常: ${e.message}")
        }
    }

    /**
     * 檢測一般硬體 MediaCodec 是否支援 4K 硬解 (HEVC / AV1 / AVC)
     */
    fun checkCodec4kSupport(): CapabilityResult {
        val targetMimeTypes = listOf(
            "video/hevc" to "HEVC/H.265",
            "video/av01" to "AV1",
            "video/avc" to "AVC/H.264"
        )

        for ((mime, name) in targetMimeTypes) {
            if (isFormatCodecSupported(mime, 3840, 2160)) {
                return CapabilityResult(true, "硬體支援 $name 4K 硬解")
            }
        }
        return CapabilityResult(false, "硬體晶片不支援任何主流編碼 (HEVC/AV1/AVC) 之 4K 硬解")
    }

    /**
     * 根據實際影片編碼 (MIME)、解析度及幀率檢測解碼器支援度
     * 不因其他編碼支援 4K 就認定當前影片可播
     */
    fun isFormatCodecSupported(mimeType: String, width: Int, height: Int, frameRate: Double? = null): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in codecList.codecInfos) {
                if (info.isEncoder) continue
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !info.isHardwareAccelerated) {
                    continue
                }

                try {
                    val caps = info.getCapabilitiesForType(mimeType)
                    val videoCaps = caps.videoCapabilities ?: continue
                    if (videoCaps.isSizeSupported(width, height)) {
                        if (frameRate != null && frameRate > 0) {
                            if (!videoCaps.areSizeAndRateSupported(width, height, frameRate)) {
                                continue
                            }
                        }
                        return true
                    }
                } catch (_: Exception) {}
            }
            false
        } catch (_: Exception) {
            false
        }
    }
}
