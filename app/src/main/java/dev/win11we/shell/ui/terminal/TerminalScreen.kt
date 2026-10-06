package dev.win11we.shell.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ConsoleBackground = Color(0xFF0B0F16)
private val ChromeBackground = Color(0xFF12171F)
private val PromptGreen = Color(0xFF7BE0A8)
private val OutputGrey = Color(0xFFD6DEE9)
private val InfoBlue = Color(0xFF7FC9FF)
private val ErrorRed = Color(0xFFFF8A7A)
private val SystemTeal = Color(0xFF6FE3DC)

private val QuickCommands = listOf("help", "ls", "pwd", "free", "log", "wine --version", "clear")

/**
 * Terminal window: transcript, quick commands and an input row with history on
 * the arrow keys. Execution goes through [CommandExecutor], which R2 swaps for
 * the container pty without changing this UI.
 */
@Composable
fun TerminalScreen(state: TerminalState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val shown = state.lines
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.animateScrollToItem(shown.size - 1)
    }

    Column(modifier = modifier.fillMaxSize().background(ConsoleBackground)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            items(shown) { line ->
                Text(
                    text = line.text,
                    color = when (line.kind) {
                        LineKind.PROMPT -> PromptGreen
                        LineKind.OUTPUT -> OutputGrey
                        LineKind.ERROR -> ErrorRed
                        LineKind.INFO -> InfoBlue
                        LineKind.SYSTEM -> SystemTeal
                    },
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().background(ChromeBackground).padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuickCommands.forEach { command ->
                Text(
                    text = command,
                    color = InfoBlue,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x14FFFFFF))
                        .clickable(onClickLabel = command) { state.submit(command) }
                        .padding(horizontal = 9.dp, vertical = 4.dp),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(ChromeBackground)
                .border(1.dp, Color(0x1FFFFFFF))
                .padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(state.prompt, color = PromptGreen, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    value = state.draft,
                    onValueChange = { state.draft = it },
                    singleLine = true,
                    textStyle = TextStyle(color = OutputGrey, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(PromptGreen),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { state.submit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(22.dp)
                        .onPreviewKeyEvent { event ->
                            when {
                                event.type != KeyEventType.KeyDown -> false
                                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                                    state.submit()
                                    true
                                }
                                event.key == Key.DirectionUp -> {
                                    state.historyPrevious()
                                    true
                                }
                                event.key == Key.DirectionDown -> {
                                    state.historyNext()
                                    true
                                }
                                else -> false
                            }
                        },
                )
            }
        }
    }
}
