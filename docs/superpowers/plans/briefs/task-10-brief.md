# Task 10: Streaming UI reads activityFlow (A1, approach B: FSM + part.time combined)

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt`

**Decision (B):** `isStreaming` = `(session activity is Streaming) AND (part.time.end == null)`. FSM activity is the authoritative gate (fixes part.time-end-stuck-null bug); part.time.end keeps per-part precision. NOT pure session-level (which would show timer on all reasoning parts during streaming).

## Background — read existing code first

- **ChatViewModel.sessionMetaState** (around line 539-562): a combine of ~5 flows; Task 8+9 made it read `sessionStateService.statusFlow` (index 2). `SessionMetaState` data class (around line 70) has `sessionStatus`. READ both.
- **ChatMessageList** (around line 139): `streamingMsgId` computed from `rawMessages.lastOrNull { it.isAssistant && it.message.time.completed == null }`. READ how it receives params (does it get sessionMeta or individual fields?).
- **PartContent** (around line 117): `val isStreaming = part.time?.end == null` in the Reasoning branch. READ the Reasoning branch + how PartContent is called.
- **ChatScreen**: READ where it renders ChatMessageList + PartContent (to provide CompositionLocal).
- **NOTE: ChatMessageList.kt has uncommitted debug logging (AutoPag Log.d in the pagination section around line 234+). DO NOT touch those lines — only change the streamingMsgId logic around line 139.**

## Step 1: ChatViewModel — add isStreaming to SessionMetaState

1a. Add `val isStreaming: Boolean = false` to `SessionMetaState` data class.

1b. In `sessionMetaState` combine, add `sessionStateService.activityFlow` as a new source flow. Extract it: `val activities = args[N] as Map<String, SessionActivity?>` (N = the new index — add activityFlow as the LAST source to avoid shifting existing indices, OR carefully reindex).

1c. Compute `isStreaming` in the combine: `val isStreaming = (activities[sid] is SessionActivity.Streaming)`. Set `sessionStatus = ..., isStreaming = isStreaming` in the returned SessionMetaState.

Import: `dev.leonardo.ocremotev2.domain.model.SessionActivity`.

## Step 2: ChatMessageList — gate streamingMsgId by isStreaming

The `streamingMsgId` (line ~139) should be null when session is NOT streaming. Determine how ChatMessageList receives the streaming flag:
- If it already receives `sessionMeta` or an `isStreaming`-equivalent, use it.
- Otherwise, add an `isSessionStreaming: Boolean` param to ChatMessageList, passed from ChatScreen (which has sessionMeta).

```kotlin
val streamingMsgId = if (!isSessionStreaming) null else remember(rawMessages) {
    rawMessages.lastOrNull { it.isAssistant && it.message.time.completed == null }?.message?.id
}
```

Keep the rest of ChatMessageList unchanged (esp. the pagination debug logs — leave them).

## Step 3: PartContent — isStreaming = CompositionLocal AND part.time.end

3a. Add a CompositionLocal (in ChatScreen.kt or a shared file):
```kotlin
val LocalSessionStreaming = staticCompositionLocalOf { false }
```

3b. In PartContent's Reasoning branch (line ~117), change:
```kotlin
// BEFORE: val isStreaming = part.time?.end == null
// AFTER (approach B):
val isStreaming = LocalSessionStreaming.current && (part.time?.end == null)
```

Reasoning `durationMs`/`startTimeMs` STAY reading `part.time` (unchanged).

## Step 4: ChatScreen — provide LocalSessionStreaming

Where ChatScreen wraps the message list / content, provide the CompositionLocal:
```kotlin
CompositionLocalProvider(LocalSessionStreaming provides sessionMeta.isStreaming) {
    // existing ChatMessageList / content rendering
}
```

Pass `isSessionStreaming = sessionMeta.isStreaming` to ChatMessageList (if you added that param in Step 2).

## Step 5: Compile

Run: `.\gradlew :app:compileDevDebugKotlin` (120000ms)
Expected: BUILD SUCCESSFUL

## Step 6: Commit

```bash
git add app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatViewModel.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/ChatScreen.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/ChatMessageList.kt app/src/main/kotlin/dev/leonardo/ocremotev2/ui/screens/chat/components/PartContent.kt
git commit -m "refactor(chat): streaming UI reads activityFlow (A1, FSM+part.time combined)"
```

NOTE on ChatMessageList.kt: it has uncommitted debug logs (AutoPag). When you `git add` it, those logs will be staged too. That's acceptable (they're Log.d, harmless) — do NOT try to selectively stage. Just ensure your streamingMsgId change is correct.

## Manual verification (optional, if device available)

Open a session, trigger streaming → Reasoning shows "thinking"; when done → stops. If Bug 2 scenario (background during streaming) reproduces, timer should stop on return (FSM activity gate).
