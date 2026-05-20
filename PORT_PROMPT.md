# universalWasmLoader — New Language Port Prompt

Use this document as the starting prompt when implementing a new language port of the universalWasmLoader spec. It captures the design ideal, the proven architecture, and the hard-won lessons from prior ports.

This file lives in the JVM port. The JVM implementation itself is in `src/main/kotlin/` and serves as a reference alongside the JS and Rust ports.

---

## The Goal: Match the TypeScript Import Model

The TypeScript reference is the UX ideal. Every language port should approximate it as closely as the target language allows. The user should feel like they are importing a module the same way they import any other dependency — plain path string, no boilerplate, no async ceremony, no manual error unwrapping.

**TypeScript reference patterns:**

```typescript
// Pattern 1 — universal handle, dynamic call
import { wasmImport } from "universal-wasm-loader";
const m = wasmImport("./math.wasm");
const result = m.call("add", [3, 4]);       // 7

// Pattern 2 — destructuring (bind individual exports)
const { add, square } = wasmImport("./math.wasm");
const result = add(3, 4);                   // 7

// Pattern 3 — typed interface (named methods, type-checked)
interface MathModule {
  add(a: number, b: number): number;
  square(x: number): number;
}
const math = wasmImport<MathModule>("./math.wasm");
const result = math.add(3, 4);             // 7
```

The user never writes:

- `await` for loading a local file
- `.unwrap()` / `try!` / manual error handling at each call site
- A wrapper function to register host callbacks
- An ABI profile selection
- A path helper to locate the fixture

---

## Spec and Reference Implementations

| Resource | Location |
| --- | --- |
| Spec | `SPEC.md` (this repo) or `../universalWasmLoader-js/SPEC.md` |
| JS/TS reference impl | `../universalWasmLoader-js/` — read `abi.js`, `wit-parser.js`, `universal-wasm-loader.js` |
| JVM port (this repo) | `src/main/kotlin/com/jrmarcum/universalwasmloader/` |
| Test fixtures | `src/test/resources/tests/` — copy `.wasm` + `.wit` pairs, do not hand-edit binaries |

Before writing any ABI logic, read `abi.js`. The port must match the JS reference exactly.

---

## Required Public API

Implement all of the following. Map each to the most idiomatic equivalent in the target language.

### 1. `wasm_load(path, callbacks?) → Handle`

**Synchronous.** Loads from a file path only. This is the primary entry point — no `await`, no async runtime in user space. Auto-detects `.wit` (replace `.wasm` → `.wit`). Falls back to raw WASM exports if no `.wit` is found.

```
// User writes:
m = wasm_load("math.wasm")
result = m.call("add", [3, 4])
```

### 2. `wasm_import(source, callbacks?) → Handle` *(async)*

**Asynchronous.** Accepts both file paths and `http://`/`https://` URLs. Does IO first (file or HTTP), then runs compilation in a background/blocking context so it does not block the async runtime. Required for URL sources; optional for file paths.

```
// User writes (only when they need URLs or are already in async):
m = await wasm_import("https://example.com/math.wasm")
```

### 3. Bind / Destructure

Extract a named export as a standalone callable. Approximates JavaScript destructuring.

```
// User writes:
m = wasm_load("math.wasm")
add = m.bind("add")       // returns a callable
result = add(3, 4)
```

In languages with destructuring syntax, support it natively if possible.

### 4. Typed Interface / Struct

A language-idiomatic way to declare a named handle with typed methods. In Rust this is a `macro_rules!` macro; in Python a class decorator; in Go a code-generated struct; in C# a source generator or generic wrapper; on the JVM, a JDK dynamic proxy over a user-supplied interface. The `load` constructor must be **synchronous**.

```kotlin
// JVM example:
interface MathModule { fun add(a: Int, b: Int): Int }
val math = wasmLoad<MathModule>("math.wasm")
val result = math.add(3, 4)
```

### 5. `create_singleton(path, callbacks?) → Loader`

Returns a callable that loads once on first call and returns the same handle on every subsequent call. All callers share the same instance (reference-counted or equivalent). Provides an identity-check method (`ptr_eq` in Rust, `is` in Python, `isSameInstance` on JVM, etc.).

### 6. `InstancePool(path, callbacks?, size)` *(async)*

Pre-instantiates N independent WASM instances — each with its own linear memory. Provides a `run(fn)` method that atomically acquires an instance, calls `fn`, and releases it — even if `fn` throws/returns an error. Uses the language's semaphore/channel primitive to block callers when all instances are busy.

### 7. `Callbacks` Builder

Lets users register host import functions. The key is the **camelCase** WIT import name (e.g. `"envMul"` for `env-mul`). Closures/lambdas receive decoded native values — no manual encoding. Types are inferred from the closure signature where possible.

```
cbs = Callbacks()
    .on("envMul", lambda a, b: a * b)
    .on("envAdd", lambda a, b: a + b)
m = wasm_load("imports.wasm", cbs)
```

---

## Critical Invariants

### WIT Name Conventions

