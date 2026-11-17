package com.clarklevis.dsh.shared.localchat

import com.clarklevis.dsh.shared.history.decodeUtf8
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLResponse
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionResponseAllow
import platform.Foundation.NSURLSessionResponseDisposition
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSUserDomainMask
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.coroutines.resume

/**
 * iOS 渚х殑鏈湴浼氳瘽瀛樺偍锛欰pplication Support/local-chat銆? * 鍙粡 NSData 涓庡瓧鑺傛暟缁勪氦浜掞紝涓嶇 NSString 妗ユ帴锛岄伩鍏?ObjC 鏄犲皠宸紓銆? */
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
private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData.dataWithBytes(null, 0u)
    return usePinned { pinned -> NSData.dataWithBytes(pinned.addressOf(0), size.toULong()) }
}

/**
 * iOS 渚х殑娴佸紡鎵ц鍣細NSURLSession + data delegate锛屾敹鍒板垎鐗囧氨鍠?shared 鐨勮В鏋愬櫒锛? * 涓?Android 鍏辩敤鍚屼竴濂?SSE 瑙ｆ瀽涓庡け璐ヨ涔夈€? */
@OptIn(ExperimentalForeignApi::class)
class IosChatStreamTransport : ChatStreamTransport {
    override suspend fun stream(
        settings: DeepSeekChatSettings,
        apiKey: String,
        messages: List<ChatMessage>,
        onEvent: (ChatStreamEvent) -> Unit,
    ): LocalChatFailure? = suspendCancellableCoroutine { continuation ->
        val url = NSURL.URLWithString(settings.chatCompletionsUrl)
        if (url == null) {
            continuation.resume(LocalChatFailure(LocalChatFailureReason.TRANSPORT_ERROR, detail = "invalid endpoint"))
            return@suspendCancellableCoroutine
        }
        val request = NSMutableURLRequest.requestWithURL(url)
        request.setHTTPMethod("POST")
        request.setValue("Bearer ${apiKey.trim()}", forHTTPHeaderField = "Authorization")
        request.setValue("text/event-stream", forHTTPHeaderField = "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField = "Content-Type")
        request.setHTTPBody(
            ChatRequestBuilder.body(settings, messages, stream = true).encodeToByteArray().toNSData(),
        )
        val delegate = StreamDelegate(onEvent) { failure ->
            if (continuation.isActive) continuation.resume(failure)
        }
        val session = NSURLSession.sessionWithConfiguration(
            NSURLSessionConfiguration.defaultSessionConfiguration,
            delegate = delegate,
            delegateQueue = NSOperationQueue.mainQueue,
        )
        val task = session.dataTaskWithRequest(request)
        continuation.invokeOnCancellation {
            task.cancel()
            session.invalidateAndCancel()
        }
        task.resume()
    }
}

@OptIn(ExperimentalForeignApi::class)
private class StreamDelegate(
    private val onEvent: (ChatStreamEvent) -> Unit,
    private val onComplete: (LocalChatFailure?) -> Unit,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val parser = ChatStreamParser()
    private val errorBody = StringBuilder()
    private var statusCode = 0
    private var completed = false

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveResponse: NSURLResponse,
        completionHandler: (NSURLSessionResponseDisposition) -> Unit,
    ) {
        statusCode = (didReceiveResponse as? NSHTTPURLResponse)?.statusCode?.toInt() ?: 0
        completionHandler(NSURLSessionResponseAllow)
    }

    override fun URLSession(
        session: NSURLSession,
        dataTask: NSURLSessionDataTask,
        didReceiveData: NSData,
    ) {
        val chunk = decodeUtf8(didReceiveData.toByteArray())
        if (chunk.isEmpty()) return
        if (statusCode in 200..299) {
            parser.feed(chunk).forEach(onEvent)
        } else if (errorBody.length < 4096) {
            errorBody.append(chunk)
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didCompleteWithError: platform.Foundation.NSError?,
    ) {
        if (completed) return
        completed = true
        session.finishTasksAndInvalidate()
        val completionError = didCompleteWithError
        val failure = when {
            statusCode !in 200..299 -> LocalChatFailure(
                reason = LocalChatFailureReason.HTTP_ERROR,
                httpStatus = statusCode,
                detail = ChatCompletionParser.errorMessage(errorBody.toString())
                    ?: errorBody.toString().take(300).ifBlank { null },
            )
            completionError != null -> LocalChatFailure(
                reason = LocalChatFailureReason.TRANSPORT_ERROR,
                detail = completionError.localizedDescription,
            )
            else -> null
        }
        if (failure == null) parser.end().forEach(onEvent)
        onComplete(failure)
    }
}
