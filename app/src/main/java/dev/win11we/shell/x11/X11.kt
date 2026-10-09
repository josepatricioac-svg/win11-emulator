package dev.win11we.shell.x11

/**
 * X11 wire primitives. Everything here is byte-order aware because the client
 * declares its order in the first byte of the connection setup ('B' or 'l').
 *
 * Pure Kotlin, no Android types: the whole protocol core is unit-tested.
 */
object X11 {
    const val PROTOCOL_MAJOR = 11
    const val PROTOCOL_MINOR = 0

    /** Release number reported in the setup reply: X11R6.4 clients expect 11.x here. */
    const val RELEASE_NUMBER = 11 shl 16

    /** Vendor string reported to clients. Ours, not a real X.org one. */
    const val VENDOR = "Win11WE X server"

    const val RESOURCE_ID_BASE = 0x00200000
    const val RESOURCE_ID_MASK = 0x001FFFFF
    const val RESOURCE_SHIFT = 20

    // --- core request opcodes (X11 protocol numbers) -------------------------
    // Numbers verified against xorgproto 2024.1 include/X11/Xproto.h (the X_*
    // defines). 1-52 are contiguous except for the two ListFontsWithInfo slots;
    // the protocol inserts the drawable/GC requests (CreatePixmap 53 ..
    // SetClipRectangles 59) before FreeGC at 60, then the drawing block 61-77,
    // colormaps/colours 78-92, cursors 93-97, extensions 98-99, keyboard /
    // pointer / screensaver / host control 100-119, NoOperation 127.
    // Opcodes 128-255 belong to extensions (QueryExtension hands out the
    // major opcode; see XConnection's extension registry).
    const val REQ_CREATE_WINDOW = 1
    const val REQ_CHANGE_WINDOW_ATTRIBUTES = 2
    const val REQ_GET_WINDOW_ATTRIBUTES = 3
    const val REQ_DESTROY_WINDOW = 4
    const val REQ_DESTROY_SUBWINDOWS = 5
    const val REQ_CHANGE_SAVE_SET = 6
    const val REQ_REPARENT_WINDOW = 7
    const val REQ_MAP_WINDOW = 8
    const val REQ_MAP_SUBWINDOWS = 9
    const val REQ_UNMAP_WINDOW = 10
    const val REQ_UNMAP_SUBWINDOWS = 11
    const val REQ_CONFIGURE_WINDOW = 12
    const val REQ_CIRCULATE_WINDOW = 13
    const val REQ_GET_GEOMETRY = 14
    const val REQ_QUERY_TREE = 15
    const val REQ_INTERN_ATOM = 16
    const val REQ_GET_ATOM_NAME = 17
    const val REQ_CHANGE_PROPERTY = 18
    const val REQ_DELETE_PROPERTY = 19
    const val REQ_GET_PROPERTY = 20
    const val REQ_LIST_PROPERTIES = 21
    const val REQ_SET_SELECTION_OWNER = 22
    const val REQ_GET_SELECTION_OWNER = 23
    const val REQ_CONVERT_SELECTION = 24
    const val REQ_SEND_EVENT = 25
    const val REQ_GRAB_POINTER = 26
    const val REQ_UNGRAB_POINTER = 27
    const val REQ_GRAB_BUTTON = 28
    const val REQ_UNGRAB_BUTTON = 29
    const val REQ_CHANGE_ACTIVE_POINTER_GRAB = 30
    const val REQ_GRAB_KEYBOARD = 31
    const val REQ_UNGRAB_KEYBOARD = 32
    const val REQ_GRAB_KEY = 33
    const val REQ_UNGRAB_KEY = 34
    const val REQ_ALLOW_EVENTS = 35
    const val REQ_GRAB_SERVER = 36
    const val REQ_UNGRAB_SERVER = 37
    const val REQ_QUERY_POINTER = 38
    const val REQ_GET_MOTION_EVENTS = 39
    const val REQ_TRANSLATE_COORDINATES = 40
    const val REQ_WARP_POINTER = 41
    const val REQ_SET_INPUT_FOCUS = 42
    const val REQ_GET_INPUT_FOCUS = 43
    const val REQ_QUERY_KEYMAP = 44
    const val REQ_OPEN_FONT = 45
    const val REQ_CLOSE_FONT = 46
    const val REQ_QUERY_FONT = 47
    const val REQ_QUERY_TEXT_EXTENTS = 48
    const val REQ_LIST_FONTS = 49
    const val REQ_LIST_FONTS_WITH_INFO = 50
    const val REQ_SET_FONT_PATH = 51
    const val REQ_GET_FONT_PATH = 52
    const val REQ_CREATE_PIXMAP = 53
    const val REQ_FREE_PIXMAP = 54
    const val REQ_CREATE_GC = 55
    const val REQ_CHANGE_GC = 56
    const val REQ_COPY_GC = 57
    const val REQ_SET_DASHES = 58
    const val REQ_SET_CLIP_RECTANGLES = 59
    const val REQ_FREE_GC = 60
    const val REQ_CLEAR_AREA = 61
    const val REQ_COPY_AREA = 62
    const val REQ_COPY_PLANE = 63
    const val REQ_POLY_POINT = 64
    const val REQ_POLY_LINE = 65
    const val REQ_POLY_SEGMENT = 66
    const val REQ_POLY_RECTANGLE = 67
    const val REQ_POLY_ARC = 68
    const val REQ_FILL_POLY = 69
    const val REQ_POLY_FILL_RECTANGLE = 70
    const val REQ_POLY_FILL_ARC = 71
    const val REQ_PUT_IMAGE = 72
    const val REQ_GET_IMAGE = 73
    const val REQ_POLY_TEXT8 = 74
    const val REQ_POLY_TEXT16 = 75
    const val REQ_IMAGE_TEXT16 = 77
    const val REQ_IMAGE_TEXT8 = 76
    const val REQ_CREATE_COLORMAP = 78
    const val REQ_FREE_COLORMAP = 79
    const val REQ_COPY_COLORMAP_AND_FREE = 80
    const val REQ_INSTALL_COLORMAP = 81
    const val REQ_UNINSTALL_COLORMAP = 82
    const val REQ_LIST_INSTALLED_COLORMAPS = 83
    const val REQ_ALLOC_COLOR = 84
    const val REQ_ALLOC_NAMED_COLOR = 85
    const val REQ_ALLOC_COLOR_CELLS = 86
    const val REQ_ALLOC_COLOR_PLANES = 87
    const val REQ_FREE_COLORS = 88
    const val REQ_STORE_COLORS = 89
    const val REQ_STORE_NAMED_COLOR = 90
    const val REQ_QUERY_COLORS = 91
    const val REQ_LOOKUP_COLOR = 92
    const val REQ_CREATE_CURSOR = 93
    const val REQ_CREATE_GLYPH_CURSOR = 94
    const val REQ_FREE_CURSOR = 95
    const val REQ_RECOLOR_CURSOR = 96
    const val REQ_QUERY_BEST_SIZE = 97
    const val REQ_QUERY_EXTENSION = 98
    const val REQ_LIST_EXTENSIONS = 99
    const val REQ_CHANGE_KEYBOARD_MAPPING = 100
    const val REQ_GET_KEYBOARD_MAPPING = 101
    const val REQ_CHANGE_KEYBOARD_CONTROL = 102
    const val REQ_GET_KEYBOARD_CONTROL = 103
    const val REQ_BELL = 104
    const val REQ_CHANGE_POINTER_CONTROL = 105
    const val REQ_GET_POINTER_CONTROL = 106
    const val REQ_SET_SCREEN_SAVER = 107
    const val REQ_GET_SCREEN_SAVER = 108
    const val REQ_CHANGE_HOSTS = 109
    const val REQ_LIST_HOSTS = 110
    const val REQ_SET_ACCESS_CONTROL = 111
    const val REQ_SET_CLOSE_DOWN_MODE = 112
    const val REQ_KILL_CLIENT = 113
    const val REQ_ROTATE_PROPERTIES = 114
    const val REQ_FORCE_SCREEN_SAVER = 115
    const val REQ_SET_POINTER_MAPPING = 116
    const val REQ_GET_POINTER_MAPPING = 117
    const val REQ_SET_MODIFIER_MAPPING = 118
    const val REQ_GET_MODIFIER_MAPPING = 119
    const val REQ_NO_OPERATION = 127

