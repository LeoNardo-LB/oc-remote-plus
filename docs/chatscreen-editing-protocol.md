# ChatScreen.kt Editing Protocol

- NEVER edit ChatScreen.kt in parallel across multiple agents
- ALWAYS Read before Edit to confirm current content
- After each Edit, run `.\gradlew.bat :app:compileDebugKotlin`
- Commit after each successful compilation
- If compilation fails: `git checkout -- ChatScreen.kt`, re-read, retry
- One commit = one logical change (no bundling)

## Rationale

ChatScreen.kt is 8000+ lines and contains the entire chat UI. Earlier beta cycles (beta.62-64) showed repeated code loss when multiple changes were made simultaneously. This protocol ensures each change is validated and committed before the next begins.
