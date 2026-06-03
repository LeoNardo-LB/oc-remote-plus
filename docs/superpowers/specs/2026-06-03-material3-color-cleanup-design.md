# Material 3 Color Cleanup — Design Spec

**Date**: 2026-06-03
**Scope**: Eliminate hardcoded color values, align all buttons and components with Material 3 defaults
**Principle**: Use `MaterialTheme.colorScheme.xxx` semantic tokens instead of inline `Color(0x...)` or `Color.Black`

---

## 1. ButtonTokens Refactor

### Current State

| Role | Component | Colors |
|------|-----------|--------|
| Primary | FilledTonalButton | Custom: `primary`/`onPrimary` |
| Secondary | FilledTonalButton | Custom: `surfaceContainerHighest`/`onSurfaceVariant` |
| Danger | FilledTonalButton | Custom: `error`/`onError` |

### Target State

| Role | Component | Colors |
|------|-----------|--------|
| **Primary** | **Button** (Filled) | Material 3 default: `primary`/`onPrimary` (solid fill) |
| **Secondary** | FilledTonalButton | Material 3 default (no custom colors passed) |
| **Danger** | FilledTonalButton | `errorContainer`/`onErrorContainer` (Material 3 tonal error) |
| AMOLED (all) | Same components | Black container + themed content color + primary border |

### Changes

**`ButtonTokens.kt`**:

- `tonalColors()` → rename to `filledColors()`, return `ButtonDefaults.buttonColors()` for non-AMOLED, keep AMOLED logic (Black + primary)
- `tonalBorder()` → rename to `amoledBorder()`, keep logic unchanged
- `secondaryColors()` → **delete entirely**; Secondary buttons will not pass custom colors
- `dangerColors()` → simplify: non-AMOLED uses `errorContainer`/`onErrorContainer`; AMOLED uses Black + error
- Remove unused imports

**`DialogButtons.kt`**:

- Primary: change from `FilledTonalButton` to `Button`, use `ButtonTokens.filledColors()`
- Secondary: keep `FilledTonalButton`, remove `colors` parameter entirely (Material 3 default)
- Danger: keep `FilledTonalButton`, use `ButtonDefaults.filledTonalButtonColors(containerColor = errorContainer, contentColor = onErrorContainer)`; AMOLED: Black + error
- Add `border = ButtonTokens.amoledBorder()` to Secondary (AMOLED consistency)

---

## 2. Hardcoded Color Replacement

### 2.1 MarkdownContent.kt (2 changes)

| Line | Current | Replace With |
|------|---------|--------------|
| 119 | `Color(0xFF2A2A36)` (AMOLED code block bg) | `MaterialTheme.colorScheme.surfaceContainerHighest` |
| 156 | `Color(0xFF353540)` (AMOLED inline code bg) | `MaterialTheme.colorScheme.surfaceContainerHighest` |

Rationale: AMOLED theme defines `surfaceContainerHighest = Color(0xFF2A2A36)`, so the resolved color is identical. The inline code bg becomes slightly darker (was `0xFF353540`, now `0xFF2A2A36`) but the difference is negligible.

### 2.2 DiffHelpers.kt — `ui/screens/chat/tools/DiffHelpers.kt` (2 changes)

| Line | Current | Replace With |
|------|---------|--------------|
| 64 | `DiffAdded.copy(alpha = 0.1f)` | `DiffAdded.copy(alpha = AlphaTokens.DIFF_BG)` |
| 65 | `DiffRemoved.copy(alpha = 0.1f)` | `DiffRemoved.copy(alpha = AlphaTokens.DIFF_BG)` |

Add to **`Alpha.kt`**: `const val DIFF_BG = 0.1f`

### 2.3 RevertBanner.kt (1 change)

| Line | Current | Replace With |
|------|---------|--------------|
| 45 | `Color(0xFF2A3E44)` (AMOLED banner bg) | `MaterialTheme.colorScheme.tertiaryContainer` |

Rationale: AMOLED theme inherits `DarkColorScheme` which has a `tertiaryContainer` value. The visual difference is minimal.

### 2.4 TerminalKeyboardOverlay.kt (1 change)

| Line | Current | Replace With |
|------|---------|--------------|
| 138 | `Color(0xFF80CBC4)` (active key highlight) | `MaterialTheme.colorScheme.tertiary` |

Rationale: The teal color matches `tertiary` semantic meaning (accent/tertiary action). Dark theme `tertiary = Color(0xFF7DD0E1)` is visually close.

---

## 3. AMOLED Color.Black Cleanup (~40 changes across ~25 files)

### Pattern

All instances of this pattern:

```kotlin
color = if (isAmoled) Color.Black else MaterialTheme.colorScheme.xxx
```

Replace with:

```kotlin
color = MaterialTheme.colorScheme.xxx
```

