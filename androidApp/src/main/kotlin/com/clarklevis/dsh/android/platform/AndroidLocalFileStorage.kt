package com.clarklevis.dsh.android.platform

import android.content.Context
import com.clarklevis.dsh.shared.localchat.LocalFileStorage
import java.io.File

/**
 * 本地导入会话的落地目录：`filesDir/local-chat/`。
 * 一条会话一个文件，索引单独一份，避免每次追加消息重写全量。
 */
class AndroidLocalFileStorage(context: Context) : LocalFileStorage {
    private val directory: File = File(context.filesDir, DIRECTORY_NAME).apply { mkdirs() }

    override fun read(name: String): String? {
        val file = File(directory, name)
        if (!file.isFile) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    override fun write(name: String, text: String) {
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        temporary.writeText(text, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(text, Charsets.UTF_8)
            temporary.delete()
        }
    }

    override fun delete(name: String) {
        File(directory, name).delete()
    }

    override fun list(prefix: String): List<String> =
        directory.list()?.filter { it.startsWith(prefix) }.orEmpty()

    companion object {
        private const val DIRECTORY_NAME = "local-chat"
    }
}
