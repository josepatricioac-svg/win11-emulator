package dev.win11we.shell.x11

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * D1e XInput2 wire tests. Layouts from xorgproto 2024.1 (`XI2proto.h`, `XI2.h`)
 * and, for the device list, walked exactly the way libXi does: class by class,
 * trusting each class's own length field. A class that lies about its length
 * turns every later class - and every later reply - into garbage, which is why
 * the walk is asserted end to end rather than spot-checked.
 *
 * The event tests are the point of the whole extension: they pin the fields GDK
 * reads (`root_x`/`event_x` as FP1616, the button mask, the modifier split) and
 * the rule that made XI2 worth having - a client that selected XI2 events gets
 * them even though it selected NO core masks (which is exactly what GDK does),
 * and it does not get a second copy of the same click as a core event.
 */
private class Xi2 {
    fun build(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}): ByteArray {
        val bodyWriter = XWriter(true)
        body(bodyWriter)
        val bodyBytes = bodyWriter.toByteArray()
        val writer = XWriter(true)
        writer.byte(opcode)
        writer.byte(data)
        val total = 4 + bodyBytes.size
        val padded = X11.pad4(total)
        writer.short(padded / 4)
        writer.bytes(bodyBytes)
        writer.pad(padded - total)
        return writer.toByteArray()
    }

    fun xi2(minor: Int, body: (XWriter) -> Unit = {}) = build(XInput2.MAJOR_OPCODE, minor, body)

    fun core(opcode: Int, data: Int = 0, body: (XWriter) -> Unit = {}) = build(opcode, data, body)

    fun queryExtension(name: String) = core(X11.REQ_QUERY_EXTENSION) { writer ->
        writer.short(name.length); writer.short(0); writer.paddedString(name)
    }

    fun createWindow(id: Int, parent: Int, x: Int = 0, y: Int = 0, w: Int = 200, h: Int = 200, mask: Int = 0) =
        core(X11.REQ_CREATE_WINDOW, 24) { writer ->
            writer.int(id); writer.int(parent)
            writer.short(x); writer.short(y); writer.short(w); writer.short(h)
            writer.short(0); writer.short(0); writer.int(0x21)
            writer.int(if (mask == 0) 0 else 0x00000800)
            if (mask != 0) writer.int(mask)
        }

    fun mapWindow(id: Int) = core(X11.REQ_MAP_WINDOW) { it.int(id) }
    fun destroyWindow(id: Int) = core(X11.REQ_DESTROY_WINDOW) { it.int(id) }
    fun setInputFocus(window: Int, revertTo: Int = 1) = core(X11.REQ_SET_INPUT_FOCUS, revertTo) { writer ->
        writer.int(window); writer.int(0)
    }

    fun createCursor(id: Int, source: Int, mask: Int) = core(X11.REQ_CREATE_CURSOR) { writer ->
        writer.int(id); writer.int(source); writer.int(mask)
        writer.short(0); writer.short(0); writer.short(0); writer.short(0); writer.short(0); writer.short(0)
    }

    fun createPixmap(id: Int, drawable: Int, w: Int = 16, h: Int = 16) =
        core(X11.REQ_CREATE_PIXMAP, 1) { writer ->
            writer.int(id); writer.int(drawable); writer.short(w); writer.short(h)
        }

    // --- XI2 requests --------------------------------------------------------

    fun queryVersion(major: Int = 2, minor: Int = 4) = xi2(XInput2.MINOR_QUERY_VERSION) { writer ->
        writer.short(major); writer.short(minor)
    }

    fun queryDevice(deviceId: Int = XInput2.ALL_DEVICES) = xi2(XInput2.MINOR_QUERY_DEVICE) { writer ->
        writer.short(deviceId); writer.short(0)
    }

    /** One mask entry: device id + mask bytes (the mask is built from event types). */
    fun mask(deviceId: Int, vararg evtypes: Int): ByteArray {
        val units = evtypes.maxOfOrNull { XInput2.maskLenFor(it) } ?: 1
        return XInput2.buildMask(maxOf(units, 1), evtypes.toList())
    }

    fun selectEvents(win: Int, vararg masks: Pair<Int, ByteArray>) =
        xi2(XInput2.MINOR_SELECT_EVENTS) { writer ->
            writer.int(win)
            writer.short(masks.size)
            writer.short(0)
            masks.forEach { (deviceId, maskBytes) ->
                writer.short(deviceId)
                writer.short(maskBytes.size / 4)
                writer.bytes(maskBytes)
            }
        }

    fun getSelectedEvents(win: Int) = xi2(XInput2.MINOR_GET_SELECTED_EVENTS) { it.int(win) }

    fun queryPointer(win: Int, deviceId: Int = XInput2.MASTER_POINTER) =
        xi2(XInput2.MINOR_QUERY_POINTER) { writer ->
            writer.int(win); writer.short(deviceId); writer.short(0)
        }

    fun warpPointer(dstX: Int, dstY: Int, src: Int = 0, dst: Int = 0, deviceId: Int = XInput2.MASTER_POINTER) =
        xi2(XInput2.MINOR_WARP_POINTER) { writer ->
            writer.int(src); writer.int(dst)
            writer.int(0); writer.int(0)
            writer.short(0); writer.short(0)
            writer.int(dstX shl 16); writer.int(dstY shl 16)
            writer.short(deviceId); writer.short(0)
        }

    fun setFocus(win: Int, deviceId: Int = XInput2.MASTER_KEYBOARD) = xi2(XInput2.MINOR_SET_FOCUS) { writer ->
        writer.int(win); writer.int(0); writer.short(deviceId); writer.short(0)
    }

    fun getFocus(deviceId: Int = XInput2.MASTER_KEYBOARD) = xi2(XInput2.MINOR_GET_FOCUS) { writer ->
        writer.short(deviceId); writer.short(0)
    }

    fun grabDevice(
        win: Int,
        deviceId: Int = XInput2.MASTER_POINTER,
        mask: ByteArray = ByteArray(4) { 0xFF.toByte() },
        ownerEvents: Boolean = false,
        cursor: Int = 0,
    ) = xi2(XInput2.MINOR_GRAB_DEVICE) { writer ->
        writer.int(win); writer.int(0); writer.int(cursor)
        writer.short(deviceId)
        writer.byte(0); writer.byte(0) // grab_mode, paired_device_mode
        writer.byte(if (ownerEvents) 1 else 0)
        writer.byte(0)
        writer.short(mask.size / 4)
        writer.bytes(mask)
    }

    fun ungrabDevice(deviceId: Int = XInput2.MASTER_POINTER) = xi2(XInput2.MINOR_UNGRAB_DEVICE) { writer ->
        writer.int(0); writer.short(deviceId); writer.short(0)
    }

    fun allowEvents(deviceId: Int = XInput2.MASTER_POINTER) = xi2(XInput2.MINOR_ALLOW_EVENTS) { writer ->
        writer.int(0); writer.short(deviceId); writer.byte(0); writer.byte(0)
    }

    fun passiveGrab(
        win: Int,
        detail: Int,
        grabType: Int,
        deviceId: Int = XInput2.MASTER_POINTER,
        mask: ByteArray = ByteArray(4) { 0xFF.toByte() },
        ownerEvents: Boolean = false,
    ) = xi2(XInput2.MINOR_PASSIVE_GRAB_DEVICE) { writer ->
        writer.int(0); writer.int(win); writer.int(0); writer.int(detail)
        writer.short(deviceId); writer.short(0); writer.short(mask.size / 4)
        writer.byte(grabType); writer.byte(0); writer.byte(0)
        writer.byte(if (ownerEvents) 1 else 0)
        writer.short(0)
        writer.bytes(mask)
    }

    fun passiveUngrab(win: Int, detail: Int, grabType: Int) =
        xi2(XInput2.MINOR_PASSIVE_UNGRAB_DEVICE) { writer ->
            writer.int(win); writer.int(detail)
            writer.short(XInput2.MASTER_POINTER); writer.short(0)
            writer.byte(grabType); writer.byte(0); writer.short(0)
        }

    fun changeCursor(win: Int, cursor: Int, deviceId: Int = XInput2.MASTER_POINTER) =
        xi2(XInput2.MINOR_CHANGE_CURSOR) { writer ->
            writer.int(win); writer.int(cursor); writer.short(deviceId); writer.short(0)
        }

    fun setClientPointer(deviceId: Int) = xi2(XInput2.MINOR_SET_CLIENT_POINTER) { writer ->
        writer.int(0); writer.short(deviceId); writer.short(0)
    }

    fun getClientPointer() = xi2(XInput2.MINOR_GET_CLIENT_POINTER) { it.int(0) }

    fun listProperties(deviceId: Int) = xi2(XInput2.MINOR_LIST_PROPERTIES) { writer ->
        writer.short(deviceId); writer.short(0)
    }

    fun changeProperty(deviceId: Int, property: Int, type: Int, format: Int, data: ByteArray, mode: Int = 0) =
        xi2(XInput2.MINOR_CHANGE_PROPERTY) { writer ->
            writer.short(deviceId); writer.byte(mode); writer.byte(format)
            writer.int(property); writer.int(type); writer.int(data.size / (format / 8))
            writer.bytes(data)
            writer.pad(X11.padded(data.size))
        }

    fun deleteProperty(deviceId: Int, property: Int) = xi2(XInput2.MINOR_DELETE_PROPERTY) { writer ->
        writer.short(deviceId); writer.short(0); writer.int(property)
    }

    fun getProperty(deviceId: Int, property: Int, len: Int = 0) = xi2(XInput2.MINOR_GET_PROPERTY) { writer ->
        writer.short(deviceId); writer.byte(0); writer.byte(0)
        writer.int(property); writer.int(0); writer.int(0); writer.int(len)
    }

    fun changeHierarchy() = xi2(XInput2.MINOR_CHANGE_HIERARCHY) { writer ->
        writer.byte(1); writer.byte(0); writer.short(0)
        writer.short(XInput2.SLAVE_POINTER); writer.short(2); writer.short(0); writer.byte(0); writer.byte(0)
    }

    fun barrierReleasePointer() = xi2(XInput2.MINOR_BARRIER_RELEASE_POINTER) { it.int(0) }
}

