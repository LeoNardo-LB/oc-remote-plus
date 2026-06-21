package dev.leonardo.ocremotev2.ui.screens.chat.terminal

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import dev.leonardo.ocremotev2.ui.theme.AppMotion
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.os.SystemClock
import android.util.Log
import dev.leonardo.ocremotev2.BuildConfig
import dev.leonardo.ocremotev2.ui.screens.chat.TerminalEmulator
import dev.leonardo.ocremotev2.ui.theme.CodeTypography
import kotlinx.coroutines.delay
import dev.leonardo.ocremotev2.ui.theme.AlphaTokens

@Composable
internal fun SessionTerminalInline(
    emulator: TerminalEmulator,
    terminalVersion: Long,
    connected: Boolean,
    focusRequester: FocusRequester,
    onSendInput: (String) -> Unit,
    onPaste: () -> Unit,
    onResize: (cols: Int, rows: Int) -> Unit,
    fontSizeSp: Float,
    onFontSizeChange: (Float) -> Unit,
    contentBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val baseTextToolbar = LocalTextToolbar.current
    var inputCapture by remember { mutableStateOf(TextFieldValue("")) }
    val terminalScrollState = rememberScrollState()
    var terminalFollowMode by rememberSaveable { mutableStateOf(true) }
    // Dedup: some IMEs can fire onValueChange twice for a single keystroke.
    // Track the last chunk + timestamp to suppress duplicates.
    var lastSentChunk by remember { mutableStateOf("") }
    var lastSentTime by remember { mutableStateOf(0L) }

    val terminalTextToolbar = remember(baseTextToolbar, onPaste) {
        object : TextToolbar {
            override val status: TextToolbarStatus
                get() = baseTextToolbar.status

            override fun hide() {
                baseTextToolbar.hide()
            }

            override fun showMenu(
                rect: Rect,
                onCopyRequested: (() -> Unit)?,
                onPasteRequested: (() -> Unit)?,
                onCutRequested: (() -> Unit)?,
                onSelectAllRequested: (() -> Unit)?
            ) {
                baseTextToolbar.showMenu(
                    rect = rect,
                    onCopyRequested = onCopyRequested,
                    onPasteRequested = {
                        onPaste()
                        onPasteRequested?.invoke()
                    },
                    onCutRequested = onCutRequested,
                    onSelectAllRequested = onSelectAllRequested
                )
            }
        }
    }

    val terminalStyle = remember(fontSizeSp) {
        CodeTypography.copy(
            fontSize = fontSizeSp.sp,
            // Tight line spacing is required for continuous box-drawing in TUIs (mc, htop).
            lineHeight = fontSizeSp.sp,
            platformStyle = PlatformTextStyle(includeFontPadding = false)
        )
    }
    val latestFontSizeSp by rememberUpdatedState(fontSizeSp)

    Column(
        modifier = modifier
            .background(Color.Black)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        BasicTextField(
            value = inputCapture,
            onValueChange = { next ->
                if (!connected) {
                    inputCapture = TextFieldValue("")
                    return@BasicTextField
                }
                val old = inputCapture.text
                val now = next.text
                val delta = when {
                    now.startsWith(old) -> now.drop(old.length)
                    old.startsWith(now) -> "\u007F".repeat((old.length - now.length).coerceAtLeast(0))
                    else -> now
                }
                if (delta.isNotEmpty()) {
                    if (BuildConfig.DEBUG && delta.contains('~')) {
                        Log.d("TerminalInput", "onValueChange: delta='$delta' old='$old' now='$now'")
                    }
                    // Dedup: suppress identical chunk within 100ms (IME double-fire).
                    val ts = SystemClock.elapsedRealtime()
                    if (delta == lastSentChunk && ts - lastSentTime < 100) {
                        if (BuildConfig.DEBUG) {
                            Log.d("TerminalInput", "DEDUP: suppressed duplicate delta='$delta'")
                        }
                        inputCapture = next.copy(selection = TextRange(next.text.length))
                        return@BasicTextField
                    }
                    lastSentChunk = delta
                    lastSentTime = ts
                    val mapped = delta
                        .replace("\r\n", "\r")
                        .replace('\n', '\r')
                    onSendInput(mapped)
                }
                // Keep IME context (caps/symbol lock, composing state) stable by
                // preserving TextFieldValue instead of clearing it after each key.
                inputCapture = next.copy(selection = TextRange(next.text.length))
            },
            modifier = Modifier
                .size(1.dp)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> {
                            onSendInput("\r")
                            true
                        }
                        Key.Tab -> {
                            onSendInput("\t")
                            true
                        }
                        Key.Backspace -> {
                            onSendInput("\u007F")
                            true
                        }
                        else -> {
                            val native = event.nativeKeyEvent
                            val unicode = native.unicodeChar
                            if (unicode > 0 && (unicode and android.view.KeyCharacterMap.COMBINING_ACCENT) == 0) {
                                if (native.isCtrlPressed) {
                                    val lower = unicode.toChar().lowercaseChar()
                                    if (lower in 'a'..'z') {
                                        val ctrl = (lower.code - 'a'.code + 1).toChar().toString()
                                        onSendInput(ctrl)
                                        true
                                    } else {
                                        false
                                    }
                                } else {
                                    onSendInput(String(Character.toChars(unicode)))
                                    true
                                }
                            } else {
                                val baseLetter = when (event.key) {
                                    Key.A -> 'a'
                                    Key.B -> 'b'
                                    Key.C -> 'c'
                                    Key.D -> 'd'
                                    Key.E -> 'e'
                                    Key.F -> 'f'
                                    Key.G -> 'g'
                                    Key.H -> 'h'
                                    Key.I -> 'i'
                                    Key.J -> 'j'
                                    Key.K -> 'k'
                                    Key.L -> 'l'
                                    Key.M -> 'm'
                                    Key.N -> 'n'
                                    Key.O -> 'o'
                                    Key.P -> 'p'
                                    Key.Q -> 'q'
                                    Key.R -> 'r'
                                    Key.S -> 's'
                                    Key.T -> 't'
                                    Key.U -> 'u'
                                    Key.V -> 'v'
                                    Key.W -> 'w'
                                    Key.X -> 'x'
                                    Key.Y -> 'y'
                                    Key.Z -> 'z'
                                    else -> null
                                }
                                if (baseLetter != null) {
                                    val upper = native.isShiftPressed.xor(native.isCapsLockOn)
                                    val out = if (upper) baseLetter.uppercaseChar() else baseLetter
                                    if (native.isCtrlPressed) {
                                        val ctrl = (baseLetter.code - 'a'.code + 1).toChar().toString()
                                        onSendInput(ctrl)
                                    } else {
                                        onSendInput(out.toString())
                                    }
                                    true
                                } else {
                                    false
                                }
                            }
                        }
                    }
                },
            singleLine = false,
            textStyle = terminalStyle,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Send
            ),
            keyboardActions = KeyboardActions(
                onSend = { onSendInput("\r") },
                onDone = { onSendInput("\r") },
                onGo = { onSendInput("\r") }
            )
        )

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = contentBottomPadding)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            focusRequester.requestFocus()
                            keyboard?.show()
                        }
                    )
                }
        ) {
            // Measure character dimensions using native Paint for consistency with
            // Canvas rendering. This avoids mismatches between Compose textMeasurer
            // line height and native Paint font metrics that cause vertical gaps.
            val density = LocalDensity.current
            if (BuildConfig.DEBUG) {
                Log.d("TerminalZoom", "BoxWithConstraints recompose: fontSizeSp=$fontSizeSp connected=$connected viewW=${constraints.maxWidth} viewH=${constraints.maxHeight}")
            }
            val charWidthPx = remember(fontSizeSp) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = with(density) { fontSizeSp.sp.toPx() }
                }
                paint.measureText("X").also { w ->
                    if (BuildConfig.DEBUG) {
                        Log.d("TerminalZoom", "charWidthPx RECOMPUTED: fontSizeSp=$fontSizeSp -> charW=$w textSizePx=${paint.textSize}")
                    }
                }
            }
            // Row height: ceil(descent - ascent) snapped to int pixels.
            // This excludes inter-line leading so rows are compact and fill
            // the viewport correctly.  Anti-aliased seams are prevented by
            // drawing with nativeCanvas + isAntiAlias=false.
            val rowHeightPx = remember(fontSizeSp) {
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = android.graphics.Typeface.MONOSPACE
                    textSize = with(density) { fontSizeSp.sp.toPx() }
                }
                val fm = paint.fontMetrics
                kotlin.math.ceil((fm.descent - fm.ascent).toDouble()).toInt().also { h ->
                    if (BuildConfig.DEBUG) {
                        Log.d("TerminalZoom", "rowHeightPx RECOMPUTED: fontSizeSp=$fontSizeSp -> rowH=$h textSizePx=${paint.textSize}")
                    }
                }
            }
            // Use inner constraints from BoxWithConstraints (already reflects bottom padding).
            val viewportWidthPx = constraints.maxWidth
            val viewportHeightPx = constraints.maxHeight
            val termCols = if (viewportWidthPx > 0) {
                (viewportWidthPx / charWidthPx).toInt().coerceAtLeast(20)
            } else 80
            // Simple integer division — our rows start at y=0 so no offset needed.
            val termRows = if (viewportHeightPx > 0) {
                (viewportHeightPx / rowHeightPx).coerceAtLeast(8)
            } else 24
            val maxScrollbackOffsetRows = remember(terminalVersion, termRows) {
                emulator.maxScrollbackOffset(termRows)
            }
            val totalRows = remember(terminalVersion) {
                emulator.totalRowsWithScrollback().coerceAtLeast(1)
            }
            val renderedOutput = remember(terminalVersion, totalRows) {
                emulator.render(
                    scrollbackOffsetRows = 0,
                    windowRows = totalRows,
                )
            }
            val renderedRuns = remember(terminalVersion, totalRows) {
                emulator.renderRuns(
                    scrollbackOffsetRows = 0,
                    windowRows = totalRows,
                )
            }
            val maxScrollPx = maxScrollbackOffsetRows * rowHeightPx
            val followThresholdPx = (rowHeightPx * 2).coerceAtLeast(1)
            val isNearBottom = terminalScrollState.value >= (maxScrollPx - followThresholdPx).coerceAtLeast(0)
            LaunchedEffect(isNearBottom) {
                if (isNearBottom) {
                    terminalFollowMode = true
                }
            }
            LaunchedEffect(maxScrollPx, terminalVersion, terminalFollowMode) {
                when {
                    terminalFollowMode -> {
                        if (terminalScrollState.value != maxScrollPx) {
                            terminalScrollState.scrollTo(maxScrollPx)
                        }
                    }
                    terminalScrollState.value > maxScrollPx -> {
                        terminalScrollState.scrollTo(maxScrollPx)
                    }
                }
            }
            val firstVisibleRow = (terminalScrollState.value / rowHeightPx)
                .coerceIn(0, maxScrollbackOffsetRows)
            val scrollbackOffsetRows = (maxScrollbackOffsetRows - firstVisibleRow).coerceAtLeast(0)
            val verticalOffsetPx = firstVisibleRow * rowHeightPx
            if (BuildConfig.DEBUG) {
                Log.d("TerminalZoom", "GRID CALC: fontSp=$fontSizeSp charW=$charWidthPx rowH=$rowHeightPx viewW=$viewportWidthPx viewH=$viewportHeightPx -> cols=$termCols rows=$termRows")
            }
            // Send resize immediately then retry after a short delay to handle
            // race conditions around PTY startup and IME transitions.
            LaunchedEffect(termCols, termRows, connected) {
                if (BuildConfig.DEBUG) {
                    Log.d("TerminalZoom", "LaunchedEffect FIRED: cols=$termCols rows=$termRows connected=$connected viewW=$viewportWidthPx viewH=$viewportHeightPx fontSp=$fontSizeSp")
                }
                if (connected && viewportWidthPx > 0 && viewportHeightPx > 0) {
                    if (BuildConfig.DEBUG) {
                        Log.d("TerminalInput", "resize: cols=$termCols rows=$termRows viewW=$viewportWidthPx viewH=$viewportHeightPx charW=$charWidthPx rowH=$rowHeightPx fontSp=$fontSizeSp")
                    }
                    onResize(termCols, termRows)
                    delay(120)
                    onResize(termCols, termRows)
                }
            }

            val cursorPos = remember(terminalVersion, scrollbackOffsetRows, termRows) {
                emulator.getCursorPositionInWindow(
                    scrollbackOffsetRows = scrollbackOffsetRows,
                    windowRows = termRows,
                )
            }
            val cursorAnim = rememberInfiniteTransition(label = "terminal_cursor")
            val cursorAlpha by cursorAnim.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = AppMotion.TERMINAL),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "terminal_cursor_alpha"
            )

            val terminalBgColor = Color.Black
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(rowHeightPx, maxScrollbackOffsetRows) {
                        var accumulatedScale = 1f
                        detectTransformGestures { _, pan, zoom, _ ->
                            if (zoom != 1f) {
                                accumulatedScale *= zoom
                                if (BuildConfig.DEBUG) {
                                    Log.d("TerminalZoom", "gesture: zoom=$zoom accumulated=$accumulatedScale")
                                }
                                if (accumulatedScale < 0.9f || accumulatedScale > 1.1f) {
                                    val increase = accumulatedScale > 1f
                                    val current = latestFontSizeSp
                                    val next = (current + if (increase) 1f else -1f)
                                        .coerceIn(6f, 20f)
                                    if (BuildConfig.DEBUG) {
                                        Log.d("TerminalZoom", "threshold hit: increase=$increase current=$current next=$next")
                                    }
                                    if (next != current) {
                                        onFontSizeChange(next)
                                    }
                                    accumulatedScale = 1f
                                }
                            }

                            if (maxScrollbackOffsetRows > 0 && pan.y != 0f) {
                                terminalScrollState.dispatchRawDelta(-pan.y)
                                val nearBottomAfterPan = terminalScrollState.value >=
                                    (maxScrollPx - followThresholdPx).coerceAtLeast(0)
                                terminalFollowMode = nearBottomAfterPan
                            }
                        }
                    }
            ) {
                // Canvas layer: draw each character at its exact grid position to
                // guarantee monospaced alignment for box-drawing characters.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val nativeCanvas = drawContext.canvas.nativeCanvas

                    // Paint for background fills — no anti-aliasing for pixel-perfect
                    // row tiling (matches Termux approach).
                    val bgPaint = android.graphics.Paint().apply {
                        isAntiAlias = false
                        style = android.graphics.Paint.Style.FILL
                    }

                    // Fill the entire terminal area with the default background.
                    bgPaint.color = terminalBgColor.toArgb()
                    nativeCanvas.drawRect(0f, 0f, size.width, size.height, bgPaint)

                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = terminalStyle.fontSize.toPx()
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    // Baseline offset: -ascent positions glyphs correctly within
                    // each row (ascent is negative, so -ascent is positive).
                    val baseline = -textPaint.fontMetrics.ascent
                    val rowH = rowHeightPx.toFloat()

                    for ((rowIdx, runs) in renderedRuns.withIndex()) {
                        val y = ((rowIdx * rowHeightPx) - verticalOffsetPx).toFloat()
                        if (y + rowH <= 0f || y >= size.height) continue
                        for (run in runs) {
                            val x = run.col * charWidthPx
                            // Draw background rectangle for the whole run.
                            // Integer row height with integer y-positions tiles exactly —
                            // no overlap needed (matches Termux).
                            if (run.bg != Color.Unspecified && run.bg != terminalBgColor) {
                                bgPaint.color = run.bg.toArgb()
                                nativeCanvas.drawRect(
                                    x, y,
                                    x + run.text.length * charWidthPx, y + rowH,
                                    bgPaint
                                )
                            }
                            // Configure paint for this run's style.
                            textPaint.color = run.fg.toArgb()
                            val typefaceStyle = when {
                                run.bold && run.italic -> android.graphics.Typeface.BOLD_ITALIC
                                run.bold -> android.graphics.Typeface.BOLD
                                run.italic -> android.graphics.Typeface.ITALIC
                                else -> android.graphics.Typeface.NORMAL
                            }
                            textPaint.typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, typefaceStyle)
                            textPaint.isUnderlineText = run.underline
                            // Draw each character individually at its grid position.
                            val textY = y + baseline
                            for ((i, ch) in run.text.withIndex()) {
                                if (ch != ' ') {
                                    nativeCanvas.drawText(
                                        ch.toString(),
                                        x + i * charWidthPx,
                                        textY,
                                        textPaint
                                    )
                                }
                            }
                        }
                    }
                }

                // Invisible text layer for native text selection (long-press copy).
                // We strip all explicit span colors so text is invisible, but the
                // Compose SelectionContainer still draws a visible selection highlight.
                val selectionOutput = remember(terminalVersion) {
                    buildAnnotatedString {
                        append(
                            emulator.renderSelectionText(
                                scrollbackOffsetRows = 0,
                                windowRows = totalRows,
                            )
                        )
                    }
                }
                // Match the selection overlay line height to the canvas row height
                // so selection handles align with the rendered text.
                val selectionLineHeight = with(LocalDensity.current) { rowHeightPx.toSp() }
                val selectionStyle = remember(fontSizeSp, selectionLineHeight) {
                    terminalStyle.copy(
                        color = Color.Transparent,
                        lineHeight = selectionLineHeight,
                    )
                }
                val selectionColors = TextSelectionColors(
                    handleColor = Color(0xFF4FC3F7),
                    backgroundColor = Color(0xFF4FC3F7).copy(alpha = AlphaTokens.FAINT)
                )
                CompositionLocalProvider(
                    LocalTextToolbar provides terminalTextToolbar,
                    LocalTextSelectionColors provides selectionColors
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(terminalScrollState)
                    ) {
                        SelectionContainer {
                            Text(
                                text = selectionOutput,
                                style = selectionStyle,
                                softWrap = false,
                                maxLines = Int.MAX_VALUE,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                if (connected && cursorPos != null) {
                    val cursorCol = cursorPos.second.coerceIn(0, (termCols - 1).coerceAtLeast(0))
                    val cursorRow = cursorPos.first.coerceIn(0, (termRows - 1).coerceAtLeast(0))
                    val cursorX = with(LocalDensity.current) { (cursorCol * charWidthPx).toDp() }
                    val cursorY = with(LocalDensity.current) { (cursorRow * rowHeightPx).toDp() }
                    val cursorW = with(LocalDensity.current) { charWidthPx.toDp() }
                    val cursorH = with(LocalDensity.current) { rowHeightPx.toDp() }

                    Box(
                        modifier = Modifier
                            .offset(x = cursorX, y = cursorY)
                            .size(width = cursorW, height = cursorH)
                            .background(Color(0xFFD3D7CF).copy(alpha = cursorAlpha))
                    )
                }
            }
        }
    }
}
