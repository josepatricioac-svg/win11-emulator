package dev.win11we.shell.x11

/**
 * SYNC (D1f) - counters, awaits and alarms: the frame-synchronisation half of
 * the X desktop contract.
 *
 * Why it matters here: GDK3 queries SYNC at display-open and, when present,
 * uses a counter + `XSyncAwait` around synchronised configuration changes (the
 * `_NET_WM_SYNC_REQUEST` handshake a WM drives during live resize), and Wine's
 * X11 driver creates counters for its own ordering. Without SYNC those paths
 * fall back to round-trip `XSync()` flushes - survivable, but every client
 * asks, and answering "present" obliges us to answer the counter requests
 * truthfully. So:
 *
 * - counters are real server-wide state (INT64), created/queried/set/changed/
 *   destroyed per the protocol;
 * - `Await` is real: satisfied-now conditions reply immediately; unsatisfied
 *   ones are *held* (no reply) and answered the moment a counter change
 *   satisfies every condition - which is exactly the observable behaviour of a
 *   blocking Await for a client that pipelined the request;
 * - alarms fire [XSync.EV_ALARM_NOTIFY] events on their trigger conditions,
 *   including the delta re-adjustment the spec requires;
 * - transition conditions are evaluated against the (old -> new) pair of the
 *   counter change, comparisons against the new value - the spec's distinction;
 * - fences (3.0, GPU sync objects) are refused BadRequest and named in the
 *   trace: we have no GL/DRI path they could mean anything on, and a client
 *   that asked for them must hear "no", not silence.
 *
 * Version claim: 3.1 - the fence requests exist in the protocol at 3.1, but
 * refusing an individual request is legal at any version (BadRequest names it
 * in the trace); the counter/alarm/await surface clients actually use
 * (libXext's XSync* API, GDK3, Wine) is complete.
 *
 * Wire layouts follow xorgproto's `syncproto.h` exactly (checked against the
 * header while writing this; `XSyncTest` pins every reply byte-for-byte):
 * note the Await body is LISTofWAITCONDITION at **28 bytes each** (counter,
 * value_type, wait_value INT64, test_type, event_threshold INT64), and
 * QueryAlarm's reply is **40 bytes** (length 2).
 */
object XSync {

    /** The name clients query (`XQueryExtension("SYNC")`). */
    const val NAME = "SYNC"

    /** Dynamically assigned major opcode (after XFIXES' 133). */
    const val MAJOR_OPCODE = 134

    /** Event base (after XFIXES' two): AlarmNotify = +0, CounterNotify = +1. */
    const val FIRST_EVENT = 68
    const val EV_ALARM_NOTIFY = FIRST_EVENT + 0
    const val EV_COUNTER_NOTIFY = FIRST_EVENT + 1

    /** Error base (after XFIXES' two): BadCounter, BadAlarm, BadIdle. */
    const val FIRST_ERROR = 171
    const val ERR_BAD_COUNTER = FIRST_ERROR + 0
    const val ERR_BAD_ALARM = FIRST_ERROR + 1
    const val ERR_BAD_IDLE = FIRST_ERROR + 2

    const val VERSION_MAJOR = 3
    const val VERSION_MINOR = 1

    // --- minor opcodes (syncproto.h X_Sync*) ---------------------------------
    const val MINOR_INITIALIZE = 0
    const val MINOR_LIST_SYSTEM_COUNTERS = 1
    const val MINOR_CREATE_COUNTER = 2
    const val MINOR_SET_COUNTER = 3
    const val MINOR_CHANGE_COUNTER = 4
    const val MINOR_QUERY_COUNTER = 5
    const val MINOR_DESTROY_COUNTER = 6
    const val MINOR_AWAIT = 7
    const val MINOR_CREATE_ALARM = 8
    const val MINOR_CHANGE_ALARM = 9
    const val MINOR_QUERY_ALARM = 10
    const val MINOR_DESTROY_ALARM = 11
    const val MINOR_SET_PRIORITY = 12
    const val MINOR_GET_PRIORITY = 13
    const val MINOR_CREATE_FENCE = 14
    const val MINOR_TRIGGER_FENCE = 15
    const val MINOR_RESET_FENCE = 16
    const val MINOR_DESTROY_FENCE = 17
    const val MINOR_QUERY_FENCE = 18
    const val MINOR_AWAIT_FENCE = 19

