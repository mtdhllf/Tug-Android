# Tug Download Library · Public API

> 語言：[简体中文](API.md) · [English](API.en.md) · 繁體中文
> 版本：v1.0.0

Tug 下載程式庫把 HTTP/HTTPS、BitTorrent 下載能力拆成可獨立相依的 Kotlin/Android 模組。應用層只需建立 `TaskManager`，即可取得佇列、並行、狀態與進度更新。

---

## 1. 模組與座標

groupId：`io.github.mtdhllf`，五個模組使用同一版本號。

| 座標 | 內容 |
|---|---|
| `io.github.mtdhllf:tug-engine-core:1.0.0` | 引擎契約 + 模型 + `TaskManager`/`TaskStore` |
| `io.github.mtdhllf:tug-http:1.0.0` | HTTP 引擎（JDK `HttpURLConnection`，零第三方 HTTP 相依） |
| `io.github.mtdhllf:tug-http-okhttp:1.0.0` | HTTP 引擎（OkHttp 傳輸，二選一） |
| `io.github.mtdhllf:tug-store-sqlite:1.0.0` | 任務與設定的持久化儲存 |
| `io.github.mtdhllf:tug-engine-torrent:1.0.0` | BitTorrent/magnet 引擎（libtorrent4j） |

```kotlin
dependencies {
    implementation("io.github.mtdhllf:tug-engine-core:1.0.0")
    implementation("io.github.mtdhllf:tug-http:1.0.0")          // JDK 實作
    // 或
    // implementation("io.github.mtdhllf:tug-http-okhttp:1.0.0") // OkHttp 實作

    implementation("io.github.mtdhllf:tug-store-sqlite:1.0.0")   // Android，可選
    implementation("io.github.mtdhllf:tug-engine-torrent:1.0.0") // Android，可選
}
```

要求：純 JVM 程式庫需要 JDK 17；Android 最低 API 28。BT 程式庫預設包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。

---

## 2. 初始化

```kotlin
class App : Application() {
    lateinit var manager: TaskManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val engine = HttpDownloadEngine(scope = scope)
        // 已有 OkHttp 時：
        // val engine = OkHttpDownloadEngine(scope = scope)

        val downloadDir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }

        manager = TaskManager(
            store = SqliteTaskStore(this), // Demo / 測試可用 InMemoryTaskStore()
            engines = mapOf("http" to engine),
            scope = scope,
            defaultDownloadDir = downloadDir.absolutePath,
            initialSettings = Settings(
                connectionsPerTask = 4,
                maxConcurrentTasks = 2,
            ),
            deleter = TaskFileDeleter { task ->
                File(task.path).delete()
            },
        )
        scope.launch { manager.bootstrap() }
    }
}
```

Android 需要 `android.permission.INTERNET`；使用 App 私有目錄時不需要儲存權限。

---

## 3. TaskManager

### 3.1 建立與操作

```kotlin
val id = manager.add(NewTask(url = "https://example.com/file.zip"))

manager.pause(id)
manager.resume(id)
manager.remove(id, deleteFiles = true)

manager.pauseAll()
manager.resumeAll()
```

只有 `Queued` 狀態會自動開始；`Failed` 需要明確呼叫 `resume(id)` 重試。

### 3.2 狀態與進度

```kotlin
val tasks by manager.tasks.collectAsState()
val stats by manager.stats.collectAsState()
```

- `tasks` 合併持久化欄位與引擎即時進度（約 500ms 更新）。
- `stats` 為全域速度/任務數統計。
- `TaskView.state`：`Queued`、`FetchingMetadata`、`Downloading`、`Paused`、`Seeding`、`Completed`、`Failed`。

### 3.3 設定

```kotlin
manager.applySettings(
    Settings().apply {
        connectionsPerTask = 4
        maxConcurrentTasks = 2
        globalDownloadLimitKib = 512 // null = 不限速
    },
)
```

限速/並行對執行中任務立即生效；連線數於任務下次啟動時生效。

`globalDownloadLimitKib` 為 `null` 時表示不限速，設定正數時以 KiB/s 限速。

---

## 4. 儲存

- `InMemoryTaskStore`：適合 Demo/測試，程式結束後不保留。
- `SqliteTaskStore(context)`：Android 原生 SQLite，無 Room/KSP。
- 第三方可實作 `TaskStore` 介面，整合 Room、SQLDelight 或自己的儲存。

`SettingsRepository(context)` 是 SharedPreferences 實作，可選用。

---

## 5. HTTP 傳輸層

預設 `tug-http` 不相依 OkHttp，使用 JDK `HttpURLConnection`。

需要 OkHttp 或想重用專案既有 OkHttp 時，使用 `tug-http-okhttp`：

```kotlin
val engine = OkHttpDownloadEngine(scope = scope)
// 或自訂 OkHttpClient
val engine2 = HttpDownloadEngine(
    transport = OkHttpTransport(OkHttpClient.Builder().build()),
)
```

也可以實作 `HttpTransport` 介面，整合其他網路程式庫。

---

## 6. BitTorrent

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
manager.add(NewTask(url = "https://example.com/file.torrent", kind = TaskKind.Torrent))
```

本機 `.torrent` 檔案可透過 `NewTask(torrentBytes = bytes, kind = TaskKind.Torrent)` 加入佇列。

限制：
- 僅下載，不做種/上傳；
- 預設包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`；
- 部分種子相依 tracker/DHT，速度受 peer 數量影響。

大小最佳化：應用程式端依需求保留 ABI：

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

---

## 7. 版本與相容性

- 五個模組必須使用相同版本號（目前 `1.0.0`）。
- `tug-engine-core` 的公開 API 遵循語意化版本；升級前請查看 Release Notes。
- 純 JVM 模組不包含 Android/Compose 相依。

License: Apache-2.0.
