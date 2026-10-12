package dev.win11we.shell.x11

/**
 * The two pieces of transport every other extension sits on:
 *
 * - **BIG-REQUESTS**: a normal request header carries its length as a CARD16 in
 *   4-byte units, so 64 KiB is the ceiling. Cairo/GTK blow through that with a
 *   single `PutImage` of a window-sized ARGB buffer or a long
 *   `SetClipRectangles`, and libX11 then re-sends the request in the extended
 *   form: the CARD16 length field is **0** and the real length (in the same
 *   4-byte units) is a CARD32 at offset 4, with the body starting at offset 8
 *   (`xBigReq` in xorgproto's bigreqsproto.h). A server that does not accept
 *   that form desynchronises the stream - the client thinks it sent one request,
 *   the server parses the CARD32 length as a request body - and GTK's error
 *   handler `_exit(1)`s with nothing on stderr.
 *
 * - **Generic Event Extension (XGE)**: core events are exactly 32 bytes, which
 *   is not enough for XInput2's device events (they carry valuator masks,
 *   modifier/group state and a 32-bit event type). XGE is the agreed framing:
 *   byte 0 is 35 (`GenericEvent`), byte 1 the major opcode of the extension
 *   that owns the event, bytes 4-7 the length in 4-byte units *after* the first
 *   32 bytes, and bytes 8-9 the extension's own event type. libX11 reads that
 *   header itself (`XGetEventData`), so a client that has not enabled anything
 *   still receives XI2 events correctly - which is why the extension is
 *   advertised but its `QueryVersion` is the only request it has.
 *
 * Layouts from xorgproto 2024.1: `bigreqsproto.h`, `geproto.h`, and the
 * `xGenericEvent` struct in `Xproto.h`.
 */
object XBigRequests {
    /** Extension name clients query (`XQueryExtension("BIG-REQUESTS")`). */
    const val NAME = "BIG-REQUESTS"

    const val MAJOR_OPCODE = 132

    /** `X_BigReqEnable` - the extension's only request. */
    const val MINOR_ENABLE = 0

    /**
     * What the enable reply advertises as the largest request, in 4-byte units.
     * Xorg answers 4194303 (16 MiB); we answer 4 MiB, which is far above any
     * request cairo or GTK produces for a window-sized buffer and keeps one
     * connection's reassembly buffer bounded on a phone.
     */
    const val MAX_REQUEST_UNITS = 1024 * 1024

    /** A CARD16 length of 0 means "the real length is the CARD32 at offset 4". */
    const val BIG_REQUEST_MARKER = 0

    /** Bytes of a big request's header before its body (xBigReq is 8 bytes). */
    const val HEADER_BYTES = 8
}

object XGenericEvent {
    /** Extension name clients query (`XQueryExtension("Generic Event Extension")`). */
    const val NAME = "Generic Event Extension"

    const val MAJOR_OPCODE = 128

    /** `X_GEQueryVersion` - the extension's only request. */
    const val MINOR_QUERY_VERSION = 1

    const val VERSION_MAJOR = 1
    const val VERSION_MINOR = 0

    /** The event type byte every generic event carries (`GenericEvent`). */
    const val EVENT_TYPE = 35

    /** Fixed part of a generic event; `length` counts 4-byte units beyond this. */
    const val HEADER_BYTES = 32
}
