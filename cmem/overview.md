# Overview — universalWasmLoader-jvm

## What it is

The **JVM (Java/Kotlin) port** of the Universal WASM Loader. It loads a `.wasm` module, auto-detects
the companion `.wit` file, applies the Canonical/Wasic ABI, and returns an ABI-translated handle keyed
by camelCase WIT export names. It is the JVM-side equivalent of the JS reference loader
(`@jrmarcum/universalwasmloader`, JSR) — the same conceptual `wasmImport` / `createSingleton` /
`InstancePool` surface, expressed idiomatically for the JVM.

- **Language:** Kotlin (consumable from Java 17+, Kotlin, Scala, Clojure, any JVM language).
- **WASM runtime:** **Chicory** (`com.dylibso.chicory:runtime` + `:wasm`, v1.0.0) — a pure-Java WASM
  runtime, chosen so the loader is truly universal across JVM platforms with **no native code** (JNI
  alternatives like `wasmtime-java` were rejected because they need a per-platform `.dll`/`.so`/`.dylib`).
- **Async dependency:** `kotlinx-coroutines-core` 1.9.0 (for `wasmImport` URL loading and `InstancePool`).
- **Package registry:** **Maven Central** (Sonatype Central Portal) via the
  `com.vanniktech.maven.publish` plugin. Coordinates: `io.github.jrmarcum:universal-wasm-loader-jvm`
  (the README still shows the older `com.jrmarcum:...` groupId — `build.gradle.kts` is authoritative:
  group `io.github.jrmarcum`). Current version: **0.1.2** (`build.gradle.kts`). Not yet released —
  CI publish on `v*` tag requires four GitHub secrets to be set first (see Release flow below).
- **Toolchain:** Gradle 9.x (Kotlin DSL), Kotlin 2.2.0, JVM target 24, JDK 25 toolchain (requires JDK 24+).

## Source layout

```
src/main/kotlin/com/jrmarcum/universalwasmloader/
├── UniversalWasmLoader.kt   # Public API + ModuleHandle + string-return decode + load/instantiate helpers
├── Abi.kt                   # ABI encode/decode, MemRef, import-env builder, StringAbi enum
├── Callbacks.kt             # Host import callback builder (.on(camelName){...} 0–3 arg overloads)
└── WitParser.kt             # Regex-based, zero-dependency WIT parser
src/test/kotlin/.../LoaderTest.kt   # 24 @Test cases (mirrors SPEC §8)
src/test/resources/tests/           # math_50 / booleans_50 / strings_50 / imports_50 (.wasm + .wit)
```

## Public API surface (what actually exists)

All in `UniversalWasmLoader.kt` unless noted.

- `wasmLoad(path, callbacks = Callbacks()): ModuleHandle` — **synchronous**, local file / classpath
  resource only. Primary entry point (no coroutine needed). Auto-detects `.wit` (`.wasm`→`.wit`).
  Supports `@N` version pinning (asserts an exported `version` i32 global == N; C SONAME convention).
- `wasmLoad<T>(path, callbacks): T` — reified-generic typed-interface variant. Wraps the handle in a
  `java.lang.reflect.Proxy` implementing interface `T`; each method dispatches to `handle.call(method.name, …)`.
- `wasmImport(source, callbacks): ModuleHandle` — **suspend** fun (coroutines). Same as `wasmLoad`
  but also accepts `http://`/`https://` URLs (fetches `.wasm` and best-effort `.wit` via `HttpClient`).
- `createSingleton(path, callbacks): () -> ModuleHandle` — lazy-cached single handle (Kotlin `lazy{}`).
- `class InstancePool(path, callbacks, size = 4)` — pre-instantiates `size` independent instances
  (each own linear memory); coroutine-safe via `kotlinx.coroutines.sync.Semaphore` + `ArrayBlockingQueue`.
  Methods: `suspend acquire()`, `release(handle)`, `suspend run(fn)` (acquire→call→release, even on throw).
- `class ModuleHandle` — `call(name, vararg args): Any?`, `bind(name): BoundFunction`,
  `isSameInstance(other): Boolean`. `BoundFunction.invoke(vararg args)` wraps a single export.
- `class Callbacks` — builder `.on("camelName") { … }` (0–3 args); keys are camelCase WIT import names
  (`env-mul` → `envMul`).

### Name conventions (critical)
WASM exports are looked up by **camelCase** (`is-positive` → `isPositive`), which is also the JVM API
name (no snake_case conversion, unlike the Rust port). Chicory host-import namespace key is underscore
(`env-mul` → `env_mul`); user callback key is camelCase (`envMul`).

## ABI implemented

