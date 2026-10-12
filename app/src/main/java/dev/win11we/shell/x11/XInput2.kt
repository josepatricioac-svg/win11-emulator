package dev.win11we.shell.x11

import kotlin.math.abs

/**
 * XInput 2 (XI2) - the input extension GTK3/GDK uses for *all* of its input.
 *
 * Why it matters here: GDK3 asks for XI 2.4 at display-open time
 * (`_gdk_x11_device_manager_new`: `XIQueryVersion(2, 4)`) and only falls back to
 * the legacy core device manager when the server answers `BadRequest`. Without
 * XI2 Firefox still opens a display, but it then runs on core-protocol input:
 * one pointer, no device model, no XI grabs - the path GTK has kept alive for
 * compatibility and nothing else. With XI2 the shell gets the input model a
 * desktop browser expects, including `XIGrabDevice` (what GTK's menus and popups
 * grab with) and per-window event selection.
 *
 * **The version we claim is 2.2, not 2.4.** GDK gates only two things on the
 * minor version - touch (2.2) and touchpad gestures (2.4) - and selects those
 * event types when the claim is high enough. We report **no touch and no gesture
 * devices**, so a client that selects `XI_TouchBegin` simply never receives one;
 * Android touch arrives as single-pointer `XI_Motion`/`XI_ButtonPress` from
 * [XInput], exactly as it does on the core path. Claiming 2.4 would invite
 * gesture selections we cannot honour. Multi-touch (`XI_TouchBegin/Update/End`
 * with real touch ids, which is what would give Firefox pinch-zoom) is the named
 * next step - it needs the Android pointer-index plumbing in `XDesktop`, not
 * protocol work here.
 *
 * Everything a 2.2 client can send is implemented except the requests that would
 * be a lie to answer: `XIChangeHierarchy` (our device set is static - there is no
 * hotplug to model) and `XIBarrierReleasePointer` (we have no pointer barriers and
 * never send barrier events). Both answer `BadRequest` and are named in the
 * protocol trace, so a device log says which one a client hit.
 *
 * Wire layouts from xorgproto 2024.1: `XI2proto.h` (requests, replies, events),
 * `XI2.h` (event types, device uses, class types, masks), `Xproto.h`
 * (`xGenericEvent` - every XI2 event travels as a GenericEvent).
 */
object XInput2 {
    /** The name clients query: `XQueryExtension("XInputExtension")`. */
    const val NAME = "XInputExtension"

    const val MAJOR_OPCODE = 131

    /**
     * XI2's own events are GenericEvents, so this base is informational - it is
     * the offset XI 1.x-style events (`DeviceValuator` and friends) would use.
     * libXi reports it to clients either way.
     */
    const val FIRST_EVENT = 65

    /** After RENDER's five errors (160-164); extension error codes start at 128. */
    const val FIRST_ERROR = 165
    const val ERR_BAD_DEVICE = FIRST_ERROR + 0
    const val ERR_BAD_EVENT = FIRST_ERROR + 1
    const val ERR_BAD_MODE = FIRST_ERROR + 2
    const val ERR_BAD_DEVICE_ID = FIRST_ERROR + 3
    const val ERR_BAD_MATCH = FIRST_ERROR + 4

    const val VERSION_MAJOR = 2
    const val VERSION_MINOR = 2

    // --- minor opcodes (XI2proto.h) -----------------------------------------
    const val MINOR_QUERY_POINTER = 40
    const val MINOR_WARP_POINTER = 41
    const val MINOR_CHANGE_CURSOR = 42
    const val MINOR_CHANGE_HIERARCHY = 43
    const val MINOR_SET_CLIENT_POINTER = 44
    const val MINOR_GET_CLIENT_POINTER = 45
    const val MINOR_SELECT_EVENTS = 46
    const val MINOR_QUERY_VERSION = 47
    const val MINOR_QUERY_DEVICE = 48
    const val MINOR_SET_FOCUS = 49
    const val MINOR_GET_FOCUS = 50
    const val MINOR_GRAB_DEVICE = 51
    const val MINOR_UNGRAB_DEVICE = 52
    const val MINOR_ALLOW_EVENTS = 53
    const val MINOR_PASSIVE_GRAB_DEVICE = 54
    const val MINOR_PASSIVE_UNGRAB_DEVICE = 55
    const val MINOR_LIST_PROPERTIES = 56
    const val MINOR_CHANGE_PROPERTY = 57
    const val MINOR_DELETE_PROPERTY = 58
    const val MINOR_GET_PROPERTY = 59
    const val MINOR_GET_SELECTED_EVENTS = 60
    const val MINOR_BARRIER_RELEASE_POINTER = 61

