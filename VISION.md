# Ecosystem Vision

See the authoritative vision document in the wasmtk repository:
[wasmtk/VISION.md](https://github.com/jrmarcum/wasmtk/blob/main/VISION.md)

---

## Design Directives — Loader API Simplicity

The following directives apply to this loader and all dependent projects that build loaders, bindings, or tooling on top of it. They define the intended user experience at the call site.

### 1. Minimal user surface

The user specifies only two things:

- **What** to load — the `.wasm` file path
- **What to give back** — optional host callbacks the WASM module calls into

Everything else (WIT detection, ABI selection, memory wiring, name mapping) is handled automatically inside the loader. Users must never be asked to configure encoding, profiles, or file paths beyond the module itself.

### 2. ESM-aligned call pattern

The API mirrors ES module import semantics. Both of these patterns MUST work without any additional options:

```kotlin
// Bind individual exports — mirrors: import { fn } from "..."
val m = wasmLoad("mod.wasm")
val greet = m.bind("greet")

// Namespace all exports — mirrors: import * as m from "..."
val m = wasmLoad("mod.wasm")
m.call("greet", "World")
```

### 3. Host interactivity via flat callbacks

When a WASM module calls back into JVM code (e.g., event dispatch, GUI updates), the user supplies a `Callbacks` builder with flat camelCase keys — nothing more:

```kotlin
val m = wasmLoad("ui.wasm", Callbacks()
    .on("onButtonClick") { id -> handleClick(id as Int) }
    .on("getViewportWidth") { -> viewportWidth }
)
```

No nesting, no namespace keys, no ABI flags. This is the intended pattern for future graphical and interactive WASM modules.

### 4. Zero configuration

No options object. No ABI selection. No explicit WIT path. The loader detects and applies everything from the module's companion `.wit` file automatically. If dependent tooling adds new ABI profiles or loader features in the future, they MUST be auto-detected — never surfaced as user-facing options.

### 5. Progressive disclosure

The `createSingleton` and `InstancePool` utilities follow the same principle — the only arguments are the path and optional host callbacks. Advanced lifecycle management (caching, pooling) is available but does not add API surface complexity for users who don't need it.
