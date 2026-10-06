package dev.win11we.shell.ui.notepad

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.win11we.shell.R
import dev.win11we.shell.ui.theme.AccentBlue
import dev.win11we.shell.ui.theme.CardSurface
import dev.win11we.shell.ui.theme.StrokeSubtle
import dev.win11we.shell.ui.theme.TextSecondary
import dev.win11we.shell.ui.theme.TextTertiary

private val ChromeColor = Color(0xFFF3F6FA)
private val EditorBackground = Color(0xFFFFFFFF)
private val EditorText = Color(0xFF14181F)
private val SmallShape = RoundedCornerShape(5.dp)

/** The text area: word wrap off scrolls both ways, zoom scales the font. */
@Composable
fun NotepadEditor(state: NotepadState, modifier: Modifier = Modifier) {
    val fontSize = 14.sp * (state.zoom / 100f)
    val vertical = rememberScrollState()
    Box(modifier = modifier.fillMaxSize().background(EditorBackground)) {
        BasicTextField(
            state = state.textState,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vertical)
                .then(if (state.wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            textStyle = TextStyle(
                color = EditorText,
                fontSize = fontSize,
                lineHeight = fontSize * 1.45f,
                fontFamily = FontFamily.Monospace,
            ),
            cursorBrush = SolidColor(Color(0xFF2E8FE0)),
            lineLimits = if (state.wordWrap) TextFieldLineLimits.MultiLine() else TextFieldLineLimits.SingleLine,
        )
    }
}

/** Find / Replace strip, shown under the menu bar while [NotepadState.findBarVisible]. */
@Composable
fun NotepadFindBar(
    state: NotepadState,
    onClose: () -> Unit,
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ChromeColor)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FindField(
            value = state.findQuery,
            hint = "Find",
            onChange = { state.findQuery = it },
            modifier = Modifier.width(170.dp),
        )
        FindField(
            value = state.replaceQuery,
            hint = "Replace with",
            onChange = { state.replaceQuery = it },
            modifier = Modifier.width(170.dp),
        )
        Text(
            text = "Aa",
            color = if (state.findMatchCase) AccentBlue else TextTertiary,
            fontSize = 12.5.sp,
            modifier = Modifier
                .clip(SmallShape)
                .background(if (state.findMatchCase) Color(0x224CC2FF) else Color.Transparent)
                .clickable(onClickLabel = "Match case") { state.findMatchCase = !state.findMatchCase }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
        FindButton("Previous") { onResult(if (state.findPrevious()) "" else "No matches") }
        FindButton("Next") { onResult(if (state.findNext()) "" else "No matches") }
        FindButton("Replace") { state.replaceNext(); onResult("") }
        FindButton("All") { onResult("${state.replaceAll()} replaced") }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier
                .size(26.dp)
                .clip(SmallShape)
                .clickable(onClickLabel = "Close find", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_close), "Close find", tint = TextSecondary, modifier = Modifier.size(11.dp))
        }
    }
}

@Composable
private fun FindField(value: String, hint: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(SmallShape)
            .background(Color.White)
            .border(1.dp, StrokeSubtle, SmallShape)
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) Text(hint, color = TextTertiary, fontSize = 12.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = EditorText, fontSize = 12.sp),
            cursorBrush = SolidColor(AccentBlue),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun FindButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color(0xFF1B1F27),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(SmallShape)
            .background(CardSurface)
            .border(1.dp, StrokeSubtle, SmallShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 6.dp),
    )
}

/** Status bar: Ln/Col, length, zoom stepper, endings, encoding. */
@Composable
fun NotepadStatusBar(state: NotepadState, modifier: Modifier = Modifier) {
    val position = state.position()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(ChromeColor)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Ln ${position.line}, Col ${position.column}", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.width(14.dp))
        Text("${state.text.length} characters  ${state.lineCount()} lines", color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        Text("-", color = TextSecondary, fontSize = 13.sp,
            modifier = Modifier.clip(SmallShape).clickable(onClickLabel = "Zoom out") { state.zoomBy(-10f) }
                .padding(horizontal = 7.dp))
        Text(state.zoomLabel(), color = TextSecondary, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
        Text("+", color = TextSecondary, fontSize = 13.sp,
            modifier = Modifier.clip(SmallShape).clickable(onClickLabel = "Zoom in") { state.zoomBy(10f) }
                .padding(horizontal = 7.dp))
        Spacer(Modifier.width(14.dp))
        Text(state.lineEndings.label, color = TextSecondary, fontSize = 11.sp)
        Spacer(Modifier.width(14.dp))
        Text(state.encoding.label, color = TextSecondary, fontSize = 11.sp)
    }
}
