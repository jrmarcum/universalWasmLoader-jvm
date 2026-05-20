package com.jrmarcum.universalwasmloader

import com.dylibso.chicory.runtime.HostImports
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.runtime.Module
import com.dylibso.chicory.wasm.types.Value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.ArrayBlockingQueue

// ---------------------------------------------------------------------------
// ModuleHandle — the handle returned by every load/import call
// ---------------------------------------------------------------------------

class ModuleHandle internal constructor(
    internal val instance: Instance,
    internal val witDoc: WitDocument?
) {
    /**
     * Call a WASM export by its camelCase name with JVM-typed arguments.
     * In WIT mode, ABI encoding and decoding are applied automatically.
     * Without a WIT file, calls the raw WASM export with type-inferred encoding.
     */
    fun call(name: String, vararg args: Any?): Any? {
        return if (witDoc == null) {
            callRaw(name, args)
        } else {
            callWithAbi(name, args)
        }
    }

    private fun callRaw(name: String, args: Array<out Any?>): Any? {
        val fn = instance.export(name)
        val wasmArgs = args.map { Abi.encodeRawArg(it) }.toTypedArray()
        val result = fn.apply(*wasmArgs)
        return if (result.isEmpty()) null else Abi.decodeRawValue(result[0])
    }

    private fun callWithAbi(name: String, args: Array<out Any?>): Any? {
        val fn = witDoc!!.exports.find { it.camelName == name }
            ?: throw IllegalArgumentException(
                "Export '$name' not found in WIT. Available: ${witDoc.exports.map { it.camelName }}"
            )

        val wasmFn = instance.export(fn.camelName)

        val wasmArgs = mutableListOf<Value>()
        for ((i, param) in fn.params.withIndex()) {
            wasmArgs.addAll(Abi.encodeArg(instance, param.type, args.getOrNull(i)))
        }

        return if (fn.result == "string") {
            val cabiRealloc = instance.export("cabi_realloc")
            val retBuf = cabiRealloc.apply(
                Value.i32(0), Value.i32(0), Value.i32(4), Value.i32(8)
            )[0].asInt()
            wasmFn.apply(*wasmArgs.toTypedArray(), Value.i32(retBuf))
            Abi.decodeStringReturn(instance, retBuf)
        } else {
            val result = wasmFn.apply(*wasmArgs.toTypedArray())
            Abi.decodeResult(fn.result, result)
        }
    }

    /**
     * Bind a named export as a standalone callable.
     * Approximates JavaScript destructuring: val add = m.bind("add"); add(3, 4)
     */
    fun bind(name: String): (vararg Any?) -> Any? = { args -> call(name, *args) }

    /** True if this handle is the same object instance as [other]. */
    fun isSameInstance(other: ModuleHandle): Boolean = this === other
}

// ---------------------------------------------------------------------------
// wasmLoad — synchronous file loader (primary entry point)
// ---------------------------------------------------------------------------

/**
 * Load a `.wasm` file synchronously. Auto-detects the companion `.wit` file
 * and applies the Canonical ABI. Supports `@N` version pinning.
 *
 * @param path  File path to the `.wasm` file. Append `@N` to pin to a major version.
 * @param callbacks  Host functions called by the WASM module. Flat camelCase keys.
 */
fun wasmLoad(path: String, callbacks: Callbacks = Callbacks()): ModuleHandle {
    val (cleanPath, version) = parseVersionSuffix(path)
    val wasmBytes = loadBytes(cleanPath)

    val witPath   = cleanPath.replace(".wasm", ".wit")
    val witSource = tryLoadText(witPath)
    val witDoc    = witSource?.let { WitParser.parse(it) }

    return instantiateFromBytes(wasmBytes, witDoc, callbacks, cleanPath, version)
}

/**
 * Typed interface variant. Wraps the module handle in a JVM dynamic proxy
 * that implements interface [T], delegating each method call to [ModuleHandle.call].
 *
 * @param path  File path to the `.wasm` file. Append `@N` to pin to a major version.
 * @param callbacks  Host functions called by the WASM module.
 */
inline fun <reified T : Any> wasmLoad(path: String, callbacks: Callbacks = Callbacks()): T {
    val handle = wasmLoad(path, callbacks)
    @Suppress("UNCHECKED_CAST")
    return java.lang.reflect.Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, args ->
        handle.call(method.name, *(args ?: emptyArray()))
    } as T
}

// ---------------------------------------------------------------------------
// wasmImport — async file + URL loader
// ---------------------------------------------------------------------------

/**
 * Load a `.wasm` file or URL asynchronously. Use this when loading from HTTP/HTTPS
 * or when already inside a coroutine scope. For local files, prefer [wasmLoad].
 *
 * @param source  File path or `http(s)://` URL. Append `@N` to pin to a major version.
 * @param callbacks  Host functions called by the WASM module.
 */
suspend fun wasmImport(source: String, callbacks: Callbacks = Callbacks()): ModuleHandle {
    return withContext(Dispatchers.IO) {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            loadFromUrl(source, callbacks)
        } else {
            wasmLoad(source, callbacks)
        }
    }
}

