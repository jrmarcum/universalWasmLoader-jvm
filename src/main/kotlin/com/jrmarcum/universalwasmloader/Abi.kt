package com.jrmarcum.universalwasmloader

import com.dylibso.chicory.runtime.HostFunction
import com.dylibso.chicory.runtime.HostImports
import com.dylibso.chicory.runtime.Instance
import com.dylibso.chicory.wasm.types.Value
import com.dylibso.chicory.wasm.types.ValueType
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MemRef {
    @Volatile
    var current: com.dylibso.chicory.runtime.Memory? = null
}

// Read N bytes from Chicory memory starting at ptr.
// Chicory's Memory.read(addr) returns a single unsigned byte as Int.
internal fun com.dylibso.chicory.runtime.Memory.readByteArray(ptr: Int, len: Int): ByteArray {
    val buf = ByteArray(len)
    for (i in 0 until len) {
        buf[i] = read(ptr + i).toByte()
    }
    return buf
}

internal object Abi {

    // Map WIT type to the list of Chicory ValueTypes consumed as WASM parameters.
    // strings expand to two I32s (ptr, len).
    fun witTypeToParamTypes(type: String): List<ValueType> = when (type) {
        "s32", "bool" -> listOf(ValueType.I32)
        "s64"         -> listOf(ValueType.I64)
        "f32"         -> listOf(ValueType.F32)
        "f64"         -> listOf(ValueType.F64)
        "string"      -> listOf(ValueType.I32, ValueType.I32)
        else          -> listOf(ValueType.I32)
    }

    // Map WIT return type to Chicory result ValueTypes.
    // strings use an out-parameter so the WASM function returns nothing.
    fun witTypeToReturnTypes(type: String?): List<ValueType> = when (type) {
        null, "string" -> emptyList()
        "s32", "bool"  -> listOf(ValueType.I32)
        "s64"          -> listOf(ValueType.I64)
        "f32"          -> listOf(ValueType.F32)
        "f64"          -> listOf(ValueType.F64)
        else           -> listOf(ValueType.I32)
    }

    // Encode a JVM value to one or more Chicory Values according to its WIT type.
    // For strings: allocates WASM memory via cabi_realloc and returns (ptr, len).
    fun encodeArg(instance: Instance, type: String, value: Any?): List<Value> = when (type) {
        "s32"  -> listOf(Value.i32((value as? Number)?.toInt() ?: 0))
        "s64"  -> listOf(Value.i64((value as? Number)?.toLong() ?: 0L))
        "f32"  -> listOf(Value.fromFloat((value as? Number)?.toFloat() ?: 0f))
        "f64"  -> listOf(Value.fromDouble((value as? Number)?.toDouble() ?: 0.0))
        "bool" -> listOf(Value.i32(if (value as? Boolean == true) 1 else 0))
        "string" -> {
            val bytes = (value?.toString() ?: "").toByteArray(Charsets.UTF_8)
            val cabiRealloc = instance.export("cabi_realloc")
            val ptr = cabiRealloc.apply(
                Value.i32(0), Value.i32(0), Value.i32(1), Value.i32(bytes.size)
            )[0].asInt()
            instance.memory().write(ptr, bytes)
            listOf(Value.i32(ptr), Value.i32(bytes.size))
        }
        else -> listOf(Value.i32((value as? Number)?.toInt() ?: 0))
    }

    // Decode a WASM result array to a JVM value according to the WIT return type.
    // Does not handle string returns — use decodeStringReturn for those.
    fun decodeResult(type: String?, wasmResult: Array<Value>): Any? = when (type) {
        null   -> null
        "s32"  -> wasmResult[0].asInt()
        "s64"  -> wasmResult[0].asLong()
        "f32"  -> wasmResult[0].asFloat()
        "f64"  -> wasmResult[0].asDouble()
        "bool" -> wasmResult[0].asInt() != 0
        else   -> wasmResult[0].asInt()
    }

