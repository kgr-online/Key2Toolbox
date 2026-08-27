# Localization extraction spec (temporary — delete before commit)

Goal: pull every user-facing English string literal in the assigned Kotlin files
into `app/src/main/res/values/strings.xml` resources, and replace the literal
with `stringResource(R.string.<key>)` (or `context.getString(...)` off the main
composable path, e.g. inside `scope.launch`/callbacks that build a status message).

## Rules

1. **Do NOT edit `app/src/main/res/values/strings.xml`.** Instead, at the end,
   report the exact `<string name="...">...</string>` lines you would add, as a
   block, grouped by a `<!-- comment -->` naming the module.
2. **Only touch your assigned files.** Edit them in place.
3. Add `import androidx.compose.ui.res.stringResource` where you introduce it.
4. **Key naming** — reuse the vocabulary in `/tmp/.../pr_strings.xml` when a
   matching key exists (same English text or same concept). New keys:
   `<module>_<slot>` snake_case, e.g. `bt_idle_enabled_label`, `zram_size_title`.
   Screen title/subtitle keys: `title_<module>` / `subtitle_<module>` — but those
   live in `Screen.kt`'s `data object` constructor args; convert those too if
   `Screen.kt` is in your set.
5. **Format args**: `"$n selected of $total apps"` becomes
   `<string name="x">%1$d selected of %2$d apps</string>` +
   `stringResource(R.string.x, n, total)`. Use `%1$s` for strings, `%1$d` for ints.
   Escape a literal `%` as `%%`, `'` as `\'`, `&` as `&amp;`, `<` as `&lt;`.
6. **Do NOT extract**: log messages, shell commands, package names, view-id
   strings, `contentDescription = null`, technical constants, comments. Only what
   a user reads on screen.
7. `AccessibilityServiceBanner`'s green/red status text and button labels ARE
   user-facing — extract them.
8. Leave the code compiling. Run `./gradlew compileDebugKotlin` if unsure.
9. Ticker strings already exist as `R.string.ticker_*` — the two Ticker screens
   are mostly done; only convert any remaining hardcoded literals there.

## Deliverable

A short report with: (a) the list of files you converted, (b) the full
`<string>` block to append to values/strings.xml, (c) any strings you were
unsure whether to extract.
