# Android design for Numi

Research checked on 2026-10-11. The target is a native Android messaging and calling app with short labels, visible status, and direct actions. Recommendations below are product decisions inferred from the cited guidance; they are not Google requirements for Numi.

## Screen structure

Use three labelled peer destinations: **Messages**, **Calls**, **Settings**. Keep Spam within Messages, and automatic filtering preferences within Settings. Preserve the Fossify messaging and dialer features while improving their surrounding navigation. Android recommends a bottom navigation bar for compact windows and a rail as windows expand. [Adaptive navigation](https://developer.android.com/develop/ui/compose/layouts/adaptive/build-adaptive-navigation).

Settings should be a grouped list showing current values. Give **Telegram**, **Spam filter**, and **Device access** their own screens; retain access to messaging and calling preferences. Use the same name for a destination row and its screen title. Android's settings guidance calls for brief labels, optional supporting text, and status instead of behavioral descriptions. Group related rows rather than giving every preference a separate card. [Settings design and writing](https://developer.android.com/design/ui/mobile/guides/patterns/settings).

| Screen | Visible controls and status |
| --- | --- |
| Messages | Conversation list, Search, New message, Spam entry |
| Calls | Existing Recents, Contacts, Favorites, and dialpad actions |
| Settings | Telegram status; Spam filter status; Device access; Messages; Calls |
| Telegram | Master switch; Connected / Paused / Needs setup; Connection; Test connection; Conversation topics |
| Connection | Bot token, Owner ID, Save; field-level validation |
| Device access | SMS app, Phone app, Permissions, Battery; current state and the relevant system action |
| Spam | Sender, timestamp, message preview; open message; Move to inbox; Always allow sender |
| Spam filter | Automatic filtering switch; Ready / Checking / Downloading / Needs download; one applicable recovery action |

Put explanations behind Help or the relevant edit screen when needed. Show a brief prerequisite beside an unavailable control, such as **Enable topics in BotFather**. Saving a bot configuration and testing a connection are actions; enabled/disabled preferences use switches. [Settings selection and dependencies](https://developer.android.com/design/ui/mobile/guides/patterns/settings), [Compose switches](https://developer.android.com/develop/ui/compose/components/switch).

## Material implementation

- Use existing `androidx.compose.material3` components: `Scaffold`, `TopAppBar`, `ListItem`, `Switch`, `OutlinedTextField`, `Button`, and standard progress indicators. Share the theme and reusable settings rows. Use `MaterialTheme.colorScheme`, typography, and shapes; keep decoration secondary to content. [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3).
- Support dynamic light/dark colors on Android 12+, with a static palette below API 31. Respect the existing Fossify appearance preferences. Match Compose and XML colors through semantic roles; Views have their own `DynamicColors` integration. [Compose theming](https://developer.android.com/develop/ui/compose/designsystems/material3), [Dynamic colors for Views](https://developer.android.com/develop/ui/views/theming/dynamic-colors).
- Give connection fields persistent labels and suitable keyboard/IME actions. Mask the bot token and validate errors beside the field. Use Material fields rather than drawing custom input chrome. Keep the field API compatible with the pinned Compose BOM; the newest state-based examples are not an automatic dependency-upgrade requirement. [Text input](https://developer.android.com/develop/ui/compose/text/user-input), [BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping).
- Express progress in the affected row with an indicator and short status. Use percentages only when actual progress is known. Keep model/runtime names out of routine controls; download size and on-device processing can help users decide. [Progress indicators](https://developer.android.com/develop/ui/compose/components/progress).

### Insets and accessibility

Enable edge-to-edge deliberately, applying system-bar, cutout, and keyboard insets once. Consume `Scaffold` padding before additional inset modifiers. Keep the last input, primary action, dialpad, and FAB reachable with the keyboard open and under gesture or three-button navigation. Edge-to-edge becomes enforced on Android 15 when targeting SDK 35; earlier targets can opt in. [Compose inset consumption](https://developer.android.com/develop/ui/compose/system/insets-ui), [View edge-to-edge behavior](https://developer.android.com/develop/ui/views/layout/edge-to-edge).

Make toggle rows one accessible target using `Modifier.toggleable(role = Role.Switch)` and a child `Switch(onCheckedChange = null)`. Reserve **48 × 48 dp minimum** interactive areas; do not shrink them to fit short text. Label icon-only actions, leave decorative icons undescribed, and rely on native text/control semantics instead of duplicating every label in `contentDescription`. [Compose accessibility defaults](https://developer.android.com/develop/ui/compose/accessibility/api-defaults).

Use scalable type, allow settings labels to wrap, and check 200% font scaling. Verify text contrast of at least 4.5:1 and non-text controls at 3:1; status needs words or icons as well as color. Test TalkBack traversal and provide visible alternatives to gestures. [Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility).

## Agent skills evaluated from source

**Recommended primary: `android-material-guidelines`.** This is a concise native Android workflow, even though its host repository also contains web code. It requires current official Android/Material sources, supports existing Views, and avoids copying web/iOS navigation and density. The full entrypoint and its official-links reference were inspected. Both declare/use native Android guidance rather than React MUI. [Pinned skill](https://github.com/zoonk/zoonk/blob/944dce2e8814b8043115bac8fa59d2848b924fbf/.agents/skills/android-material-guidelines/SKILL.md), [pinned reference](https://github.com/zoonk/zoonk/blob/944dce2e8814b8043115bac8fa59d2848b924fbf/.agents/skills/android-material-guidelines/references/official-links.md).

| Recommendation | Repository revision | Exact path |
| --- | --- | --- |
| Primary: Zoonk Android Material workflow | `zoonk/zoonk@944dce2e8814b8043115bac8fa59d2848b924fbf` | `.agents/skills/android-material-guidelines/SKILL.md` |
| Optional: native Compose pattern reference | `wshobson/agents@46891e7e60da0e52baf1050b7b6391b64e84c6d9` | `plugins/ui-design/skills/mobile-android-design/SKILL.md` |

The optional wshobson skill and `references/details.md` cover native Compose navigation, state restoration, dynamic themes, typography, and layouts. Treat snippets as examples: its blanket advice to add descriptions to all interactive elements conflicts with Compose's built-in semantics, and routine lambda memoization should not replace profiling. Keep the referenced directory with the entrypoint. [Pinned entrypoint](https://github.com/wshobson/agents/blob/46891e7e60da0e52baf1050b7b6391b64e84c6d9/plugins/ui-design/skills/mobile-android-design/SKILL.md), [pinned detailed patterns](https://github.com/wshobson/agents/blob/46891e7e60da0e52baf1050b7b6391b64e84c6d9/plugins/ui-design/skills/mobile-android-design/references/details.md).

Other inspected sources:

- `hhushhas/material-ui-skill@233c86cf240a282f74ecad34ea75c4d286257fd1`, `material-ui/SKILL.md`: genuinely native Compose M3 despite the name; good restrained operational UI advice. Its contract assumes app-specific `core/design` helpers and business color meanings that Numi does not have. Useful ideas, less portable as an unchanged skill. [Entrypoint](https://github.com/hhushhas/material-ui-skill/blob/233c86cf240a282f74ecad34ea75c4d286257fd1/material-ui/SKILL.md), [implementation contract](https://github.com/hhushhas/material-ui-skill/blob/233c86cf240a282f74ecad34ea75c4d286257fd1/material-ui/references/implementation-contract.md).
- `hamen/material-3-skill@14385f2bf3804d8779f8b4db2604211f1e70b4c1`, `skills/material-3/SKILL.md`: Compose-first but includes Flutter and substantial web/CSS/component tables. Broader than this redesign requires. [Entrypoint](https://github.com/hamen/material-3-skill/blob/14385f2bf3804d8779f8b4db2604211f1e70b4c1/skills/material-3/SKILL.md).
- Official `android/skills@42dc2270e96032bd860bb94511e440aa00a43125`: `system/edge-to-edge/SKILL.md` requires raising target SDK to 35; `jetpack-compose/adaptive/SKILL.md` requires all-Compose screens and Navigation 3. Numi currently targets 34 and retains XML forks, so these migration workflows would expand scope. Consult their focused inset guidance without invoking wholesale migrations. [Edge-to-edge skill](https://github.com/android/skills/blob/42dc2270e96032bd860bb94511e440aa00a43125/system/edge-to-edge/SKILL.md), [adaptive skill](https://github.com/android/skills/blob/42dc2270e96032bd860bb94511e440aa00a43125/jetpack-compose/adaptive/SKILL.md).

MUI's **Material UI library** is React/web and currently implements Material Design 2. It is separate from the native Android skill above and is not a UI dependency for Numi. [MUI's own platform and design description](https://mui.com/material-ui/).

No skills were installed globally. Source revisions were verified through read-only GitHub commit queries or `git ls-remote`; evaluation used actual entrypoints and referenced content rather than skill rankings.

## Acceptance checks

Top-level Messages, Calls, and Settings switches have no activity enter/exit animation. They select a destination at the same hierarchy level. Detail and edit screens retain normal Back navigation. [Android activity animation options](https://developer.android.com/reference/android/app/ActivityOptions#makeCustomAnimation(android.content.Context,%20int,%20int)).

Build and lint the app; inspect fresh screenshots of Messages, Calls, Settings, Telegram, Device access, Spam, and Spam filter. Check light/dark themes, font scaling, long labels, keyboard focus, system bars, Back navigation, and full-row toggles. Exercise empty, connected, paused, failed, missing-model, downloading, and ready states. Preserve existing data and domain behavior while verifying SMS, calls, Telegram setup, filtering, and restoration through their visible controls.

## Copy and icon requirements

Do not add Unicode dot separators, bullets, or ellipses to authored Android or Telegram copy. Use short labels, commas, or separate lines. Preserve incoming message content exactly, including punctuation written by its sender. Help belongs in the relevant edit screen or an explicit Help action.

Use Google's filled **Material Symbols Rounded `perm_phone_msg`** for the launcher and connector notification icon. It combines a handset and message bubble. The source is pinned at `google/material-design-icons@49d4db35df873165d6bd6ba09b063c7dafbac2f4`; the Apache-2.0 license is packaged in `assets/material-symbols-LICENSE.txt`. [Official Android vector](https://github.com/google/material-design-icons/blob/49d4db35df873165d6bd6ba09b063c7dafbac2f4/symbols/android/perm_phone_msg/materialsymbolsrounded/perm_phone_msg_fill1_24px.xml), [license](https://github.com/google/material-design-icons/blob/49d4db35df873165d6bd6ba09b063c7dafbac2f4/LICENSE).

Keep a transparent 108dp foreground canvas with the glyph centered inside the 66dp safe area. Numi's glyph occupies 42dp, leaving visible padding inside launcher masks. Provide a separate opaque monochrome silhouette so supported launchers can tint it after the user enables themed icons on Android 13+. The app does not force the launcher's themed-icon preference. [Android adaptive and themed icons](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive).

The normal adaptive icon also uses the user's system accent palette on Android 12+, with a static fallback on earlier versions and reversed light/dark contrast. The colors reference Android's public `system_accent1_100` and `system_accent1_700` resources. This is an implementation choice inferred from the platform palette APIs; it does not require reading the wallpaper. Default in-app appearance follows the same system palette through the existing Commons theme and native Views. [System dynamic colors](https://source.android.com/docs/core/display/dynamic-color), [public accent resources](https://developer.android.com/reference/android/R.color#system_accent1_100).

## Implementation verification

The host now provides shared Messages, Calls, and Settings navigation, concise settings rows, a focused Telegram connection form, and separate Spam and Spam filter screens. The native SMS/MMS and dialer features remain in their existing modules. Shared Commons appearance preferences supply both Views and Compose.

Local validation passed: 31 Android unit tests, release lint with zero errors, 11 release-planner/copy checks, and four isolated formatting device tests. Atlas screenshots covered all seven main screens, normal and 200% font scaling, light/dark settings, the connection form with the keyboard open, and native appearance settings. Repeated bottom-tab clicks selected the correct activity without the false Fossify dialog after a clean process restart.

The inherited Commons startup and appearance guards assumed an `org.fossify` package. The compatibility transform now recognizes the actual application package. During verification Atlas retained a process started at 18:44:03 across an APK replacement recorded at 18:48:37, so the old dialog could still appear despite the patched APK being installed. Explicitly stopping and relaunching Numi loaded the corrected code; deployment verification must use a fresh process after replacement.

The signed candidate preserved all fields of 28 SMS rows and five call-history rows, all 27 filter decisions (10 safe and 17 spam), both default-app roles, Telegram configuration, and the existing Gemma model/head hashes. It generated no historical Telegram alerts. Private snapshots and screenshots remain outside the repository.
