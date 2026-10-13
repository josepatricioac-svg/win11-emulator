package dev.win11we.shell.x11

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1f SYNC wire tests. Layouts follow xorgproto's `syncproto.h` byte-for-byte:
 * note the Await body is LISTofWAITCONDITION at 28 bytes each, INT64 values are
 * hi/lo CARD32 pairs, and QueryAlarm's reply is 40 bytes (length 2).
 *
 * The behavioural heart is the held Await: a client that Awaits an unsatisfied
 * condition gets NO reply until a counter change satisfies every condition -
 * and that late reply still carries the Await request's sequence number, which
 * is what makes it match the request on the client side.
 */
private class Sy {
    fun ext(minor: Int, body: (XWriter) -> Unit = {}): ByteArray =
        build(XSync.MAJOR_OPCODE, minor, body)

    fun build(major: Int, minor: Int, body: (XWriter) -> Unit = {}): ByteArray {
        val bodyWriter = XWriter(true)
        body(bodyWriter)
        val bodyBytes = bodyWriter.toByteArray()
        val writer = XWriter(true)
        writer.byte(major)
        writer.byte(minor)
        val total = 4 + bodyBytes.size
        val padded = X11.pad4(total)
        writer.short(padded / 4)
        writer.bytes(bodyBytes)
        writer.pad(padded - total)
        return writer.toByteArray()
    }

    fun core(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}): ByteArray =
        build(opcode, data, body)

    fun queryExtension(name: String) = core(X11.REQ_QUERY_EXTENSION) { writer ->
        writer.short(name.length); writer.pad(2); writer.paddedString(name)
    }

    fun initialize(clientMajor: Int = 3, clientMinor: Int = 1) = ext(XSync.MINOR_INITIALIZE) { writer ->
        writer.byte(clientMajor); writer.byte(clientMinor); writer.pad(2)
    }

    fun listSystemCounters() = ext(XSync.MINOR_LIST_SYSTEM_COUNTERS)

    private fun int64(writer: XWriter, value: Long) {
        writer.int((value shr 32).toInt())
        writer.int((value and 0xFFFFFFFFL).toInt())
    }

    fun createCounter(id: Int, value: Long = 0) = ext(XSync.MINOR_CREATE_COUNTER) { writer ->
        writer.int(id); int64(writer, value)
    }

    fun setCounter(id: Int, value: Long) = ext(XSync.MINOR_SET_COUNTER) { writer ->
        writer.int(id); int64(writer, value)
    }

    fun changeCounter(id: Int, delta: Long) = ext(XSync.MINOR_CHANGE_COUNTER) { writer ->
        writer.int(id); int64(writer, delta)
    }

    fun queryCounter(id: Int) = ext(XSync.MINOR_QUERY_COUNTER) { it.int(id) }

    fun destroyCounter(id: Int) = ext(XSync.MINOR_DESTROY_COUNTER) { it.int(id) }

    /** One 28-byte xSyncWaitCondition. */
    fun await(vararg conditions: Triple<Int, Int, Long>) = ext(XSync.MINOR_AWAIT) { writer ->
        conditions.forEach { (counterId, valueType, value) ->
            writer.int(counterId)
            writer.int(valueType)
            int64(writer, value)
            writer.int(0) // test_type: absolute
            writer.int(0); writer.int(0) // event_threshold (unused)
        }
    }

    /** Relative test_type: the wait value is an offset from the counter's value now. */
    fun awaitRelative(counterId: Int, valueType: Int, offset: Long) = ext(XSync.MINOR_AWAIT) { writer ->
        writer.int(counterId)
        writer.int(valueType)
        int64(writer, offset)
        writer.int(1) // test_type: relative
        writer.int(0); writer.int(0)
    }

    fun createAlarm(
        id: Int,
        counterId: Int,
        valueType: Int,
        value: Long,
        events: Boolean,
        delta: Long = 0,
    ) = ext(XSync.MINOR_CREATE_ALARM) { writer ->
        writer.int(id)
        // mask: Counter | ValueType | Value | Events (+ Delta when non-zero)
        val mask = XSync.CA_COUNTER or XSync.CA_VALUE_TYPE or XSync.CA_VALUE or XSync.CA_EVENTS or
            (if (delta != 0L) XSync.CA_DELTA else 0)
        writer.int(mask)
        writer.int(counterId) // in mask bit order:
        writer.int(valueType)
        int64(writer, value)
        writer.int(if (events) 1 else 0)
        if (delta != 0L) int64(writer, delta)
    }

    fun changeAlarmValue(id: Int, value: Long) = ext(XSync.MINOR_CHANGE_ALARM) { writer ->
        writer.int(id)
        writer.int(XSync.CA_VALUE)
        int64(writer, value)
    }

    fun queryAlarm(id: Int) = ext(XSync.MINOR_QUERY_ALARM) { it.int(id) }
    fun destroyAlarm(id: Int) = ext(XSync.MINOR_DESTROY_ALARM) { it.int(id) }
    fun setPriority(id: Int, priority: Int) = ext(XSync.MINOR_SET_PRIORITY) { writer ->
        writer.int(id); writer.int(priority)
    }

    fun getPriority(id: Int) = ext(XSync.MINOR_GET_PRIORITY) { it.int(id) }
    fun createFence() = ext(XSync.MINOR_CREATE_FENCE) { writer ->
        writer.int(0); writer.int(0); writer.byte(0); writer.pad(3)
    }
}

