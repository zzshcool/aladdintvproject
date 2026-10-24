# 阿拉丁TV (Aladdin TV)

> **阿拉丁TV，如你所願** · *Aladdin TV: Live Streams as You Wish.*

台灣電視直播播放器，支援 Android TV／電視盒與 Android 手機。整合公開 M3U 串流、YouTube 官方直播及 ofiii／四季線上等官方觀看頁面。官方來源可能含廣告、登入或地區限制；收錄不代表所有頻道或節目都免費或持續可用。

---

## 核心特性

- 啟動與快取：8-bit 神燈吐煙後顯示 App 圖示，約 3.5 秒，可點擊或按確認略過；脫離系統動畫縮放限制穩定播放。先讀取本機快取與種子資料，再背景更新來源。
- 多來源播放：Media3 播放直接串流，YouTube 官方播放器與相容 WebView 處理官方觀看頁面；實際起播時間取決於裝置、網路、廣告及來源。
- 手動選線：同一頻道合併多條線路，記住使用者選擇；自動備援可在設定中選擇開啟。
- 播放狀態：綠／黃／紅／灰燈呈現播放進度、連線及失敗狀態；未驗證或過期資訊不應視為保證可播。
- 畫質偏好：自動、最高 1080p 或允許 4K；原生解碼能力由裝置及來源決定，網頁播放器由提供者控制畫質。
- 雙端操作：電視遙控器 D-pad、數字跳台與手機觸控選單／手勢，支援完整畫面或填滿裁切。
- 個人設定：收藏、最近觀看、隱藏與恢復頻道、跟隨系統／淺色／深色外觀、金／藍／綠／紫主題色、介面多語言切換（跟隨系統／繁體中文／English），以及開機動畫開關（開啟播放 3.5 秒神燈動畫，關閉直接呈現精簡 LOGO 畫面）。
- 資料管理：本機快取及播放診斷，提供 WebView 快取／登入資料清除入口。設定面板於首次開啟時載入。

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
│   │   └── repository/ChannelRepository.kt // 本地快取讀寫、網路更新與多線路合併
│   ├── player/
│   │   └── AladdinPlayerManager.kt   // Media3 封裝 (Wi-Fi 低延遲 LoadControl 與自動重試)
│   ├── ui/
│   │   ├── adapter/ChannelAdapter.kt // 左側抽屜頻道列表適配器 (實體焦點放大反饋)
│   │   ├── view/LampSplashView.kt  // 原生像素神燈啟動動畫
│   │   ├── view/YouTubeIframePlayerView.kt // 官方播放器與網頁觀看
│   │   └── MainActivity.kt           // 遙控器按鍵劫持、手勢偵測與狀態聯動
│   └── util/
│       └── SystemUiUtils.kt          // 全螢幕沉浸式工具 (強制隱藏狀態列與導航列)
└── res/
    ├── layout/
    │   ├── activity_main.xml         // 主畫面：播放器 + 左側選台抽屜 + OSD + 數字跳台浮層
    │   ├── panel_settings.xml        // 延後載入的設定面板
    │   ├── item_source.xml           // 手動線路清單
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

- 右上角選單按鈕：顯示／隱藏觸控工具列，可選擇頻道、線路、設定、畫面縮放或離開。
- 官方網頁操作模式下，觸控事件由該網站處理；使用返回／選單回到 App 控制。
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
- JDK 17
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
## 隱私政策與公開頁面 (Privacy Policy)

- [公開隱私政策 (繁體中文 / English)](https://zzshcool.github.io/aladdintvproject/privacy-policy.html)
  - 繁體中文版錨點：[https://zzshcool.github.io/aladdintvproject/privacy-policy.html#zh-tw](https://zzshcool.github.io/aladdintvproject/privacy-policy.html#zh-tw)
  - 英文版錨點 (English Anchor)：[https://zzshcool.github.io/aladdintvproject/privacy-policy.html#en](https://zzshcool.github.io/aladdintvproject/privacy-policy.html#en)
- 頁面原始檔：[docs/privacy-policy.html](docs/privacy-policy.html)
- 雙語支援：提供繁體中文與英文雙語切換，內容符合 Google Play、Amazon Appstore 及各大 Android 應用商店的審查規範。
- App 內整合：App「設定」面板中提供「隱私政策」按鈕，可直接透過裝置瀏覽器開啟；若裝置無瀏覽器則彈出網址對話框。
- 商店發布與資料安全（Data Safety）審查指引：
  - 個人資料收集：無註冊系統，不收集使用者姓名、Email、電話、地理位置或通訊錄等 PII。
  - 設備識別碼：不收集或傳輸 IMEI、MAC 位址、Android ID 等敏感硬體識別碼。
  - 權限宣告：僅宣告 `INTERNET`、`ACCESS_NETWORK_STATE`、`WAKE_LOCK` 核心播放權限；未申請相機、麥克風、位置或外部儲存讀取等敏感權限。
  - 資料儲存與刪除：所有收藏、線路偏好與日誌均存於裝置本機沙盒；提供 App 內「清除網頁快取與登入資料」及 Android 系統清除資料途徑。

GitHub Pages 使用獨立的 `codex/privacy-pages` 分支根目錄發布，僅放入政策 HTML；部署時須同步該分支，單獨修改 `main` 的 `docs/privacy-policy.html` 不會自動發布。

## 驗證與發布

```powershell
.\gradlew.bat assembleDebug lintDebug
```

啟動動畫已在測試手機驗證自動結束、確認鍵／觸控略過及背景恢復。這些檢查不代表全部頻道或所有電視盒均已驗證。部分版本更新／裝置設計提示有具理由的個別 Lint 例外。

`app-debug.apk` 僅供開發測試。商店發布應另行設定並安全保存正式簽章金鑰、建立正式簽署 APK，後續更新使用相同簽章並遞增 versionCode。不得將金鑰、密碼、Token 或 local.properties 提交到公開儲存庫。
