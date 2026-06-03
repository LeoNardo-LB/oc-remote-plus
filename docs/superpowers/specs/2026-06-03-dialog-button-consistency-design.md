# Dialog Button Consistency — Design Spec

Date: 2026-06-03

## Problem

After the previous round of button unification (v2.0.0-beta.135), 4 categories of inconsistency remain:

1. **Single-select picker dialogs have redundant Cancel buttons** — 6 picker dialogs each contain a single "Cancel" button with no confirm action. Clicking a list item already selects and closes the dialog (like `ModelPickerDialog` which has no buttons at all).

2. **Page-level buttons still use native `Button`** — 4 locations use `Button` instead of `FilledTonalButton`, creating visual inconsistency with the unified card buttons.

3. **ServerProvidersScreen OAuth dialog manually builds buttons** — A `Row` + `TextButton` + `FilledTonalButton` layout that should use `DialogButtons`.

4. **`DialogButtons` internal `FilledTonalButton` lacks AMOLED adaptation** — `amoledTonalButtonColors()` is only used for card buttons; dialog Primary buttons get default tonal colors in AMOLED mode, mismatching card button appearance.

## Scope

### In scope

| # | Change | Files |
|---|--------|-------|
| 1 | Remove Cancel buttons from 6 single-select picker dialogs | ImageCompressionDialog, ReconnectModePickerDialog, MessageCountPickerDialog, FontSizePickerDialog, LanguagePickerDialog, ThemePickerDialog |
| 2 | Replace 4 native `Button` with `FilledTonalButton` | SessionListScreen, ChatScreen, EmptyServersView, QuestionCard |
| 3 | OAuth dialog: manual Row → `DialogButtons` | ServerProvidersScreen |
| 4 | `DialogButtons` Primary button: add AMOLED colors + border | DialogButtons.kt |

### Out of scope (justified current usage)

- **ListItem inline TextButton** (ProviderRow, ServerProvidersScreen Connect/Disconnect) — compact inline actions, correct as TextButton
- **Batch action bar TextButton** (ChatMessageList "全部拒绝"/"全部跳过") — contextual batch actions, TextButton is appropriate
- **MarkdownPreviewDialog TopBar TextButton** — TopBar action slot, standard Material 3 pattern
- **ImagePreviewDialog IconButton** — overlay icon actions, not standard text buttons
- **ModelPickerDialog** — already correct (no buttons, click to select)
- **DirectoryDetailsDialog** — single "Copy Path" Secondary button, functionally necessary
- **SessionDetailsDialog** — 3 action buttons in Column full-width via DialogButtons, correct per existing layout rules
- **LocalLaunchOptionsDialog** — full-screen Dialog+Scaffold, different pattern, not a BasicAlertDialog

## Detailed Design

### 1. Remove Cancel from single-select picker dialogs

**What changes**: Delete the `DialogButtons(buttons = ...)` call and the `Spacer(Modifier.height(16.dp))` before it in each picker dialog. The `onDismissRequest` on `BasicAlertDialog` already handles closing via tap-outside or back button.

**Files** (6 dialogs, all follow identical pattern):

- `ui/screens/settings/components/ImageCompressionDialog.kt` — 2 dialogs (MaxSide + Quality)
- `ui/screens/settings/components/ReconnectModePickerDialog.kt`
- `ui/screens/settings/components/MessageCountPickerDialog.kt`
- `ui/screens/settings/components/FontSizePickerDialog.kt`
- `ui/screens/settings/components/LanguagePickerDialog.kt`
- `ui/screens/settings/components/ThemePickerDialog.kt`

**Pattern to remove** (appears in each):
```kotlin
// DELETE these lines from each picker dialog:
Spacer(Modifier.height(16.dp))
DialogButtons(
    buttons = listOf(
        Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary, onDismiss)
    )
)
```

After removal, unused imports may need cleanup:
- `DialogButtons`, `DialogButtonRole` from `dev.minios.ocremote.ui.components.DialogButtons`
- `Spacer` if no longer used elsewhere in the function

### 2. Native `Button` → `FilledTonalButton`

