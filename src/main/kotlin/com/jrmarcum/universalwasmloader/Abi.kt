package com.jrmarcum.universalwasmloader

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.ImportValues
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.ValueType

internal class MemRef {
    @Volatile
    var current: com.dylibso.chicory.runtime.Memory? = null
}

// Detected at instantiation time; drives string encoding/decoding paths.
internal enum class StringAbi { CANONICAL, WASIC, NONE }

internal object Abi {

    fun witTypeToParamTypes(type: String): List<ValueType> = when (type) {
        "s32", "bool" -> listOf(ValueType.I32)
        "s64"         -> listOf(ValueType.I64)
        "f32"         -> listOf(ValueType.F32)
        "f64"         -> listOf(ValueType.F64)
        "string"      -> listOf(ValueType.I32, ValueType.I32)
        else          -> listOf(ValueType.I32)
    }

    fun witTypeToReturnTypes(type: String?): List<ValueType> = when (type) {
        null, "string" -> emptyList()   // string returns use out-param or side-channel
        "s32", "bool"  -> listOf(ValueType.I32)
        "s64"          -> listOf(ValueType.I64)
        "f32"          -> listOf(ValueType.F32)
        "f64"          -> listOf(ValueType.F64)
        else           -> listOf(ValueType.I32)
    }

    // Encode a JVM value to one or more raw longs according to its WIT type.
    // String params are allocated in WASM linear memory via the appropriate ABI.
    fun encodeArg(instance: Instance, type: String, value: Any?, stringAbi: StringAbi): List<Long> = when (type) {
        "s32"  -> listOf(((value as? Number)?.toInt() ?: 0).toLong())
        "s64"  -> listOf((value as? Number)?.toLong() ?: 0L)
        "f32"  -> listOf(((value as? Number)?.toFloat() ?: 0f).toBits().toLong())
        "f64"  -> listOf(((value as? Number)?.toDouble() ?: 0.0).toBits())
        "bool" -> listOf(if (value as? Boolean == true) 1L else 0L)
        "string" -> {
            val bytes = (value?.toString() ?: "").toByteArray(Charsets.UTF_8)
            val ptr = when (stringAbi) {
                StringAbi.CANONICAL ->
                    instance.export("cabi_realloc").apply(0L, 0L, 1L, bytes.size.toLong())[0].toInt()
                StringAbi.WASIC ->
                    instance.export("__malloc").apply(bytes.size.toLong())[0].toInt()
                StringAbi.NONE ->
                    throw IllegalStateException("Module has no string allocator (cabi_realloc or __malloc)")
            }
            instance.memory().write(ptr, bytes)
            listOf(ptr.toLong(), bytes.size.toLong())
        }
        else -> listOf(((value as? Number)?.toInt() ?: 0).toLong())
    }

    // Decode a WASM result (LongArray) to a JVM value according to the WIT return type.
    // String returns are handled separately in callWithAbi — not here.
    fun decodeResult(type: String?, wasmResult: LongArray): Any? = when (type) {
        null   -> null
        "s32"  -> wasmResult[0].toInt()
        "s64"  -> wasmResult[0]
        "f32"  -> Float.fromBits(wasmResult[0].toInt())
        "f64"  -> Double.fromBits(wasmResult[0])
        "bool" -> wasmResult[0] != 0L
        else   -> wasmResult[0].toInt()
    }

    // Decode a raw Long to a JVM type for raw-mode (no WIT info).
    fun decodeRawValue(value: Long): Any = value

    // Encode a JVM value to a raw Long for raw-mode calls (no WIT type info).
    fun encodeRawArg(value: Any?): Long = when (value) {
        is Int     -> value.toLong()
        is Long    -> value
        is Float   -> value.toBits().toLong()
        is Double  -> value.toBits()
        is Boolean -> if (value) 1L else 0L
        is Number  -> value.toLong()
        else       -> 0L
    }

    // Build the Chicory ImportValues for the WIT import section.
    // memRef.current must be set to instance.memory() after instantiation for string decoding.
    fun buildImportEnv(
        importFuncs: List<WitFunction>,
        userCallbacks: Callbacks,
        memRef: MemRef
    ): ImportValues {
        val hostFunctions = importFuncs.map { fn ->
            val wasmKey    = fn.name.replace('-', '_')  // env-mul → env_mul
            val camelKey   = fn.camelName               // env-mul → envMul (user callback key)
            val params     = fn.params
            val resultType = fn.result

            val paramTypes  = params.flatMap { witTypeToParamTypes(it.type) }
            val returnTypes = witTypeToReturnTypes(resultType)

            HostFunction("env", wasmKey, paramTypes, returnTypes) { _, rawArgs ->
                val jsArgs = mutableListOf<Any?>()
                var i = 0
                for (p in params) {
                    when (p.type) {
                        "string" -> {
                            val ptr = rawArgs[i++].toInt()
                            val len = rawArgs[i++].toInt()
                            val mem = memRef.current
                                ?: error("MemRef not set — memory unavailable in import callback")
                            jsArgs.add(String(mem.readBytes(ptr, len), Charsets.UTF_8))
                        }
                        "bool" -> jsArgs.add(rawArgs[i++] != 0L)
                        "s64"  -> jsArgs.add(rawArgs[i++])
                        "f32"  -> jsArgs.add(Float.fromBits(rawArgs[i++].toInt()))
                        "f64"  -> jsArgs.add(Double.fromBits(rawArgs[i++]))
                        else   -> jsArgs.add(rawArgs[i++].toInt())
                    }
                }

                val ret = userCallbacks.invoke(camelKey, jsArgs)

                when (resultType) {
                    null   -> longArrayOf()
                    "bool" -> longArrayOf(if (ret as? Boolean == true) 1L else 0L)
                    "s64"  -> longArrayOf((ret as? Number)?.toLong() ?: 0L)
                    "f32"  -> longArrayOf(((ret as? Number)?.toFloat() ?: 0f).toBits().toLong())
                    "f64"  -> longArrayOf(((ret as? Number)?.toDouble() ?: 0.0).toBits())
                    else   -> longArrayOf(((ret as? Number)?.toInt() ?: 0).toLong())
                }
            }
        }

        val builder = ImportValues.builder()
        hostFunctions.forEach { builder.addFunction(it) }
        return builder.build()
    }
}
