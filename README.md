# universalWasmLoader-jvm

A lightweight, zero-dependency WebAssembly loader for JVM languages. Works with **Java 17+**, **Kotlin**, **Scala**, **Clojure**, and any other JVM language — without any native code or platform-specific setup.

Built on [Chicory](https://github.com/dylibso/chicory) — a pure-Java WebAssembly runtime.

## Installation

Add to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.jrmarcum:universal-wasm-loader-jvm:0.1.2")
}
```

Or `pom.xml`:

```xml
<dependency>
    <groupId>io.github.jrmarcum</groupId>
    <artifactId>universal-wasm-loader-jvm</artifactId>
    <version>0.1.2</version>
</dependency>
```

## Usage

### Basic — dynamic call

```kotlin
import com.jrmarcum.universalwasmloader.*

val m = wasmLoad("math.wasm")
println(m.call("add", 3, 4))          // 7
println(m.call("multiply", 2.5, 4.0)) // 10.0
```

### Bind individual exports

```kotlin
val m   = wasmLoad("math.wasm")
val add = m.bind("add")
println(add(3, 4))   // 7
```

### Typed interface

Wrap the module in any JVM interface. Method names must match the camelCase WIT export names.

```kotlin
interface MathModule {
    fun add(a: Int, b: Int): Int
    fun square(x: Int): Int
}

val math = wasmLoad<MathModule>("math.wasm")
println(math.add(3, 4))    // 7
println(math.square(5))    // 25
```

### Version pinning

Append `@N` to assert the module's exported `version` global. Throws a descriptive error on mismatch — the same convention C shared libraries use with SONAME major versioning.

```kotlin
val m = wasmLoad("math.wasm@1")   // throws if version global != 1
```

### With host import callbacks

When your WASM module calls back into JVM code, pass host functions as a `Callbacks` builder.
Keys are the camelCase WIT import names (`env-mul` → `envMul`).

```kotlin
val cbs = Callbacks()
    .on("envMul") { a, b -> (a as Double) * (b as Double) }
    .on("envAdd") { a, b -> (a as Int) + (b as Int) }

val m = wasmLoad("math.wasm", cbs)
println(m.call("scale", 3.0, 4.0))    // 12.0
```

### Async loading (URLs)

Use `wasmImport` inside a coroutine to load from HTTP/HTTPS or when already in a suspend context.

```kotlin
import kotlinx.coroutines.runBlocking

val m = runBlocking { wasmImport("https://example.com/math.wasm") }
println(m.call("add", 3, 4))
```

### Singleton — load once, reuse forever

```kotlin
val getMath = createSingleton("math.wasm")
val first   = getMath()
val second  = getMath()
println(first.isSameInstance(second))   // true
```

### Instance pool — concurrent workloads

Pre-instantiates N independent WASM instances (each with its own linear memory).

```kotlin
import kotlinx.coroutines.runBlocking

val pool   = InstancePool("math.wasm", size = 4)
val result = runBlocking { pool.run { m -> m.call("add", 3, 4) } }
println(result)   // 7
```

| Method | Description |
| --- | --- |
| `acquire()` | Check out an instance. Suspends if all are in use. |
| `release(handle)` | Return an instance to the pool. |
| `run(fn)` | Acquire, call `fn`, release — even on exception. |

## How It Works

1. Resolves the `.wasm` file path (file system or classpath)
2. Auto-detects the companion `.wit` file by replacing `.wasm` → `.wit`
3. Applies the Canonical ABI (wasmtime) — bool and string types are handled automatically
4. Returns an ABI-translated `ModuleHandle` keyed by camelCase WIT export names

If no `.wit` file is found, raw WASM exports are returned with type-inferred encoding.

## API Reference

### `wasmLoad(path, callbacks?)`

| Parameter | Type | Description |
| --- | --- | --- |
| `path` | `String` | Path to the `.wasm` file (file system or classpath). Append `@N` to pin to a major version. |
| `callbacks` | `Callbacks` | Host functions the WASM module calls into JVM code. |

Returns `ModuleHandle`.

### `wasmLoad<T>(path, callbacks?)`

Same as above but wraps the handle in a JDK dynamic proxy implementing interface `T`.

### `wasmImport(source, callbacks?)`

Suspend function. Same as `wasmLoad` but also accepts `http://`/`https://` URLs.

