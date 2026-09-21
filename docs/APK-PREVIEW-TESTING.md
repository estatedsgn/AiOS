# AIS 0.2.0 APK developer preview

This is an installable Android application, **not a ROM, firmware, a root service, or a complete OpenClaw port**. Pixel 9 Pro on stock Android is the first physical acceptance target. The APK has no CPU-specific native runtime and targets Android API 35, with API 26 as the minimum. Minimum API support is not a claim that every old device has been tested.

## Install without flashing

1. Open this PR's successful **build** workflow and download the **aios-apk** artifact. Extract the ZIP. It contains an `ais-preview-<commit>.apk`, `SHA256SUMS.txt`, `BUILD-INFO.txt` and these instructions.
2. Transfer/open the APK on the phone. Android may ask you to allow installation from that specific browser or file manager. Review that prompt and install.
3. Open **AiOS** from the app list. The first page is the new AIS mobile workspace, not the old screen-agent laboratory.

Or use a computer with Android platform-tools and a USB-debugging-authorized phone:

```sh
adb devices
adb install -r ais-preview-<commit>.apk
adb shell am start -n ai.aios.app/.preview.PreviewActivity
```

No bootloader unlock, OEM unlocking, root, factory reset, flash command or new ROM is required. Do not follow the ROM prerequisites in `AIS-DEVELOPER-PREVIEW.md` for this APK. Do not use the older `tools/install.*` scripts for a least-privilege preview install: those legacy scripts also configure Accessibility. The new main workspace does not need Accessibility.

The APK is debug-signed. CI does not yet use a durable production signing identity. A later CI APK can have a different signing certificate. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, do not automatically uninstall: uninstalling destroys the local workspace and credentials. A stable release signing key and an explicit data migration/export policy are separate release-hardening work. Keep this preview on test data.

## What this slice actually implements

- Russian mobile Agent Home with chat, local work, application list, activity history, keyboard-aware composer and system dictation. Dictation inserts an editable draft and never sends it automatically.
- A real Anthropic-backed conversation/tool loop using the existing provider. Supply your own API key in masked, screenshot-protected settings and opt in to cloud processing. Saving a key does not validate connectivity or account billing.
- Local tasks with optional ISO calendar dates and reversible completion, contacts, and opportunities. They are **AIS records, not remote CRM or Android contacts**. No demo records are silently seeded.
- An encrypted, versioned on-device workspace. Writes report success after commit; unreadable data is not silently reset. Retained history is bounded to 200 chat messages and 300 action records. Limits: 1000 tasks, 500 contacts, 500 opportunities.
- Eight declared tools: `workspace_read`, `task_add`, `task_set_status`, `contact_add`, `deal_add`, `apps_list`, `app_open`, `message_draft`. Read/write risk and routes come from code, not model-supplied metadata.
- `workspace_read` returns the most recent 20 records per collection plus full counts. The complete local collection can be searched in the workspace UI.
- READ/LOCAL tools run without extra prompts. EXTERNAL/SENSITIVE require exact one-action approval. Rejection, failure, timeout and handoff end the run; there is no silent UI fallback. Unknown tools and unknown argument fields are rejected.
- `app_open` requests a named Android launch, not proof that a destination workflow completed. `message_draft` requires approval and opens Android's share chooser. The user picks the application/recipient and sends manually. AIS never labels that handoff as a delivered message.
- Foreground runs have a visible Stop notification. The app refuses to start an agent run without notification permission on Android 13+. Manual workspace editing and app launching still work without it.
- Process recovery marks unfinished work interrupted; it does not restore an approval or replay an external action. Cancellation is checked again after model/approval waits and immediately before execution.
- Optional Android Home role with a searchable app list. Installing never silently replaces the current launcher. Use AIS Settings → choose/restore home, or Android Settings → Apps → Default apps → Home app, to change back.

## First phone test: no API key or Accessibility needed

