package dev.mooner.starlightworkspace

/**
 * Where a script error is, read from the engine's exception in the cause chain: NeonJS's NeonSyntaxException
 * (`line`, `column`, `sourceName`) or Rhino's RhinoException (`lineNumber()`, `columnNumber()`, `sourceName()`).
 * Looked up by name, as the engines are not dependencies of this plugin.
 */
internal object ErrorLocations {

    class Location(val source: String?, val line: Int, val column: Int?)

    private val GETTERS = listOf(
        Triple("getLine", "getColumn", "getSourceName"),
        Triple("lineNumber", "columnNumber", "sourceName"),
    )

    fun find(error: Throwable): Location? =
        generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }
            .take(MAX_DEPTH)
            .firstNotNullOfOrNull(::locationOf)

    private fun locationOf(error: Throwable): Location? {
        for ((lineName, columnName, sourceName) in GETTERS) {
            val line = call(error, lineName) as? Int ?: continue
            if (line <= 0)
                continue
            val column = (call(error, columnName) as? Int)?.takeIf { it > 0 }
            return Location(call(error, sourceName) as? String, line, column)
        }
        return null
    }

    private fun call(target: Any, name: String): Any? =
        runCatching { target.javaClass.getMethod(name).invoke(target) }.getOrNull()

    private const val MAX_DEPTH = 8
}
