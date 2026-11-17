package com.clarklevis.dsh.android.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clarklevis.dsh.android.AndroidLocalChatGraph
import com.clarklevis.dsh.android.DshAndroidApplication
import com.clarklevis.dsh.android.R
import com.clarklevis.dsh.android.localization.appText
import com.clarklevis.dsh.shared.history.ImportedConversation
import com.clarklevis.dsh.shared.history.ImportedMessage
import com.clarklevis.dsh.shared.history.ImportedMessageRole
import com.clarklevis.dsh.shared.localchat.DeepSeekChatSettings
import com.clarklevis.dsh.shared.localchat.LocalChatFailure
import com.clarklevis.dsh.shared.localchat.LocalChatFailureReason
import com.clarklevis.dsh.shared.localchat.LocalConversationSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun localChatGraph(context: android.content.Context): AndroidLocalChatGraph =
    (context.applicationContext as DshAndroidApplication).localChat

private fun LocalChatFailure.displayText(): String = when (reason) {
    LocalChatFailureReason.MISSING_API_KEY -> appText("请先在设置里填入 API Key")
    LocalChatFailureReason.CONVERSATION_NOT_FOUND -> appText("找不到这个对话")
    LocalChatFailureReason.EMPTY_MESSAGE -> appText("消息不能为空")
    LocalChatFailureReason.HTTP_ERROR -> appText("请求失败（HTTP %1\$s）", httpStatus ?: 0) +
        (detail?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty())
    LocalChatFailureReason.EMPTY_RESPONSE -> appText("模型没有返回内容，请重试")
    LocalChatFailureReason.TRANSPORT_ERROR -> appText("网络异常") +
        (detail?.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty())
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalChatListScreen(
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { localChatGraph(context) }
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0D1118) else Color(0xFFF7F7F8)

    var conversations by remember { mutableStateOf<List<LocalConversationSummary>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }

    suspend fun refresh() {
        conversations = withContext(Dispatchers.IO) { graph.conversations() }
        loaded = true
    }

    LaunchedEffect(Unit) { refresh() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            status = appText("正在导入…")
            val outcome = runCatching { withContext(Dispatchers.IO) { graph.importArchive(uri) } }
            status = outcome.fold(
                onSuccess = { result ->
                    refresh()
                    appText(
                        "已导入 %1\$s 个对话（跳过 %2\$s 个，失败 %3\$s 个）",
                        result.imported,
                        result.skipped,
                        result.failed,
                    )
                },
                onFailure = { error ->
                    appText("导入失败") + "：" + (error.message ?: appText("无法解析这个文件"))
                },
            )
            busy = false
        }
    }

    Scaffold(
        containerColor = background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText("我的对话"), style = SettingsTypography.title) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                actions = {
                    TextButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.testTag("local-chat-settings"),
                    ) {
                        Text(appText("设置"))
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = background),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TextButton(
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    },
                    enabled = !busy,
                    modifier = Modifier.testTag("local-chat-import"),
                ) {
                    Text(appText("导入 DeepSeek 聊天记录"))
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp))
            }
            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = SettingsTypography.description,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider(color = if (dark) Color(0xFF1E2430) else Color(0xFFE4E6EB))
            if (loaded && conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = appText("还没有导入的对话。导出 DeepSeek App 的聊天记录后在这里导入。"),
                        style = SettingsTypography.item,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations, key = { it.id }) { conversation ->
                        LocalChatRow(conversation) { onOpenConversation(conversation.id) }
                        HorizontalDivider(color = if (dark) Color(0xFF1E2430) else Color(0xFFE4E6EB))
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalChatRow(conversation: LocalConversationSummary, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local-chat-row")
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(
            text = conversation.title.ifBlank { appText("未命名对话") },
            style = SettingsTypography.item,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = appText("%1\$s 条消息", conversation.messageCount),
            style = SettingsTypography.description,
        )
        TextButton(onClick = onClick) { Text(appText("继续对话")) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalChatScreen(
    conversationId: String,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val graph = remember(context) { localChatGraph(context) }
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0D1118) else Color(0xFFF7F7F8)
    val listState = rememberLazyListState()

    var conversation by remember { mutableStateOf<ImportedConversation?>(null) }
    var draft by remember { mutableStateOf("") }
    var streaming by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(conversationId) {
        conversation = withContext(Dispatchers.IO) { graph.loadConversation(conversationId) }
    }

    Scaffold(
        containerColor = background,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        text = conversation?.title?.ifBlank { appText("未命名对话") } ?: appText("对话"),
                        style = SettingsTypography.title,
                    )
                },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                actions = {
                    TextButton(onClick = onOpenSettings) { Text(appText("设置")) }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).imePadding(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val messages = conversation?.messages.orEmpty()
                items(messages.size) { index ->
                    LocalChatBubble(messages[index], dark)
                }
                if (streaming.isNotEmpty()) {
                    item { LocalChatStreamingBubble(streaming, dark) }
                }
            }
            LaunchedEffect(conversation?.messages?.size, streaming.length) {
                val total = (conversation?.messages?.size ?: 0) + if (streaming.isEmpty()) 0 else 1
                if (total > 0) listState.animateScrollToItem(total - 1)
            }
            if (errorText.isNotEmpty()) {
                Text(
                    text = errorText,
                    color = Color(0xFFD32F2F),
                    style = SettingsTypography.description,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).testTag("local-chat-composer"),
                    placeholder = { Text(appText("继续这段对话…")) },
                    maxLines = 6,
                )
                TextButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isEmpty() || sending) return@TextButton
                        draft = ""
                        errorText = ""
                        streaming = ""
                        sending = true
                        scope.launch {
                            val result = graph.sendMessage(
                                conversationId = conversationId,
                                text = text,
                                onDelta = { delta -> streaming += delta },
                            )
                            streaming = ""
                            sending = false
                            if (!result.succeeded) {
                                errorText = result.failure?.displayText() ?: appText("发送失败")
                            }
                            conversation = withContext(Dispatchers.IO) { graph.loadConversation(conversationId) }
                        }
                    },
                    enabled = !sending && draft.isNotBlank(),
                    modifier = Modifier.testTag("local-chat-send"),
                ) {
                    Text(if (sending) appText("生成中") else appText("发送"))
                }
            }
        }
    }
}

