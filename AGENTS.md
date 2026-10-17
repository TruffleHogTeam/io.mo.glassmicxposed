# AGENTS.md

## Project

GlassMic — open-source LSPosed virtual microphone module for rooted Android devices. Intercepts AudioRecord/AAudio calls and fills them from imported audio files.

## Code search

**Prefer the `codebase-memory` MCP knowledge graph over grep/ripgrep for structural questions.** This repo is already indexed. Graph queries return precise results in ~500 tokens where grep would burn tens of thousands.

- Who calls X / what does X call → `trace_path` (inbound / outbound / both)
- Find symbols by name → `search_graph(name_pattern=...)`
- Read a symbol's source → `get_code_snippet(qualified_name=...)`
- Architecture / modules at a glance → `get_architecture`
- Impact of local edits → `detect_changes`

Fall back to Grep/Glob only for plain-text matches (comments, strings, resources) or when the graph lacks the answer. The index is a snapshot stored outside the repo (`~/.cache/codebase-memory-mcp/`), so **re-index after changing code** (`index_repository`, `fast` mode is fine); a new conversation reuses the existing index without re-indexing.

## Build

```bash
.\gradlew.bat assembleDebug
```

Output: `app/build/outputs/apk/debug/`

Requirements: JDK 17, Android SDK (API 35 required, API 36 recommended), Build-Tools 35.0.0. CMake 4.1.2 and NDK 30.0.14904198 (rc1) are configured for native builds.

**No test suites, lint commands, or typecheck commands are configured.** There are no unit tests or instrumented tests in this repo.

## Module Structure

Three Gradle modules, all built into a single APK:

| Module | Purpose | Key Quirks |
|--------|---------|------------|
| `app` | Compose UI, services, ContentProviders, audio pipeline | Application class, foreground service, floating window, Room DB, Hilt DI |
| `xposed` | LSPosed/libxposed entry points, AudioRecord hooks, native AAudio hook via shadowhook | **arm64-v8a only**, C++ native code under `src/main/cpp/` |
| `core` | Shared models, constants, ScopeMatcher | **Zero heavy dependencies** — must stay loadable in xposed (injected into other processes). Do NOT add androidx or Hilt here. |

Dependency flow: `app` → `core`, `app` → `xposed` → `core`. The `xposed` module is an `implementation` dependency of `app` (bundled into the APK), not a separate install.

## Key Architecture Decisions

- **Per-process hook via LSPosed scope**: LSPosed loads modules into each app process *after* fork, and only for packages in the module's scope — there is no "hook once in zygote, every app inherits it" path (`initZygote` is just a name). `GlassMicXposedModule` / `LegacyXposedEntry` hook `Application.attach` inside each scoped process, then install the Java and native audio hooks. Checking an app on the Scope page requests LSPosed scope via `libxposed:service` (`LsposedServiceManager.requestScope`). Protected processes (systemui, launcher, GMS, input methods, and GlassMic itself) are still blocklisted in `XposedHookGate.kt` in case they get scoped.
- **PCM pipe via ContentProvider**: `PcmStreamProvider` serves PCM data; `XposedPcmReader` reads it. Per-consumer sample-rate/channel conversion happens on the hook side.
- **Dual Xposed API**: Both libxposed API 101 and legacy API 82 are supported. `XposedHookGate.tryMarkAudioHookInstalled()` prevents duplicate hook installation when both entry points fire.
- **Native AAudio hook**: `glass_aaudio.cpp` uses shadowhook (ByteDance) for inline hooking. Links against NDK's `libaaudio.so`.
- **公平运行内存 (ITGSA / HyperOS fair memory)**: `app/.../memory/` adapts the ITGSA memory-fairness contract. `FairMemoryController` registers a runtime receiver for `itgsa.intent.action.TRIM` (+ `.KILL`) on a dedicated HandlerThread, releases memory, and replies over the intent's callback binder (`transact(FIRST_CALL_TRANSACTION, …)`, one-way) inside the system's 3s window. `Application.onTrimMemory`/`onLowMemory` feed the same path. Components that hold droppable memory implement `MemoryReleasable` and register with `MemoryPressureBus` themselves (registration, not injection — injecting them would force the whole audio pipeline to be constructed at process start). **The live audio path is never trimmed**: `PlaybackController` keeps the TTS PCM buffer whenever it's the current source or a consumer is attached.
- **Protobuf Lite**: DataStore uses Proto format. Proto files at `app/src/main/proto/`. Generated Java Lite + Kotlin Lite classes.

## Critical Constraints

- `core` module must remain dependency-free (no androidx, no Hilt). It loads inside hooked processes via the xposed module.
- `ksp.useKSP2=false` in `gradle.properties` — KSP2 has Hilt + Room compatibility issues.
- `jniLibs.pickFirsts` for `libshadowhook.so` and `libshadowhook_nothing.so` — the .so comes from both the `:xposed` module and the shadowhook AAR; content is identical.
- Resource configurations: only `zh-rCN` and `en` are bundled.
- `minSdk = 29` (Android 10), `targetSdk = 35` (Android 15), `compileSdk = 35`.
- Release builds use ProGuard with minification and resource shrinking enabled.

## LSPosed Scope

Default `scope.list` is `system` + `android` (system_server — needed for the package-visibility and volume-key hooks). **Every target app must also be in the LSPosed scope**, and be fully restarted after granting; GlassMic's own whitelist (`ScopeMatcher`) is a second, independent gate.

`MediaRecorder` recording (e.g. Snapchat voice notes) is captured inside the system media service, not the app process, so the LSPosed backend cannot replace it — see `docs/2026-10-17_逆向-Snapchat录音兼容性-report.md`.