    /** Request name for the protocol trace, so a device log names the request. */
    fun minorName(minor: Int): String = when (minor) {
        MINOR_QUERY_POINTER -> "QueryPointer"
        MINOR_WARP_POINTER -> "WarpPointer"
        MINOR_CHANGE_CURSOR -> "ChangeCursor"
        MINOR_CHANGE_HIERARCHY -> "ChangeHierarchy"
        MINOR_SET_CLIENT_POINTER -> "SetClientPointer"
        MINOR_GET_CLIENT_POINTER -> "GetClientPointer"
        MINOR_SELECT_EVENTS -> "SelectEvents"
        MINOR_QUERY_VERSION -> "QueryVersion"
        MINOR_QUERY_DEVICE -> "QueryDevice"
        MINOR_SET_FOCUS -> "SetFocus"
        MINOR_GET_FOCUS -> "GetFocus"
        MINOR_GRAB_DEVICE -> "GrabDevice"
        MINOR_UNGRAB_DEVICE -> "UngrabDevice"
        MINOR_ALLOW_EVENTS -> "AllowEvents"
        MINOR_PASSIVE_GRAB_DEVICE -> "PassiveGrabDevice"
        MINOR_PASSIVE_UNGRAB_DEVICE -> "PassiveUngrabDevice"
        MINOR_LIST_PROPERTIES -> "ListProperties"
        MINOR_CHANGE_PROPERTY -> "ChangeProperty"
        MINOR_DELETE_PROPERTY -> "DeleteProperty"
        MINOR_GET_PROPERTY -> "GetProperty"
        MINOR_GET_SELECTED_EVENTS -> "GetSelectedEvents"
        MINOR_BARRIER_RELEASE_POINTER -> "BarrierReleasePointer"
        else -> "minor$minor"
    }

    // --- event types (XI2.h) -------------------------------------------------
    const val EV_DEVICE_CHANGED = 1
    const val EV_KEY_PRESS = 2
    const val EV_KEY_RELEASE = 3
    const val EV_BUTTON_PRESS = 4
    const val EV_BUTTON_RELEASE = 5
    const val EV_MOTION = 6
    const val EV_ENTER = 7
    const val EV_LEAVE = 8
    const val EV_FOCUS_IN = 9
    const val EV_FOCUS_OUT = 10
    const val EV_HIERARCHY_CHANGED = 11
    const val EV_PROPERTY_EVENT = 12

    fun eventName(evtype: Int): String = when (evtype) {
        EV_DEVICE_CHANGED -> "DeviceChanged"
        EV_KEY_PRESS -> "KeyPress"
        EV_KEY_RELEASE -> "KeyRelease"
        EV_BUTTON_PRESS -> "ButtonPress"
        EV_BUTTON_RELEASE -> "ButtonRelease"
        EV_MOTION -> "Motion"
        EV_ENTER -> "Enter"
        EV_LEAVE -> "Leave"
        EV_FOCUS_IN -> "FocusIn"
        EV_FOCUS_OUT -> "FocusOut"
        EV_HIERARCHY_CHANGED -> "HierarchyChanged"
        EV_PROPERTY_EVENT -> "PropertyEvent"
        else -> "evtype$evtype"
    }

    // --- device ids and uses (XI2.h) ----------------------------------------
    /** Pseudo-devices a client may select for or query. */
    const val ALL_DEVICES = 0
    const val ALL_MASTER_DEVICES = 1

    /** The fixed device set: one master pair plus the slave pair behind it. */
    const val MASTER_POINTER = 2
    const val MASTER_KEYBOARD = 3
    const val SLAVE_POINTER = 4
    const val SLAVE_KEYBOARD = 5

    const val USE_MASTER_POINTER = 1
    const val USE_MASTER_KEYBOARD = 2
    const val USE_SLAVE_POINTER = 3
    const val USE_SLAVE_KEYBOARD = 4
    const val USE_FLOATING_SLAVE = 5

    // --- device classes (XI2.h) ---------------------------------------------
    const val CLASS_KEY = 0
    const val CLASS_BUTTON = 1
    const val CLASS_VALUATOR = 2
    const val CLASS_SCROLL = 3
    const val CLASS_TOUCH = 8
    const val CLASS_GESTURE = 9

    const val MODE_RELATIVE = 0
    const val MODE_ABSOLUTE = 1

    // --- notify modes and details (XI2.h) -----------------------------------
    const val NOTIFY_NORMAL = 0
    const val NOTIFY_ANCESTOR = 2
    const val NOTIFY_INFERIOR = 1
    const val NOTIFY_NONLINEAR = 4
    const val NOTIFY_POINTER = 6
    const val NOTIFY_POINTER_ROOT = 7

