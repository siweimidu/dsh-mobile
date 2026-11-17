package com.clarklevis.dsh.shared.localchat

import com.clarklevis.dsh.shared.history.decodeUtf8
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * iOS 侧的本地会话存储：Application Support/local-chat。
 * 只经 NSData 与字节数组交互，不碰 NSString 桥接，避免 ObjC 映射差异。
 */
@OptIn(ExperimentalForeignApi::class)
class IosLocalFileStorage(
    directoryName: String = "local-chat",
    baseDirectory: String = defaultBaseDirectory(),
) : LocalFileStorage {
    private val fileManager = NSFileManager.defaultManager
    private val directory: String = "$baseDirectory/$directoryName"

    init {
        fileManager.createDirectoryAtPath(
            path = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
    }

    override fun read(name: String): String? {
        val path = pathOf(name)
        if (!fileManager.fileExistsAtPath(path)) return null
        val data = NSData.dataWithContentsOfFile(path) ?: return null
        return decodeUtf8(data.toByteArray())
    }

    override fun write(name: String, text: String) {
        text.encodeToByteArray().toNSData().writeToFile(pathOf(name), atomically = true)
    }

    override fun delete(name: String) {
        fileManager.removeItemAtPath(pathOf(name), error = null)
    }

    override fun list(prefix: String): List<String> {
        val entries = fileManager.contentsOfDirectoryAtPath(directory, error = null) ?: return emptyList()
        return entries.mapNotNull { it as? String }.filter { it.startsWith(prefix) }
    }

    private fun pathOf(name: String): String = "$directory/$name"

    private companion object {
        fun defaultBaseDirectory(): String =
            (NSSearchPathForDirectoriesInDomains(
                NSApplicationSupportDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String) ?: NSTemporaryDirectory()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData.dataWithBytes(null, 0u)
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}