private suspend fun loadFromUrl(source: String, callbacks: Callbacks): ModuleHandle {
    val (cleanUrl, version) = parseVersionSuffix(source)
    val witUrl = cleanUrl.replace(".wasm", ".wit")

    val httpClient = HttpClient.newHttpClient()

    val wasmBytes = withContext(Dispatchers.IO) {
        httpClient.send(
            HttpRequest.newBuilder().uri(URI.create(cleanUrl)).build(),
            HttpResponse.BodyHandlers.ofByteArray()
        ).body()
    }

    val witSource = withContext(Dispatchers.IO) {
        try {
            val resp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(witUrl)).build(),
                HttpResponse.BodyHandlers.ofString()
            )
            if (resp.statusCode() == 200) resp.body() else null
        } catch (_: Exception) {
            null
        }
    }

    val witDoc = witSource?.let { WitParser.parse(it) }
    return instantiateFromBytes(wasmBytes, witDoc, callbacks, cleanUrl, version)
}

// ---------------------------------------------------------------------------
// createSingleton — load once, reuse forever
// ---------------------------------------------------------------------------

/**
 * Returns an accessor that loads the WASM instance on the first call and caches it.
 * All callers share the same [ModuleHandle].
 */
fun createSingleton(path: String, callbacks: Callbacks = Callbacks()): () -> ModuleHandle {
    val lazy = lazy { wasmLoad(path, callbacks) }
    return { lazy.value }
}

// ---------------------------------------------------------------------------
// InstancePool — pre-instantiated pool for concurrent workloads
// ---------------------------------------------------------------------------

/**
 * Pre-instantiates [size] independent WASM instances. Each instance has its own
 * linear memory. Callers acquire an instance, use it, and release it back.
 *
 * @param path  File path to the `.wasm` file.
 * @param callbacks  Host functions called by the WASM module.
 * @param size  Number of independent instances to pre-instantiate (default: 4).
 */
class InstancePool(
    path: String,
    callbacks: Callbacks = Callbacks(),
    size: Int = 4
) {
    private val pool      = ArrayBlockingQueue<ModuleHandle>(size)
    private val semaphore = Semaphore(size)

    init {
        repeat(size) { pool.add(wasmLoad(path, callbacks)) }
    }

    /** Acquire an instance. Suspends if all instances are in use. */
    suspend fun acquire(): ModuleHandle {
        semaphore.acquire()
        return pool.poll()!!
    }

    /** Return an instance to the pool. */
    fun release(handle: ModuleHandle) {
        pool.offer(handle)
        semaphore.release()
    }

    /**
     * Acquire an instance, invoke [fn], then release — even if [fn] throws.
     * This is the preferred way to use the pool.
     */
    suspend fun <T> run(fn: suspend (ModuleHandle) -> T): T =
        semaphore.withPermit {
            val handle = pool.poll()!!
            try {
                fn(handle)
            } finally {
                pool.offer(handle)
            }
        }
}

// ---------------------------------------------------------------------------
// Internal helpers
// ---------------------------------------------------------------------------

private val VERSION_SUFFIX = Regex("""@(\d+)$""")

internal fun parseVersionSuffix(path: String): Pair<String, Int?> {
    val match = VERSION_SUFFIX.find(path) ?: return Pair(path, null)
    return Pair(path.substring(0, match.range.first), match.groupValues[1].toInt())
}

internal fun loadBytes(path: String): ByteArray {
    val file = File(path)
    if (file.exists()) return file.readBytes()
    // Fallback: classpath resource (useful for test fixtures packaged in JARs)
    val resource = path.trimStart('/', '\\').let { "/$it" }
    return (ModuleHandle::class.java.getResourceAsStream(resource)
        ?: ModuleHandle::class.java.getResourceAsStream(path)
        ?: throw java.io.FileNotFoundException("WASM file not found: $path"))
        .use { it.readBytes() }
}

internal fun tryLoadText(path: String): String? {
    return try {
        val file = File(path)
        if (file.exists()) return file.readText()
        val resource = path.trimStart('/', '\\').let { "/$it" }
        (ModuleHandle::class.java.getResourceAsStream(resource)
            ?: ModuleHandle::class.java.getResourceAsStream(path))
            ?.use { it.bufferedReader().readText() }
    } catch (_: Exception) {
        null
    }
}

private fun instantiateFromBytes(
    wasmBytes: ByteArray,
    witDoc: WitDocument?,
    callbacks: Callbacks,
    pathForError: String,
    version: Int?
): ModuleHandle {
    val module  = Module.builder(wasmBytes).build()
    val memRef  = MemRef()

    val hostImports = if (witDoc != null && witDoc.imports.isNotEmpty()) {
        Abi.buildImportEnv(witDoc.imports, callbacks, memRef)
    } else {
        HostImports(emptyArray())
    }

    val instance = Instance.builder(module)
        .withHostImports(hostImports)
        .build()

    memRef.current = instance.memory()

    if (version != null) {
        assertVersion(instance, pathForError, version)
    }

    return ModuleHandle(instance, witDoc)
}

private fun assertVersion(instance: Instance, path: String, expected: Int) {
    // The WASM module exports a mutable i32 global named "version".
    // In Chicory, globals may be exported as zero-argument functions that return their value,
    // or accessible via a dedicated globals API depending on the runtime version.
    // We attempt a direct function call first; if that fails, check via export as Value.
    try {
        val versionExport = instance.export("version")
        val actual = versionExport.apply()[0].asInt()
        check(actual == expected) {
            "Version mismatch for '$path': requested @$expected but module exports version=$actual"
        }
    } catch (e: IllegalStateException) {
        throw e
    } catch (_: Exception) {
        throw IllegalStateException(
            "Module '$path' does not export a 'version' global. Requested @$expected."
        )
    }
}