Every WIT function has **three** name forms. Getting this wrong produces "export not found" errors with no obvious cause.

| Field | Form | Example | Used for |
| --- | --- | --- | --- |
| Source name | kebab-case | `is-positive` | WIT file identity |
| Binary name | camelCase | `isPositive` | Actual WASM export name — use this when calling the runtime |
| API name | lang-idiomatic | `isPositive` (JVM), `is_positive` (Rust/Python) | User-facing method/key name |

**Never look up a WASM export by its snake_case or kebab-case name.** Always look up by camelCase (`camel_name`). Import names use underscores instead of camelCase (`env-mul` → `env_mul`).

### ABI Auto-Detection

Never expose ABI profile selection to the user. Detect it internally at instantiation time:

- If the module exports `cabi_realloc` → **Component ABI** (Canonical ABI)
- Otherwise → raw exports (no WIT ABI applied)

### ABI: Export Call Paths

Reference `abi.js` for the authoritative implementation. Summary:

| Profile | String param encoding | String return decoding |
| --- | --- | --- |
| Component | `cabi_realloc(0,0,1,len)` → write bytes → pass `(ptr, len)` | allocate 8-byte area via `cabi_realloc(0,0,4,8)`, pass as trailing arg, read little-endian i32 pair |

Bool encoding: `true → 1`, `false → 0` on input; `!= 0 → true` on return. Numerics pass through as-is.

### ABI: Import Side (Host Callbacks)

Register callbacks in the WASM linker under the `"env"` namespace before instantiation. Memory is only available after instantiation, so **read memory from the caller context at call time**, not during registration. This avoids the chicken-and-egg problem. Use a `MemRef`/ref-cell/atomic-ref pattern.

### Version Pinning

Support `@N` suffix on path strings: `"my_module.wasm@2"` asserts the module's `version` i32 global equals `2`. Error if mismatched.

---

## What Belongs in the Module, Not in User Space

| Concern | Wrong (user space) | Right (module internal) |
| --- | --- | --- |
| Async loading | `m = await load(path)` for local files | `m = load(path)` — sync by default |
| Error unwrapping | `result = m.call(...).unwrap()` | Use exceptions / idiomatic propagation |
| Path helpers | `fixture("tests/math.wasm")` | `wasm_load("tests/math.wasm")` — plain strings |
| Callback wrappers | `.on("envMul", host_fn(lambda...))` | `.on("envMul", lambda a, b: a * b)` |
| ABI selection | `wasm_load(path, abi="wasic")` | Auto-detected from binary |
| WIT path | `wasm_load(path, wit="math.wit")` | Auto-detected: `.wasm` → `.wit` |

---

## Test Suite

The test suite must pass all assertions from SPEC.md §8. Fixture files live in `src/test/resources/tests/`.

| Fixture | WASM exports | Assertions |
| --- | --- | --- |
| `math_50` | `add`, `multiply`, `square` | 3 — numeric passthrough |
| `booleans_50` | `isPositive`, `inRange`, `isEven` | 6 — bool normalization |
| `strings_50` | `greet`, `shout`, `strLen` | 3 — string params + returns |
| `imports_50` | `scale`, `combine` | 2 — host import callbacks |
| lifecycle | singleton, pool | 4 — identity + concurrent pool |

---

## Implementation Order

1. **WIT parser** — regex-based, no external dependencies. Parse `name`, `camelName` from `.wit` source. Verify against the JS reference.
2. **ABI layer** — implement `encodeArg`, `decodeResult`, `decodeStringReturn`, and `buildImportEnv`. Verify each with the corresponding fixture before moving on.
3. **Core handle** — the `ModuleHandle`/equivalent type that wraps runtime state and exposes `call` / `bind`.
4. **`wasmLoad`** — synchronous file loader. Run the spec test suite against it before adding any async code.
5. **`wasmImport`** — async file + URL loader. Reuse the same instantiation path; only the IO layer differs.
6. **`bind`** — thin wrapper over `call`; one or two lines.
7. **Typed interface** — proxy/macro/decorator pattern; calls `wasmLoad` internally.
8. **`createSingleton`** — one-time init with language's lazy/once primitive.
9. **`InstancePool`** — async; semaphore-guarded pool of handles.

---

## Development Notes

- **Do not expose the WASM runtime type system** — users work with native language types only (`Int`, `Double`, `String`, `Boolean`). The port translates to/from WASM values internally.
- **Callbacks must capture** — the host callback must own everything it needs; it will be called from inside the WASM runtime after the setup scope has ended.
- **Blocking work in async runtimes** — module compilation is CPU-intensive. Offload it to `Dispatchers.IO` when called from a coroutine context. Never call blocking HTTP inside a blocking-thread context — do the HTTP fetch in the async context first, then hand off bytes.
- **Do not use kebab-case to look up WASM exports** — always use camelCase. This is the single most common source of "export not found" errors.
- **Pin the WASM runtime version** — ABI behavior changes between major versions. Document the pinned version in `build.gradle.kts`.