    /**
     * The major opcode this server hands out for the XTest extension. Extension
     * opcodes live in 128-255 and are assigned dynamically by the server; real
     * X.org servers tend to give XTest something in the 130s. Clients learn the
     * number from QueryExtension, so any free value >= 128 works.
     */
    const val EXT_XTEST_OPCODE = 130

    // --- events ------------------------------------------------------------
    const val EV_KEY_PRESS = 2
    const val EV_KEY_RELEASE = 3
    const val EV_BUTTON_PRESS = 4
    const val EV_BUTTON_RELEASE = 5
    const val EV_MOTION_NOTIFY = 6
    const val EV_ENTER_NOTIFY = 7
    const val EV_LEAVE_NOTIFY = 8
    const val EV_FOCUS_IN = 9
    const val EV_FOCUS_OUT = 10
    const val EV_EXPOSE = 12
    const val EV_GRAPHICS_EXPOSURE = 13
    const val EV_MAP_REQUEST = 20
    const val EV_CONFIGURE_REQUEST = 23
    const val EV_CREATE_NOTIFY = 16
    const val EV_DESTROY_NOTIFY = 17
    const val EV_UNMAP_NOTIFY = 18
    const val EV_MAP_NOTIFY = 19
    const val EV_REPARENT_NOTIFY = 21
    const val EV_CONFIGURE_NOTIFY = 22
    const val EV_PROPERTY_NOTIFY = 28
    const val EV_SELECTION_NOTIFY = 31
    const val EV_CLIENT_MESSAGE = 33