@Composable
private fun LocalChatBubble(message: ImportedMessage, dark: Boolean) {
    val fromUser = message.role == ImportedMessageRole.USER
    val bubbleColor = when {
        fromUser && dark -> Color(0xFF1C2D49)
        fromUser -> Color(0xFFEDF2FF)
        dark -> Color(0xFF161C26)
        else -> Color(0xFFFFFFFF)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            if (!message.reasoning.isNullOrBlank()) {
                Text(
                    text = appText("思考过程"),
                    style = SettingsTypography.description,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = message.reasoning.orEmpty(),
                    style = SettingsTypography.description,
                )
                androidx.compose.foundation.layout.Spacer(Modifier.size(6.dp))
            }
            Text(
                text = message.text.ifBlank {
                    if (message.attachments.isEmpty()) appText("（无正文）") else ""
                },
                style = SettingsTypography.item,
            )
            for (attachment in message.attachments) {
                Text(
                    text = appText("附件：%1\$s", attachment.fileName),
                    style = SettingsTypography.description,
                )
            }
            for (notice in message.notices) {
                Text(text = notice.text, style = SettingsTypography.description)
            }
        }
    }
}

@Composable
private fun LocalChatStreamingBubble(text: String, dark: Boolean) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (dark) Color(0xFF161C26) else Color(0xFFFFFFFF))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text = text, style = SettingsTypography.item)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalChatSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = remember(context) { localChatGraph(context) }
    val scope = rememberCoroutineScope()
    val dark = isSystemInDarkTheme()
    val background = if (dark) Color(0xFF0D1118) else Color(0xFFF7F7F8)

    var apiKey by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(DeepSeekChatSettings.Default) }
    var saved by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        apiKey = withContext(Dispatchers.IO) { graph.loadApiKey() }.orEmpty()
        settings = graph.settings()
        loaded = true
    }

    Scaffold(
        containerColor = background,
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(appText("API 设置"), style = SettingsTypography.title) },
                navigationIcon = {
                    TopBarCircleButton(
                        iconRes = R.drawable.ic_back_chevron,
                        description = appText("返回"),
                        onClick = onBack,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = background),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = appText("填入 API Key 后，导入的对话可以直接接着聊。Key 只保存在本机加密存储里。"),
                style = SettingsTypography.description,
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(appText("DeepSeek API Key")) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().testTag("local-chat-api-key"),
            )
            OutlinedTextField(
                value = settings.baseUrl,
                onValueChange = { settings = settings.copy(baseUrl = it) },
                label = { Text(appText("接口地址")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = settings.model,
                onValueChange = { settings = settings.copy(model = it) },
                label = { Text(appText("模型")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            graph.saveApiKey(apiKey)
                            graph.updateSettings(settings)
                        }
                        saved = appText("已保存")
                    }
                },
                enabled = loaded,
                modifier = Modifier.testTag("local-chat-save"),
            ) {
                Text(appText("保存"))
            }
            if (saved.isNotEmpty()) {
                Text(text = saved, style = SettingsTypography.description)
            }
        }
    }
}
