import SwiftUI
import UniformTypeIdentifiers
import DeepSeekHarnessShared

/// 导入 + 续聊的运行入口：Swift 侧只持有 runtime 与当前 API Key。
@MainActor
final class LocalChatService: ObservableObject {
    let runtime: IosLocalChatRuntime
    private let transport = LocalChatTransport()

    init() {
        runtime = IosLocalChatRuntime(
            storage: IosLocalFileStorage(directoryName: "local-chat", baseDirectory: LocalChatService.defaultBaseDirectory()),
            transport: transport,
            preferences: UserDefaults.standard
        )
    }

    private static func defaultBaseDirectory() -> String {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
        return base?.path ?? NSTemporaryDirectory()
    }
}

struct LocalChatListView: View {
    @StateObject private var service = LocalChatService()
    @State private var conversations: [LocalConversationSummary] = []
    @State private var status = ""
    @State private var isBusy = false
    @State private var isImporting = false
    @State private var showingSettings = false

    var body: some View {
        List {
            Section {
                Button {
                    isImporting = true
                } label: {
                    Label("导入 DeepSeek 聊天记录", systemImage: "square.and.arrow.down")
                }
                .disabled(isBusy)
                if !status.isEmpty {
                    Text(status).font(.footnote).foregroundStyle(.secondary)
                }
            }
            if conversations.isEmpty {
                Section {
                    Text("还没有导入的对话。导出 DeepSeek App 的聊天记录后在这里导入。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                Section {
                    ForEach(conversations, id: \.id) { conversation in
                        NavigationLink {
                            LocalChatConversationView(service: service, conversationId: conversation.id)
                        } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                Text(conversation.title.isEmpty ? String(localized: "未命名对话") : conversation.title)
                                Text(String(localized: "条消息") + " " + String(conversation.messageCount))
                                    .font(.footnote)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(Text("我的对话"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    showingSettings = true
                } label: {
                    Image(systemName: "key")
                }
            }
        }
        .sheet(isPresented: $showingSettings) {
            NavigationStack { LocalChatSettingsView(service: service) }
        }
        .fileImporter(
            isPresented: $isImporting,
            allowedContentTypes: [.zip, .data],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case let .success(urls):
                guard let url = urls.first else { return }
                importArchive(from: url)
            case let .failure(error):
                status = String(localized: "导入失败") + ": " + error.localizedDescription
            }
        }
        .task { reload() }
    }

    private func reload() {
        conversations = service.runtime.conversations()
    }

    private func importArchive(from url: URL) {
        isBusy = true
        status = String(localized: "正在导入…")
        let scoped = url.startAccessingSecurityScopedResource()
        Task.detached(priority: .userInitiated) {
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            do {
                let copy = FileManager.default.temporaryDirectory
                    .appendingPathComponent("deepseek-export-\(UUID().uuidString).zip")
                try FileManager.default.copyItem(at: url, to: copy)
                let result = try self.service.runtime.importArchive(path: copy.path)
                try? FileManager.default.removeItem(at: copy)
                await MainActor.run {
                    let summary = "\(result.imported) / \(result.skipped) / \(result.failed)"
                    self.status = String(localized: "导入结果（导入 / 跳过 / 失败）") + ": " + summary
                    self.isBusy = false
                    self.reload()
                }
            } catch {
                await MainActor.run {
                    self.status = String(localized: "导入失败") + ": " + error.localizedDescription
                    self.isBusy = false
                }
            }
        }
    }
}

struct LocalChatConversationView: View {
    let service: LocalChatService
    let conversationId: String

    @State private var conversation: ImportedConversation?
    @State private var draft = ""
    @State private var streaming = ""
    @State private var errorText = ""
    @State private var isSending = false

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        ForEach(Array((conversation?.messages ?? []).enumerated()), id: \.offset) { _, message in
                            bubble(for: message)
                        }
                        if !streaming.isEmpty {
                            Text(streaming)
                                .padding(10)
                                .background(Color(uiColor: .secondarySystemBackground))
                                .clipShape(RoundedRectangle(cornerRadius: 14))
                        }
                        Color.clear.frame(height: 1).id("bottom")
                    }
                    .padding(.horizontal, 14)
                    .padding(.vertical, 10)
                }
                .onChange(of: (conversation?.messages.count ?? 0) + streaming.count) { _ in
                    withAnimation { proxy.scrollTo("bottom", anchor: .bottom) }
                }
            }
            if !errorText.isEmpty {
                Text(errorText)
                    .font(.footnote)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
            }
            HStack(spacing: 8) {
                TextField("继续这段对话…", text: $draft, axis: .vertical)
                    .lineLimit(1...5)
                    .textFieldStyle(.roundedBorder)
                Button(isSending ? String(localized: "生成中") : String(localized: "发送")) {
                    send()
                }
                .disabled(isSending || draft.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
        }
        .navigationTitle(Text(conversation?.title.isEmpty == false ? (conversation?.title ?? "") : String(localized: "对话")))
        .navigationBarTitleDisplayMode(.inline)
        .task { reload() }
    }