    // --- modifier/state masks ---------------------------------------------
    const val MASK_SHIFT = 1 shl 0
    const val MASK_LOCK = 1 shl 1
    const val MASK_CONTROL = 1 shl 2
    const val MASK_MOD1 = 1 shl 3 // Alt
    const val MASK_MOD2 = 1 shl 4
    const val MASK_MOD4 = 1 shl 6 // Super
    const val MASK_BUTTON1 = 1 shl 8
    const val MASK_BUTTON2 = 1 shl 9
    const val MASK_BUTTON3 = 1 shl 10

    // --- error codes (X11/X.h) ---------------------------------------------
    const val ERR_SUCCESS = 0
    const val ERR_BAD_REQUEST = 1
    const val ERR_BAD_VALUE = 2
    const val ERR_BAD_WINDOW = 3
    const val ERR_BAD_PIXMAP = 4
    const val ERR_BAD_ATOM = 5
    const val ERR_BAD_CURSOR = 6
    const val ERR_BAD_FONT = 7
    const val ERR_BAD_MATCH = 8
    const val ERR_BAD_DRAWABLE = 9
    const val ERR_ACCESS = 10
    const val ERR_BAD_ALLOC = 11
    const val ERR_BAD_COLORMAP = 12 // BadColor
    const val ERR_BAD_GC = 13
    const val ERR_BAD_ID_CHOICE = 14
    const val ERR_BAD_NAME = 15 // font or colour name does not exist
    const val ERR_BAD_LENGTH = 16
    const val ERR_BAD_IMPLEMENTATION = 17
    const val ERR_NOT_IMPLEMENTED = 127 // not an X error: our own marker

    /** Rounds a byte length up to the 4-byte boundary X requires. */
    fun pad4(length: Int): Int = (length + 3) and 0x7FFFFFFC