    // --- grab statuses (XI2.h) ----------------------------------------------
    const val GRAB_SUCCESS = 0
    const val GRAB_ALREADY_GRABBED = 1
    const val GRAB_INVALID_TIME = 2
    const val GRAB_NOT_VIEWABLE = 3
    const val GRAB_FROZEN = 4

    /** Passive grab types (XIPassiveGrabDevice). */
    const val GRAB_TYPE_BUTTON = 0
    const val GRAB_TYPE_KEYCODE = 1
    const val GRAB_TYPE_ENTER = 2
    const val GRAB_TYPE_FOCUS_IN = 3
    const val GRAB_TYPE_TOUCH_BEGIN = 4

    /** DeviceEvent flags (XI2.h). We never set XIPointerEmulated or XIKeyRepeat. */
    const val FLAG_KEY_REPEAT = 1 shl 16

    /** Buttons we report; Android's back/forward land on 8 and 9 ([XInput.buttonFor]). */
    const val NUM_BUTTONS = 9

    /**
     * One entry of the fixed device list. `sourceId` is what device events report
     * as their source: Xorg reports the slave that produced the event even when the
     * event's device is the master it is attached to.
     */
    data class Device(
        val id: Int,
        val name: String,
        val use: Int,
        val attachment: Int,
        val sourceId: Int,
        val pointer: Boolean,
    )

    val DEVICES: List<Device> = listOf(
        Device(MASTER_POINTER, "Virtual core pointer", USE_MASTER_POINTER, MASTER_KEYBOARD, SLAVE_POINTER, pointer = true),
        Device(MASTER_KEYBOARD, "Virtual core keyboard", USE_MASTER_KEYBOARD, MASTER_POINTER, SLAVE_KEYBOARD, pointer = false),
        Device(SLAVE_POINTER, "win11we touch pointer", USE_SLAVE_POINTER, MASTER_POINTER, SLAVE_POINTER, pointer = true),
        Device(SLAVE_KEYBOARD, "win11we keyboard", USE_SLAVE_KEYBOARD, MASTER_KEYBOARD, SLAVE_KEYBOARD, pointer = false),
    )

    fun device(id: Int): Device? = DEVICES.firstOrNull { it.id == id }

    /** True for the two pseudo ids and every real device we have. */
    fun isKnownDevice(id: Int): Boolean = id == ALL_DEVICES || id == ALL_MASTER_DEVICES || device(id) != null

    fun isMaster(id: Int): Boolean = device(id)?.use == USE_MASTER_POINTER || device(id)?.use == USE_MASTER_KEYBOARD

    /**
     * Which real device an event carries for a request naming [id]: a master's
     * events come from the master, `XIAllMasterDevices` selects both masters.
     */
    fun eventDeviceFor(id: Int): Device? = device(id)

    /**
     * Does a selection made for [selectedDevice] cover an event from [eventDevice]?
     * `XIAllDevices` covers everything, `XIAllMasterDevices` only the masters.
     */
    fun selectionCovers(selectedDevice: Int, eventDevice: Int): Boolean = when (selectedDevice) {
        ALL_DEVICES -> true
        ALL_MASTER_DEVICES -> isMaster(eventDevice)
        else -> selectedDevice == eventDevice
    }

    // --- fixed-point helpers -------------------------------------------------

    /** Writes an FP1616 (XI2's coordinate format) for an integer pixel value. */
    fun XWriter.fp1616(value: Int): XWriter = int(value shl 16)

    /** Writes an FP3232 (XI2's valuator format); the fraction is scaled to 2^32. */
    fun XWriter.fp3232(value: Double): XWriter {
        val integral = value.toInt()
        val fraction = ((value - integral) * 4294967296.0).toLong()
        int(integral)
        int((fraction and 0xFFFFFFFFL).toInt())
        return this
    }

    /**
     * Builds the `XIQueryDevice` reply payload: `numDevices` plus, per device, an
     * `xXIDeviceInfo`, the padded name and its classes. Class lengths are in
     * 4-byte units and libXi walks the list by them, so a class that lies about
     * its own length makes every later class - and every later reply - garbage.
     *
     * [labels] maps an axis/button label name to its atom id (interned by the
     * caller, because atoms live in [XDocument]).
     */
    fun queryDevicePayload(
        littleEndian: Boolean,
        keyboard: XKeyboard,
        labels: (String) -> Int,
        pointerPosition: (deviceId: Int) -> Pair<Int, Int>,
        devices: List<Device> = DEVICES,
    ): ByteArray {
        val writer = XWriter(littleEndian)
        devices.forEach { device ->
            val nameBytes = device.name.toByteArray(Charsets.US_ASCII)
            val classes = classesFor(littleEndian, device, keyboard, labels, pointerPosition)
            writer.short(device.id)
            writer.short(device.use)
            writer.short(device.attachment)
            writer.short(classes.size) // num_classes
            writer.short(nameBytes.size) // name_len
            writer.byte(1) // enabled
            writer.pad(1)
            writer.bytes(nameBytes)
            writer.pad(X11.padded(nameBytes.size))
            classes.forEach { writer.bytes(it) }
        }
        return writer.toByteArray()
    }

