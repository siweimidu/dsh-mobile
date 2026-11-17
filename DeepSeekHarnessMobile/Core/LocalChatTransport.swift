import Foundation
import DeepSeekHarnessShared

/// 把 API Key 存在钥匙串里；shared 只按调用接收明文，不落盘密钥。
enum LocalChatKeychain {
    private static let service = "com.clarklevis.dsh.mobile.localchat"
    private static let account = "deepseek-api-key"

    static func loadApiKey() -> String {
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        query[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data,
              let text = String(data: data, encoding: .utf8)
        else { return "" }
        return text
    }

    static func saveApiKey(_ apiKey: String) {
        let trimmed = apiKey.trimmingCharacters(in: .whitespacesAndNewlines)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
        if trimmed.isEmpty {
            SecItemDelete(query as CFDictionary)
            return
        }
        let data = Data(trimmed.utf8)
        let attributes: [String: Any] = [
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        let status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query
            insert.merge(attributes) { current, _ in current }
            SecItemAdd(insert as CFDictionary, nil)
        }
    }
}

/// Kotlin 的 fun interface 在 Swift 里是协议，需要一个具体类型来承载取消动作。
private final class LocalChatCancellation: NSObject, ChatStreamCancellation {
    private let onCancel: () -> Void

    init(onCancel: @escaping () -> Void) {
        self.onCancel = onCancel
    }

    func cancel() {
        onCancel()
    }
}

/// URLSession + data delegate 实现 shared 的流式契约；SSE 解析仍复用 shared 的 ChatStreamParser。
final class LocalChatTransport: NSObject, ChatStreamTransport, URLSessionDataDelegate {
    private var onEvent: ((ChatStreamEvent) -> Void)?
    private var onComplete: ((LocalChatFailure?) -> Void)?
    private var parser: ChatStreamParser?
    private var statusCode = 0
    private var errorBody = ""
    private var finished = false
    private var session: URLSession?

    func start(
        settings: DeepSeekChatSettings,
        apiKey: String,
        messages: [ChatMessage],
        onEvent: @escaping (ChatStreamEvent) -> Void,
        onComplete: @escaping (LocalChatFailure?) -> Void
    ) -> ChatStreamCancellation {
        self.onEvent = onEvent
        self.onComplete = onComplete
        parser = ChatStreamParser()
        statusCode = 0
        errorBody = ""
        finished = false

        guard let url = URL(string: settings.chatCompletionsUrl) else {
            onComplete(LocalChatFailures.shared.transport(detail: "invalid endpoint"))
            return LocalChatCancellation {}
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey.trimmingCharacters(in: .whitespacesAndNewlines))", forHTTPHeaderField: "Authorization")
        request.setValue("text/event-stream", forHTTPHeaderField: "Accept")
        request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
        request.httpBody = ChatRequestBuilder.shared
            .body(settings: settings, messages: messages, stream: true)
            .data(using: .utf8)

        let configuration = URLSessionConfiguration.default
        configuration.timeoutIntervalForRequest = 300
        let session = URLSession(configuration: configuration, delegate: self, delegateQueue: .main)
        self.session = session
        let task = session.dataTask(with: request)
        task.resume()
        return LocalChatCancellation { [weak self] in
            task.cancel()
            self?.session?.invalidateAndCancel()
            self?.finished = true
        }
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        statusCode = (response as? HTTPURLResponse)?.statusCode ?? 0
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        guard let chunk = String(data: data, encoding: .utf8), !chunk.isEmpty else { return }
        if (200...299).contains(statusCode) {
            parser?.feed(chunk: chunk).forEach { onEvent?($0) }
        } else if errorBody.count < 4096 {
            errorBody += chunk
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard !finished else { return }
        finished = true
        session.finishTasksAndInvalidate()
        if !(200...299).contains(statusCode) {
            let detail = ChatCompletionParser.shared.errorMessage(body: errorBody) ?? String(errorBody.prefix(300))
            onComplete?(LocalChatFailures.shared.http(status: Int32(statusCode), detail: detail))
            return
        }
        if let error {
            onComplete?(LocalChatFailures.shared.transport(detail: error.localizedDescription))
            return
        }
        parser?.end().forEach { onEvent?($0) }
        onComplete?(nil)
    }
}
