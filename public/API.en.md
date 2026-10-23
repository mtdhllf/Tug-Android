# Tug Download Library · Public API

> Language: [简体中文](API.md) · [繁體中文](API.zh-TW.md) · English
> Version: v1.0.0

Tug splits HTTP/HTTPS and BitTorrent downloading into independently consumable Kotlin/Android modules. Applications only need to create a `TaskManager` to get queuing, concurrency, state, and progress updates.

---

## 1. Modules and Coordinates

groupId: `io.github.mtdhllf`. All five modules share the same version.

| Coordinate | Contents |
|---|---|
| `io.github.mtdhllf:tug-engine-core:1.0.0` | Engine contracts + models + `TaskManager`/`TaskStore` |
| `io.github.mtdhllf:tug-http:1.0.0` | HTTP engine (JDK `HttpURLConnection`, no third-party HTTP dependency) |
| `io.github.mtdhllf:tug-http-okhttp:1.0.0` | HTTP engine (OkHttp transport, choose one) |
| `io.github.mtdhllf:tug-store-sqlite:1.0.0` | Persistent storage for tasks and settings |
| `io.github.mtdhllf:tug-engine-torrent:1.0.0` | BitTorrent/magnet engine (libtorrent4j) |

```kotlin
dependencies {
    implementation("io.github.mtdhllf:tug-engine-core:1.0.0")
    implementation("io.github.mtdhllf:tug-http:1.0.0")          // JDK implementation
    // or
    // implementation("io.github.mtdhllf:tug-http-okhttp:1.0.0") // OkHttp implementation

    implementation("io.github.mtdhllf:tug-store-sqlite:1.0.0")   // Android, optional
    implementation("io.github.mtdhllf:tug-engine-torrent:1.0.0") // Android, optional
}
```

Requirements: pure JVM libraries require JDK 17; Android requires API 28+. The BT library includes `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` by default.

---

## 2. Initialization

```kotlin
class App : Application() {
    lateinit var manager: TaskManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        val engine = HttpDownloadEngine(scope = scope)
        // With OkHttp:
        // val engine = OkHttpDownloadEngine(scope = scope)

        val downloadDir = File(getExternalFilesDir(null), "downloads").apply { mkdirs() }

        manager = TaskManager(
            store = SqliteTaskStore(this), // InMemoryTaskStore() for demos/tests
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

Android requires `android.permission.INTERNET`. No storage permission is required when using an app-private directory.

---

## 3. TaskManager

### 3.1 Create and Operate

```kotlin
val id = manager.add(NewTask(url = "https://example.com/file.zip"))

manager.pause(id)
manager.resume(id)
manager.remove(id, deleteFiles = true)

manager.pauseAll()
manager.resumeAll()
```

Only `Queued` tasks start automatically; `Failed` tasks require an explicit `resume(id)`.

### 3.2 State and Progress

```kotlin
val tasks by manager.tasks.collectAsState()
val stats by manager.stats.collectAsState()
```

- `tasks` combines persisted fields with real-time engine progress (about 500ms updates).
- `stats` provides global speed and task-count statistics.
- `TaskView.state`: `Queued`, `FetchingMetadata`, `Downloading`, `Paused`, `Seeding`, `Completed`, `Failed`.

### 3.3 Settings

```kotlin
manager.applySettings(
    Settings().apply {
        connectionsPerTask = 4
        maxConcurrentTasks = 2
        globalDownloadLimitKib = 512 // null = unlimited
    },
)
```

Rate limit and concurrency apply to running tasks immediately; the connection count applies the next time a task starts.

`globalDownloadLimitKib = null` means unlimited; a positive value limits downloads in KiB/s.

---

## 4. Storage

- `InMemoryTaskStore`: suitable for demos/tests; data is lost when the process exits.
- `SqliteTaskStore(context)`: native Android SQLite, without Room or KSP.
- Third parties can implement `TaskStore` for Room, SQLDelight, or custom storage.

`SettingsRepository(context)` is an optional SharedPreferences-backed implementation.

---

## 5. HTTP Transport

`tug-http` does not depend on OkHttp by default and uses JDK `HttpURLConnection`.

Use `tug-http-okhttp` when you need OkHttp or want to reuse an existing OkHttp dependency:

```kotlin
val engine = OkHttpDownloadEngine(scope = scope)
// Or provide a custom OkHttpClient
val engine2 = HttpDownloadEngine(
    transport = OkHttpTransport(OkHttpClient.Builder().build()),
)
```

You can also implement `HttpTransport` to integrate another network library.

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

Local `.torrent` files can be enqueued with `NewTask(torrentBytes = bytes, kind = TaskKind.Torrent)`.

Limitations:
- Download only; no seeding/uploading.
- Includes `armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64` by default.
- Some torrents rely on tracker/DHT discovery; speed depends on peer availability.

Size optimization: keep only the ABIs you need in your app:

```kotlin
android {
    defaultConfig {
        ndk { abiFilters += listOf("arm64-v8a") }
    }
}
```

---

## 7. Versioning and Compatibility

- All five modules must use the same version (currently `1.0.0`).
- The public API of `tug-engine-core` follows semantic versioning; check Release Notes before upgrading.
- Pure JVM modules do not depend on Android or Compose.

License: Apache-2.0.