    /** The class blobs for one device, in the order Xorg reports them. */
    private fun classesFor(
        littleEndian: Boolean,
        device: Device,
        keyboard: XKeyboard,
        labels: (String) -> Int,
        pointerPosition: (deviceId: Int) -> Pair<Int, Int>,
    ): List<ByteArray> = if (device.pointer) {
        val (px, py) = pointerPosition(device.id)
        listOf(
            buttonClass(littleEndian, device.sourceId, labels),
            valuatorClass(littleEndian, device.sourceId, 0, labels("Rel X"), px.toDouble()),
            valuatorClass(littleEndian, device.sourceId, 1, labels("Rel Y"), py.toDouble()),
        )
    } else {
        listOf(keyClass(littleEndian, device.sourceId, keyboard.minKeycode, keyboard.maxKeycode))
    }

    /**
     * `xXIButtonInfo` + the button state mask (padded to 4-byte chunks) + one Atom
     * per button naming it. GDK ignores this class; libXi still walks it, so the
     * length has to be right.
     */
    fun buttonClass(littleEndian: Boolean, sourceId: Int, labels: (String) -> Int): ByteArray {
        val maskBytes = (NUM_BUTTONS + 7) / 8
        val writer = XWriter(littleEndian)
        writer.short(CLASS_BUTTON)
        writer.short(0) // length, patched below
        writer.short(sourceId)
        writer.short(NUM_BUTTONS)
        writer.bytes(ByteArray(maskBytes)) // no button is logically held down here
        writer.pad(X11.padded(maskBytes))
        for (button in 1..NUM_BUTTONS) writer.int(labels("Button$button"))
        return patchLength(writer, littleEndian)
    }

    /** `xXIKeyInfo` + one CARD32 keycode per key: the range [XKeyboard] serves. */
    fun keyClass(littleEndian: Boolean, sourceId: Int, minKeycode: Int, maxKeycode: Int): ByteArray {
        val count = maxKeycode - minKeycode + 1
        val writer = XWriter(littleEndian)
        writer.short(CLASS_KEY)
        writer.short(0)
        writer.short(sourceId)
        writer.short(count)
        for (keycode in minKeycode..maxKeycode) writer.int(keycode)
        return patchLength(writer, littleEndian)
    }

    /**
     * `xXIValuatorInfo`: 44 bytes (11 units). The labels are the *relative* ones
     * ("Rel X"/"Rel Y") because that is what our pointer is: GDK maps only the
     * "Abs *" labels onto axes and ignores the rest, and pointer position travels
     * in the events themselves (root_x/event_x), so an ignored axis loses nothing.
     */
    fun valuatorClass(littleEndian: Boolean, sourceId: Int, number: Int, labelAtom: Int, value: Double): ByteArray {
        val writer = XWriter(littleEndian)
        writer.short(CLASS_VALUATOR)
        writer.short(0)
        writer.short(sourceId)
        writer.short(number)
        writer.int(labelAtom)
        writer.fp3232(0.0) // min: unused for a relative axis
        writer.fp3232(0.0) // max
        writer.fp3232(value)
        writer.int(0) // resolution
        writer.byte(MODE_RELATIVE)
        writer.pad(3)
        return patchLength(writer, littleEndian)
    }

    /** Writes the class's own length field (4-byte units) now that its size is known. */
    private fun patchLength(writer: XWriter, littleEndian: Boolean): ByteArray {
        val bytes = writer.toByteArray()
        val units = bytes.size / 4
        if (littleEndian) {
            bytes[2] = (units and 0xFF).toByte()
            bytes[3] = ((units shr 8) and 0xFF).toByte()
        } else {
            bytes[2] = ((units shr 8) and 0xFF).toByte()
            bytes[3] = (units and 0xFF).toByte()
        }
        return bytes
    }