This works because the AMOLED theme sets:
- `surface = Color.Black`
- `surfaceContainerLowest = Color.Black`
- `background = Color.Black`

**Note:** Only `surface`, `surfaceContainerLowest`, and `background` resolve to `Color.Black` in the AMOLED theme. Other tokens like `secondaryContainer`, `primaryContainer`, `tertiaryContainer`, `errorContainer` inherit Material 3 `darkColorScheme()` defaults (e.g., `secondaryContainer ≈ #332E41`, `errorContainer ≈ #8C1D18`). Replacing `Color.Black` with these tokens in AMOLED mode produces a subtle visual shift from pure black to Material 3's intended dark tint — this is acceptable and aligns with Material Design guidelines.

### Mapping Guide

| Current AMOLED fallback | Use semantic token |
|--------------------------|--------------------|
| `Color.Black` (surface-level bg) | `MaterialTheme.colorScheme.surface` |
| `Color.Black` (container bg) | `MaterialTheme.colorScheme.surfaceContainer` or `surfaceContainerLowest` |
| `Color.Black` (card bg) | `MaterialTheme.colorScheme.surfaceContainer` |

### Affected Files

**Cards/Components** (each has 1-3 instances):
- `ServerCard.kt`, `MessageCard.kt`, `FileCard.kt`, `ReasoningBlock.kt`
- `PermissionRequestCard.kt`, `QuestionCard.kt`, `TodoListCard.kt`, `PatchCard.kt`, `EditToolCard.kt`
- `ErrorPayloadContent.kt`, `ProviderRow.kt`, `LocalRuntimeCard.kt`

**Chat UI**:
- `ChatInputBar.kt` (~7 instances)
- `ChatMessageList.kt` (~2 instances)
- `ChatTopBar.kt` (~1 instance)

**Terminal**:
- `TerminalEmulator.kt` (~2 instances — default background)
- `ChatTerminalView.kt` (~4 instances)
- `SessionTerminalInline.kt` (~2 instances)

**Dialogs**:
- `ImagePreviewDialog.kt` (~1 instance)

**Screens**:
- `HomeScreen.kt` (TopBar bg)
- `SessionListScreen.kt` (FAB bg)
- `SettingsScreen.kt` (Switch track)
- `ServerDialog.kt` (Switch track)
- `ServerModelFilterScreen.kt` (Switch track)
- `LocalLaunchOptionsDialog.kt` (Switch + container)
- `LocalServerLaunchOptionsDialog.kt` (Switch track)

**Other**:
- `AppPickerList.kt` (selected item bg)
- `ChatColors.kt` (tool output container)
- Tool card callers (must update `toolOutputContainerColor()` argument): `ToolCardRenderer.kt`, `WriteToolCard.kt`, `ReadToolCard.kt`, `TaskToolCard.kt`, `SearchToolCard.kt`, `BashToolCard.kt`
- `DiffHelpers.kt` in `ui/screens/chat/tools/` (unchanged line bg)
- `MarkdownContent.kt` (already handled in section 2.1)

### Important Notes

- Each file must be read before editing to verify the exact pattern
- Some `Color.Black` uses are in `when` blocks or ternary chains — handle each case appropriately
- **TerminalEmulator.kt** default background is terminal-specific (not AMOLED-specific) — see Section 4
- Switch track colors that set **both** tracks to `Color.Black` in AMOLED may need special handling — verify they don't break Switch visuals

---

## 4. Items NOT Changing

These have valid reasons to keep custom colors:

| Item | Reason |
|------|--------|
| Terminal ANSI 16-color palette (18 values in `TerminalEmulator.kt`) | ANSI standard, cannot use Material colors |
| Agent 7-color cycle + QUEUED badge (9 values in `ChatColors.kt`) | Cross-platform consistency with opencode CLI TUI |
| `DiffAdded`/`DiffRemoved` (Color.kt constants) | Version control convention (green/red) |
| `StatusConnected` (Color.kt constant) | Green = connected is industry standard |
| Terminal UI colors (handle, cursor in `SessionTerminalInline.kt`) | Must match terminal theme |
| `Color.Transparent` (8 instances) | Standard Compose constant, not a custom color |
| AlphaTokens usages (~150 instances) | Already part of the design token system |
| TerminalEmulator default background (2 unconditional `Color.Black`) | Terminal always has black background, not AMOLED-specific |
| `AmoledCard.kt` (`Color.Black` in AMOLED branch) | `surfaceContainerHighest` in AMOLED is `0xFF2A2A36`, not `Color.Black`; component exists specifically for this distinction |

---

## 5. Verification

After all changes:

1. `compileDevDebugKotlin` — must pass with no new errors
2. Visual check: all three button roles render correctly in Light/Dark/AMOLED
3. Visual check: Markdown code blocks, Diff backgrounds, RevertBanner, terminal keyboard overlay — no visual regressions
4. Version bump + build + release
