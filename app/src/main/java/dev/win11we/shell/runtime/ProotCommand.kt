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

    /**
     * Bare `--rootfs` binds NOTHING else (verified against termux/proot's
     * cli/proot.c: only -R adds the recommended set), and the extracted
     * ubuntu-base has an empty /dev and no /proc - guests die instantly without
     * these. proot-distro binds exactly the same three.
     */
    val SYSTEM_BINDS = listOf("/dev", "/proc", "/sys")

    /*
     * ARGUMENT FORM MATTERS - two rules, both read out of the vendored fork's own
     * sources (termux/proot v5.1.107.96: src/cli/cli.c parse_config() and the
     * generated src/cli/proot.h option table) and both confirmed by a real device
     * failure:
     *
     * 1. A long option whose value is a separate argument is refused: `--rootfs /path`
     *    dies with "option '--rootfs' and its value must be separated by '='". In the
     *    option table, `-r`/`-b`/`-m`/`-w`/`-q`/`-v`/`-k`/`-i`/`-R`/`-S` are declared
     *    with `separator = ' '` (detached value OK) while `--rootfs`/`--bind`/`--mount`
     *    /`--cwd`/`--pwd`/`--qemu`/`--verbose` are `separator = '='`. So this builder
     *    and RootfsProvisioner use -r / -b / -w. Never emit a long option as a detached
     *    pair; RuntimeTest pins it.
     *
     * 2. There is NO `--` end-of-options marker: the table has no such entry, so `--`
     *    reaches `note(ERROR, USER, "unknown option '%s'.")` and proot aborts (this is
     *    what "Firefox setup" died on: `proot error: unknown option "--"`). Parsing
     *    stops at the first argument that does not begin with '-', so the guest command
     *    simply follows the last option - and must therefore never begin with '-'.
     *    [build] refuses such a command instead of producing a line proot cannot parse.
     */
    fun build(
        prootBinary: File,
        rootfsDir: File,
        prefixDir: File,
        binds: List<Bind> = emptyList(),
        guestCommand: List<String>,
        link2symlink: Boolean = true,
        killOnExit: Boolean = true,
        extraArgs: List<String> = emptyList(),
        /** Bind target of [prefixDir]; Wine's fixed `/root/.wine`, or a session's own state dir. */
        guestPrefix: String = GUEST_PREFIX,
        /** Host paths bound onto themselves inside the guest (kernel interfaces). */
        systemBinds: List<String> = SYSTEM_BINDS,
    ): List<String> {
        require(guestCommand.isNotEmpty()) { "a guest command is required" }
        // No `--` exists to terminate proot's option list, so a guest command that
        // begins with '-' would be parsed as an (unknown) option. Callers must pass
        // an absolute path or a bare program name; wrap anything else in a shell.
        require(!guestCommand.first().startsWith("-")) {
            "the guest command must not begin with '-' (this proot has no '--' separator): " +
                guestCommand.first()
        }
        val command = mutableListOf(prootBinary.absolutePath)
        command += "-r"
        command += rootfsDir.absolutePath
        if (link2symlink) command += "--link2symlink"
        if (killOnExit) command += "--kill-on-exit"
        command += "-b"
        command += "${prefixDir.absolutePath}:$guestPrefix"
        systemBinds.forEach { path ->
            command += "-b"
            command += path
        }
        binds.forEach { bind ->
            command += "-b"
            command += bind.argument
        }
        command += extraArgs
        command += "-w"
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