    /** A CARD32 mask as XI2 writes it: bit `i` of byte `i/8`, least significant first. */
    fun maskIsSet(mask: ByteArray, bit: Int): Boolean {
        val byte = bit ushr 3
        if (byte < 0 || byte >= mask.size) return false
        return mask[byte].toInt() and (1 shl (bit and 7)) != 0
    }

    /** Builds an XI2 event mask (little-endian, `maskLen` 4-byte units) from event types. */
    fun buildMask(maskLenUnits: Int, evtypes: List<Int>): ByteArray {
        val mask = ByteArray(maskLenUnits * 4)
        evtypes.forEach { evtype ->
            val byte = evtype ushr 3
            if (byte < mask.size) mask[byte] = (mask[byte].toInt() or (1 shl (evtype and 7))).toByte()
        }
        return mask
    }

    /** The smallest mask length (in 4-byte units) that can hold [evtype]. */
    fun maskLenFor(evtype: Int): Int = (evtype / 8 / 4) + 1

    /**
     * Splits an XI2 modifier state the way `xXIModifierInfo` expects: base = the
     * modifiers physically down, locked = the latches (CapsLock), effective = both.
     * The core X mask we track mixes them, so this is where they are separated.
     */
    fun modifierParts(coreMask: Int): Triple<Int, Int, Int> {
        val locked = coreMask and X11.MASK_LOCK
        val base = coreMask and locked.inv()
        return Triple(base, locked, coreMask)
    }

    /**
     * Converts the core X button-state mask (Button1Motion at bit 8, as
     * [XInput] maintains it) into XI2's device button mask, where button *n* is
     * bit *n-1*. XI2's mask is 32 bits wide, so buttons 8/9 (Android's
     * back/forward) survive the conversion.
     */
    fun xi2ButtonMask(coreButtonMask: Int): Int {
        var out = 0
        for (button in 1..16) {
            if (coreButtonMask and (1 shl (7 + button)) != 0) out = out or (1 shl (button - 1))
        }
        return out
    }

    /** Sanity guard used by tests: every valuator/button class length is in whole units. */
    internal fun checkClassLengths(classes: List<ByteArray>): Boolean =
        classes.all { it.size % 4 == 0 && it.size / 4 == ((it[2].toInt() and 0xFF) or ((it[3].toInt() and 0xFF) shl 8)) }

    /** Absolute difference helper for coordinate clamping in tests. */
    internal fun delta(a: Int, b: Int): Int = abs(a - b)
}

/**
 * One connection's XI2 state: the negotiated version, the per-window event
 * selections (`XISelectEvents`) and the client pointer. Selections are
 * per-connection by definition - that is the whole point of XI2 over the core
 * protocol's window masks - so events go only to the client that asked for them.
 */
class XInput2Selections {

    /** One `xXIEventMask`: a device (or pseudo-device) plus its mask bytes. */
    data class Mask(val deviceId: Int, val mask: ByteArray) {
        fun wants(evtype: Int): Boolean = XInput2.maskIsSet(mask, evtype)

        override fun equals(other: Any?): Boolean =
            other is Mask && other.deviceId == deviceId && other.mask.contentEquals(mask)

        override fun hashCode(): Int = deviceId * 31 + mask.contentHashCode()
    }

    private val byWindow = HashMap<Int, MutableList<Mask>>()

    /** Replaces the selection for one device on one window (XISelectEvents semantics). */
    fun select(windowId: Int, deviceId: Int, mask: ByteArray) {
        val masks = byWindow.getOrPut(windowId) { mutableListOf() }
        masks.removeAll { it.deviceId == deviceId }
        if (mask.any { it.toInt() != 0 }) masks.add(Mask(deviceId, mask))
        if (masks.isEmpty()) byWindow.remove(windowId)
    }

    fun forget(windowId: Int) {
        byWindow.remove(windowId)
    }

    fun isEmpty(): Boolean = byWindow.isEmpty()

    /** The masks to echo back for `XIGetSelectedEvents`. */
    fun masksFor(windowId: Int): List<Mask> = byWindow[windowId]?.toList() ?: emptyList()

    /**
     * Does this connection want [evtype] from [deviceId] on [windowId]?
     * `XIAllDevices` and `XIAllMasterDevices` selections are honoured.
     */
    fun wants(windowId: Int, deviceId: Int, evtype: Int): Boolean =
        byWindow[windowId]?.any { mask ->
            XInput2.selectionCovers(mask.deviceId, deviceId) && mask.wants(evtype)
        } == true

    /** True when any window of this connection selected [evtype] at all. */
    fun wantsAnywhere(deviceId: Int, evtype: Int): Boolean =
        byWindow.values.any { masks -> masks.any { XInput2.selectionCovers(it.deviceId, deviceId) && it.wants(evtype) } }
}