private fun ByteArray.intLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.int64LE(offset: Int): Long =
    (intLE(offset).toLong() shl 32) or (intLE(offset + 4).toLong() and 0xFFFFFFFFL)

private fun ByteArray.shortLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

class XSyncTest {

    private val s = Sy()
    private val rootId = X11.RESOURCE_ID_BASE
    private lateinit var document: XDocument
    private lateinit var connection: XConnection

    private val counter = rootId + 200
    private val counter2 = rootId + 201
    private val alarm = rootId + 210

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        connection = XConnection(document)
        drain()
    }

    private fun send(vararg requests: ByteArray) {
        requests.forEach { connection.onBytes(it) }
    }

    private fun drain(): List<ByteArray> = connection.drainOutbox()
    private fun List<ByteArray>.replies() = filter { it[0].toInt() == 1 }
    private fun List<ByteArray>.errors() = filter { it[0].toInt() == 0 }
    private fun List<ByteArray>.events(type: Int) = filter { it[0].toInt() == type }

    // --- negotiation ---------------------------------------------------------------

    @Test
    fun `QueryExtension hands out SYNC's bases`() {
        send(s.queryExtension("SYNC"))
        val reply = drain().replies().single()
        assertEquals(1, reply[1].toInt())
        assertEquals(XSync.MAJOR_OPCODE, reply[8].toInt() and 0xFF)
        assertEquals(XSync.FIRST_EVENT, reply[9].toInt() and 0xFF)
        assertEquals(XSync.FIRST_ERROR, reply[10].toInt() and 0xFF)
    }

    @Test
    fun `Initialize negotiates 3_1 and never above it`() {
        send(s.initialize(3, 1))
        var reply = drain().replies().single()
        assertEquals(3, reply[8].toInt() and 0xFF)
        assertEquals(1, reply[9].toInt() and 0xFF)
        send(s.initialize(4, 0))
        reply = drain().replies().single()
        assertEquals(3, reply[8].toInt() and 0xFF)
        assertEquals(1, reply[9].toInt() and 0xFF)
        send(s.initialize(2, 0))
        reply = drain().replies().single()
        assertEquals(2, reply[8].toInt() and 0xFF)
        assertEquals(0, reply[9].toInt() and 0xFF)
    }

    @Test
    fun `ListSystemCounters honestly reports none`() {
        send(s.listSystemCounters())
        val reply = drain().replies().single()
        assertEquals(0, reply.intLE(4)) // length: no counter list follows
        assertEquals(0, reply.intLE(8)) // nCounters
    }

    // --- counters -------------------------------------------------------------------

    @Test
    fun `counters carry the full INT64 range`() {
        send(s.createCounter(counter, -1))
        send(s.queryCounter(counter))
        var reply = drain().replies().single()
        assertEquals(-1L, reply.int64LE(8))
        send(s.setCounter(counter, 1L shl 32)) // 2^32: needs the hi word
        send(s.queryCounter(counter))
        reply = drain().replies().single()
        assertEquals(1L shl 32, reply.int64LE(8))
        send(s.changeCounter(counter, 5))
        send(s.queryCounter(counter))
        reply = drain().replies().single()
        assertEquals((1L shl 32) + 5, reply.int64LE(8))
        send(s.changeCounter(counter, -((1L shl 32) + 5)))
        send(s.queryCounter(counter))
        assertEquals(0L, drain().replies().single().int64LE(8))
    }

    @Test
    fun `duplicate CreateCounter is BadIDChoice and unknown counters are BadCounter`() {
        send(s.createCounter(counter, 0))
        drain()
        send(s.createCounter(counter, 0))
        assertEquals(X11.ERR_BAD_ID_CHOICE, drain().errors().single()[1].toInt() and 0xFF)
        send(s.queryCounter(424242))
        val error = drain().errors().single()
        assertEquals(XSync.ERR_BAD_COUNTER, error[1].toInt() and 0xFF)
        assertEquals(XSync.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertEquals(XSync.MINOR_QUERY_COUNTER, error.shortLE(8))
    }

    @Test
    fun `DestroyCounter frees the id and a second destroy is BadCounter`() {
        send(s.createCounter(counter, 7))
        send(s.destroyCounter(counter))
        assertTrue(drain().errors().isEmpty())
        send(s.destroyCounter(counter))
        assertEquals(XSync.ERR_BAD_COUNTER, drain().errors().single()[1].toInt() and 0xFF)
    }

    // --- Await ------------------------------------------------------------------------

    @Test
    fun `a satisfied Await replies immediately`() {
        send(s.createCounter(counter, 10))
        drain()
        send(s.await(Triple(counter, XSync.VALUE_POSITIVE_COMPARISON, 5L)))
        val reply = drain().replies().single()
        assertEquals(0, reply.intLE(4)) // no body
    }

    @Test
    fun `a held Await replies on the satisfying change - with its OWN sequence number`() {
        send(s.initialize())
        val initReply = drain().replies().single()
        val initSeq = initReply.shortLE(2)
        send(s.createCounter(counter, 0)) // no reply; seq = initSeq + 1
        send(s.await(Triple(counter, XSync.VALUE_POSITIVE_COMPARISON, 10L))) // seq = initSeq + 2
        assertTrue("the Await must be held", drain().replies().isEmpty())
        send(s.queryCounter(counter)) // seq = initSeq + 3
        val queryReply = drain().replies().single()
        assertEquals(initSeq + 3, queryReply.shortLE(2))
        send(s.setCounter(counter, 9)) // not yet satisfied
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 10)) // satisfied -> the held reply arrives
        val awaitReply = drain().replies().single()
        assertEquals(1, awaitReply[0].toInt())
        assertEquals(initSeq + 2, awaitReply.shortLE(2)) // the AWAIT's sequence
    }

    @Test
    fun `transition conditions fire on the crossing, not the level`() {
        send(s.createCounter(counter, 0))
        drain()
        send(s.await(Triple(counter, XSync.VALUE_POSITIVE_TRANSITION, 10L)))
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 5)) // below the wait value: no crossing
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 12)) // 5 -> 12 crosses 10
        assertEquals(1, drain().replies().single()[0].toInt())
    }

    @Test
    fun `negative transitions fire crossing downwards`() {
        send(s.createCounter(counter, 20))
        drain()
        send(s.await(Triple(counter, XSync.VALUE_NEGATIVE_TRANSITION, 10L)))
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 12)) // 20 -> 12: still above
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 8)) // crosses down through 10
        assertEquals(1, drain().replies().single()[0].toInt())
    }

    @Test
    fun `a multi-counter Await needs EVERY condition true at once`() {
        send(s.createCounter(counter, 0), s.createCounter(counter2, 0))
        drain()
        send(
            s.await(
                Triple(counter, XSync.VALUE_POSITIVE_COMPARISON, 10L),
                Triple(counter2, XSync.VALUE_POSITIVE_COMPARISON, 10L),
            ),
        )
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 15)) // only one true
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter2, 11)) // both true now
        assertEquals(1, drain().replies().single()[0].toInt())
    }

    @Test
    fun `a relative Await measures from the counter value at Await time`() {
        send(s.createCounter(counter, 100))
        drain()
        send(s.awaitRelative(counter, XSync.VALUE_POSITIVE_COMPARISON, 5))
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 104))
        assertTrue(drain().replies().isEmpty())
        send(s.setCounter(counter, 105))
        assertEquals(1, drain().replies().single()[0].toInt())
    }

    @Test
    fun `Await on an unknown counter is BadCounter`() {
        send(s.await(Triple(424242, XSync.VALUE_POSITIVE_COMPARISON, 1L)))
        assertEquals(XSync.ERR_BAD_COUNTER, drain().errors().single()[1].toInt() and 0xFF)
    }

    // --- alarms -------------------------------------------------------------------------

    @Test
    fun `a comparison alarm with events delivers AlarmNotify on every satisfying change`() {
        send(s.createCounter(counter, 0))
        send(s.createAlarm(alarm, counter, XSync.VALUE_POSITIVE_COMPARISON, 10, events = true))
        assertTrue(drain().errors().isEmpty())
        send(s.setCounter(counter, 12))
        val event = drain().events(XSync.EV_ALARM_NOTIFY).single()
        assertEquals(XSync.KIND_ALARM_NOTIFY, event[1].toInt())
        assertEquals(alarm, event.intLE(4))
        assertEquals(12L, event.int64LE(8)) // counter_value
        assertEquals(10L, event.int64LE(16)) // alarm (wait) value
        assertEquals(XSync.ALARM_ACTIVE, event[28].toInt()) // state at trigger
        send(s.setCounter(counter, 13)) // still >= 10: fires again (comparison semantics)
        assertEquals(1, drain().events(XSync.EV_ALARM_NOTIFY).size)
        send(s.setCounter(counter, 3)) // below: silent
        assertTrue(drain().events(XSync.EV_ALARM_NOTIFY).isEmpty())
    }

    @Test
    fun `an alarm delta re-adjusts the counter after firing`() {
        send(s.createCounter(counter, 0))
        send(s.createAlarm(alarm, counter, XSync.VALUE_POSITIVE_TRANSITION, 10, events = true, delta = -10))
        drain()
        send(s.setCounter(counter, 12))
        drain() // the notify
        send(s.queryCounter(counter))
        assertEquals(2L, drain().replies().single().int64LE(8)) // 12 - 10
    }

    @Test
    fun `events=false alarms stay silent`() {
        send(s.createCounter(counter, 0))
        send(s.createAlarm(alarm, counter, XSync.VALUE_POSITIVE_COMPARISON, 1, events = false))
        drain()
        send(s.setCounter(counter, 5))
        assertTrue(drain().events(XSync.EV_ALARM_NOTIFY).isEmpty())
    }

    @Test
    fun `QueryAlarm round-trips every attribute in the 40-byte reply`() {
        send(s.createCounter(counter, 0))
        send(s.createAlarm(alarm, counter, XSync.VALUE_NEGATIVE_TRANSITION, -7, events = true, delta = 3))
        send(s.queryAlarm(alarm))
        val reply = drain().replies().single()
        assertEquals(2, reply.intLE(4)) // length: 40 bytes total
        assertEquals(counter, reply.intLE(8))
        assertEquals(XSync.VALUE_NEGATIVE_TRANSITION, reply.intLE(12))
        assertEquals(-7L, reply.int64LE(16))
        assertEquals(0, reply.intLE(24)) // test_type: absolute
        assertEquals(3L, reply.int64LE(28))
        assertEquals(1, reply[36].toInt()) // events BOOL
        assertEquals(XSync.ALARM_ACTIVE, reply[37].toInt()) // state
    }

    @Test
    fun `ChangeAlarm edits attributes and DestroyAlarm frees the id`() {
        send(s.createCounter(counter, 0))
        send(s.createAlarm(alarm, counter, XSync.VALUE_POSITIVE_COMPARISON, 100, events = true))
        send(s.changeAlarmValue(alarm, 1))
        assertTrue(drain().errors().isEmpty())
        send(s.setCounter(counter, 2)) // would not fire at wait=100; fires at wait=1
        assertEquals(1, drain().events(XSync.EV_ALARM_NOTIFY).size)
        send(s.destroyAlarm(alarm))
        assertTrue(drain().errors().isEmpty())
        send(s.destroyAlarm(alarm))
        assertEquals(XSync.ERR_BAD_ALARM, drain().errors().single()[1].toInt() and 0xFF)
    }

    @Test
    fun `CreateAlarm on an unknown counter is BadCounter and creates nothing`() {
        send(s.createAlarm(alarm, 424242, XSync.VALUE_POSITIVE_COMPARISON, 1, events = true))
        assertEquals(XSync.ERR_BAD_COUNTER, drain().errors().single()[1].toInt() and 0xFF)
        send(s.queryAlarm(alarm))
        assertEquals(XSync.ERR_BAD_ALARM, drain().errors().single()[1].toInt() and 0xFF)
    }

    // --- priorities + the refused fence tail --------------------------------------------

    @Test
    fun `priorities are accepted and honestly zero`() {
        send(s.createCounter(counter, 0))
        send(s.setPriority(counter, 5))
        assertTrue(drain().errors().isEmpty())
        send(s.getPriority(counter))
        val reply = drain().replies().single()
        assertEquals(0, reply.intLE(8))
    }

    @Test
    fun `fences are refused BadRequest and named in the trace`() {
        send(s.createFence())
        val error = drain().errors().single()
        assertEquals(X11.ERR_BAD_REQUEST, error[1].toInt() and 0xFF)
        assertEquals(XSync.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertEquals(XSync.MINOR_CREATE_FENCE, error.shortLE(8))
        assertTrue(connection.trace.unimplementedOpcodes().containsKey("134.14"))
        assertTrue(connection.trace.summary().contains("SYNC.CreateFence"))
    }

    // --- lifecycle -------------------------------------------------------------------------

    @Test
    fun `a dead connection takes its counters, alarms and held awaits with it`() {
        send(s.createCounter(counter, 0))
        send(s.createAlarm(alarm, counter, XSync.VALUE_POSITIVE_COMPARISON, 1, events = true))
        send(s.await(Triple(counter, XSync.VALUE_POSITIVE_COMPARISON, 99L)))
        drain()
        connection.releaseResources() // what XServer calls when the socket dies
        val survivor = XConnection(document)
        survivor.onBytes(s.queryCounter(counter))
        assertEquals(XSync.ERR_BAD_COUNTER, survivor.drainOutbox().errors().single()[1].toInt() and 0xFF)
        survivor.onBytes(s.queryAlarm(alarm))
        assertEquals(XSync.ERR_BAD_ALARM, survivor.drainOutbox().errors().single()[1].toInt() and 0xFF)
    }
}