### `ModuleHandle`

| Method | Description |
| --- | --- |
| `call(name, vararg args)` | Call a WASM export by camelCase name. Returns `Any?`. |
| `bind(name)` | Returns a standalone `(vararg Any?) -> Any?` callable. |
| `isSameInstance(other)` | Identity check (used to verify `createSingleton` caching). |

### Type mapping

| WIT type | JVM → WASM | WASM → JVM |
| --- | --- | --- |
| `s32` | `Int` → i32 | i32 → `Int` |
| `s64` | `Long` → i64 | i64 → `Long` |
| `f32` | `Float` → f32 | f32 → `Float` |
| `f64` | `Double` → f64 | f64 → `Double` |
| `bool` | `Boolean` → `1`/`0` | `!= 0` → `Boolean` |
| `string` | UTF-8 encode → `cabi_realloc` → `(ptr, len)` | i32 return ptr → read `[ptr, len]` → UTF-8 decode → `cabi_post_<name>` (SPEC 3.0.0) |

See [SPEC.md](./SPEC.md) for the full cross-language conformance specification.

## Building from Source

```sh
./gradlew build      # compile + test
./gradlew test       # run tests only
```

Requires JDK 24+ (the build targets JVM 24 with a JDK 25 toolchain). The Gradle wrapper (`./gradlew`)
downloads the correct Gradle version automatically.

## Releasing (Maven Central)

The single source of truth for the version is the `version = "X.Y.Z"` line in `build.gradle.kts`.

**1. Bump the version** (patch/minor/major; patch is the default):

```sh
./gradlew bump                 # patch:  0.1.2 -> 0.1.3
./gradlew bump -Pkind=minor    # minor:  0.1.2 -> 0.2.0
./gradlew bump -Pkind=major    # major:  0.1.2 -> 1.0.0
# or directly:  ./scripts/bump.sh [patch|minor|major]
```

**2. Commit, then tag + push** to trigger CI:

```sh
git commit -am "bump version to v0.1.3"
./gradlew release              # tags v<version> and force-pushes the tag
```

Pushing the `v*` tag runs `.github/workflows/publish.yml`, which tests, then publishes the signed
artifacts to **Maven Central** (Sonatype Central Portal) via the `com.vanniktech.maven.publish`
plugin, then cuts a GitHub Release.

> **`run:`-only workflow.** This org's Actions policy permits only `jrmarcum`-owned actions, so the
> publish workflow uses **no third-party `uses:` steps** — checkout is `git clone`, the JDK is
> installed via `run:`, and the committed Gradle wrapper drives the build. Reintroducing any `uses:`
> step would cause a `startup_failure`.

### Owner setup required before the first release

| GitHub secret | Gradle property (`ORG_GRADLE_PROJECT_…`) | What it is |
| --- | --- | --- |
| `MAVEN_CENTRAL_USERNAME` | `mavenCentralUsername` | Central Portal **user token** name |
| `MAVEN_CENTRAL_PASSWORD` | `mavenCentralPassword` | Central Portal **user token** secret |
| `GPG_PRIVATE_KEY` | `signingInMemoryKey` | ASCII-armored GPG **private** key block |
| `GPG_PASSPHRASE` | `signingInMemoryKeyPassword` | passphrase for that GPG key |

Prerequisites (one-time):

1. A **Sonatype Central Portal** account (central.sonatype.com); generate a **user token** for the
   two `MAVEN_CENTRAL_*` secrets.
2. **Verify the `io.github.jrmarcum` namespace** on the Central Portal (links the `jrmarcum` GitHub
   account). The groupId `io.github.jrmarcum` cannot publish until the namespace shows *Verified*.
3. A **GPG key** whose **public** key is published to a keyserver
   (`gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>`); export the private key with
   `gpg --armor --export-secret-keys <KEYID>` into `GPG_PRIVATE_KEY`.

## License

See [LICENSE](./LICENSE).
