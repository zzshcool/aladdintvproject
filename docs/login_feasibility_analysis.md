# Aladdin TV 官方帳號登入可行性評估報告

## 1. 執行摘要

本報告針對在 Aladdin TV（Android TV 電視盒環境）中支援 Google / YouTube 及 ofiii 官方帳號登入進行技術可行性、安全政策及法規限制調查。

### 核心結論
1. **Google 嚴格阻擋 WebView 登入**：Google 自 2016 年起全面推行「Modernizing OAuth Interactions in Native Apps」政策，任何在 Android `WebView` 載入的 Google 登入網頁均會被攔截並回傳 `403 disallowed_useragent`（不允許的瀏覽器環境）。
2. **OAuth Device Flow 無法轉換為網頁 Session**：Google 官方提供電視裝置的 OAuth 2.0 Device Flow（RFC 8628）僅授權後端與 API 客戶端存取 YouTube Data API v3，**無法**將取得之 OAuth Access Token 注入 `WebView` 作為 YouTube 網頁播放之 Session Cookie。
3. **偽裝 User-Agent 具重大合規風險**：透過修改 User-Agent 偽裝為 Chrome Desktop 或 Safari 繞過限制，嚴重違反 Google API 服務條款與 YouTube 服務協議，且隨時會被 Google 風控機制封鎖。
4. **現行最佳合規處置**：系統不自製帳號密碼輸入介面、不收集使用者密碼；在偵測到串流需要官方登入時，明確標示「此來源需要登入，目前不支援」，並引導使用者手動切換至其他免登入線路。

---

## 2. Google 官方驗證機制與限制深入分析

### 2.1 Google disallowed_useragent 政策
- **政策緣起**：為了防範中間人攻擊（Man-In-The-Middle, MITM）與憑證竊取，Google 自 2016 年底起禁止應用程式在嵌入式 WebView 中進行 Google 帳號驗證。
- **觸發條件**：只要 HTTP 請求的 User-Agent 包含 `Version/4.0`、`wv` 或非標準瀏覽器標籤，Google 登入伺服器即判定為 WebView 並阻擋連線。
- **電視裝置影響**：Android TV 內建之 WebKit/Chromium 核心載入 `accounts.google.com` 時會遭到立即阻擋。

### 2.2 OAuth 2.0 for TV and Limited-Input Devices (RFC 8628)
Google 針對客廳電視與連網家電提供之官方授權流程為 Device Authorization Grant：
1. 電視端向 Google 授權伺服器請求一組 User Code 與驗證網址（例如 `https://www.google.com/device`）。
2. 電視端顯示 QR Code 與代碼，使用者使用手機或電腦瀏覽器開啟網址並登入授權。
3. 電視端在背景輪詢（Polling）Google Token Endpoint，取得 Access Token 與 Refresh Token。

#### 為何此流程無法解決 YouTube WebView 播放問題？
- **授權範疇（Scope）差異**：
  Device Flow 取得之 Token 僅具備呼叫 REST API（如 `https://www.googleapis.com/auth/youtube.readonly`）的權限，用於查詢訂閱清單、播放清單或頻道歷史。
- **無法建立網頁 Cookie Session**：
  YouTube 官方網頁播放器（不論是 IFrame 播放器或 `youtube.com/watch`）之觀看權限（如會員專屬內容、年齡限制影片）完全依賴 Google Accounts 發行之 Session Cookie（如 `SAPISID`、`SSID`、`HSID`、`LOGIN_INFO`）。
  OAuth Access Token **不具備**直接轉換為 Cookie 之端點或機制。
- **DRM 與播放解密**：
  即使透過 API 取得受保護影片的 Metadata，若無專屬的數位版權管理（Widevine DRM）認證管道，亦無法在第三方用戶端直接取得解碼串流。

---

## 3. 官方網頁（如 ofiii / LiTV）帳號登入可行性

### 3.1 本地業者網頁登入機制
- ofiii（歐飛）等台灣正版免費平台多數提供免登入直接收視（含前貼片廣告）。
- 若部分特定頻道或付費隨選需要登入：
  - 若其登入流程為自有帳號密碼網頁表單（非 Google / Facebook 第三方 OAuth），在 WebView 內可正常渲染與互動。
  - 使用者可透過本專案建立之「官方網頁互動模式」，使用電視遙控器之方向鍵與確認鍵操作登入。

### 3.2 資料隔離與清除機制
為保障使用者隱私與資料安全，Aladdin TV 採取嚴格資料隔離：
1. **獨立資料庫與儲存空間**：
   WebView 使用之 WebStorage、IndexedDB 與 Cookie 均由應用程式內部 Sandbox 獨立管理，不與電視系統中其他 App 共用。
2. **手動清除機制**：
   在設定面板提供「清除網頁快取與登入資料」按鈕，呼叫以下方法執行徹底清除：
   - `CookieManager.getInstance().removeAllCookies(null)`
   - `WebStorage.getInstance().deleteAllData()`
   - `WebView.clearCache(true)`
   - `WebView.clearHistory()`
3. **無密碼留存**：
   本系統不提供任何本機快取密碼或自動填充功能。

---

## 4. 產品策略與 UIUX 對應方案

依據上述技術限制，Aladdin TV 採取以下安全且透明的使用者體驗設計：

| 情境 | 偵測指標 | UIUX 呈現 | 使用者操作出口 |
| :--- | :--- | :--- | :--- |
| **YouTube 需要登入**<br>(年齡限制/私人/會員專屬) | DOM 出現 `Sign in to confirm your age` 或 JS 回呼 `onLoginRequired` | 顯示紅色提示橫幅：<br>「此來源需要官方登入，目前不支援在 TV 播放」 | 1. 選擇其他線路<br>2. 返回頻道列表 |
| **官方禁止嵌入**<br>(錯誤碼 101/150) | YouTube IFrame API 回傳 `errorCode: 150` | 顯示提示橫幅：<br>「頻道官方禁止第三方嵌入 (150)」 | 1. 以官方網頁觀看<br>2. 選擇其他線路<br>3. 返回頻道列表 |
| **自動播放被阻擋**<br>(需要手動點擊) | JS 攔截到 `playVideo().catch` 或 `autoplayBlocked` | 停止旋轉緩衝圖示，顯示：<br>「等待手動播放 (按 OK 或點擊起播)」 | 1. 手動播放 (按 OK 起播)<br>2. 選擇其他線路 |
| **串流逾時或黑畫面** | 看門狗逾時（10 秒）無進度證據 | 停止無窮 spinner，顯示：<br>「連線逾時，請重試或切換線路」 | 1. 重試此線路 (保留當前線路)<br>2. 選擇其他線路<br>3. 返回頻道列表 |

---

## 5. 總結

1. **不可行方案**：
   - 在 WebView 中強制提供 Google 帳號登入（技術被 Google 阻擋）。
   - 偽裝 User-Agent 偽裝為桌面 Chrome（違反服務條款、極易失效）。
   - 嘗試以 OAuth Token 注入 Cookie（技術不可行）。
2. **已實作之合規方案**：
   - 預設「手動選線路」，故障或需登入時絕不擅自換線。
   - 正確識別登入限制狀態，提供明確提示。
   - 完整支援線路選擇面板，讓使用者自主挑選其他可用來源。
   - 提供安全的一鍵清除網頁資料功能。
