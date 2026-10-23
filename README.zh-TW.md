<h1 align="center">
  <img src="public/icon-512.png" width="40" alt="Tug-Android application icon"> Tug-Android
</h1>

<p align="center">多連線 HTTP/HTTPS 與 BitTorrent 下載器 · 可重複使用的 Kotlin 下載程式庫</p>

<p align="center">
  <a href="README.md">简体中文</a> · <b>繁體中文</b> · <a href="README.en.md">English</a>
</p>

<p align="center">
  <a href="https://central.sonatype.com/artifact/io.github.mtdhllf/tug-engine-core"><img alt="Maven Central version" src="https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fmtdhllf%2Ftug-engine-core%2Fmaven-metadata.xml&query=%2Fmetadata%2Fversioning%2Frelease&label=release"></a>
  <img alt="Android 9+" src="https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white">
  <img alt="Kotlin 2.2" src="https://img.shields.io/badge/Kotlin-2.2-7F52FF?logo=kotlin&logoColor=white">
  <img alt="Jetpack Compose" src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4?logo=jetpackcompose&logoColor=white">
  <a href="public/LICENSE"><img alt="License Apache-2.0" src="https://img.shields.io/badge/License-Apache--2.0-blue"></a>
</p>

---

Tug-Android 是 [Tug](https://github.com/mtdhllf/Tug-Desktop) 的 Android 版本。桌面端（Rust）著重於 BitTorrent 與 Windows 整合；Android 端以 **HTTP/HTTPS 多連線下載、續傳、背景下載** 為核心，同時把下載核心拆成可對外重複使用的輕量 Kotlin 程式庫。

### 專案成果

- 完成 HTTP/HTTPS 多連線下載、續傳、背景下載與公用目錄發布。
- 完成 BitTorrent/magnet 下載（libtorrent4j），提供 Files/Peers/Trackers 詳細資料。
- 下載核心拆為 5 個可重複使用的程式庫（`tug-engine-core`、`tug-http`、`tug-http-okhttp`、`tug-store-sqlite`、`tug-engine-torrent`），包含 JDK / OkHttp 兩套 HTTP 實作。
- 提供 `sample-http`、`sample-torrent` 兩個最小整合 Demo，涵蓋初始化、設定、清單狀態、暫停/繼續/刪除；HTTP Demo 同時整合 JDK/OkHttp 兩套實作。

---

## 螢幕截圖

| 首頁 | 任務清單 | 設定 | 新增下載 |
|---|---|---|---|
| ![首頁](public/screenshots/zh/home.png) | ![任務](public/screenshots/zh/tasks.png) | ![設定](public/screenshots/zh/settings.png) | ![新增](public/screenshots/zh/add.png) |

---

## 功能

### 下載
- HTTP/HTTPS 多連線分段下載，連線數 1–32
- 續傳：`.part` + 中繼資料（分段完成 + 分段內位移 checkpoint，1 秒粒度）
- 暫停/繼續、失敗重試、自動重新導向（≤10 次）
- ETag / Last-Modified / 檔案大小一致性驗證
- SHA-256 驗證（引擎支援，UI 待整合）
- 全域下載限速、僅 Wi-Fi、斷網自動暫停/恢復
- 完成後發布到公用下載目錄 **`Download/Tug`**（Android 10+ 走 MediaStore，9 及以下直寫）
- 絕不覆寫既有檔案（同名自動 `name (1).ext`）

### 任務與介面
- 首頁（統計 / 快速新增 / 進行中 / 最近完成）
- 任務清單：搜尋、篩選（全部/下載中/已完成/失敗）、排序（最近/速度/名稱）、長按多選批次操作
- 任務詳細資料：完整進度、速度、ETA、儲存位置、複製連結、開啟/分享檔案、刪除確認
- 設定六個子頁面：下載、網路、通知、外觀（淺色/深色/跟隨系統/動態取色）、背景與電池、關於
- 前景服務 + 通知：進度、完成/失敗、全部暫停；點擊通知直達任務詳細資料
- 分享接收（連結/種子）與 `tug://task/{id}` 深度連結
- 多語言介面（简体中文、繁體中文、English、Español、हिन्दी、العربية）

### 下載核心程式庫

五個程式庫（groupId `io.github.mtdhllf`；已發布至 Maven Central，亦可於儲存庫內直接使用 project 相依）：

當前版本：![Maven Central](https://img.shields.io/badge/dynamic/xml?url=https%3A%2F%2Frepo1.maven.org%2Fmaven2%2Fio%2Fgithub%2Fmtdhllf%2Ftug-engine-core%2Fmaven-metadata.xml&query=%2Fmetadata%2Fversioning%2Frelease&label=release)

| 座標 | 內容 |
|---|---|
| `io.github.mtdhllf:tug-engine-core` | 引擎契約 + 任務/狀態/設定/事件模型 + `TaskManager`/`TaskStore` |
| `io.github.mtdhllf:tug-http` | HTTP 多連線分段引擎（JDK HttpURLConnection 預設實作） |
| `io.github.mtdhllf:tug-http-okhttp` | HTTP 引擎的 OkHttp 實作（已有 OkHttp 相依時二選一） |
| `io.github.mtdhllf:tug-store-sqlite` | 任務與設定的持久化儲存 |
| `io.github.mtdhllf:tug-engine-torrent` | BitTorrent/magnet 引擎（libtorrent4j） |

```kotlin
implementation("io.github.mtdhllf:tug-engine-core:1.0.0")
implementation("io.github.mtdhllf:tug-http:1.0.0")          // 或
implementation("io.github.mtdhllf:tug-http-okhttp:1.0.0")
implementation("io.github.mtdhllf:tug-store-sqlite:1.0.0")  // Android，可選
implementation("io.github.mtdhllf:tug-engine-torrent:1.0.0") // Android，可選
```

> 公開 API 與整合說明請見 [`public/API.zh-TW.md`](public/API.zh-TW.md)。

### Demo 專案

| 模組 | 引擎 | 示範內容 |
|---|---|---|
| `sample-http` | `HttpDownloadEngine`（JDK）+ `OkHttpDownloadEngine` | 初始化、常用設定、下載清單狀態、暫停/繼續/刪除 |
| `sample-torrent` | `TorrentDownloadEngine`（libtorrent4j） | magnet / `.torrent` URL / 本機 `.torrent` 檔案、清單狀態、Peers、暫停/繼續/刪除 |

`sample-http` 同時相依於 `tug-http` 與 `tug-http-okhttp`，於 `DemoApp.kt` 中切換：

```kotlin
// false = JDK HttpURLConnection；true = OkHttp
private const val USE_OKHTTP = false
```

兩個 Demo 都使用 `InMemoryTaskStore`（程式重新啟動後任務不保留）與 App 私有下載目錄，不包含通知/前景服務/MediaStore 發布，**重點展示程式庫 API**。

```bash
./gradlew :sample-http:assembleDebug :sample-torrent:assembleDebug
adb install -r -t sample-http/build/outputs/apk/debug/sample-http-debug.apk
adb install -r -t sample-torrent/build/outputs/apk/debug/sample-torrent-debug.apk
```

也可以在 Android Studio 中直接執行兩個模組。

### 程式庫整合 / API 說明

#### 1. 初始化

在 `Application` 或 DI 容器中建立一次 `TaskManager`（負責佇列、並行、狀態、進度）：

```kotlin
class App : Application() {
    lateinit var manager: TaskManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val engine = HttpDownloadEngine(scope = scope)
        // 已有 OkHttp 時：val engine = OkHttpDownloadEngine(scope = scope)
        // 需要持久化時：val store = SqliteTaskStore(this)
        val downloadDir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }

        manager = TaskManager(
            store = InMemoryTaskStore(),          // 或 SqliteTaskStore(this)
            engines = mapOf("http" to engine),
            scope = scope,
            defaultDownloadDir = downloadDir.absolutePath,
            initialSettings = Settings(connectionsPerTask = 4, maxConcurrentTasks = 2),
            deleter = TaskFileDeleter { task -> File(task.path).delete() },
        )
        scope.launch { manager.bootstrap() }
    }
}
```

Android 需要 `INTERNET` 權限。

#### 2. 常用設定

```kotlin
manager.applySettings(
    Settings().apply {
        connectionsPerTask = 4
        maxConcurrentTasks = 2
        globalDownloadLimitKib = 512 // null = 不限速
    },
)
```

設定對執行中的任務立即生效（連線數於下次啟動任務時生效，限速/並行立即生效）。

#### 3. 任務操作

```kotlin
val id = manager.add(NewTask(url = "https://example.com/file.zip")) // 入隊並自動開始
manager.pause(id)
manager.resume(id)
manager.remove(id, deleteFiles = true)

// Compose 中觀察列表（也可以用 collect 到自己的狀態層）
val tasks by manager.tasks.collectAsState()
val stats by manager.stats.collectAsState()
```

狀態流語意：
- 只有 `Queued` 會自動啟動；`Failed` 需要明確呼叫 `resume(id)` 重試。
- `manager.tasks` 同時包含持久化欄位與引擎即時進度（約 500ms 更新），UI 不需要自行輪詢。
- 暫停/刪除會取消進行中的連線；完成後任務會留在清單，除非呼叫 `remove`。

#### 4. 儲存與 BT

- `tug-store-sqlite` 提供 `SqliteTaskStore`（Android 原生 SQLite）與 `SettingsRepository`（SharedPreferences）；第三方也可以只實作 `TaskStore` 介面。
- BT 需要額外相依 `tug-engine-torrent`，並把引擎註冊到同一個 `TaskManager`：

```kotlin
val bt = TorrentDownloadEngine(
    workDir = File(filesDir, "torrent").apply { mkdirs() },
    scope = scope,
)
val manager = TaskManager(
    store = SqliteTaskStore(this),
    engines = mapOf("http" to httpEngine, "torrent" to bt),
    scope = scope,
    defaultDownloadDir = downloadDir.absolutePath,
)
manager.add(NewTask(url = "magnet:?xt=urn:btih:...", kind = TaskKind.Torrent))
```

BT 程式庫預設包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。  
如需縮小應用程式大小，在應用程式端依需求裁減：

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

完整契約與語意請見 [`public/API.zh-TW.md`](public/API.zh-TW.md)。

---

## 專案結構

```
Tug-Android/
├─ app/                       Compose UI + 前景服務 + 儲存發布
├─ core/
│  ├─ engine-core/             引擎契約 + 模型 + TaskManager/TaskStore（tug-engine-core）
│  ├─ engine-http/            純 JVM HTTP 引擎（tug-http，JDK 傳輸）
│  ├─ engine-okhttp/          OkHttp 傳輸實作（tug-http-okhttp）
│  ├─ store-sqlite/           原生 SQLite TaskStore + 設定（tug-store-sqlite）
│  └─ engine-torrent/         BitTorrent 引擎（tug-engine-torrent）
├─ sample-http/               JDK + OkHttp HTTP 接入 Demo（Compose）
├─ sample-torrent/            BT/magnet 接入 Demo（Compose）
├─ samples/jvm-downloader/    純 JVM CLI 範例
└─ gradle/                    版本目錄與 wrapper
```

關鍵相依：Kotlin 2.2.10 / AGP 9.4.1 / Compose BOM / kotlinx-coroutines / kotlinx-serialization。
HTTP 核心預設使用 JDK `HttpURLConnection`（零第三方 HTTP 相依），宿主可透過 `HttpTransport` 注入自己的用戶端實作。
任務程式庫使用 **Android 原生 SQLite（SQLiteOpenHelper）**，無 Room/KSP。

---

## 建置

環境需求：
- JDK 17（Gradle toolchain）與 JDK 25（Gradle daemon，見 `gradle/gradle-daemon-jvm.properties`）
- Android SDK：platform **android-37**、build-tools **37.0.0**
- 於 `local.properties` 內設定 `sdk.dir`

```bash
# Debug
./gradlew :app:assembleDebug

# 單元測試
./gradlew :core:engine-core:test :core:engine-http:test :core:engine-okhttp:test :app:testDebugUnitTest

# Demo
./gradlew :sample-http:assembleDebug :sample-torrent:assembleDebug

# 儀器測試（先手動啟動 App，否則 ActivityScenario 可能拿不到前景視窗）
./gradlew :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.github.mtdhllf.tug.ui.MainUiTest \
  com.github.mtdhllf.tug.test/androidx.test.runner.AndroidJUnitRunner

# Release（AAB / APK）
./gradlew :app:assembleRelease
```

### 簽署設定

在儲存庫根目錄建立 `keystore.properties`：

```properties
storeFile=/absolute/path/to/app.jks
storePassword=******
keyAlias=******
keyPassword=******
```

---

## 測試

| 層級 | 涵蓋範圍 |
|---|---|
| JVM 單元測試 | 引擎 25 例（分段/續傳/暫停/單流/限速/假完成）、編排 5 例、OkHttp Transport、網路策略、格式化、孤兒清理 |
| 儀器測試 | 原生 SQLite 持久化、設定持久化、發布到公用目錄、端對端下載、App 組裝、暫停續傳、Compose UI（導覽/新增驗證） |
| Demo 驗收 | `sample-http` / `sample-torrent` 實機啟動、初始化/設定/清單/新增 UI；暫停/繼續/刪除已實作 |
| 實機驗收 | 10MB/20MB 實際下載寫入磁碟、100MB 暫停、通知、深度連結、release 安裝 |

> UI 案例每次操作之間有 700ms 停留，便於肉眼觀察。

---

## 路線圖

- ✅ v1.0.0：HTTP/HTTPS 多連線下載、續傳、背景下載、公用目錄發布、完整 UI、深度連結/分享、BitTorrent/magnet（libtorrent4j）、檔案選擇開關、Files/Peers/Trackers 詳細資料、5 個可重複使用的程式庫與 Demo、多語言介面（6 種語言）
- 規劃中：fastresume 資料最佳化、網路 BT 可重複測試、背景 BT/FGS

---

## License

Apache-2.0，見 [LICENSE](public/LICENSE)。