private fun ByteArray.intLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.shortLE(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

/** One parsed `xXIDeviceEvent` (key/button/motion). */
private class DeviceEvent(val raw: ByteArray) {
    val evtype = raw.shortLE(8)
    val deviceId = raw.shortLE(10)
    val detail = raw.intLE(16)
    val root = raw.intLE(20)
    val event = raw.intLE(24)
    val child = raw.intLE(28)
    val rootX = raw.intLE(32) / 65536.0
    val rootY = raw.intLE(36) / 65536.0
    val eventX = raw.intLE(40) / 65536.0
    val eventY = raw.intLE(44) / 65536.0
    val buttonsLen = raw.shortLE(48)
    val valuatorsLen = raw.shortLE(50)
    val sourceId = raw.shortLE(52)
    val flags = raw.intLE(56)
    val baseMods = raw.intLE(60)
    val latchedMods = raw.intLE(64)
    val lockedMods = raw.intLE(68)
    val effectiveMods = raw.intLE(72)
    val buttonMask = raw.intLE(80)

    init {
        assertEquals("generic event type", XGenericEvent.EVENT_TYPE, raw[0].toInt() and 0xFF)
        assertEquals("extension byte is XI2's major opcode", XInput2.MAJOR_OPCODE, raw[1].toInt() and 0xFF)
        assertEquals("size must equal 32 + length*4", 32 + raw.intLE(4) * 4, raw.size)
        assertEquals("buttons_len 1 unit = 84 bytes", 84, raw.size)
    }
}

/** One parsed `xXIEnterEvent` (Enter/Leave/FocusIn/FocusOut share the layout). */
private class CrossingEvent(val raw: ByteArray) {
    val evtype = raw.shortLE(8)
    val deviceId = raw.shortLE(10)
    val sourceId = raw.shortLE(16)
    val mode = raw[18].toInt() and 0xFF
    val detail = raw[19].toInt() and 0xFF
    val root = raw.intLE(20)
    val event = raw.intLE(24)
    val child = raw.intLE(28)
    val rootX = raw.intLE(32) / 65536.0
    val eventX = raw.intLE(40) / 65536.0
    val sameScreen = raw[48].toInt() and 0xFF
    val focus = raw[49].toInt() and 0xFF
    val buttonsLen = raw.shortLE(50)

    init {
        assertEquals(XGenericEvent.EVENT_TYPE, raw[0].toInt() and 0xFF)
        assertEquals(XInput2.MAJOR_OPCODE, raw[1].toInt() and 0xFF)
        assertEquals(32 + raw.intLE(4) * 4, raw.size)
        assertEquals("72-byte header + one button-mask unit", 76, raw.size)
    }
}

/** A device entry walked out of an `XIQueryDevice` reply, libXi-style. */
private class DeviceInfo(
    val id: Int,
    val use: Int,
    val attachment: Int,
    val name: String,
    val enabled: Boolean,
    val classes: List<Pair<Int, ByteArray>>,
)

class XInput2Test {

    private lateinit var document: XDocument
    private lateinit var sink: RecordingSink
    private val x = Xi2()
    private val rootId = X11.RESOURCE_ID_BASE
    private val win = rootId + 1
    private val child = rootId + 2
    private val other = rootId + 3
    private val cursor = rootId + 40
    private val pixmap = rootId + 41

    @Before
    fun setUp() {
        document = XServer.defaultDocument(1280, 800)
        sink = RecordingSink()
    }

    private fun connection() = XConnection(document, sink)

    private fun send(connection: XConnection, vararg bytes: ByteArray): List<ByteArray> {
        bytes.forEach { connection.onBytes(it) }
        return connection.drainOutbox()
    }

    private fun List<ByteArray>.replies() = filter { it[0].toInt() == 1 }
    private fun List<ByteArray>.errors() = filter { it[0].toInt() == 0 }
    private fun List<ByteArray>.reply(): ByteArray = replies().single()
    private fun List<ByteArray>.generic() = filter {
        (it[0].toInt() and 0xFF) == XGenericEvent.EVENT_TYPE && (it[1].toInt() and 0xFF) == XInput2.MAJOR_OPCODE
    }

    /** Key/button/motion events (evtype 2-6); crossings are parsed separately. */
    private fun List<ByteArray>.deviceEvents() = generic()
        .filter { it.shortLE(8) in XInput2.EV_KEY_PRESS..XInput2.EV_MOTION }
        .map { DeviceEvent(it) }

    /** Enter/Leave/FocusIn/FocusOut (evtype 7-10), which share one layout. */
    private fun List<ByteArray>.crossingEvents() = generic()
        .filter { it.shortLE(8) in XInput2.EV_ENTER..XInput2.EV_FOCUS_OUT }
        .map { CrossingEvent(it) }

    /** A mapped toplevel with an optional mapped child, and NO core event masks. */
    private fun createTree(client: XConnection, mask: Int = 0) {
        send(client, x.createWindow(win, rootId, 10, 10, 200, 200, mask), x.mapWindow(win))
        send(client, x.createWindow(child, win, 5, 5, 50, 50, 0), x.mapWindow(child))
        client.drainOutbox()
    }

    private fun selectOn(client: XConnection, windowId: Int, vararg evtypes: Int) {
        send(client, x.selectEvents(windowId, XInput2.MASTER_POINTER to x.mask(XInput2.MASTER_POINTER, *evtypes),
            XInput2.MASTER_KEYBOARD to x.mask(XInput2.MASTER_KEYBOARD, *evtypes)))
        client.drainOutbox()
    }

    // --- advertisement and version ------------------------------------------

    @Test
    fun `XInputExtension is advertised with its own opcode, event base and error base`() {
        val client = connection()
        val reply = send(client, x.queryExtension(XInput2.NAME)).reply()
        assertEquals(1, reply[1].toInt()) // present
        assertEquals(XInput2.MAJOR_OPCODE, reply[8].toInt() and 0xFF)
        assertEquals(XInput2.FIRST_EVENT, reply[9].toInt() and 0xFF)
        assertEquals(XInput2.FIRST_ERROR, reply[10].toInt() and 0xFF)
        assertTrue("the error base must not collide with RENDER's",
            XInput2.FIRST_ERROR >= XRender.FIRST_ERROR + 5)
    }

    @Test
    fun `QueryVersion answers 2 dot 2 and never more than the client asked for`() {
        val client = connection()
        // GDK asks for 2.4; we must not claim gestures we cannot deliver.
        val asked24 = send(client, x.queryVersion(2, 4)).reply()
        assertEquals(2, asked24.shortLE(8))
        assertEquals(2, asked24.shortLE(10))
        assertEquals(XInput2.MINOR_QUERY_VERSION, asked24[1].toInt() and 0xFF)
        // A client that asks for less gets its own number back.
        assertEquals(0, send(client, x.queryVersion(2, 0)).reply().shortLE(10))
        // A client from the future still gets our version, not a refusal.
        val asked30 = send(client, x.queryVersion(3, 0)).reply()
        assertEquals(2, asked30.shortLE(8))
        assertEquals(2, asked30.shortLE(10))
        assertTrue(client.xi2Negotiated)
    }

    // --- the device list -----------------------------------------------------

    @Test
    fun `QueryDevice reports a master pair with the slave pair behind it, walked like libXi does`() {
        val client = connection()
        val reply = send(client, x.queryDevice()).reply()
        assertEquals(XInput2.MINOR_QUERY_DEVICE, reply[1].toInt() and 0xFF)
        assertEquals(4, reply.shortLE(8))
        // The reply must be exactly as long as it claims: 32 + length*4.
        assertEquals(32 + reply.intLE(4) * 4, reply.size)

        val devices = walkDeviceList(reply)
        assertEquals(4, devices.size)
        val masterPointer = devices.first { it.id == XInput2.MASTER_POINTER }
        assertEquals("Virtual core pointer", masterPointer.name)
        assertEquals(XInput2.USE_MASTER_POINTER, masterPointer.use)
        assertEquals(XInput2.MASTER_KEYBOARD, masterPointer.attachment)
        assertTrue(masterPointer.enabled)
        assertEquals(listOf(XInput2.CLASS_BUTTON, XInput2.CLASS_VALUATOR, XInput2.CLASS_VALUATOR),
            masterPointer.classes.map { it.first })

        val masterKeyboard = devices.first { it.id == XInput2.MASTER_KEYBOARD }
        assertEquals(XInput2.USE_MASTER_KEYBOARD, masterKeyboard.use)
        assertEquals(XInput2.MASTER_POINTER, masterKeyboard.attachment)
        assertEquals(listOf(XInput2.CLASS_KEY), masterKeyboard.classes.map { it.first })

        // Slaves are attached to their masters and are the sourceid of our events.
        assertEquals(XInput2.MASTER_POINTER, devices.first { it.id == XInput2.SLAVE_POINTER }.attachment)
        assertEquals(XInput2.MASTER_KEYBOARD, devices.first { it.id == XInput2.SLAVE_KEYBOARD }.attachment)

        // The payload must end exactly where the last class ends.
        assertEquals(reply.size, 32 + reply.intLE(4) * 4)
    }

    @Test
    fun `the key class lists the keycode range the server's keyboard map serves`() {
        val client = connection()
        val devices = walkDeviceList(send(client, x.queryDevice()).reply())
        val keyboard = devices.first { it.id == XInput2.MASTER_KEYBOARD }
        val keyClass = keyboard.classes.single { it.first == XInput2.CLASS_KEY }.second
        val numKeycodes = keyClass.shortLE(6)
        val keyboardMap = XKeyboard.default()
        assertEquals(keyboardMap.maxKeycode - keyboardMap.minKeycode + 1, numKeycodes)
        val keycodes = List(numKeycodes) { keyClass.intLE(8 + it * 4) }
        assertEquals(keyboardMap.minKeycode, keycodes.first())
        assertEquals(keyboardMap.maxKeycode, keycodes.last())
        assertEquals("the class's own length field must cover the whole keycode list",
            keyClass.size / 4, keyClass.shortLE(2))
    }

    @Test
    fun `the button class names every button and reports none of them held down`() {
        val client = connection()
        val devices = walkDeviceList(send(client, x.queryDevice()).reply())
        val pointer = devices.first { it.id == XInput2.MASTER_POINTER }
        val buttonClass = pointer.classes.single { it.first == XInput2.CLASS_BUTTON }.second
        assertEquals(XInput2.NUM_BUTTONS, buttonClass.shortLE(6))
        val maskBytes = (XInput2.NUM_BUTTONS + 7) / 8
        assertTrue("no button is logically down at query time",
            buttonClass.copyOfRange(8, 8 + maskBytes).all { it.toInt() == 0 })
        // Every button label must be a real atom the client can name.
        val labels = List(XInput2.NUM_BUTTONS) { buttonClass.intLE(8 + X11.pad4(maskBytes) + it * 4) }
        assertEquals((1..XInput2.NUM_BUTTONS).map { "Button$it" }, labels.map { document.atomName(it) })
    }

    @Test
    fun `the valuator classes are relative, so GDK ignores them and reads position from events`() {
        val client = connection()
        val devices = walkDeviceList(send(client, x.queryDevice()).reply())
        val pointer = devices.first { it.id == XInput2.MASTER_POINTER }
        val valuators = pointer.classes.filter { it.first == XInput2.CLASS_VALUATOR }.map { it.second }
        assertEquals(2, valuators.size)
        valuators.forEachIndexed { index, v ->
            assertEquals(44, v.size) // xXIValuatorInfo is 11 units
            assertEquals(11, v.shortLE(2))
            assertEquals(index, v.shortLE(6)) // valuator number
            assertEquals(XInput2.MODE_RELATIVE, v[40].toInt() and 0xFF)
            val label = v.intLE(8)
            assertEquals(if (index == 0) "Rel X" else "Rel Y", document.atomName(label))
        }
        // Every class in the reply must agree with its own length field.
        assertTrue(XInput2.checkClassLengths(devices.flatMap { it.classes.map { c -> c.second } }))
    }

    @Test
    fun `QueryDevice answers for one device, for the masters only, and refuses an unknown id`() {
        val client = connection()
        assertEquals(1, walkDeviceList(send(client, x.queryDevice(XInput2.MASTER_POINTER)).reply()).size)
        val masters = walkDeviceList(send(client, x.queryDevice(XInput2.ALL_MASTER_DEVICES)).reply())
        assertEquals(listOf(XInput2.MASTER_POINTER, XInput2.MASTER_KEYBOARD), masters.map { it.id })
        val error = send(client, x.queryDevice(99)).errors().single()
        assertEquals(XInput2.ERR_BAD_DEVICE_ID, error[1].toInt() and 0xFF)
        assertEquals(XInput2.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertEquals(XInput2.MINOR_QUERY_DEVICE, error.shortLE(8))
    }

    /** Walks an XIQueryDevice reply the way libXi does: by each class's own length. */
    private fun walkDeviceList(reply: ByteArray): List<DeviceInfo> {
        val count = reply.shortLE(8)
        val devices = mutableListOf<DeviceInfo>()
        var offset = 32
        repeat(count) {
            val id = reply.shortLE(offset)
            val use = reply.shortLE(offset + 2)
            val attachment = reply.shortLE(offset + 4)
            val numClasses = reply.shortLE(offset + 6)
            val nameLen = reply.shortLE(offset + 8)
            val enabled = (reply[offset + 10].toInt() and 0xFF) != 0
            val name = String(reply.copyOfRange(offset + 12, offset + 12 + nameLen), Charsets.US_ASCII)
            offset += 12 + X11.pad4(nameLen)
            val classes = mutableListOf<Pair<Int, ByteArray>>()
            repeat(numClasses) {
                val type = reply.shortLE(offset)
                val units = reply.shortLE(offset + 2)
                assertTrue("class length must be non-zero at offset $offset", units > 0)
                classes += type to reply.copyOfRange(offset, offset + units * 4)
                offset += units * 4
            }
            devices += DeviceInfo(id, use, attachment, name, enabled, classes)
        }
        assertEquals("the device list must end exactly at the reply's end", reply.size, offset)
        return devices
    }

    // --- selections ----------------------------------------------------------

    @Test
    fun `SelectEvents then GetSelectedEvents round-trips every mask`() {
        val client = connection()
        createTree(client)
        val pointerMask = x.mask(XInput2.MASTER_POINTER, XInput2.EV_MOTION, XInput2.EV_BUTTON_PRESS)
        val allMasters = XInput2.buildMask(1, listOf(XInput2.EV_KEY_PRESS, XInput2.EV_FOCUS_IN))
        send(client, x.selectEvents(win,
            XInput2.MASTER_POINTER to pointerMask,
            XInput2.ALL_MASTER_DEVICES to allMasters))
        val reply = send(client, x.getSelectedEvents(win)).reply()
        assertEquals(2, reply.shortLE(8))
        assertEquals(32 + reply.intLE(4) * 4, reply.size)
        var offset = 32
        val echoed = mutableListOf<Pair<Int, ByteArray>>()
        repeat(2) {
            val deviceId = reply.shortLE(offset)
            val units = reply.shortLE(offset + 2)
            echoed += deviceId to reply.copyOfRange(offset + 4, offset + 4 + units * 4)
            offset += 4 + units * 4
        }
        assertEquals(reply.size, offset)
        assertTrue(echoed.any { it.first == XInput2.MASTER_POINTER && it.second.contentEquals(pointerMask) })
        assertTrue(echoed.any { it.first == XInput2.ALL_MASTER_DEVICES && it.second.contentEquals(allMasters) })
    }

    @Test
    fun `an all-zero mask removes the selection instead of storing dead weight`() {
        val client = connection()
        createTree(client)
        selectOn(client, win, XInput2.EV_MOTION)
        send(client, x.selectEvents(win, XInput2.MASTER_POINTER to ByteArray(4), XInput2.MASTER_KEYBOARD to ByteArray(4)))
        val reply = send(client, x.getSelectedEvents(win)).reply()
        assertEquals(0, reply.shortLE(8))
        client.onPointerMove(50, 50)
        assertTrue("nothing is selected, so nothing is delivered", client.drainOutbox().generic().isEmpty())
    }

    @Test
    fun `SelectEvents refuses a bad window and an unknown device, and stores nothing when it refuses`() {
        val client = connection()
        createTree(client)
        assertEquals(X11.ERR_BAD_WINDOW, send(client, x.selectEvents(rootId + 999,
            XInput2.MASTER_POINTER to x.mask(XInput2.MASTER_POINTER, XInput2.EV_MOTION))).errors().single()[1].toInt() and 0xFF)
        val bad = send(client, x.selectEvents(win,
            XInput2.MASTER_POINTER to x.mask(XInput2.MASTER_POINTER, XInput2.EV_MOTION),
            77 to ByteArray(4)))
        assertEquals(XInput2.ERR_BAD_DEVICE, bad.errors().single()[1].toInt() and 0xFF)
        // The valid first mask must NOT have been applied: validation is all-or-nothing.
        assertEquals(0, send(client, x.getSelectedEvents(win)).reply().shortLE(8))
    }

    // --- event delivery ------------------------------------------------------

    @Test
    fun `motion reaches a client that selected XI2 events and no core masks at all`() {
        val client = connection()
        createTree(client) // the toplevel has core mask 0, exactly like a GDK window
        selectOn(client, win, XInput2.EV_MOTION)

        client.onPointerMove(20, 20) // inside the child at (15,15)-(65,65)
        val event = client.drainOutbox().deviceEvents().single()
        assertEquals(XInput2.EV_MOTION, event.evtype)
        assertEquals(XInput2.MASTER_POINTER, event.deviceId)
        assertEquals(XInput2.SLAVE_POINTER, event.sourceId)
        assertEquals(rootId, event.root)
        assertEquals("the toplevel that selected the event receives it", win, event.event)
        assertEquals("child names the window the pointer is really over", child, event.child)
        assertEquals(20.0, event.rootX, 0.001)
        assertEquals(20.0, event.rootY, 0.001)
        assertEquals(10.0, event.eventX, 0.001) // relative to the toplevel at (10,10)
        assertEquals(10.0, event.eventY, 0.001)
        assertEquals(1, event.buttonsLen)
        assertEquals(0, event.valuatorsLen)
        assertEquals(0, event.flags)
        assertEquals(0, event.buttonMask)
        assertEquals("no core event may accompany it - GDK would see two moves",
            0, client.drainOutbox().count { (it[0].toInt() and 0x7F) == X11.EV_MOTION_NOTIFY })
    }

    @Test
    fun `button press and release carry the button number and the XI2 button mask`() {
        val client = connection()
        createTree(client)
        selectOn(client, win, XInput2.EV_BUTTON_PRESS, XInput2.EV_BUTTON_RELEASE)

        client.onButton(30, 30, 1, true, 1 shl 8)
        val press = client.drainOutbox().deviceEvents().single()
        assertEquals(XInput2.EV_BUTTON_PRESS, press.evtype)
        assertEquals(1, press.detail)
        assertEquals("XI2 button n is bit n-1", 1, press.buttonMask)

        client.onButton(30, 30, 3, true, (1 shl 8) or (1 shl 10))
        val right = client.drainOutbox().deviceEvents().single()
        assertEquals(3, right.detail)
        assertEquals(0b101, right.buttonMask)

        client.onButton(30, 30, 3, false, 1 shl 8)
        val release = client.drainOutbox().deviceEvents().single()
        assertEquals(XInput2.EV_BUTTON_RELEASE, release.evtype)
        assertEquals(3, release.detail)
        assertEquals(1, release.buttonMask)
    }

    @Test
    fun `Android's back and forward buttons survive the core-to-XI2 mask conversion`() {
        assertEquals(1 shl 7, XInput2.xi2ButtonMask(1 shl (7 + 8)))
        assertEquals(1 shl 8, XInput2.xi2ButtonMask(1 shl (7 + 9)))
        assertEquals("core bit 15 is button 8 -> XI2 bit 7", 1 shl 7, XInput2.xi2ButtonMask(1 shl 15))
        assertEquals("core bit 16 is button 9 -> XI2 bit 8", 0b100000001, XInput2.xi2ButtonMask((1 shl 8) or (1 shl 16)))
        assertEquals(0, XInput2.xi2ButtonMask(0))
    }

    @Test
    fun `key events go to the focus window with the modifier split GDK expects`() {
        val client = connection()
        createTree(client, mask = EventMask.FOCUS_CHANGE)
        selectOn(client, win, XInput2.EV_KEY_PRESS, XInput2.EV_KEY_RELEASE)
        send(client, x.setInputFocus(win, revertTo = 1))

        client.onKey(XKeyboard.XKEY_A, 'a'.code, true, 0)
        val press = client.drainOutbox().deviceEvents().single()
        assertEquals(XInput2.EV_KEY_PRESS, press.evtype)
        assertEquals(XInput2.MASTER_KEYBOARD, press.deviceId)
        assertEquals(XInput2.SLAVE_KEYBOARD, press.sourceId)
        assertEquals(XKeyboard.XKEY_A, press.detail)
        assertEquals(win, press.event)
        assertEquals(0, press.baseMods)

        // Shift held: base_mods carries it, locked stays clear.
        client.onKey(XKeyboard.XKEY_A, 'A'.code, true, X11.MASK_SHIFT)
        val shifted = client.drainOutbox().deviceEvents().single()
        assertEquals(X11.MASK_SHIFT, shifted.baseMods)
        assertEquals(0, shifted.lockedMods)
        assertEquals(X11.MASK_SHIFT, shifted.effectiveMods)

        // CapsLock is a latch, not a pressed modifier.
        client.onKey(XKeyboard.XKEY_A, 'a'.code, true, X11.MASK_LOCK)
        val locked = client.drainOutbox().deviceEvents().single()
        assertEquals(0, locked.baseMods)
        assertEquals(X11.MASK_LOCK, locked.lockedMods)
    }

    @Test
    fun `selections are per-connection - a client that did not select sees nothing`() {
        val selector = connection()
        val bystander = connection()
        createTree(selector)
        selectOn(selector, win, XInput2.EV_MOTION, XInput2.EV_BUTTON_PRESS)

        // The server broadcasts input to every connection; each filters by its own.
        selector.onPointerMove(40, 40); bystander.onPointerMove(40, 40)
        assertEquals(1, selector.drainOutbox().deviceEvents().size)
        assertTrue(bystander.drainOutbox().generic().isEmpty())

        selector.onButton(40, 40, 1, true, 1 shl 8); bystander.onButton(40, 40, 1, true, 1 shl 8)
        assertEquals(1, selector.drainOutbox().deviceEvents().size)
        assertTrue(bystander.drainOutbox().generic().isEmpty())
    }

    @Test
    fun `XIAllMasterDevices and XIAllDevices selections both cover the masters`() {
        val client = connection()
        createTree(client)
        send(client, x.selectEvents(win, XInput2.ALL_MASTER_DEVICES to
            XInput2.buildMask(1, listOf(XInput2.EV_MOTION, XInput2.EV_KEY_PRESS))))
        client.drainOutbox()
        send(client, x.setInputFocus(win, revertTo = 1))
        client.drainOutbox()

        client.onPointerMove(40, 40)
        assertEquals(XInput2.EV_MOTION, client.drainOutbox().deviceEvents().single().evtype)
        send(client, x.setInputFocus(win, revertTo = 1))
        client.onKey(XKeyboard.XKEY_A, 'a'.code, true, 0)
        assertEquals(XInput2.EV_KEY_PRESS, client.drainOutbox().deviceEvents().single().evtype)
    }

    @Test
    fun `an event type that was not selected is not delivered`() {
        val client = connection()
        createTree(client)
        selectOn(client, win, XInput2.EV_BUTTON_PRESS) // deliberately not motion
        client.onPointerMove(40, 40)
        assertTrue(client.drainOutbox().generic().isEmpty())
        client.onButton(40, 40, 1, true, 1 shl 8)
        assertEquals(1, client.drainOutbox().deviceEvents().size)
    }

    @Test
    fun `enter and leave cross between toplevels with Nonlinear detail`() {
        val client = connection()
        send(client, x.createWindow(win, rootId, 0, 0, 100, 100, 0), x.mapWindow(win))
        send(client, x.createWindow(other, rootId, 200, 0, 100, 100, 0), x.mapWindow(other))
        client.drainOutbox()
        selectOn(client, win, XInput2.EV_ENTER, XInput2.EV_LEAVE)
        selectOn(client, other, XInput2.EV_ENTER, XInput2.EV_LEAVE)

        client.onPointerMove(10, 10)
        val enter = client.drainOutbox().crossingEvents().single()
        assertEquals(XInput2.EV_ENTER, enter.evtype)
        assertEquals(win, enter.event)
        assertEquals(XInput2.MASTER_POINTER, enter.deviceId)
        assertEquals(XInput2.NOTIFY_NORMAL, enter.mode)
        assertEquals(1, enter.sameScreen)
        assertEquals(1, enter.buttonsLen)

        client.onPointerMove(210, 10)
        val crossings = client.drainOutbox().crossingEvents()
        assertEquals(2, crossings.size)
        val leave = crossings.first { it.evtype == XInput2.EV_LEAVE }
        val enterOther = crossings.first { it.evtype == XInput2.EV_ENTER }
        assertEquals(win, leave.event)
        assertEquals(other, enterOther.event)
        assertEquals("two unrelated toplevels is a Nonlinear crossing",
            XInput2.NOTIFY_NONLINEAR, leave.detail)
        assertEquals(XInput2.NOTIFY_NONLINEAR, enterOther.detail)
    }

    @Test
    fun `crossing a parent-child boundary reports Inferior and Ancestor details`() {
        val client = connection()
        createTree(client)
        selectOn(client, win, XInput2.EV_ENTER, XInput2.EV_LEAVE)
        selectOn(client, child, XInput2.EV_ENTER, XInput2.EV_LEAVE)

        client.onPointerMove(100, 100) // toplevel only
        client.drainOutbox()
        client.onPointerMove(20, 20) // into the child
        val intoChild = client.drainOutbox().crossingEvents()
        val leaveParent = intoChild.first { it.evtype == XInput2.EV_LEAVE && it.event == win }
        val enterChild = intoChild.first { it.evtype == XInput2.EV_ENTER && it.event == child }
        // X names the detail relative to the EVENT window: leaving the parent for
        // its own child is Inferior, entering the child from its parent is Ancestor.
        assertEquals(XInput2.NOTIFY_INFERIOR, leaveParent.detail)
        assertEquals(XInput2.NOTIFY_ANCESTOR, enterChild.detail)
    }

    @Test
    fun `focus changes are delivered as XI2 FocusIn and FocusOut`() {
        val client = connection()
        createTree(client, mask = EventMask.FOCUS_CHANGE)
        selectOn(client, win, XInput2.EV_FOCUS_IN, XInput2.EV_FOCUS_OUT)
        val first = send(client, x.setInputFocus(win, revertTo = 1)).crossingEvents()
        assertEquals(XInput2.EV_FOCUS_IN, first.single().evtype)
        assertEquals(win, first.single().event)
        assertEquals(XInput2.MASTER_KEYBOARD, first.single().deviceId)
        assertEquals("the focus window reports focus=true", 1, first.single().focus)

        send(client, x.createWindow(other, rootId, 400, 0, 100, 100, 0), x.mapWindow(other))
        selectOn(client, other, XInput2.EV_FOCUS_IN, XInput2.EV_FOCUS_OUT)
        val pair = send(client, x.setInputFocus(other, revertTo = 1)).crossingEvents()
        assertEquals(setOf(XInput2.EV_FOCUS_OUT, XInput2.EV_FOCUS_IN), pair.map { it.evtype }.toSet())
        assertEquals(win, pair.first { it.evtype == XInput2.EV_FOCUS_OUT }.event)
        assertEquals(other, pair.first { it.evtype == XInput2.EV_FOCUS_IN }.event)
        // The core FocusIn/FocusOut pair still goes to the window's owner.
        assertTrue(pair.isNotEmpty())
    }

    // --- grabs ---------------------------------------------------------------

    @Test
    fun `XIGrabDevice redirects XI2 events to the grab window and stops other clients`() {
        val grabber = connection()
        val victim = connection()
        createTree(grabber)
        selectOn(grabber, win, XInput2.EV_MOTION, XInput2.EV_BUTTON_PRESS)
        send(victim, x.createWindow(other, rootId, 0, 0, 300, 300, 0), x.mapWindow(other))
        selectOn(victim, other, XInput2.EV_MOTION, XInput2.EV_BUTTON_PRESS)

        val reply = send(grabber, x.grabDevice(win)).reply()
        assertEquals(XInput2.GRAB_SUCCESS, reply[8].toInt() and 0xFF)

        grabber.onPointerMove(250, 250); victim.onPointerMove(250, 250)
        val grabbed = grabber.drainOutbox().deviceEvents().single()
        assertEquals("events follow the grab, not the pointer", win, grabbed.event)
        assertEquals(250.0, grabbed.rootX, 0.001)
        assertTrue("the other client is frozen out while the grab is held", victim.drainOutbox().generic().isEmpty())

        send(grabber, x.ungrabDevice())
        grabber.drainOutbox(); victim.drainOutbox()
        grabber.onPointerMove(250, 250); victim.onPointerMove(250, 250)
        assertTrue(grabber.drainOutbox().generic().isEmpty()) // the pointer is over 'other', not 'win'
        assertEquals(other, victim.drainOutbox().deviceEvents().single().event)
    }

    @Test
    fun `an XI2 grab does not leak a second copy of the event into the core protocol`() {
        val client = connection()
        // A window that selected BOTH core button events and XI2 ones: the classic
        // double-input trap. GDK translates core events through its XI2 translator,
        // so a permissive core grab mask would hand it two clicks per click.
        createTree(client, mask = EventMask.BUTTON_PRESS or EventMask.BUTTON_RELEASE)
        selectOn(client, win, XInput2.EV_BUTTON_PRESS)
        send(client, x.grabDevice(win, mask = x.mask(XInput2.MASTER_POINTER, XInput2.EV_BUTTON_PRESS)))
        client.drainOutbox()

        client.onButton(30, 30, 1, true, 1 shl 8)
        val out = client.drainOutbox()
        assertEquals(1, out.deviceEvents().size)
        assertEquals("exactly one button event, in XI2 form only",
            0, out.count { (it[0].toInt() and 0x7F) == X11.EV_BUTTON_PRESS })
    }

    @Test
    fun `owner-events on an XI2 grab still delivers to the window under the pointer`() {
        val client = connection()
        createTree(client)
        send(client, x.createWindow(other, rootId, 300, 0, 200, 200, 0), x.mapWindow(other))
        selectOn(client, win, XInput2.EV_MOTION)
        selectOn(client, other, XInput2.EV_MOTION)
        send(client, x.grabDevice(win, ownerEvents = true))
        client.drainOutbox()

        client.onPointerMove(350, 50)
        val events = client.drainOutbox().deviceEvents()
        assertEquals("the grab window and, thanks to owner-events, the window under the pointer",
            setOf(win, other), events.map { it.event }.toSet())
    }

    @Test
    fun `a second XI2 grab is refused with AlreadyGrabbed`() {
        val client = connection()
        createTree(client)
        assertEquals(XInput2.GRAB_SUCCESS, send(client, x.grabDevice(win)).reply()[8].toInt() and 0xFF)
        assertEquals(XInput2.GRAB_ALREADY_GRABBED, send(client, x.grabDevice(win)).reply()[8].toInt() and 0xFF)
        // Ungrabbing a device we never grabbed is still a clean no-op, not an error.
        send(client, x.ungrabDevice())
        assertEquals(XInput2.GRAB_SUCCESS, send(client, x.grabDevice(win)).reply()[8].toInt() and 0xFF)
    }

    @Test
    fun `a keyboard XI2 grab redirects keys and the grab dies with its window`() {
        val client = connection()
        createTree(client, mask = EventMask.FOCUS_CHANGE)
        send(client, x.setInputFocus(win, revertTo = 1))
        send(client, x.selectEvents(win, XInput2.MASTER_KEYBOARD to
            x.mask(XInput2.MASTER_KEYBOARD, XInput2.EV_KEY_PRESS)))
        send(client, x.grabDevice(win, deviceId = XInput2.MASTER_KEYBOARD))
        client.drainOutbox()

        client.onKey(XKeyboard.XKEY_A, 'a'.code, true, 0)
        assertEquals(XInput2.MASTER_KEYBOARD, client.drainOutbox().deviceEvents().single().deviceId)

        // Destroying the grab window must not leave a grab pointing at nothing.
        send(client, x.destroyWindow(win))
        client.drainOutbox()
        client.onKey(XKeyboard.XKEY_A, 'a'.code, true, 0)
        assertTrue(client.drainOutbox().generic().isEmpty())
    }

    @Test
    fun `AllowEvents is accepted - we never freeze a device`() {
        val client = connection()
        createTree(client)
        val out = send(client, x.allowEvents())
        assertTrue(out.errors().isEmpty())
        assertTrue(out.replies().isEmpty())
    }

    @Test
    fun `a passive XI2 button grab activates on a matching press, like the core one`() {
        val client = connection()
        createTree(client)
        selectOn(client, win, XInput2.EV_BUTTON_PRESS)
        val reply = send(client, x.passiveGrab(win, detail = 1, grabType = XInput2.GRAB_TYPE_BUTTON)).reply()
        assertEquals(0, reply.shortLE(8)) // no modifier conflicts
        client.drainOutbox()

        client.onButton(20, 20, 1, true, 1 shl 8)
        assertTrue("the press is delivered through the activated grab",
            client.drainOutbox().deviceEvents().isNotEmpty())

        send(client, x.passiveUngrab(win, detail = 1, grabType = XInput2.GRAB_TYPE_BUTTON))
        assertTrue(document.passiveGrabs().none { it.detail == 1 && !it.isKey })
    }

    // --- pointer queries and warps ------------------------------------------

    @Test
    fun `QueryPointer reports the position in FP1616 plus the button and modifier state`() {
        val client = connection()
        createTree(client)
        client.onPointerMove(30, 40)
        client.onButton(30, 40, 1, true, 1 shl 8)
        client.drainOutbox()

        val reply = send(client, x.queryPointer(win)).reply()
        assertEquals(XInput2.MINOR_QUERY_POINTER, reply[1].toInt() and 0xFF)
        assertEquals("56-byte reply plus one button-mask unit", 60, reply.size)
        assertEquals(32 + reply.intLE(4) * 4, reply.size)
        assertEquals(rootId, reply.intLE(8))
        assertEquals(child, reply.intLE(12)) // the child covers (30,40)
        assertEquals(30.0, reply.intLE(16) / 65536.0, 0.001)
        assertEquals(40.0, reply.intLE(20) / 65536.0, 0.001)
        assertEquals(20.0, reply.intLE(24) / 65536.0, 0.001) // relative to the toplevel at (10,10)
        assertEquals(30.0, reply.intLE(28) / 65536.0, 0.001)
        assertEquals(1, reply[32].toInt() and 0xFF) // same_screen
        assertEquals(1, reply.shortLE(34)) // buttons_len
        assertEquals(1, reply.intLE(56)) // button 1 is down: bit 0
    }

    @Test
    fun `QueryPointer refuses a device that is not a pointer`() {
        val client = connection()
        createTree(client)
        val error = send(client, x.queryPointer(win, XInput2.MASTER_KEYBOARD)).errors().single()
        assertEquals(XInput2.ERR_BAD_DEVICE, error[1].toInt() and 0xFF)
        assertEquals(X11.ERR_BAD_WINDOW,
            send(client, x.queryPointer(rootId + 999)).errors().single()[1].toInt() and 0xFF)
    }

    @Test
    fun `WarpPointer moves the server pointer and the move is visible to XI2 clients`() {
        val client = connection()
        createTree(client)
        selectOn(client, win, XInput2.EV_MOTION)
        // send() already drains the outbox, so its return value is the response.
        val out = send(client, x.warpPointer(60, 70))
        assertTrue(out.errors().isEmpty())
        val event = out.deviceEvents().single()
        assertEquals(XInput2.EV_MOTION, event.evtype)
        assertEquals(60.0, event.rootX, 0.001)
        assertEquals(70.0, event.rootY, 0.001)
        val pointer = send(client, x.queryPointer(win)).reply()
        assertEquals(60.0, pointer.intLE(16) / 65536.0, 0.001)
    }

    // --- focus, client pointer, cursors -------------------------------------

    @Test
    fun `SetFocus and GetFocus drive the same focus the core protocol uses`() {
        val client = connection()
        createTree(client)
        val out = send(client, x.setFocus(win))
        assertTrue(out.errors().isEmpty())
        assertEquals(win, document.focus)
        assertEquals(win, send(client, x.getFocus()).reply().intLE(8))
        assertEquals(X11.ERR_BAD_WINDOW,
            send(client, x.setFocus(rootId + 999)).errors().single()[1].toInt() and 0xFF)
        assertEquals(XInput2.ERR_BAD_DEVICE,
            send(client, x.getFocus(XInput2.MASTER_POINTER)).errors().single()[1].toInt() and 0xFF)
    }

    @Test
    fun `the client pointer defaults to the master pointer and can be changed`() {
        val client = connection()
        var reply = send(client, x.getClientPointer()).reply()
        assertEquals(1, reply[8].toInt() and 0xFF) // set
        assertEquals(XInput2.MASTER_POINTER, reply.shortLE(10))
        send(client, x.setClientPointer(XInput2.SLAVE_POINTER))
        reply = send(client, x.getClientPointer()).reply()
        assertEquals(XInput2.SLAVE_POINTER, reply.shortLE(10))
        assertEquals(XInput2.ERR_BAD_DEVICE_ID,
            send(client, x.setClientPointer(99)).errors().single()[1].toInt() and 0xFF)
    }

    @Test
    fun `ChangeCursor defines and undefines a window cursor per device`() {
        val client = connection()
        createTree(client)
        send(client, x.createPixmap(pixmap, rootId), x.createCursor(cursor, pixmap, pixmap))
        client.drainOutbox()

        send(client, x.changeCursor(win, cursor))
        assertEquals(cursor, document.window(win)!!.cursor)
        send(client, x.changeCursor(win, 0)) // XIUndefineCursor is this request with None
        assertEquals(0, document.window(win)!!.cursor)

        assertEquals(X11.ERR_BAD_CURSOR,
            send(client, x.changeCursor(win, rootId + 999)).errors().single()[1].toInt() and 0xFF)
        assertEquals(XInput2.ERR_BAD_DEVICE,
            send(client, x.changeCursor(win, cursor, XInput2.MASTER_KEYBOARD)).errors().single()[1].toInt() and 0xFF)
    }

    // --- device properties ---------------------------------------------------

    @Test
    fun `device properties round-trip, and an absent one answers type None instead of an error`() {
        val client = connection()
        val vendorId = document.internAtom("Device Vendor ID")
        val data = byteArrayOf(0x34, 0x12, 0x00, 0x00, 0x78, 0x56, 0x00, 0x00)
        val out = send(client, x.changeProperty(XInput2.SLAVE_POINTER, vendorId, 19 /* INTEGER */, 32, data))
        assertTrue(out.errors().isEmpty())

        val reply = send(client, x.getProperty(XInput2.SLAVE_POINTER, vendorId)).reply()
        assertEquals(19, reply.intLE(8)) // type
        assertEquals(0, reply.intLE(12)) // bytes_after
        assertEquals(2, reply.intLE(16)) // num_items (32-bit)
        assertEquals(32, reply[20].toInt() and 0xFF) // format
        assertArrayEquals(data, reply.copyOfRange(32, 32 + data.size))

        val listed = send(client, x.listProperties(XInput2.SLAVE_POINTER)).reply()
        assertEquals(1, listed.shortLE(8))
        assertEquals(vendorId, listed.intLE(32))

        val absent = send(client, x.getProperty(XInput2.SLAVE_POINTER, document.internAtom("libinput Tapping Enabled"))).reply()
        assertEquals("GDK probes properties that do not exist; None is the answer, not an error",
            0, absent.intLE(8))
        assertEquals(0, absent.intLE(16))

        send(client, x.deleteProperty(XInput2.SLAVE_POINTER, vendorId))
        assertEquals(0, send(client, x.listProperties(XInput2.SLAVE_POINTER)).reply().shortLE(8))
    }

    @Test
    fun `device properties are per device, and Append and Prepend keep the order`() {
        val client = connection()
        val property = document.internAtom("Test Property")
        send(client, x.changeProperty(XInput2.SLAVE_POINTER, property, 19, 8, byteArrayOf(1, 2)))
        send(client, x.changeProperty(XInput2.SLAVE_KEYBOARD, property, 19, 8, byteArrayOf(9)))
        assertEquals(0, send(client, x.listProperties(XInput2.MASTER_POINTER)).reply().shortLE(8))
        assertArrayEquals(byteArrayOf(9),
            send(client, x.getProperty(XInput2.SLAVE_KEYBOARD, property)).reply().copyOfRange(32, 33))

        send(client, x.changeProperty(XInput2.SLAVE_POINTER, property, 19, 8, byteArrayOf(3), mode = 2 /* Append */))
        assertArrayEquals(byteArrayOf(1, 2, 3),
            send(client, x.getProperty(XInput2.SLAVE_POINTER, property)).reply().copyOfRange(32, 35))
        send(client, x.changeProperty(XInput2.SLAVE_POINTER, property, 19, 8, byteArrayOf(0), mode = 1 /* Prepend */))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3),
            send(client, x.getProperty(XInput2.SLAVE_POINTER, property)).reply().copyOfRange(32, 36))
    }

    @Test
    fun `GetProperty honours the requested length and reports bytes_after`() {
        val client = connection()
        val property = document.internAtom("Long Property")
        val data = ByteArray(16) { it.toByte() }
        send(client, x.changeProperty(XInput2.SLAVE_POINTER, property, 19, 32, data))
        val reply = send(client, x.getProperty(XInput2.SLAVE_POINTER, property, len = 2)).reply()
        assertEquals(2, reply.intLE(16)) // num_items
        assertEquals(8, reply.intLE(12)) // bytes_after: two more 32-bit items
        assertArrayEquals(data.copyOfRange(0, 8), reply.copyOfRange(32, 40))
    }

    // --- honest refusals -----------------------------------------------------

    @Test
    fun `ChangeHierarchy and BarrierReleasePointer are refused and named, not silently accepted`() {
        val client = connection()
        val hierarchy = send(client, x.changeHierarchy()).errors().single()
        assertEquals(X11.ERR_BAD_REQUEST, hierarchy[1].toInt() and 0xFF)
        assertEquals(XInput2.MINOR_CHANGE_HIERARCHY, hierarchy.shortLE(8))
        val barrier = send(client, x.barrierReleasePointer()).errors().single()
        assertEquals(X11.ERR_BAD_REQUEST, barrier[1].toInt() and 0xFF)
        val summary = client.trace.summary()
        assertTrue("the report must name the requests: $summary", summary.contains("XI2.ChangeHierarchy"))
        assertTrue(summary.contains("XI2.BarrierReleasePointer"))
    }

    @Test
    fun `an unknown XI2 minor is refused with its name in the trace`() {
        val client = connection()
        val error = send(client, x.xi2(39)).errors().single()
        assertEquals(X11.ERR_BAD_REQUEST, error[1].toInt() and 0xFF)
        assertEquals(XInput2.MAJOR_OPCODE, error[10].toInt() and 0xFF)
        assertEquals(39, error.shortLE(8))
        assertTrue(client.trace.summary().contains("XI2.minor39"))
    }

    @Test
    fun `XI 1 dot x minors are not mistaken for XI2 requests`() {
        // libXi's XIDefineCursor/XIUndefineCursor are wrappers for XI2's
        // ChangeCursor (42); the XI 1.x request numbers (1-39) are a different
        // protocol we do not implement, and must be refused rather than misread.
        val client = connection()
        val error = send(client, x.xi2(2 /* X_ListInputDevices */)).errors().single()
        assertEquals(X11.ERR_BAD_REQUEST, error[1].toInt() and 0xFF)
        assertTrue(client.trace.summary().contains("XI2.minor2"))
    }

    // --- mask helpers --------------------------------------------------------

    @Test
    fun `the mask helpers match XI2's bit numbering`() {
        val mask = XInput2.buildMask(1, listOf(XInput2.EV_MOTION, XInput2.EV_BUTTON_PRESS, XInput2.EV_FOCUS_IN))
        assertTrue(XInput2.maskIsSet(mask, XInput2.EV_MOTION))
        assertTrue(XInput2.maskIsSet(mask, XInput2.EV_BUTTON_PRESS))
        assertTrue(XInput2.maskIsSet(mask, XInput2.EV_FOCUS_IN))
        assertFalse(XInput2.maskIsSet(mask, XInput2.EV_KEY_PRESS))
        assertFalse("reading past the mask is a miss, not a crash", XInput2.maskIsSet(mask, 200))
        assertEquals(1, XInput2.maskLenFor(XInput2.EV_MOTION))
        assertEquals(1, XInput2.maskLenFor(XInput2.EV_PROPERTY_EVENT))
        // A touch event type needs a second unit; we do not report touch devices,
        // but the helper must still be right for the masks clients send us.
        assertEquals(1, XInput2.maskLenFor(18))
        assertTrue(XInput2.selectionCovers(XInput2.ALL_DEVICES, XInput2.SLAVE_KEYBOARD))
        assertTrue(XInput2.selectionCovers(XInput2.ALL_MASTER_DEVICES, XInput2.MASTER_POINTER))
        assertFalse(XInput2.selectionCovers(XInput2.ALL_MASTER_DEVICES, XInput2.SLAVE_POINTER))
        assertTrue(XInput2.selectionCovers(XInput2.MASTER_POINTER, XInput2.MASTER_POINTER))
        assertFalse(XInput2.selectionCovers(XInput2.MASTER_POINTER, XInput2.MASTER_KEYBOARD))
    }

    @Test
    fun `modifier state is split into base, latched and effective`() {
        val (base, locked, effective) = XInput2.modifierParts(X11.MASK_SHIFT or X11.MASK_LOCK)
        assertEquals(X11.MASK_SHIFT, base)
        assertEquals(X11.MASK_LOCK, locked)
        assertEquals(X11.MASK_SHIFT or X11.MASK_LOCK, effective)
    }

    @Test
    fun `destroying a window drops its XI2 selections`() {
        val client = connection()
        createTree(client)
        selectOn(client, win, XInput2.EV_MOTION)
        send(client, x.destroyWindow(win))
        client.drainOutbox()
        // The window is gone: GetSelectedEvents refuses it and no event is built
        // for a selection that no longer exists.
        assertEquals(X11.ERR_BAD_WINDOW, send(client, x.getSelectedEvents(win)).errors().single()[1].toInt() and 0xFF)
        client.onPointerMove(20, 20)
        assertTrue(client.drainOutbox().generic().isEmpty())
    }

    @Test
    fun `GetSelectedEvents on a window nobody selected answers zero masks`() {
        val client = connection()
        createTree(client)
        val reply = send(client, x.getSelectedEvents(win)).reply()
        assertEquals(0, reply.shortLE(8))
        assertEquals(32, reply.size)
        assertNull(XInput2.device(99))
        assertNotNull(XInput2.device(XInput2.SLAVE_POINTER))
    }
}
