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

    // @JvmName differentiates this from the (Any?)->Any? overload at the JVM level
    // (both erase to Function1 otherwise).
    @JvmName("onList")
    fun on(camelName: String, handler: (List<Any?>) -> Any?): Callbacks {
        handlers[camelName] = handler
        return this
    }

    fun on(camelName: String, handler: () -> Any?): Callbacks {
        handlers[camelName] = { _ -> handler() }
        return this
    }

    fun on(camelName: String, handler: (Any?) -> Any?): Callbacks {
        handlers[camelName] = { args -> handler(if (args.isEmpty()) null else args[0]) }
        return this
    }

    fun on(camelName: String, handler: (Any?, Any?) -> Any?): Callbacks {
        handlers[camelName] = { args ->
            handler(
                if (args.isEmpty()) null else args[0],
                if (args.size < 2) null else args[1]
            )
        }
        return this
    }

    fun on(camelName: String, handler: (Any?, Any?, Any?) -> Any?): Callbacks {
        handlers[camelName] = { args ->
            handler(
                if (args.isEmpty()) null else args[0],
                if (args.size < 2) null else args[1],
                if (args.size < 3) null else args[2]
            )
        }
        return this
    }

    internal fun invoke(camelName: String, args: List<Any?>): Any? =
        handlers[camelName]?.invoke(args)
}
