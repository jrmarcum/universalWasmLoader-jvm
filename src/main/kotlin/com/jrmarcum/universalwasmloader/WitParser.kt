package com.jrmarcum.universalwasmloader

data class WitParam(val name: String, val type: String)

data class WitFunction(
    val name: String,       // kebab-case — WIT source name
    val camelName: String,  // camelCase — actual WASM binary export/import key
    val params: List<WitParam>,
    val result: String?     // null means no return value
)

data class WitDocument(
    val packageName: String,
    val worldName: String,
    val imports: List<WitFunction>,
    val exports: List<WitFunction>
)

internal object WitParser {

    fun kebabToCamel(name: String): String {
        val parts = name.split("-")
        return parts.first() + parts.drop(1).joinToString("") { it.replaceFirstChar(Char::uppercaseChar) }
    }

    private fun parseWitType(raw: String): String = when (raw.trim()) {
        "s32" -> "s32"
        "s64" -> "s64"
        "f32" -> "f32"
        "f64" -> "f64"
        "bool" -> "bool"
        "string" -> "string"
        else -> "s32"
    }

    private fun parseWitParams(raw: String): List<WitParam> {
        if (raw.isBlank()) return emptyList()
        return raw.split(",").mapNotNull { entry ->
            val colon = entry.indexOf(':')
            if (colon < 0) return@mapNotNull null
            val paramName = entry.substring(0, colon).trim()
            val paramType = parseWitType(entry.substring(colon + 1))
            WitParam(paramName, paramType)
        }
    }

    fun parse(src: String): WitDocument {
        val packageName = Regex("""package\s+([^;]+);""")
            .find(src)?.groupValues?.get(1)?.trim() ?: "unknown"

        val worldMatch = Regex(
            """world\s+([\w-]+)\s*\{([^}]*)\}""",
            RegexOption.DOT_MATCHES_ALL
        ).find(src)
        val worldName = worldMatch?.groupValues?.get(1)?.trim() ?: "unknown"
        val worldBody = worldMatch?.groupValues?.get(2) ?: ""

        val funcPattern = Regex(
            """^\s*(import|export)\s+([\w-]+):\s*func\(([^)]*)\)\s*(?:->\s*(\S+))?;""",
            setOf(RegexOption.MULTILINE)
        )

        val imports = mutableListOf<WitFunction>()
        val exports = mutableListOf<WitFunction>()

        for (match in funcPattern.findAll(worldBody)) {
            val kind = match.groupValues[1]
            val funcName = match.groupValues[2].trim()
            val paramsRaw = match.groupValues[3].trim()
            val resultRaw = match.groupValues[4].trim().takeIf { it.isNotEmpty() }

            val fn = WitFunction(
                name = funcName,
                camelName = kebabToCamel(funcName),
                params = parseWitParams(paramsRaw),
                result = resultRaw?.let { parseWitType(it) }
            )

            if (kind == "import") imports.add(fn) else exports.add(fn)
        }

        return WitDocument(packageName, worldName, imports, exports)
    }
}
