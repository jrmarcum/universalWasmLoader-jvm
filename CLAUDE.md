> **⚠️ PORTABLE PROJECT MEMORY NOW LIVES IN `cmem/`** — start at [`cmem/INDEX.md`](cmem/INDEX.md).
> When saving new project memory, write it into the matching `cmem/` topic file (and refresh its
> pointer in `cmem/INDEX.md`). The **"update the project memory"** and **"look for code issues"**
> triggers are defined in `cmem/INDEX.md` and are binding. This `CLAUDE.md` remains as the auto-loaded
> historical archive; `cmem/` is the source of truth.

# CLAUDE.md — universalWasmLoader-jvm

This file is the single source of project context for AI-assisted development. Keep it up to date as the project evolves. **All project memory lives here for portability — do not use external memory files.**

---

## Project Memory

### Architectural decisions

**WASM runtime: Chicory (pure Java)**
Selected `com.dylibso.chicory:runtime` as the WASM runtime because it is pure Java with no native code, making it truly universal across all JVM platforms (Java, Kotlin, Scala, Clojure, Android, GraalVM, etc.) without any native library setup. This matches the "universal" theme of the project.

Alternative considered: `wasmtime-java` (JNI bindings). Rejected — requires native `.dll`/`.so`/`.dylib` per platform, contradicting the universal JVM goal.

**ABI: Two string ABI modes auto-detected**
The loader detects the string ABI at instantiation time by scanning the export section:

- `cabi_realloc` present → **Canonical ABI** (Component model): string params via `cabi_realloc(0,0,1,len)`, string returns via 8-byte out-parameter allocated before the call.
- `__malloc` present → **Wasic ABI**: string params via `__malloc(len)`, string returns by reading the exported globals `__str_ret_ptr` and `__str_ret_len` after the call (they are i32 globals, NOT functions).
- Neither → **StringAbi.NONE** (strings unsupported, numeric/bool types still work).

The test fixtures (`math_50`, `booleans_50`, `imports_50`) use no string allocator and work in raw/numeric mode. `strings_50.wasm` uses the Wasic ABI (`__malloc`, `__str_ret_ptr`, `__str_ret_len`).

`StringAbi` enum lives in `Abi.kt`. Detection runs in `detectStringAbi()` in `UniversalWasmLoader.kt` and is stored on `ModuleHandle`.

#### API design: mirrors JS reference

- `wasmLoad(path)` — synchronous, local files only (primary entry point, no coroutine required)
- `wasmImport(source)` — suspend fun, local files + HTTP/HTTPS URLs
- `ModuleHandle.call(name, vararg args)` — dynamic call
- `ModuleHandle.bind(name)` — returns a standalone callable
- `wasmLoad<T>(path)` — reified generic; wraps handle in a JDK dynamic proxy over interface `T`
- `createSingleton(path)` — lazy-initialized singleton via Kotlin's `lazy {}`
- `InstancePool(path, size)` — coroutine-safe pool using `kotlinx.coroutines.sync.Semaphore`
- `Callbacks` — builder with `.on(camelName) { a, b -> ... }` overloads for 0–3 args

**JVM name convention**
JVM/Kotlin uses camelCase natively, so the API name == the WASM binary name == the camelCase form. Unlike Rust (which converts to snake_case for the user-facing API), JVM users write `m.call("isPositive", ...)` and interface methods named `isPositive(...)`. No extra conversion step.

**Typed interface via JDK Proxy**
`wasmLoad<T>(path)` uses `java.lang.reflect.Proxy.newProxyInstance` to create a runtime implementation of interface `T`. Method names map directly to camelCase WASM export names. This is idiomatic JVM and requires no annotation processing or code generation.

**Version pinning**
`wasmLoad("mod.wasm@2")` strips the `@N` suffix before loading, then asserts the module's `version` global equals `N`. Implementation: iterate `instance.module().exportSection()` to find the export named `"version"` with `ExternalType.GLOBAL`, then read `instance.global(export.index()).getValue().toInt()`. Follows C SONAME convention.

**Memory ref pattern (chicken-and-egg)**
Host import callbacks need linear memory for string decoding, but memory is only available after instantiation. Solved via `MemRef` (a `@Volatile var current`) that is set to `instance.memory()` immediately after `Instance.builder(...).build()` completes.

#### String encoding

**Canonical ABI** (`cabi_realloc` present):

- Export string params: `cabi_realloc(0, 0, 1, len)` → write UTF-8 bytes → pass `(ptr, len)` as two I32 args
- Export string returns: allocate 8-byte return buffer `cabi_realloc(0, 0, 4, 8)` → append buffer ptr as trailing I32 arg → read `(retPtr, retLen)` as little-endian i32 pair → decode UTF-8