    // --- wait/trigger value types (Xsync.h) -----------------------------------
    const val VALUE_POSITIVE_TRANSITION = 0
    const val VALUE_NEGATIVE_TRANSITION = 1
    const val VALUE_POSITIVE_COMPARISON = 2
    const val VALUE_NEGATIVE_COMPARISON = 3

    // --- CreateAlarm/ChangeAlarm value-mask bits, in bit order ----------------
    const val CA_COUNTER = 0x01
    const val CA_VALUE_TYPE = 0x02
    const val CA_VALUE = 0x04
    const val CA_EVENTS = 0x08
    const val CA_DELTA = 0x10
    const val CA_TIMEOUT = 0x20
    const val CA_TYPE = 0x40

    // --- alarm states ----------------------------------------------------------
    const val ALARM_ACTIVE = 0
    const val ALARM_INACTIVE = 1
    const val ALARM_DESTROYED = 2

    /** Event `kind` byte values. */
    const val KIND_ALARM_NOTIFY = 0
    const val KIND_COUNTER_NOTIFY = 1

    /** One Await/Alarm condition: how [counterId] must relate to [value]. */
    data class Condition(val counterId: Int, val valueType: Int, val value: Long)

    /**
     * True when the condition holds. Transition types are edge conditions: they
     * fire on a change that *crosses* [Condition.value] in the named direction,
     * so they are evaluated with the counter's (old, new) pair; comparison
     * types are level conditions evaluated on [newValue] alone. For an
     * "evaluate now" query (a fresh Await) the old value is the new one, so
     * transitions test `new >= value` / `new <= value` respectively - libXext
     * documents exactly that reading for an Await that finds the condition
     * already true.
     */
    fun holds(condition: Condition, oldValue: Long, newValue: Long, crossing: Boolean): Boolean =
        when (condition.valueType) {
            VALUE_POSITIVE_TRANSITION ->
                if (crossing) oldValue < condition.value && newValue >= condition.value
                else newValue >= condition.value
            VALUE_NEGATIVE_TRANSITION ->
                if (crossing) oldValue > condition.value && newValue <= condition.value
                else newValue <= condition.value
            VALUE_POSITIVE_COMPARISON -> newValue >= condition.value
            VALUE_NEGATIVE_COMPARISON -> newValue <= condition.value
            else -> false
        }

    /** A created counter; [owner] dies -> the counter dies (X resource rules). */
    class Counter(val id: Int, var value: Long, val owner: XConnection)

    /** A created alarm. Fields mutable: ChangeAlarm edits them in place. */
    class Alarm(
        val id: Int,
        val owner: XConnection,
        var counterId: Int = 0,
        var valueType: Int = VALUE_POSITIVE_COMPARISON,
        var value: Long = 0,
        var delta: Long = 0,
        var events: Boolean = false,
        var timeout: Int = 0,
        var alarmType: Int = 0,
        var state: Int = ALARM_ACTIVE,
        /** The counter value when the alarm last triggered - transition edges. */
        var lastSeen: Long = Long.MIN_VALUE,
    )

    fun minorName(minor: Int): String = when (minor) {
        MINOR_INITIALIZE -> "Initialize"
        MINOR_LIST_SYSTEM_COUNTERS -> "ListSystemCounters"
        MINOR_CREATE_COUNTER -> "CreateCounter"
        MINOR_SET_COUNTER -> "SetCounter"
        MINOR_CHANGE_COUNTER -> "ChangeCounter"
        MINOR_QUERY_COUNTER -> "QueryCounter"
        MINOR_DESTROY_COUNTER -> "DestroyCounter"
        MINOR_AWAIT -> "Await"
        MINOR_CREATE_ALARM -> "CreateAlarm"
        MINOR_CHANGE_ALARM -> "ChangeAlarm"
        MINOR_QUERY_ALARM -> "QueryAlarm"
        MINOR_DESTROY_ALARM -> "DestroyAlarm"
        MINOR_SET_PRIORITY -> "SetPriority"
        MINOR_GET_PRIORITY -> "GetPriority"
        MINOR_CREATE_FENCE -> "CreateFence"
        MINOR_TRIGGER_FENCE -> "TriggerFence"
        MINOR_RESET_FENCE -> "ResetFence"
        MINOR_DESTROY_FENCE -> "DestroyFence"
        MINOR_QUERY_FENCE -> "QueryFence"
        MINOR_AWAIT_FENCE -> "AwaitFence"
        else -> "minor$minor"
    }
}
