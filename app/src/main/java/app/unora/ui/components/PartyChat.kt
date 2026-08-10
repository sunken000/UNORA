package app.unora.ui.components

import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.unora.ui.screens.ChatMessageUi
import app.unora.ui.theme.UnoraShapes

@Composable
fun PartyChat(
    messages: List<ChatMessageUi>,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val atEnd by remember {
        derivedStateOf {
            messages.isEmpty() || listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index == messages.lastIndex
        }
    }
    var unread by remember { mutableIntStateOf(0) }
    var previousSize by remember { mutableIntStateOf(0) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousSize) {
            if (atEnd || previousSize == 0) listState.animateScrollToItem(messages.lastIndex.coerceAtLeast(0))
            else unread += messages.size - previousSize
        }
        previousSize = messages.size
    }
    LaunchedEffect(atEnd) { if (atEnd) unread = 0 }

    Column(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Chat",
                color = MaterialTheme.colorScheme.onBackground,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                if (messages.size == 1) "1 mensagem" else "${messages.size} mensagens",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
            )
        }
        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant) {
                        Icon(
                            Icons.Outlined.ChatBubbleOutline,
                            null,
                            Modifier.padding(13.dp).size(24.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        "Nenhuma mensagem ainda",
                        modifier = Modifier.padding(top = 12.dp),
                        color = MaterialTheme.colorScheme.onBackground,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "Comece a conversa com a party.",
                        modifier = Modifier.padding(top = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            ) {
                items(messages.size, key = { messages[it].id }) { index -> ChatRow(messages[index]) }
            }
            if (unread > 0) {
                Text(
                    text = if (unread == 1) "1 nova mensagem" else "$unread novas mensagens",
                    modifier = Modifier.align(Alignment.BottomCenter).clip(UnoraShapes.pill)
                        .background(MaterialTheme.colorScheme.primary).clickable {
                            unread = 0
                        }.padding(horizontal = 12.dp, vertical = 7.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        ChatInput(onSend = onSend)
    }
}

@Composable
private fun ChatRow(message: ChatMessageUi) {
    Surface(
        shape = UnoraShapes.small,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(11.dp), verticalAlignment = Alignment.Top) {
            Avatar(message.senderId, message.nickname)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(message.nickname, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(7.dp))
                    Text(message.timestampLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(message.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun Avatar(seed: String, nickname: String) {
    val palette = listOf(Color(0xFF8061DF), Color(0xFF257987), Color(0xFFB85872), Color(0xFF9B6B2F))
    val color = palette[(seed.hashCode().toUInt().toInt() and Int.MAX_VALUE) % palette.size]
    Box(Modifier.size(34.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(nickname.firstOrNull()?.uppercase() ?: "?", color = Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun ChatInput(onSend: (String) -> Unit) {
    var message by remember { mutableStateOf("") }
    val currentOnSend = rememberUpdatedState(onSend)
    val inputRef = remember { arrayOfNulls<EditText>(1) }
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val hintColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    fun submit(raw: String = message) {
        val cleaned = raw.trim().take(1_000)
        if (cleaned.isNotEmpty()) {
            currentOnSend.value(cleaned)
            message = ""
            inputRef[0]?.text?.clear()
        }
    }
    Surface(color = MaterialTheme.colorScheme.background, shadowElevation = 8.dp) {
        Surface(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = UnoraShapes.card,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
                AndroidView(
                    modifier = Modifier.weight(1f).height(52.dp),
                    factory = { context ->
                        EditText(context).apply {
                            inputRef[0] = this
                            hint = "Mensagem…"
                            textSize = 16f
                            includeFontPadding = false
                            setSingleLine(true)
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                            imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                            filters = arrayOf(InputFilter.LengthFilter(1_000))
                            background = null
                            setPadding((14 * resources.displayMetrics.density).toInt(), 0, 4, 0)
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                                    message = s?.toString().orEmpty()
                                }
                                override fun afterTextChanged(s: Editable?) = Unit
                            })
                            setOnEditorActionListener { view, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_SEND) {
                                    submit(view.text.toString())
                                    true
                                } else false
                            }
                        }
                    },
                    update = { editText ->
                        editText.setTextColor(textColor)
                        editText.setHintTextColor(hintColor)
                        if (editText.text.toString() != message) {
                            editText.setText(message)
                            editText.setSelection(editText.text.length)
                        }
                    },
                )
                IconButton(onClick = { submit() }, enabled = message.isNotBlank()) {
                    Icon(
                        Icons.Outlined.Send,
                        "Enviar",
                        tint = if (message.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