**Wasic ABI** (`__malloc` present, used by current test fixtures):

- Export string params: `__malloc(len)` → write UTF-8 bytes → pass `(ptr, len)` as two I32 args
- Export string returns: call function normally (no trailing arg) → read i32 globals `__str_ret_ptr` and `__str_ret_len` via `instance.global(index).getValue()` → decode UTF-8

**Both ABIs**:

- Import string params (WASM → host): read `(ptr, len)` from call stack → decode UTF-8 from linear memory via `memRef.current`

### Pending work

- **Set GitHub secrets before first release** — Publishing is fully wired. Four secrets are needed in the repo settings:
  - `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` — from central.sonatype.com → Account → User Token
  - `GPG_PRIVATE_KEY` — ASCII-armored private key: `gpg --export-secret-keys --armor KEY_ID`
  - `GPG_PASSPHRASE` — the key's passphrase
  Then push a `v*` tag to trigger the CI publish job.

### Design principles (binding for all future work)

1. `wasmLoad(path)` is synchronous — no `await`, no coroutine required for local file loading.
2. Both `val result = m.call("fn", args...)` and `val m = wasmLoad<T>(path)` must work.
3. No options objects, no ABI flags, no explicit WIT paths — zero configuration.
4. Version pinning via `@N` follows C SONAME convention, not Maven/Gradle versioning.
5. `createSingleton` and `InstancePool` share the same minimal signature.
6. See `VISION.md` for the authoritative cross-project design directives.
7. All project memory lives in this file — do not use machine-local memory stores.

---

## Project Overview

**Artifact:** `com.jrmarcum:universal-wasm-loader-jvm`
**Version:** See `build.gradle.kts`
**License:** See `LICENSE`
**Reference implementation:** `universalWasmLoader-js` (JSR)
**Spec:** See `SPEC.md`

A lightweight WebAssembly loader for JVM languages. Auto-detects the companion `.wit` file and applies the Canonical ABI (wasmtime). Works with Java 17+, Kotlin, Scala, Clojure, and any JVM language. Built on Chicory — pure Java, no native code.

## File Structure

```text
universalWasmLoader-jvm/
├── build.gradle.kts            # Gradle build (Kotlin DSL)
├── settings.gradle.kts         # Gradle settings — root project name
├── gradle.properties           # JVM args and Kotlin code style
├── SPEC.md                     # Cross-language loader specification
├── VISION.md                   # Design directives for this loader and all ports
├── PORT_PROMPT.md              # Starting prompt for future language ports
├── how-to-use.kts              # Local usage example (Kotlin script)
├── math.wasm                   # Example WASM module (calculate + version global)
├── README.md                   # Public-facing documentation
├── CLAUDE.md                   # This file — AI/developer project context and memory
├── LICENSE
├── src/
│   ├── main/kotlin/com/jrmarcum/universalwasmloader/
│   │   ├── WitParser.kt        # Regex-based WIT parser (no dependencies)
│   │   ├── Abi.kt              # Canonical ABI encode/decode + MemRef + HostImports builder
│   │   ├── Callbacks.kt        # Host import callback builder (.on() overloads)
│   │   └── UniversalWasmLoader.kt  # Public API: wasmLoad, wasmImport, createSingleton, InstancePool
│   └── test/
│       ├── kotlin/com/jrmarcum/universalwasmloader/
│       │   └── LoaderTest.kt   # Reference test suite (mirrors SPEC.md §8)
│       └── resources/tests/
│           ├── math_50.wasm + .wit         # Numeric round-trip fixture
│           ├── booleans_50.wasm + .wit     # Bool normalization fixture
│           ├── strings_50.wasm + .wit      # String param + return fixture
│           └── imports_50.wasm + .wit      # Host import callback fixture
└── .github/
    └── workflows/
        └── publish.yml         # CI: build → test → publish to Maven Central (on tag)
```

## Architecture

### No-WIT fallback

When no companion `.wit` file is found, `wasmLoad` returns a `ModuleHandle` backed by raw Chicory exports. `call()` uses runtime type inference to encode args (Int → i32, Double → f64, etc.) and decodes results by inspecting the Chicory `ValueType`. Appropriate for modules like `math.wasm` with no WIT.

### WIT-aware form

1. Detect `.wit` alongside `.wasm` (replace suffix); parse with `WitParser`
2. `Abi.buildImportEnv` — builds `HostImports` for Chicory; uses `MemRef` for deferred memory access
3. `Instance.builder(module).withHostImports(hostImports).build()`
4. Set `memRef.current = instance.memory()` so string imports can decode
5. `ModuleHandle.callWithAbi` — encodes JVM args via `Abi.encodeArg`, calls Chicory export, decodes result