1. Open **Дела**. Add a task with a valid date, a contact and an opportunity. Close/reopen the app; confirm they remain. Complete and reopen the task.
2. Open **Агент**, send `/сводка`. It must report those real local records. This command is a deterministic local brief, not a simulated model answer.
3. Send `/задача Проверить APK на Pixel`. Verify that exactly one new task appears.
4. Send `/черновик Привет, это проверка AIS`. Reject the approval. Verify the run ends, the journal says REJECTED, and no share chooser opens.
5. Repeat the draft, then press Stop in the notification while approval is pending. No chooser may open afterwards, even if an old UI event is delivered.
6. Repeat and approve once. A share chooser should open with the exact text. Cancel it. AIS must say that sending was **not** performed, not claim delivery.
7. Share text from another app into AiOS. It must only fill the draft; no network request or action may start until you press Send.
8. Open **Приложения**, search and launch an app. Try AIS as Home only after testing ordinary app mode; verify you can open Android settings and restore the stock launcher.

Task dates are not alarms, reminders or background notifications. `/задача`, `/сводка` and `/черновик` are explicitly implemented local commands and remain usable with no model configured. Voice input depends on an installed Android speech recognizer; its availability and processing are controlled by that system/provider component.

## Real model test

Add a valid Anthropic key and a model available to that account, then explicitly opt in to sending chat context and requested workspace data. The key stays in encrypted settings and is never included in workspace records. API usage is charged to the supplied provider account; this preview has a step limit, not a monetary budget.

Try: `Создай задачу проверить договор завтра и покажи мои открытые задачи.` Verify the task and date locally and inspect the journal. Then ask for a message draft and test rejection. Disable networking and retry a harmless question: AIS should show an actionable failure while manual workspace/apps remain usable. No live provider secret is required or injected in CI.

The conversational loop is bounded to eight turns, with up to 2048 requested output tokens per provider call, a 45-second provider timeout and no SDK retries. New network replies cannot execute tools after cancellation. It is not a permanently running background daemon.

## Experimental screen lab

AIS Settings offers the existing Accessibility-based screen agent separately. It requires explicit screen-data consent, your key and manual Accessibility enablement. All device actions are forced through `CONFIRM_EVERYTHING`; a legacy autonomous setting cannot bypass this. Rejection ends the run.

A fresh-screen guard checks the current package, element properties and bounds before screen-dependent execution and uses fresh handles. Returning to AIS to approve may itself change the foreground screen, in which case the old action is rejected. That conservative limitation is intentional. This is **not yet** a seamless cross-app automation experience. Android UI can also change after validation, so important transactions, passwords and banking are out of scope for this laboratory. The lab's older run log remains in memory; the persistent journal belongs to the new workspace runtime.

## Reproducible checks

```sh
./gradlew :core:test --no-daemon
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug --no-daemon
./gradlew :app:connectedDebugAndroidTest --no-daemon
```

CI runs the Android tests on an API 35 Google APIs x86_64 emulator with a Pixel 6 profile. This verifies application behavior on stock Android, **not physical Pixel 9 Pro hardware**. The physical checks above, real API connectivity, OEM-specific permission UX, system dictation, and Android Home gesture behavior must be exercised on the actual Pixel before daily-use acceptance. A green build alone does not prove all of them.

## Remaining contract / ROM work

No remote CRM or mail sync, OpenClaw Gateway pairing, server agents, web browsing, autonomous third-party sending, background scheduling, persistent conversation/deal relationships, native appointment integration or ROM system privileges are claimed. SERVICE_API and ACCESSIBILITY route identifiers are extension points; the new chat only executes WORKSPACE and ANDROID tools. A future explicit backend adapter and a properly verified UI fallback can extend that registry without bypassing its approval boundary.

A future Pixel ROM needs its own device-specific image, recovery/rollback procedure, signing/update path, threat model and physical validation. Nothing in this APK builds or flashes that image. Keep those milestones separate from this testable application slice.
