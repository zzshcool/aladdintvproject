# 阿拉丁TV (Aladdin TV)

極致輕量、純粹、零廣告的台灣第四台直播播放器。專為「晶晨 Amlogic S905X4 安卓電視盒（5GHz Wi-Fi 連線、實體遙控器操控）」與「Android 手機」量身打造。

---

## 核心特性

- 開機秒開：啟動時優先讀取本機快取與預置種子列表（起播延遲小於 100ms），背景協程非同步向網路更新最新 M3U 列表。
- 無縫換台：針對即時直播串流客製化 LoadControl，起播緩衝僅 500ms，換台無縫銜接。
- 專屬硬解：針對晶晨 Amlogic S905X4 SoC 啟用硬體解碼優先（MediaCodec 硬解 H.264 / H.265 / AV1），釋放 CPU 負擔。
- 遙控器深度適配：左側抽屜頻道列表支援 D-pad 焦點移動放大與亮色高亮框；支援 0 至 9 實體數字鍵跳台（防抖延遲 1.2 秒）。
- 雙端操作相容：電視盒實體按鍵劫持與手機端手勢（上下滑動切台、左右滑動呼叫抽屜、點擊喚起 OSD）。
- 高容錯串流解析：具備 M3U / M3U8 串流文字容錯解析器，自動提取台號、名稱、群組並清洗無效位址。
- 純粹無負擔：徹底移除點播、電影庫、爬蟲、搜尋、歷史紀錄、彈幕與任何商業廣告程式碼。

---

## 技術架構

- 開發語言：100% Kotlin（Kotlin Coroutines + StateFlow）
- 最低版本：minSdk 21 (Android 5.0)
- 目標版本：targetSdk 34 (Android 14)
- 播放核心：`androidx.media3:media3-exoplayer:1.4.1` (含 HLS 擴展與 OkHttp 數據源)
- 網路通訊：`com.squareup.okhttp3:okhttp:4.12.0`
- UI 體系：標準 View 體系（XML + ViewBinding），無肥大 Leanback 庫負擔，實現極致流暢度

---

## 專案目錄結構

```text
app/src/main/
├── AndroidManifest.xml
├── java/com/aladdin/tv/
│   ├── config/
│   │   └── AppConfig.kt              // 集中管理直播源、快取檔名、緩衝參數與防抖時間
│   ├── data/
│   │   ├── model/Channel.kt          // 頻道實體 (台號、名稱、分組、URL)
│   │   ├── parser/M3uParser.kt       // 高容錯 M3U/M3U8 解析器
│   │   └── repository/ChannelRepository.kt // 本地快取讀寫、網路更新與秒開策略
│   ├── player/
│   │   └── AladdinPlayerManager.kt   // Media3 封裝 (Wi-Fi 低延遲 LoadControl 與自動重試)
│   ├── ui/
│   │   ├── adapter/ChannelAdapter.kt // 左側抽屜頻道列表適配器 (實體焦點放大反饋)
│   │   └── MainActivity.kt           // 遙控器按鍵劫持、手勢偵測與狀態聯動
│   └── util/
│       └── SystemUiUtils.kt          // 全螢幕沉浸式工具 (強制隱藏狀態列與導航列)
└── res/
    ├── layout/
    │   ├── activity_main.xml         // 主畫面：播放器 + 左側選台抽屜 + OSD + 數字跳台浮層
    │   └── item_channel.xml          // 頻道列表項目佈局
    ├── drawable/
    │   ├── bg_channel_focus.xml      // 電視遙控器選中高亮背景選擇器
    │   └── bg_osd_gradient.xml       // 底部 OSD 漸層半透明黑底
    └── values/
        ├── colors.xml
        ├── strings.xml
        └── themes.xml
```

---

## 遙控器按鍵操作說明

| 按鍵 | 當前狀態：全螢幕播放 | 當前狀態：頻道抽屜開啟 |
| :--- | :--- | :--- |
| 方向鍵 上 / 下 (或 CH+/CH-) | 切換上一台 / 下一台 | 向上 / 向下移動選中頻道 |
| 方向鍵 左 / MENU | 打開左側選台抽屜 | 保持在抽屜內 |
| 方向鍵 右 | 顯示底部 OSD 資訊列 | 關閉左側選台抽屜 |
| OK / ENTER | 打開左側選台抽屜 | 確認切換至選中頻道並關閉抽屜 |
| 數字鍵 (0-9) | 右上角彈出數字跳台框，防抖 1.2 秒後自動跳台 | 右上角彈出數字跳台框並執行跳台 |
| 返回鍵 (BACK) | 雙擊防誤觸退出應用程式 | 關閉抽屜並返回播放畫面 |

---

## 手機端手勢操控

- 螢幕任意處單擊：顯示 / 隱藏左側選台抽屜。
- 垂直滑動（向上 / 向下）：切換上一台 / 下一台。
- 水平滑動（向右）：滑出左側頻道抽屜。
- 水平滑動（向左）：隱藏左側頻道抽屜。

---

## 自訂配置

若需變更預設直播清單來源或調整緩衝設定，請直接編輯 `AppConfig.kt`：

- `DEFAULT_M3U_URL`：主要台灣 M3U 直播源網址。
- `BACKUP_M3U_URL`：備用直播源網址。
- `MIN_BUFFER_MS` / `BUFFER_FOR_PLAYBACK_MS`：調整起播與緩衝延遲時間。
- `NUMBER_KEY_DEBOUNCE_MS`：調整數字鍵跳台防抖等待時間。

---

## 編譯與安裝

### 前置需求
- JDK 17 或 JDK 21
- Android SDK (API 34)

### 編譯除錯版本 APK
```powershell
.\gradlew.bat assembleDebug
```
編譯產物位於：`app/build/outputs/apk/debug/app-debug.apk`。

### 透過 ADB 安裝至設備
```powershell
# 安裝至連線的 Android 手機或電視盒
.\gradlew.bat installDebug
```