    // Decode a string return from the Canonical ABI out-parameter buffer.
    // retBuf is an 8-byte area allocated by cabi_realloc(0,0,4,8) before calling the WASM fn.
    // After the call, retBuf holds (retPtr: i32 LE, retLen: i32 LE).
    fun decodeStringReturn(instance: Instance, retBuf: Int): String {
        val raw = instance.memory().readByteArray(retBuf, 8)
        val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val retPtr = bb.getInt(0)
        val retLen = bb.getInt(4)
        return String(instance.memory().readByteArray(retPtr, retLen), Charsets.UTF_8)
    }

    // Decode a raw WASM Value to a JVM type with no WIT information (raw-mode fallback).
    fun decodeRawValue(value: Value): Any = when (value.type()) {
        ValueType.I32 -> value.asInt()
        ValueType.I64 -> value.asLong()
        ValueType.F32 -> value.asFloat()
        ValueType.F64 -> value.asDouble()
        else          -> value.asInt()
    }

    // Encode a JVM value to a Chicory Value using runtime type inference (raw mode).
    fun encodeRawArg(value: Any?): Value = when (value) {
        is Int     -> Value.i32(value)
        is Long    -> Value.i64(value)
        is Float   -> Value.fromFloat(value)
        is Double  -> Value.fromDouble(value)
        is Boolean -> Value.i32(if (value) 1 else 0)
        is Number  -> Value.i32(value.toInt())
        else       -> Value.i32(0)
    }

    // Build the Chicory HostImports for the WIT import section.
    // memRef.current must be set to instance.memory() after instantiation for string decoding.
    fun buildImportEnv(
        importFuncs: List<WitFunction>,
        userCallbacks: Callbacks,
        memRef: MemRef
    ): HostImports {
        val hostFunctions = importFuncs.map { fn ->
            val wasmKey  = fn.name.replace('-', '_')   // env-mul  → env_mul
            val camelKey = fn.camelName                // env-mul  → envMul (user callback key)
            val params   = fn.params
            val resultType = fn.result

            val paramTypes   = params.flatMap { witTypeToParamTypes(it.type) }
            val returnTypes  = witTypeToReturnTypes(resultType)

            HostFunction(
                { _, rawArgs ->
                    val jsArgs = mutableListOf<Any?>()
                    var i = 0
                    for (p in params) {
                        when (p.type) {
                            "string" -> {
                                val ptr = rawArgs[i++].asInt()
                                val len = rawArgs[i++].asInt()
                                val mem = memRef.current
                                    ?: error("MemRef not set — memory unavailable in import callback")
                                jsArgs.add(String(mem.readByteArray(ptr, len), Charsets.UTF_8))
                            }
                            "bool" -> jsArgs.add(rawArgs[i++].asInt() != 0)
                            "s64"  -> jsArgs.add(rawArgs[i++].asLong())
                            "f32"  -> jsArgs.add(rawArgs[i++].asFloat())
                            "f64"  -> jsArgs.add(rawArgs[i++].asDouble())
                            else   -> jsArgs.add(rawArgs[i++].asInt())
                        }
                    }

                    val ret = userCallbacks.invoke(camelKey, jsArgs)

                    when (resultType) {
                        null   -> arrayOf()
                        "bool" -> arrayOf(Value.i32(if (ret as? Boolean == true) 1 else 0))
                        "s64"  -> arrayOf(Value.i64((ret as? Number)?.toLong() ?: 0L))
                        "f32"  -> arrayOf(Value.fromFloat((ret as? Number)?.toFloat() ?: 0f))
                        "f64"  -> arrayOf(Value.fromDouble((ret as? Number)?.toDouble() ?: 0.0))
                        else   -> arrayOf(Value.i32((ret as? Number)?.toInt() ?: 0))
                    }
                },
                "env",
                wasmKey,
                paramTypes,
                returnTypes
            )
        }

        return HostImports(hostFunctions.toTypedArray())
    }
}
