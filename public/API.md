# Tug Download Library · Public API

> 语言：[English](API.en.md) · [繁體中文](API.zh-TW.md) · 简体中文
> 版本：v1.0.0

Tug 下载库把 HTTP/HTTPS、BitTorrent 下载能力拆成可独立依赖的 Kotlin/Android 模块。应用层只需创建 `TaskManager`，即可获得队列、并发、状态与进度更新。

---

## 1. 模块与坐标

groupId：`io.github.mtdhllf`，五个模块使用同一版本号。

| 坐标 | 内容 |
|---|---|
| `io.github.mtdhllf:tug-engine-core:1.0.0` | 引擎契约 + 模型 + `TaskManager`/`TaskStore` |
| `io.github.mtdhllf:tug-http:1.0.0` | HTTP 引擎（JDK `HttpURLConnection`，零三方 HTTP 依赖） |
| `io.github.mtdhllf:tug-http-okhttp:1.0.0` | HTTP 引擎（OkHttp 传输，二选一） |
| `io.github.mtdhllf:tug-store-sqlite:1.0.0` | 任务与设置的持久化存储 |
| `io.github.mtdhllf:tug-engine-torrent:1.0.0` | BitTorrent/magnet 引擎（libtorrent4j） |

```kotlin
dependencies {
    implementation("io.github.mtdhllf:tug-engine-core:1.0.0")
    implementation("io.github.mtdhllf:tug-http:1.0.0")          // JDK 实现
    // 或
    // implementation("io.github.mtdhllf:tug-http-okhttp:1.0.0") // OkHttp 实现

    implementation("io.github.mtdhllf:tug-store-sqlite:1.0.0")   // Android，可选
    implementation("io.github.mtdhllf:tug-engine-torrent:1.0.0") // Android，可选
}
```

要求：纯 JVM 库需要 JDK 17；Android 最低 API 28。BT 库默认包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。

---

## 2. 初始化

```kotlin
class App : Application() {
    lateinit var manager: TaskManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val engine = HttpDownloadEngine(scope = scope)
        // 已有 OkHttp 时：
        // val engine = OkHttpDownloadEngine(scope = scope)

        val downloadDir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }

        manager = TaskManager(
            store = SqliteTaskStore(this), // Demo / 测试可用 InMemoryTaskStore()
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

Android 需要 `android.permission.INTERNET`；使用 App 私有目录时不需要存储权限。

---

## 3. TaskManager

### 3.1 新建与操作

```kotlin
val id = manager.add(NewTask(url = "https://example.com/file.zip"))

manager.pause(id)
manager.resume(id)
manager.remove(id, deleteFiles = true)

manager.pauseAll()
manager.resumeAll()
```

只有 `Queued` 状态会自动开始；`Failed` 需要显式 `resume(id)` 重试。

### 3.2 状态与进度

```kotlin
val tasks by manager.tasks.collectAsState()
val stats by manager.stats.collectAsState()
```

- `tasks` 合并持久化字段与引擎实时进度（约 500ms 更新）。
- `stats` 为全局速度/任务数统计。
- `TaskView.state`：`Queued`、`FetchingMetadata`、`Downloading`、`Paused`、`Seeding`、`Completed`、`Failed`。

### 3.3 设置

```kotlin
manager.applySettings(
    Settings().apply {
        connectionsPerTask = 4
        maxConcurrentTasks = 2
        globalDownloadLimitKib = 512 // null = 不限速
    },
)
```

限速/并发对运行中任务立即生效；连接数在任务下次启动时生效。

`globalDownloadLimitKib` 为 `null` 时表示不限速，设置正数时按 KiB/s 限速。

---

## 4. 存储

- `InMemoryTaskStore`：适合 Demo/测试，进程退出后不保留。
- `SqliteTaskStore(context)`：Android 原生 SQLite，无 Room/KSP。
- 第三方可实现 `TaskStore` 接口，接入 Room、SQLDelight 或自己的存储。

`SettingsRepository(context)` 是 SharedPreferences 实现，可选。

---

## 5. HTTP 传输层

默认 `tug-http` 不依赖 OkHttp，使用 JDK `HttpURLConnection`。

需要 OkHttp 或想复用项目已有 OkHttp 时，使用 `tug-http-okhttp`：

```kotlin
val engine = OkHttpDownloadEngine(scope = scope)
// 或自定义 OkHttpClient
val engine2 = HttpDownloadEngine(
    transport = OkHttpTransport(OkHttpClient.Builder().build()),
)
```

也可以实现 `HttpTransport` 接口，接入其他网络库。

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

本地 `.torrent` 文件可通过 `NewTask(torrentBytes = bytes, kind = TaskKind.Torrent)` 入队。

限制：
- 仅下载，不做种/上传；
- 默认包含 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`；
- 部分种子依赖 tracker/DHT，速度受 peer 数量影响。

体积优化：应用侧按需保留 ABI：

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

---

## 7. 版本与兼容

- 五个模块必须使用相同版本号（当前 `1.0.0`）。
- `tug-engine-core` 的公开 API 遵循语义化版本；升级前请查看 Release Notes。
- 纯 JVM 模块不包含 Android/Compose 依赖。

License: Apache-2.0.
