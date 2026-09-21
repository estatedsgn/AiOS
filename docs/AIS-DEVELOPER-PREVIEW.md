# AIS Developer Preview

AIS is the agent-first Android project. The first hardware target is **Google Pixel 9 Pro**.

## Definition of the first usable preview

The preview is considered testable when a clean device can:

1. boot Android and expose AIS as the primary launcher/home experience;
2. accept a natural-language goal;
3. inspect the current Android UI through the accessibility layer;
4. route actions through native Android capabilities when available and UI automation as fallback;
5. show a human-readable action/result timeline;
6. require approval for consequential external actions;
7. stop an active run immediately;
8. survive agent/network failure without making the phone unusable.

The existing `:core` agent and `:app` Android client remain the starting point. This branch evolves them toward the preview instead of pretending the current APK is already a ROM.

## Runtime architecture

```
User / Agent Home
       |
       v
Planner -> Tool Router -> Verifier -> Approval Policy
             |
       +-----+----------------+----------------+
       |                      |                |
 Android tools          Service APIs      UI automation
 intents/system APIs    CRM/mail/etc.      accessibility
```

A tool must declare its input schema, risk class and verification strategy. Prefer a direct API or Android API over UI automation. UI automation is the compatibility fallback for apps with no integration.

## AIS Workspace / CRM

The first CRM surface inside AIS is an agent workspace rather than a traditional giant dashboard. It tracks:

- people and organizations;
- conversations and source application;
- tasks, promises and follow-ups;
- meetings and deadlines;
- opportunities/deals;
- agent-proposed actions awaiting approval;
- completed actions with evidence/result;
- daily brief items.

The daily brief is generated from these normalized records, not by blindly dumping notification text into a model.

### Core entities

```
Contact(id, displayName, channels, tags)
Conversation(id, contactId, source, externalId, updatedAt)
WorkItem(id, kind, title, contactId?, dueAt?, status, sourceRef?)
Deal(id, title, contactId?, stage, amount?, currency?, nextAction?)
Proposal(id, tool, arguments, reason, risk, status)
Activity(id, source, type, summary, occurredAt, sourceRef?)
```

No password, authentication token or raw secret belongs in these records.

## Approval levels

- READ: autonomous.
- LOCAL: autonomous reversible device action.
- EXTERNAL: sending a message, creating/changing remote data; approval by default.
- SENSITIVE: money, destructive changes, permission expansion or security changes; explicit approval and no silent fallback.

Per-contact or per-tool grants may later reduce prompts, but they must be revocable.

## Pixel 9 Pro installation target

The ROM work is device-specific. Do not flash a build intended for another Pixel.

Host requirements:

- Windows 10/11, macOS or Linux;
- current Android platform-tools (`adb` / `fastboot`);
- reliable USB-C data cable;
- Pixel 9 Pro that allows **OEM unlocking**;
- backup of anything important: bootloader unlocking wipes the device.

Before any custom image is flashed, verify that the stock factory image can be restored.

## Current status

The repository already has a buildable Android application, accessibility controller, planner loop and safety policy. The existing APK is useful for testing the agent runtime on stock Android. It is **not yet the Pixel 9 Pro ROM image**.

The next engineering milestone is intentionally boring and therefore valuable: make the agent workspace + tool router testable as an APK first, then move the proven components into the Pixel system image.
