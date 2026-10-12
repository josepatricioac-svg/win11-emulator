package dev.win11we.shell.x11

/**
 * What one client asked this server for, and what the server could not answer.
 *
 * Why this exists: GTK installs an X error handler that calls `_exit(1)` on *any*
 * asynchronous error, so when Firefox dies the shell sees an exit code and
 * nothing else. The question that matters is always "which request did the client
 * send that we answered with an error, and which extensions did it ask for?" -
 * and that is invisible without instrumentation. Every [XConnection] carries one
 * of these; [XServer] hands the summary to its reporter when the client
 * disconnects (wired to `SharedLog`, so it shows up in Terminal/Diagnostics).
 *
 * Deliberately bounded and deduplicated: a browser sends tens of thousands of
 * requests per session, and observability must never be the thing that runs the
 * shell out of memory. Counts are kept, individual requests are not.
 *
 * Pure JVM (no Android types) so it is unit-testable and reusable off-device.
 */
class XProtocolTrace(private val capacity: Int = 48) {

    private val extensions = LinkedHashMap<String, Boolean>()
    private val unimplemented = LinkedHashMap<String, Long>()
    private val failures = LinkedHashMap<String, Long>()
    private val lock = Any()

    @Volatile
    var requests: Long = 0
        private set

    @Volatile
    var errorsSent: Long = 0
        private set

    @Volatile
    var replyLengthMismatches: Long = 0
        private set

    /** Set by [XConnection] so a report can name the connection. */
    @Volatile
    var label: String = "client"

    fun recordRequest() {
        requests++
    }

    fun recordErrorSent() {
        errorsSent++
    }

    /** A QueryExtension the client made, and whether we claimed the extension. */
    fun recordExtension(name: String, present: Boolean) = synchronized(lock) {
        if (extensions.size < capacity || extensions.containsKey(name)) extensions[name] = present
    }

    /**
     * A request we have no handler for and therefore answered BadRequest - the
     * answer that kills a GTK client. [minor] is the extension minor opcode when
     * the major opcode belongs to an extension.
     */
    fun recordUnimplemented(opcode: Int, minor: Int = -1) = synchronized(lock) {
        val key = if (minor < 0) "$opcode" else "$opcode.$minor"
        val known = unimplemented.containsKey(key)
        if (known || unimplemented.size < capacity) unimplemented[key] = (unimplemented[key] ?: 0L) + 1
    }

    /** A dispatch that threw; [detail] is the exception summary, deduplicated. */
    fun recordFailure(opcode: Int, detail: String) = synchronized(lock) {
        val key = "opcode $opcode: $detail"
        val known = failures.containsKey(key)
        if (known || failures.size < capacity) failures[key] = (failures[key] ?: 0L) + 1
    }

    /**
     * A reply whose body disagreed with the length field it advertised. Emitting
     * it anyway would desynchronise the connection, so [XConnection.reply] forces
     * the declared size and reports the mismatch here.
     */
    fun recordReplyLengthMismatch(opcode: Int, actual: Int, declared: Int) = synchronized(lock) {
        replyLengthMismatches++
        val key = "reply length for opcode $opcode: wrote $actual, declared $declared"
        val known = failures.containsKey(key)
        if (known || failures.size < capacity) failures[key] = (failures[key] ?: 0L) + 1
    }

    fun extensionsAsked(): Map<String, Boolean> = synchronized(lock) { LinkedHashMap(extensions) }
    fun unimplementedOpcodes(): Map<String, Long> = synchronized(lock) { LinkedHashMap(unimplemented) }
    fun failureCounts(): Map<String, Long> = synchronized(lock) { LinkedHashMap(failures) }

    /** True when the client hit something we could not do. */
    fun hasProblems(): Boolean = synchronized(lock) {
        unimplemented.isNotEmpty() || failures.isNotEmpty() || replyLengthMismatches > 0
    }

    /**
     * One log line, e.g.
     * `x11[client#2] 812 requests, 0 errors | ext: XTEST=yes RENDER=no |
     *  unimplemented: 130x4 | failures: opcode 55: IndexOutOfBounds x1`
     * Truncated fields are marked with `+N` so a missing entry is never silently
     * read as "the client did not ask for it".
     */
    fun summary(): String = synchronized(lock) {
        val parts = StringBuilder("x11[$label] $requests requests, $errorsSent errors sent")
        if (replyLengthMismatches > 0) parts.append(", $replyLengthMismatches REPLY LENGTH MISMATCHES")
        if (extensions.isNotEmpty()) {
            parts.append(" | ext: ")
            parts.append(extensions.entries.joinToString(" ") { (name, present) ->
                "$name=${if (present) "yes" else "no"}"
            })
        }
        if (unimplemented.isNotEmpty()) {
            parts.append(" | unimplemented opcodes: ")
            parts.append(unimplemented.entries.sortedByDescending { it.value }
                .joinToString(" ") { (opcode, count) -> "${opcodeLabel(opcode)}x$count" })
        }
        if (failures.isNotEmpty()) {
            parts.append(" | failures: ")
            parts.append(failures.entries.sortedByDescending { it.value }
                .joinToString("; ") { (key, count) -> "$key x$count" })
        }
        parts.toString()
    }

    /** "129.8" -> "RENDER.Composite": device logs should name requests, not numbers. */
    private fun opcodeLabel(key: String): String {
        val dot = key.indexOf('.')
        val major = (if (dot < 0) key else key.substring(0, dot)).toIntOrNull() ?: return key
        val minor = if (dot >= 0) key.substring(dot + 1).toIntOrNull() else null
        return when (major) {
            XRender.MAJOR_OPCODE -> "RENDER." + XRender.minorName(minor ?: -1)
            XBigRequests.MAJOR_OPCODE -> "BIG-REQUESTS." + when (minor) {
                XBigRequests.MINOR_ENABLE -> "Enable"; else -> "minor$minor"
            }
            XGenericEvent.MAJOR_OPCODE -> "GenericEvent." + when (minor) {
                XGenericEvent.MINOR_QUERY_VERSION -> "QueryVersion"; else -> "minor$minor"
            }
            XInput2.MAJOR_OPCODE -> "XI2." + XInput2.minorName(minor ?: -1)
            X11.EXT_XTEST_OPCODE -> "XTEST." + when (minor) {
                0 -> "GetVersion"; 1 -> "CompareCursor"; 2 -> "FakeInput"; 3 -> "GrabControl"
                else -> "minor$minor"
            }
            else -> key
        }
    }

    override fun toString(): String = summary()
}