    fun padded(length: Int): Int = pad4(length) - length
}

/** Little/big-endian aware reader over a byte array. */
class XReader(private val data: ByteArray, private val littleEndian: Boolean, var position: Int = 0) {
    val remaining: Int get() = data.size - position

    /** Reads past the end are protocol violations, not crashes. */
    private fun need(count: Int) {
        if (position + count > data.size) throw IndexOutOfBoundsException("short request: need $count at $position of ${data.size}")
    }

    fun byte(): Int {
        need(1)
        return data[position++].toInt() and 0xFF
    }

    fun skip(count: Int) {
        position += count
    }

    fun short(): Int {
        need(2)
        val a = data[position++].toInt() and 0xFF
        val b = data[position++].toInt() and 0xFF
        return if (littleEndian) a or (b shl 8) else (a shl 8) or b
    }

    /** An INT16 field, sign-extended (coordinates and angles are signed in X). */
    fun shortSigned(): Int = short().toShort().toInt()

    fun int(): Int {
        need(4)
        val a = data[position++].toLong() and 0xFF
        val b = data[position++].toLong() and 0xFF
        val c = data[position++].toLong() and 0xFF
        val d = data[position++].toLong() and 0xFF
        val value = if (littleEndian) a or (b shl 8) or (c shl 16) or (d shl 24) else (a shl 24) or (b shl 16) or (c shl 8) or d
        return value.toInt()
    }

    fun bytes(count: Int): ByteArray {
        val out = data.copyOfRange(position, (position + count).coerceAtMost(data.size))
        position += count
        return out
    }

    /** X strings are length-prefixed and padded to 4 bytes; the length excludes padding. */
    fun paddedString(length: Int): String {
        val text = String(bytes(length), Charsets.US_ASCII)
        skip(X11.padded(length))
        return text
    }

    fun intList(count: Int): List<Int> = List(count) { int() }
}

/** Little/big-endian aware writer that grows as needed. */
class XWriter(private val littleEndian: Boolean) {
    private var buffer = ByteArray(256)
    var size: Int = 0
        private set

    private fun ensure(extra: Int) {
        if (size + extra > buffer.size) {
            buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }
    }

    fun byte(value: Int): XWriter {
        ensure(1)
        buffer[size++] = (value and 0xFF).toByte()
        return this
    }

    fun short(value: Int): XWriter {
        ensure(2)
        if (littleEndian) {
            buffer[size++] = (value and 0xFF).toByte()
            buffer[size++] = ((value shr 8) and 0xFF).toByte()
        } else {
            buffer[size++] = ((value shr 8) and 0xFF).toByte()
            buffer[size++] = (value and 0xFF).toByte()
        }
        return this
    }

    fun int(value: Int): XWriter {
        ensure(4)
        if (littleEndian) {
            buffer[size++] = (value and 0xFF).toByte()
            buffer[size++] = ((value shr 8) and 0xFF).toByte()
            buffer[size++] = ((value shr 16) and 0xFF).toByte()
            buffer[size++] = ((value shr 24) and 0xFF).toByte()
        } else {
            buffer[size++] = ((value shr 24) and 0xFF).toByte()
            buffer[size++] = ((value shr 16) and 0xFF).toByte()
            buffer[size++] = ((value shr 8) and 0xFF).toByte()
            buffer[size++] = (value and 0xFF).toByte()
        }
        return this
    }

    fun bytes(value: ByteArray): XWriter {
        ensure(value.size)
        value.copyInto(buffer, size)
        size += value.size
        return this
    }

    fun pad(count: Int): XWriter {
        repeat(count) { byte(0) }
        return this
    }

    /** Writes the string plus the padding X requires; [lengthField] is the real length. */
    fun paddedString(value: String): XWriter {
        val raw = value.toByteArray(Charsets.US_ASCII)
        bytes(raw)
        pad(X11.padded(raw.size))
        return this
    }

    fun toByteArray(): ByteArray = buffer.copyOf(size)
}
