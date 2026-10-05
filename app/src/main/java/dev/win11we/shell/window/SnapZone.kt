package dev.win11we.shell.window

/** Drop targets offered while a title bar is dragged. */
enum class SnapZone { NONE, LEFT, RIGHT, MAXIMIZE }

fun SnapZone.toMode(): WindowMode = when (this) {
    SnapZone.NONE -> WindowMode.NORMAL
    SnapZone.LEFT -> WindowMode.SNAP_LEFT
    SnapZone.RIGHT -> WindowMode.SNAP_RIGHT
    SnapZone.MAXIMIZE -> WindowMode.MAXIMIZED
}
