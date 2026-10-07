package dev.win11we.shell.runtime

import java.io.File

/** A `-b host:guest` bind mount for proot. */
data class Bind(val hostPath: String, val guestPath: String) {
    val argument: String get() = "$hostPath:$guestPath"
}

/**
 * Builds the exact `proot` command line that R2's ContainerService execs.
 *
 * The prefix bind mount is **fixed** (the guest always sees the prefix at the same
 * place, `/root/.wine`) so Wine's own absolute paths inside `user.reg`/`system.reg`
 * stay valid no matter where the prefix lives on the device. Extra drives are bound
 * on top, and the rootfs is bound read-only at `/`.
 */
object ProotCommand {

    const val GUEST_PREFIX = "/root/.wine"
    const val GUEST_HOME = "/root"

    fun build(
        prootBinary: File,
        rootfsDir: File,
        prefixDir: File,
        binds: List<Bind> = emptyList(),
        guestCommand: List<String>,
        link2symlink: Boolean = true,
        killOnExit: Boolean = true,
        extraArgs: List<String> = emptyList(),
    ): List<String> {
        require(guestCommand.isNotEmpty()) { "a guest command is required" }
        val command = mutableListOf(prootBinary.absolutePath)
        command += "--rootfs"
        command += rootfsDir.absolutePath
        if (link2symlink) command += "--link2symlink"
        if (killOnExit) command += "--kill-on-exit"
        command += "--bind"
        command += "${prefixDir.absolutePath}:$GUEST_PREFIX"
        binds.forEach { bind ->
            command += "--bind"
            command += bind.argument
        }
        command += extraArgs
        command += "--cwd"
        command += GUEST_HOME
        command += guestCommand
        return command
    }

    /** The three checks R4's self-test runs, in order, with the commands it will show. */
    fun selfTestPlan(
        prootBinary: File,
        box64Binary: File,
        rootfsDir: File,
        prefixDir: File,
    ): List<List<String>> = listOf(
        RuntimeProbe.box64Command(box64Binary),
        build(prootBinary, rootfsDir, prefixDir, guestCommand = listOf("wine64", "--version")),
        build(
            prootBinary,
            rootfsDir,
            prefixDir,
            guestCommand = listOf("wine64", "cmd", "/c", "echo", "hello"),
        ),
    )
}
