#!/usr/bin/env kotlin
// how-to-use.kts — run with: kotlinc -script how-to-use.kts
//
// Demonstrates the universalWasmLoader-jvm API against the bundled math.wasm.
// math.wasm exports:
//   calculate(a: i32, b: i32) -> i32   computes (a * b) + 10
//   version                             exported i32 global, value 1
// No companion .wit file — returns raw WebAssembly.Exports (JVM raw mode).

@file:DependsOn("com.jrmarcum:universal-wasm-loader-jvm:0.1.0")

import com.jrmarcum.universalwasmloader.*

// -------------------------------------------------------
// Pattern 1 — dynamic call via ModuleHandle.call()
// -------------------------------------------------------
val m = wasmLoad("math.wasm")
val result = m.call("calculate", 3, 7)
println("calculate(3, 7) = $result")     // 31  ( (3 * 7) + 10 )

// -------------------------------------------------------
// Pattern 2 — bind individual export as standalone callable
// -------------------------------------------------------
val calculate = m.bind("calculate")
println("calculate(5, 5) = ${calculate(5, 5)}")   // 35

// -------------------------------------------------------
// Pattern 3 — typed interface via dynamic proxy
// -------------------------------------------------------
interface MathModule {
    fun calculate(a: Int, b: Int): Int
}

val math = wasmLoad<MathModule>("math.wasm")
println("math.calculate(4, 4) = ${math.calculate(4, 4)}")   // 26

// -------------------------------------------------------
// Version pinning — asserts exported 'version' global == 1
// -------------------------------------------------------
val pinned = wasmLoad("math.wasm@1")
println("Loaded math.wasm pinned to @1 successfully")

// -------------------------------------------------------
// createSingleton — same instance on repeated loads
// -------------------------------------------------------
val getMod  = createSingleton("math.wasm")
val first   = getMod()
val second  = getMod()
println("Singleton identity: ${first.isSameInstance(second)}")   // true

// -------------------------------------------------------
// Host import callbacks — shown with imports_50.wasm
// -------------------------------------------------------
val cbs = Callbacks()
    .on("envMul") { a, b -> (a as Double) * (b as Double) }
    .on("envAdd") { a, b -> (a as Int)    + (b as Int) }

val importsModule = wasmLoad("src/test/resources/tests/imports_50.wasm", cbs)
println("scale(3.0, 4.0) = ${importsModule.call("scale", 3.0, 4.0)}")    // 12.0
println("combine(10, 7)  = ${importsModule.call("combine", 10, 7)}")      // 17
