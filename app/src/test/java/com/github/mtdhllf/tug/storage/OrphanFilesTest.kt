package com.github.mtdhllf.tug.storage

import com.github.mtdhllf.tug.core.model.TaskKind
import com.github.mtdhllf.tug.core.model.TaskState
import com.github.mtdhllf.tug.core.model.TaskView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrphanFilesTest {

    @Test
    fun clean_removesOnlyOrphanSidecars() {
        val staging = File.createTempFile("tug-orphan", null).apply {
            delete()
            mkdirs()
        }
        try {
            val expectedName = "keep.bin"
            val expectedPart = File(staging, "$expectedName.part").apply { writeText("keep") }
            val orphanPart = File(staging, "gone.bin.part").apply { writeText("orphan") }
            val orphanMeta = File(staging, "gone.bin.tug.json").apply { writeText("{}") }
            val orphanTmp = File(staging, "gone.bin.tug.json.tmp").apply { writeText("{}") }
            val finalFile = File(staging, "legacy-final.bin").apply { writeText("data") }

            val tasks = listOf(
                TaskView(
                    id = 1,
                    kind = TaskKind.Http,
                    name = expectedName,
                    url = "http://example.com/keep.bin",
                    saveDir = staging.absolutePath,
                    fileName = expectedName,
                    state = TaskState.Paused,
                ),
            )

            val removed = OrphanFiles.clean(staging, tasks)

            assertEquals(3, removed)
            assertTrue("当前任务的边车文件必须保留", expectedPart.exists())
            assertFalse(orphanPart.exists())
            assertFalse(orphanMeta.exists())
            assertFalse(orphanTmp.exists())
            assertTrue("最终文件不能被清理", finalFile.exists())
        } finally {
            staging.deleteRecursively()
        }
    }
}
