package com.partflasher.app.core.root

/**
 * Helpers for building POSIX shell scripts that are handed to `sh -c`/`su -c`
 * as a *single* argument.
 *
 * Every value that does not come from a compile-time constant — partition
 * paths, image paths, partition names, hashes — must be wrapped with [quote]
 * before it is interpolated into a script. A value is quoted by wrapping it in
 * single quotes and replacing every inner single quote with `'\''`, so the
 * shell never interprets `$`, backticks, `;`, `&`, `|`, newlines or glob
 * characters coming from the value.
 *
 * NUL bytes cannot be passed through `execve` argv, so they are rejected.
 */
object ShellScriptBuilder {

    /** Returns [value] as one safely quoted POSIX shell word. */
    fun quote(value: String): String {
        require(!value.contains('\u0000')) { "Shell 参数不能包含 NUL 字符" }
        return buildString(value.length + 2) {
            append('\'')
            for (ch in value) {
                if (ch == '\'') append("'\\''") else append(ch)
            }
            append('\'')
        }
    }

    /** Quotes every value and joins them with single spaces. */
    fun quoteAll(values: Iterable<String>): String = values.joinToString(" ") { quote(it) }

    /** Joins script lines with `\n`. */
    fun build(vararg lines: String): String = lines.joinToString("\n")

    /** Builds a script from a list of lines. */
    fun build(lines: List<String>): String = lines.joinToString("\n")
}