**4 locations**:

| File | Line | Context | Change |
|------|------|---------|--------|
| `SessionListScreen.kt` | 210 | Error state retry | `Button(...)` → `FilledTonalButton(...)` |
| `ChatScreen.kt` | 973 | Error state retry | `Button(...)` → `FilledTonalButton(...)` |
| `EmptyServersView.kt` | 44 | "Add server" empty state | `Button(...)` → `FilledTonalButton(...)` + add AMOLED colors |
| `QuestionCard.kt` | 403 | Submit answer | `Button(...)` → `FilledTonalButton(...)` |

For `EmptyServersView.kt`, also add `amoledTonalButtonColors()` and `amoledTonalButtonBorder()` since it's a full-width card-like button. Import from `DialogButtons.kt`.

For `QuestionCard.kt`, the Submit `Button` is in a Row next to a `TextButton`(Dismiss). The `Button` should become `FilledTonalButton` to match the Primary action pattern. No AMOLED helpers needed here (it's inside a Surface card with its own AMOLED handling).

### 3. OAuth dialog → `DialogButtons`

**File**: `ui/screens/server/ServerProvidersScreen.kt`, the OAuth code-input dialog (~line 215-404).

**Current** (manual layout):
```kotlin
Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    TextButton(onClick = { ... }) { Text(stringResource(R.string.cancel)) }
    if (pending.authorization.method == "code") {
        FilledTonalButton(onClick = { ... }, enabled = ...) {
            Text(stringResource(R.string.server_settings_oauth_complete))
        }
    }
}
```

**Change to**:
```kotlin
val oauthButtons = buildList {
    add(Triple(stringResource(R.string.cancel), DialogButtonRole.Secondary) {
        oauthCode = ""
        viewModel.cancelProviderOauth()
    })
    if (pending.authorization.method == "code") {
        add(Triple(
            stringResource(R.string.server_settings_oauth_complete),
            DialogButtonRole.Primary
        ) {
            viewModel.completeProviderOauth(oauthCode)
            oauthCode = ""
        })
    }
}
DialogButtons(buttons = oauthButtons)
```

Note: The `enabled` state on the Complete button needs consideration. `DialogButtons` doesn't currently support per-button `enabled`. Two options:
- **Option A**: Always render both buttons, but disable the Complete button via a condition check inside the onClick (current behavior already checks `oauthCode.isNotBlank() && !uiState.isSaving` in the `enabled` param). We can keep the button always enabled but guard the onClick.
- **Option B**: Conditionally add the Complete button (current behavior — it only shows when `method == "code"`). The `enabled` state would need to be handled differently.

**Decision**: Option B (conditionally add) matches current behavior. The `enabled` check for `oauthCode.isNotBlank()` is already guarded by the onClick lambda. We accept that the button appears always clickable but the action is no-op if blank. If needed, we can add `enabled` support to `DialogButtons` later as a separate task.

### 4. `DialogButtons` AMOLED adaptation

**File**: `ui/components/DialogButtons.kt`

**Current** (`DialogActionButton` Primary branch):
```kotlin
DialogButtonRole.Primary -> {
    FilledTonalButton(onClick = onClick, modifier = modifier) {
        Text(text)
    }
}
```

**Change to**:
```kotlin
DialogButtonRole.Primary -> {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier,
        colors = amoledTonalButtonColors(),
        border = amoledTonalButtonBorder(),
    ) {
        Text(text)
    }
}
```

This reuses the existing `amoledTonalButtonColors()` and `amoledTonalButtonBorder()` already defined in the same file. No new helpers needed.

## Verification

1. `.\gradlew :app:compileDevDebugKotlin` — compile check
2. Visual check: AMOLED mode on — dialog Primary buttons should now match card FilledTonalButton appearance (black container + primary content + primary border)
3. Visual check: all 6 picker dialogs should have no bottom button area
4. Visual check: error retry buttons, add-server button, question submit button should all be FilledTonalButton

## Version

Bump to `2.0.0-beta.136` / versionCode 336 after changes compile clean.