    @ViewBuilder
    private func bubble(for message: ImportedMessage) -> some View {
        let isUser = message.role.name == "USER"
        VStack(alignment: .leading, spacing: 6) {
            if let reasoning = message.reasoning, !reasoning.isEmpty {
                Text("思考过程").font(.caption2).bold()
                Text(reasoning).font(.caption2).foregroundStyle(.secondary)
            }
            Text(message.text.isEmpty ? String(localized: "（无正文）") : message.text)
            ForEach(Array(message.attachments.enumerated()), id: \.offset) { _, attachment in
                Text("附件：\(attachment.fileName)").font(.caption2).foregroundStyle(.secondary)
            }
        }
        .padding(10)
        .frame(maxWidth: 320, alignment: .leading)
        .background(isUser ? Color.accentColor.opacity(0.12) : Color(uiColor: .secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 14))
        .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
    }

    private func reload() {
        conversation = service.runtime.loadConversation(id: conversationId)
    }

    private func send() {
        let text = draft.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return }
        draft = ""
        errorText = ""
        streaming = ""
        isSending = true
        let apiKey = LocalChatKeychain.loadApiKey()
        Task {
            do {
                let result = try await service.runtime.sendMessage(
                    conversationId: conversationId,
                    text: text,
                    apiKey: apiKey,
                    onDelta: { delta in
                        Task { @MainActor in streaming += delta }
                    }
                )
                await MainActor.run {
                    streaming = ""
                    isSending = false
                    if !result.succeeded {
                        errorText = Self.failureText(result.failure)
                    }
                    reload()
                }
            } catch {
                await MainActor.run {
                    streaming = ""
                    isSending = false
                    errorText = error.localizedDescription
                }
            }
        }
    }

    private static func failureText(_ failure: LocalChatFailure?) -> String {
        guard let failure else { return String(localized: "发送失败") }
        switch failure.reason.name {
        case "MISSING_API_KEY": return String(localized: "请先在设置里填入 API Key")
        case "CONVERSATION_NOT_FOUND": return String(localized: "找不到这个对话")
        case "EMPTY_MESSAGE": return String(localized: "消息不能为空")
        case "EMPTY_RESPONSE": return String(localized: "模型没有返回内容，请重试")
        case "HTTP_ERROR":
            return String(localized: "请求失败（HTTP）") + " " + (failure.detail ?? "")
        default:
            return String(localized: "网络异常") + " " + (failure.detail ?? "")
        }
    }
}

struct LocalChatSettingsView: View {
    let service: LocalChatService
    @Environment(\.dismiss) private var dismiss
    @State private var apiKey = LocalChatKeychain.loadApiKey()
    @State private var baseUrl = "https://api.deepseek.com"
    @State private var model = "deepseek-chat"
    @State private var saved = false

    var body: some View {
        Form {
            Section {
                Text("填入 API Key 后，导入的对话可以直接接着聊。Key 只保存在本机钥匙串里。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                SecureField("DeepSeek API Key", text: $apiKey)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Section {
                TextField("接口地址", text: $baseUrl)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                TextField("模型", text: $model)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            Section {
                Button(String(localized: "保存")) {
                    LocalChatKeychain.saveApiKey(apiKey)
                    service.runtime.updateSettings(baseUrl: baseUrl, model: model)
                    saved = true
                }
                if saved {
                    Text("已保存").font(.footnote).foregroundStyle(.secondary)
                }
            }
        }
        .navigationTitle(Text("API 设置"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(String(localized: "完成")) { dismiss() }
            }
        }
        .task {
            let settings = service.runtime.settings()
            baseUrl = settings.baseUrl
            model = settings.model
        }
    }
}