### WIT parser (`WitParser.kt`)

Regex-based, zero dependencies. Parses the format emitted by `wasmtk`:

- `package local:name;` → `packageName`
- `world name { import ...; export ...; }` → `imports[]`, `exports[]`
- Kebab-to-camel: `is-positive` → `isPositive` (camelCase — the WASM binary key AND the JVM API name)
- Import WASM key: `env-mul` → `env_mul` (underscore, used as Chicory import namespace key)
- User callback key: `env-mul` → `envMul` (camelCase, used to look up in `Callbacks`)

### ABI utilities (`Abi.kt`)

One profile — Canonical ABI (Chicory/wasmtime):

| WIT type | Export: JVM → WASM arg | Export: WASM result → JVM |
| --- | --- | --- |
| `s32` | `Value.i32(n)` | `result[0].asInt()` |
| `s64` | `Value.i64(n)` | `result[0].asLong()` |
| `f32` | `Value.fromFloat(n)` | `result[0].asFloat()` |
| `f64` | `Value.fromDouble(n)` | `result[0].asDouble()` |
| `bool` | `Value.i32(if v 1 else 0)` | `result[0].asInt() != 0` |
| `string` | `cabi_realloc(0,0,1,len)` → write bytes → `(ptr, len)` | allocate 8-byte buf, pass as trailing arg, read i32 LE pair |

Import callbacks (WASM → JVM): inverse of above; `(ptr, len)` decoded via `memRef.current.readByteArray`.

### Typed interface proxy

`wasmLoad<T>(path)` uses `java.lang.reflect.Proxy` to create a runtime implementation of any interface `T`. Each method invocation dispatches to `ModuleHandle.call(method.name, args)`. Works for any JVM interface — no annotation processing or code generation required.

## WIT Name Conventions

| Form | Example | Used for |
| --- | --- | --- |
| kebab-case (source) | `is-positive` | WIT file |
| camelCase (binary) | `isPositive` | WASM export key (Chicory `instance.export`) |
| camelCase (API) | `isPositive` | JVM method / `call("isPositive")` — same as binary |
| underscore (import key) | `env_mul` | Chicory `HostFunction` field name in `"env"` namespace |
| camelCase (callback key) | `envMul` | `Callbacks.on("envMul", ...)` |

**Critical:** Never look up a WASM export by its kebab-case or snake_case name. Always use camelCase.

## Toolchain

- **Language:** Kotlin 2.2.x (JVM target 24, toolchain JDK 25; requires JDK 24+)
- **Build:** Gradle 9.x with Kotlin DSL (`build.gradle.kts`)
- **Publishing:** `com.vanniktech.maven.publish` 0.29.0 → Sonatype Central Portal (`io.github.jrmarcum`)
- **WASM runtime:** Chicory 1.0.0 (`com.dylibso.chicory:runtime`)
- **Async:** `kotlinx-coroutines-core` 1.9.x
- **Tests:** JUnit 5 + `kotlinx-coroutines-test`

## Running Tests

```sh
./gradlew test
```

## Building

```sh
./gradlew build
```

## Publishing Workflow

Triggered by pushing a `v*` tag. The workflow (`.github/workflows/publish.yml`) runs:

1. `./gradlew build` — compile + test
2. `./gradlew publish` — publish to Maven Central (requires `MAVEN_USERNAME` / `MAVEN_PASSWORD` secrets and GPG signing configured)

To release a new version: bump `version` in `build.gradle.kts`, commit, tag `vX.Y.Z`, push.

## Development Notes

- **No build step for sources** — edit Kotlin files directly; Gradle compiles on `build` or `test`.
- **Chicory API** — code targets Chicory 1.0.0. If upgrading Chicory, verify the `HostFunction` constructor signature, `Memory` read/write methods, and `Instance.builder` API in the release notes.
- **Global exports** — `assertVersion` calls `instance.export("version").apply()` to read the i32 global. If Chicory exposes globals differently in a given version (e.g., via a separate `instance.globals()` API), update this call accordingly.
- **Extension function** — `Memory.readByteArray(ptr, len)` in `Abi.kt` reads byte-by-byte via `Memory.read(addr)`. If Chicory adds a bulk-read API in a newer version, replace for better performance.
- **Gradle wrapper** — commit `gradle/wrapper/gradle-wrapper.jar` and `gradle-wrapper.properties` (run `gradle wrapper --gradle-version=8.x` to generate).
