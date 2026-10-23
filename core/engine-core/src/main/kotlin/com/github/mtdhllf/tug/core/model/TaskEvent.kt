package com.github.mtdhllf.tug.core.model

import kotlinx.serialization.Serializable

/** 任务事件。 */
@Serializable
public sealed class TaskEvent {
    @Serializable
    public data class Updated(val task: TaskView) : TaskEvent()

    @Serializable
    public data class Removed(val id: Long) : TaskEvent()

    @Serializable
    public data class Completed(val id: Long, val name: String, val path: String) : TaskEvent()

    @Serializable
    public data class Failed(val id: Long, val message: String) : TaskEvent()
}
