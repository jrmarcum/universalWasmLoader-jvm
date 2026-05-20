package com.jrmarcum.universalwasmloader

/**
 * Builder for host import callbacks — functions your WASM module calls into JVM code.
 *
 * Keys are camelCase WIT import names (e.g. "envMul" for WIT "env-mul").
 * The loader decodes WASM values to JVM types (Int, Long, Float, Double, Boolean, String)
 * before calling your handler, and encodes the return value back.
 *
 * Usage:
 *   val cbs = Callbacks()
 *       .on("envMul") { a, b -> (a as Double) * (b as Double) }
 *       .on("envAdd") { a, b -> (a as Int) + (b as Int) }
 *   val m = wasmLoad("imports.wasm", cbs)
 */
class Callbacks {
    internal val handlers = mutableMapOf<String, (List<Any?>) -> Any?>()

    fun on(camelName: String, handler: (List<Any?>) -> Any?): Callbacks {
        handlers[camelName] = handler
        return this
    }

    // Convenience overloads for common arities.
    fun on(camelName: String, handler: () -> Any?): Callbacks =
        on(camelName) { _ -> handler() }

    fun on(camelName: String, handler: (Any?) -> Any?): Callbacks =
        on(camelName) { args -> handler(args.getOrNull(0)) }

    fun on(camelName: String, handler: (Any?, Any?) -> Any?): Callbacks =
        on(camelName) { args -> handler(args.getOrNull(0), args.getOrNull(1)) }

    fun on(camelName: String, handler: (Any?, Any?, Any?) -> Any?): Callbacks =
        on(camelName) { args -> handler(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2)) }

    internal fun invoke(camelName: String, args: List<Any?>): Any? =
        handlers[camelName]?.invoke(args)
}
