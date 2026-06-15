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

## ✅ SPEC 3.0.0 CONFORMANCE — DONE (string RETURNS, callee-allocated, 2026-06-15)

The cross-language **`SPEC.md` is at v3.0.0** — a **BREAKING** change. String
**returns** moved from the old **caller-allocated out-parameter** convention to the **canonical
callee-allocated** convention: the export returns an **i32 pointer to a callee-allocated `[ptr,len]`
pair**; the host reads the pair, then calls a paired `cabi_post_<name>(retArea)` export (if present)
to let the module free that allocation.

**This port now implements SPEC 3.0.0 for the CANONICAL profile** (aligned 2026-06-15, VERIFIED via
`./gradlew test` → 24/24 pass, 0 skipped). String-return decoding:

- **`UniversalWasmLoader.kt` — `ModuleHandle.decodeStringReturn(name, wasmFn, wasmArgs)`.**
  - `StringAbi.CANONICAL` branch: calls the export with **only** the encoded params, captures the
    single **i32** result `retArea`, reads the little-endian `(ptr,len)` pair from `retArea` /
    `retArea+4`, decodes the UTF-8 bytes, then calls **`cabi_post_<name>(retArea)`** (looked up via
    `findExportByName`; skipped if absent), where `<name>` is the camelCase export name. No trailing
    return-area out-param is passed anymore.
  - `StringAbi.WASIC` branch: **unchanged** — still reads exported globals `__str_ret_ptr` /
    `__str_ret_len` (side-channel) after a void call. Kept as a legacy/compat fallback.
- `Abi.witTypeToReturnTypes(type, stringAbi = StringAbi.NONE)` is now **profile-aware**: a CANONICAL
  `string` return maps to a single **i32** result; non-CANONICAL string returns keep `emptyList()`
  (the void side-channel). `buildImportEnv` calls it without `stringAbi` (defaults to `NONE`), so
  host-import callback returns are unaffected.

**Fixture:** `src/test/resources/tests/strings_50.{wasm,wit}` was replaced with the new-ABI fixture
from wasmtk (`tests/bindgen_fixtures/strings_50.*`). The new `.wasm` exports `cabi_realloc` (CANONICAL,
not `__malloc`) plus `cabi_post_greet` / `cabi_post_shout`; export names are camelCase (`greet`,
`shout`, `strLen`). `math_50` / `booleans_50` / `imports_50` fixtures are untouched. The string
conformance tests still assert `greet("World")=="Hello, World!"`, `shout("hi")=="hihi"`,
`strLen("hello")==5` and pass against the new fixture.

String **params** are unchanged (`cabi_realloc(0,0,1,len)` → write → `(ptr,len)`). Numerics/bool
unchanged. Local `SPEC.md` bumped v2.0.0 → 3.0.0 with the callee-allocated return section.

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
