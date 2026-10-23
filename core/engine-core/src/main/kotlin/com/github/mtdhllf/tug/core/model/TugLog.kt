package com.github.mtdhllf.tug.core.model

/**
 * 轻量日志门面。
 *
 * JVM 测试默认打到 stdout；Android 侧在 Application 里换成 android.util.Log。
 */
public object TugLog {
    @Volatile
    public var sink: (level: String, tag: String, message: String, error: Throwable?) -> Unit =
        { level, tag, message, error ->
            val line = "[$level][$tag] $message"
            if (error != null) println(line + "\n" + error.stackTraceToString())
            else println(line)
        }

    public fun v(tag: String, message: String): Unit = sink("V", tag, message, null)

    public fun d(tag: String, message: String): Unit = sink("D", tag, message, null)

    public fun i(tag: String, message: String): Unit = sink("I", tag, message, null)

    public fun w(tag: String, message: String, error: Throwable? = null): Unit =
        sink("W", tag, message, error)

    public fun e(tag: String, message: String, error: Throwable? = null): Unit =
        sink("E", tag, message, error)
}

/** 常用 tag，方便 logcat 过滤：`adb logcat -s TugHttp TugTask TugSvc` */
public object TugTags {
    public const val HTTP: String = "TugHttp"
    public const val TASK: String = "TugTask"
    public const val SVC: String = "TugSvc"
    public const val APP: String = "TugApp"
}
