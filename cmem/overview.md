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
  group `io.github.jrmarcum`). Current version: **0.1.2** (`build.gradle.kts`). **FIRST RELEASE
  PUBLISHED 2026-06-24** — `0.1.2` was published to Maven Central (Central Portal deployment reached
  `PUBLISHED` after a manual Publish click; mirror sync to `repo1.maven.org` lags ~15 min–few hours
  after that). See Release flow below for the secret/credential gotchas that blocked the first attempt.
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

**Version source of truth:** the top-level `version = "X.Y.Z"` line in `build.gradle.kts`. Nothing
else holds the version, so a bump touches exactly that one line.

**Bump** (mirrors the -js `deno task bump` UX, patch/minor/major, patch default):

```sh
./gradlew bump                 # patch:  0.1.2 -> 0.1.3  (default)
./gradlew bump -Pkind=minor    # minor:  0.1.2 -> 0.2.0
./gradlew bump -Pkind=major    # major:  0.1.2 -> 1.0.0
# equivalently, directly:  ./scripts/bump.sh [patch|minor|major]
```

The Gradle `bump` task delegates to `scripts/bump.sh` (one implementation). The script does a
sed-anchored replace of ONLY the `^version = "..."` line, so dependency version strings and
`chicoryVersion` are never touched.

**Build + test:** `./gradlew build` / `./gradlew test`. Requires JDK 24+ (JVM target 24, JDK 25
toolchain).

**Release (bump → tag → push → CI publishes):**

```sh
./gradlew bump                                   # raise the version
git commit -am "bump version to v0.1.3"          # commit the bump
./gradlew release                                # tags v<version> and force-pushes the tag
```

`./gradlew release` tags HEAD `v<version>` and force-pushes the tag, which triggers
`.github/workflows/publish.yml`: `test` (gate) → `publishAllPublicationsToMavenCentralRepository`
(publish to Maven Central via `com.vanniktech.maven.publish`, Sonatype Central Portal, GPG-signed)
→ create `release/v<version>` branch → `gh release create`.

**Manual run from the GitHub UI (`workflow_dispatch`).** Added 2026-06-24: `publish.yml` also has a
`workflow_dispatch: {}` trigger, so the workflow is runnable from **Actions → Publish → Run workflow**
without re-pushing a tag. **Select the TAG (e.g. `v0.1.2`) in the ref dropdown**, not `main`, to
reproduce a tag-push run (clones the tagged commit + runs the release bookkeeping). The two release
steps (`Create release branch`, `Create GitHub Release`) are guarded by
`if: startsWith(github.ref, 'refs/tags/')`, so a branch-dispatch publishes the artifact but skips them.

**⚠️ Central Portal does NOT auto-publish by default.** A successful
`publishAllPublicationsToMavenCentralRepository` only **uploads** the deployment; it lands in
`VALIDATED` state and requires a **manual "Publish" button click** at central.sonatype.com →
Deployments to actually release it (this is what blocked `0.1.2` from appearing until the button was
hit). To skip the click on future releases, set the deployment to auto-publish — either in the Portal
account settings or via `publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)`
in `build.gradle.kts`.

### Credential gotchas that blocked the first release (learned 2026-06-24)

The first release attempts failed repeatedly with `Upload failed: {"error":{"message":"Invalid
token"}}` at the publish step (build + test + GPG signing all succeeded — the failure was purely
Central Portal auth). Causes to check, in order:

- The `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` secrets must be the **two bare plain values**
  from the generated user token — **NOT** the `<server>…</server>` XML block, and **NOT** the
  **base64 `username:password` blob** the Portal also shows (the plugin base64-encodes internally;
  pasting the blob double-encodes it → Invalid token).
- They must be **Repository secrets** under **Settings → Secrets and variables → Actions** (not
  Environment secrets, not Dependabot secrets, not Variables).
- Regenerating a user token **revokes the previous one**, so the secrets must hold the *current* token.

**GPG signing key used for `0.1.2`:** RSA-4096, uid `Jon Marcum <jrmarcum.se@gmail.com>`, fingerprint
`E4D45440A37F73383B38842FFF9D45E2AFFFFB6D` (long id `FF9D45E2AFFFFB6D`), public key published to
`keyserver.ubuntu.com` + `keys.openpgp.org`. The private key + passphrase live only in the
`GPG_PRIVATE_KEY` / `GPG_PASSPHRASE` repo secrets (the local exported `.asc` was a scratch file).

### `run:`-only workflow (Actions policy)

This org's Actions policy permits **only `jrmarcum`-owned actions**. Any third-party `uses:` step
(`actions/checkout`, `actions/setup-java`, `gradle/gradle-build-action`, …) causes a
`startup_failure` — the workflow never runs a single step and nothing reaches Maven Central. So
`publish.yml` is **entirely `run:` steps**: checkout via `git clone --depth=1 --branch <tag>`,
JDK 24+ installed via `run:` (reuse a preinstalled Temurin 24/25 under `/usr/lib/jvm`, else download
Temurin 25 from Adoptium), and the committed Gradle wrapper (`./gradlew`). **Do not reintroduce
`uses:` steps.** (The previous version of this workflow used `actions/setup-java@v4` and would have
failed to start.)

### Required owner setup (✅ DONE as of 2026-06-24 — kept as reference for re-keying / forks)

1. **GitHub repository secrets** (Settings → Secrets and variables → Actions). The workflow maps
   each to the `ORG_GRADLE_PROJECT_*` Gradle property the vanniktech plugin reads (in-memory signing
   + Central Portal upload; no `settings.xml` / `secring.gpg`):

   | GitHub secret | Maps to `ORG_GRADLE_PROJECT_…` | What it is |
   | --- | --- | --- |
   | `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` | Central Portal **user token** name |
   | `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` | Central Portal **user token** secret |
   | `GPG_PRIVATE_KEY` | `signingInMemoryKey` | ASCII-armored GPG **private** key (whole block) |
   | `GPG_PASSPHRASE` | `signingInMemoryKeyPassword` | passphrase for that GPG key |

2. **Maven Central namespace verification.** The groupId is `io.github.jrmarcum`. The
   `io.github.<user>` namespace is verified on the Central Portal by linking the `jrmarcum` GitHub
   account (Central Portal → Add Namespace → `io.github.jrmarcum` → verify via the generated TXT/repo
   challenge). The namespace must show **Verified** before any publish succeeds.

3. **Central Portal account + user token.** Create a Sonatype **Central Portal** account
   (central.sonatype.com), generate a **user token** (Account → Generate User Token) — its name/secret
   are the `MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` values above.

4. **GPG signing key.** Generate a key (`gpg --gen-key`), **publish the public key** to a keyserver
   (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>` — Central requires the public key to be
   discoverable to verify signatures), then export the private key
   (`gpg --armor --export-secret-keys <KEYID>`) into the `GPG_PRIVATE_KEY` secret and its passphrase
   into `GPG_PASSPHRASE`.