`Abi.kt` + `ModuleHandle.callWithAbi`/`decodeStringReturn`. Numeric/bool marshalling:
`s32`↔Int, `s64`↔Long, `f32`↔Float, `f64`↔Double, `bool`↔(`!=0`). Strings are UTF-8.

The loader **auto-detects one of two string ABIs** at instantiation (`detectStringAbi`, scans the
export section):

- **`StringAbi.CANONICAL`** — when `cabi_realloc` is exported. String **params**: `cabi_realloc(0,0,1,len)`
  → write bytes → pass `(ptr,len)`.
- **`StringAbi.WASIC`** — when `__malloc` is exported (the current `strings_50` fixture uses this).
  String **params**: `__malloc(len)` → write bytes → pass `(ptr,len)`.
- **`StringAbi.NONE`** — neither (numeric/bool still work; strings throw).

Import string params (WASM→host) are decoded from `(ptr,len)` off the call stack via `MemRef.current`
(memory captured right after `Instance.build()`, solving the chicken-and-egg of needing memory inside
import callbacks).

## ⚠️ SPEC 3.0.0 CONFORMANCE — ACTION NEEDED (string/aggregate RETURNS)

The cross-language **`SPEC.md` is now at v3.0.0 (2026-06-15)** — a **BREAKING** change. String/aggregate
**returns** moved from the old **caller-allocated out-parameter** convention to the **canonical
callee-allocated** convention: the export returns an **i32 pointer to a callee-allocated `[ptr,len]`
pair**; the host reads the pair, then **must call a paired `cabi_post_<name>(retPtr)` export** to let
the module free that allocation.

**This port still implements the OLD pre-3.0.0 return convention** (it predates 2026-06-15; the local
`SPEC.md` checked into this repo is still labeled **v2.0.0**). Specifically, string-return decoding lives in:

- **`src/main/kotlin/com/jrmarcum/universalwasmloader/UniversalWasmLoader.kt` — `ModuleHandle.decodeStringReturn(...)` (≈ lines 75–101).**
  - `StringAbi.CANONICAL` branch (≈ lines 79–87): allocates an **8-byte return buffer**
    (`cabi_realloc(0,0,4,8)`), **passes it as a trailing arg** (caller-allocated out-param), then reads
    the little-endian `(ptr,len)` pair from that buffer. This is the OLD out-param convention, NOT the
    new callee-allocated return pointer.
  - `StringAbi.WASIC` branch (≈ lines 88–98): reads exported globals `__str_ret_ptr` / `__str_ret_len`
    (side-channel). Also pre-3.0.0.
  - **No `cabi_post_<name>` call exists anywhere** in the codebase.
- Supporting code that also reflects the old model:
  - `Abi.witTypeToReturnTypes` (`Abi.kt` ≈ lines 27–34) maps `string` returns to **`emptyList()`**
    ("string returns use out-param or side-channel") — under SPEC 3.0.0 a string return is a single
    **i32** return value (the `[ptr,len]` pointer).
  - `callWithAbi` (`UniversalWasmLoader.kt` ≈ lines 55–73) appends the trailing return-area arg only
    inside `decodeStringReturn`; there is no post-return free step.

**To align with SPEC 3.0.0 (future work):** change the CANONICAL string-return path so the export is
called with **no** trailing return-area arg, treat its single i32 result as `retPtr`, read
`[ptr,len]` from `retPtr` (two LE i32s) then bytes, and finally call `cabi_post_<name>(retPtr)` if that
export exists. Decide how (or whether) to keep the legacy WASIC global side-channel as a compatibility
fallback. Update the local `SPEC.md` (v2.0.0 → 3.0.0) and the README type-mapping row accordingly.

## Tests

JUnit 5 (`./gradlew test`). `LoaderTest.kt` has **24 `@Test`** cases mirroring SPEC §8, exercising the
four fixtures: `math_50` (numeric round-trip), `booleans_50` (bool normalization), `strings_50`
(string param + return, WASIC ABI), `imports_50` (host import callbacks). No-WIT raw mode is covered by
the repo-root `math.wasm` (numeric, no `.wit`).

## Build / release flow

- Build + test: `./gradlew build` / `./gradlew test`. Requires JDK 24+.
- Release: `./gradlew release` tags HEAD `v<version>` and force-pushes the tag, which triggers
  `.github/workflows/publish.yml` (build → publish to Maven Central via `com.vanniktech.maven.publish`,
  Sonatype Central Portal, GPG-signed). **Before the first release, four GitHub secrets must be set:**
  `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`. To bump:
  edit `version` in `build.gradle.kts`, commit, then `./gradlew release`.
