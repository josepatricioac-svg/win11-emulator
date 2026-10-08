package dev.win11we.shell.container

import dev.win11we.shell.settings.Box64Preset
import dev.win11we.shell.ui.containers.ContainerManagerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ContainerCodecTest {

    private val container = Container(
        id = "office",
        name = "Office",
        prefixPath = "/data/user/0/dev.win11we.shell/files/containers/office/prefix",
        preset = Box64Preset.OFFICE,
        drives = listOf(DriveMapping('D', "/storage/emulated/0/Documents")),
        extraEnv = mapOf("BOX64_LOG" to "1", "WINEESYNC" to "1"),
        dpi = 120,
        arch = WineArch.WOW64,
    )

    @Test
    fun `a container survives an encode-decode round trip`() {
        val decoded = ContainerCodec.decode(ContainerCodec.encode(container))
        assertEquals(container.id, decoded?.id)
        assertEquals(container.name, decoded?.name)
        assertEquals(container.prefixPath, decoded?.prefixPath)
        assertEquals(Box64Preset.OFFICE, decoded?.preset)
        assertEquals(120, decoded?.dpi)
        assertEquals(WineArch.WOW64, decoded?.arch)
        assertEquals(listOf(DriveMapping('D', "/storage/emulated/0/Documents")), decoded?.drives)
        assertEquals(mapOf("BOX64_LOG" to "1", "WINEESYNC" to "1"), decoded?.extraEnv)
    }

    @Test
    fun `C and Z are implicit and are not stored twice`() {
        val text = ContainerCodec.encode(container)
        assertFalse(text.contains("drive.C="))
        assertFalse(text.contains("drive.Z="))
        val decoded = ContainerCodec.decode(text)!!
        assertEquals(listOf('C', 'Z', 'D'), decoded.allDrives.map { it.letter })
        assertEquals(decoded.prefixPath, decoded.allDrives.first { it.letter == 'C' }.hostPath)
        assertEquals("/", decoded.allDrives.first { it.letter == 'Z' }.hostPath)
    }

    @Test
    fun `values may contain equals signs and blank lines are ignored`() {
        val decoded = ContainerCodec.decode(
            """
            id=x
            name=Tricky

            prefix=/tmp/prefix
            env.FOO=a=b=c
            # a comment
            """.trimIndent(),
        )
        assertEquals("x", decoded?.id)
        assertEquals("Tricky", decoded?.name)
        assertEquals(mapOf("FOO" to "a=b=c"), decoded?.extraEnv)
    }

    @Test
    fun `garbage and unknown enum values do not crash`() {
        assertNull(ContainerCodec.decode("nothing here"))
        assertNull(ContainerCodec.decode("id=\nname=\nprefix="))
        val decoded = ContainerCodec.decode("id=a\nname=A\nprefix=/p\npreset=DOOM\narch=NOPE\ndpi=abc\n")
        assertNotNull(decoded)
        assertEquals(Box64Preset.GENERAL, decoded?.preset)
        assertEquals(WineArch.WOW64, decoded?.arch)
        assertEquals(96, decoded?.dpi)
    }
}

class ContainerStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = ContainerStore(tmp.root)

    @Test
    fun `create writes the record and the container directory`() {
        val store = store()
        val created = store.create("Office")
        assertNotNull(created)
        assertEquals("office", created?.id)
        assertTrue(File(tmp.root, "office").isDirectory)
        assertTrue(File(tmp.root, "containers.list").isFile)
        assertEquals(listOf("office"), store.containers.map { it.id })
        assertTrue(store.isDefault("office")) // the first container becomes the default
    }

    @Test
    fun `the list survives a reload, including the default choice`() {
        val store = store()
        store.create("Office")
        store.create("Games")
        store.setDefault("games")

        val reloaded = ContainerStore(tmp.root).load()
        assertEquals(listOf("games", "office"), reloaded.map { it.id })
        assertEquals("games", ContainerStore(tmp.root).also { it.load() }.defaultId)
    }

    @Test
    fun `empty and duplicate names are rejected`() {
        val store = store()
        assertNotNull(store.create("Office"))
        assertNull(store.create("Office"))
        assertNull(store.create("office")) // case-insensitive duplicate
        assertNull(store.create("   "))
        assertEquals(1, store.containers.size)
    }

    @Test
    fun `ids are slugged and never collide`() {
        val store = store()
        assertEquals("my-office-2019", store.create("My Office 2019")?.id)
        assertEquals("my-office-2019-2", store.create("My Office 2019!")?.id)
    }

    @Test
    fun `rename update and delete keep the list and the disk in sync`() {
        val store = store()
        val created = store.create("Office")!!
        assertEquals("Work", store.rename(created.id, "Work")?.name)
        assertEquals("Work", store.byId(created.id)?.name)

        val updated = store.update(created.id) { it.copy(dpi = 144, preset = Box64Preset.DOTNET) }
        assertEquals(144, updated?.dpi)
        assertEquals(Box64Preset.DOTNET, ContainerStore(tmp.root).load().first().preset)

        assertTrue(File(tmp.root, created.id).exists())
        assertTrue(store.delete(created.id))
        assertFalse(File(tmp.root, created.id).exists())
        assertTrue(store.containers.isEmpty())
        assertNull(store.defaultId)
    }

    @Test
    fun `deleting the default promotes the next container`() {
        val store = store()
        store.create("Office")
        store.create("Games")
        assertTrue(store.delete("office"))
        assertEquals("games", store.defaultId)
    }

    @Test
    fun `drive mappings can be added replaced and removed`() {
        val store = store()
        val created = store.create("Office")!!
        store.update(created.id) { it.copy(drives = listOf(DriveMapping('D', "/sdcard/Docs"))) }
        var current = store.byId(created.id)!!
        assertEquals(listOf('C', 'Z', 'D'), current.allDrives.map { it.letter })

        // adding the same letter replaces the mapping instead of duplicating it
        store.update(created.id) {
            it.copy(drives = it.drives.filterNot { drive -> drive.letter == 'D' } + DriveMapping('D', "/sdcard/New"))
        }
        current = store.byId(created.id)!!
        assertEquals("/sdcard/New", current.allDrives.first { it.letter == 'D' }.hostPath)

        store.update(created.id) { it.copy(drives = it.drives.filterNot { drive -> drive.letter == 'D' }) }
        assertEquals(listOf('C', 'Z'), store.byId(created.id)!!.allDrives.map { it.letter })
    }

    @Test
    fun `prefix size reports zero for an empty prefix instead of failing`() {
        val store = store()
        val created = store.create("Office")!!
        assertEquals(0L, store.prefixSizeKb(created.id))
        assertEquals(0L, store.prefixSizeKb("does-not-exist"))
        File(tmp.root, "${created.id}/prefix/drive_c").mkdirs()
        File(tmp.root, "${created.id}/prefix/drive_c/file.bin").writeBytes(ByteArray(4096))
        assertTrue(store.prefixSizeKb(created.id) >= 4L)
    }
}

class GuestPathsTest {

    private val container = Container(
        id = "office",
        name = "Office",
        prefixPath = "/data/containers/office/prefix",
        drives = listOf(
            DriveMapping('D', "/sdcard/Documents"),
            DriveMapping('E', "/sdcard/Documents/Work"),
        ),
    )

    @Test
    fun `the prefix maps to C and the guest root to Z`() {
        assertEquals("C:\\", GuestPaths.toGuest("/data/containers/office/prefix", container))
        assertEquals(
            "C:\\drive_c\\Program Files",
            GuestPaths.toGuest("/data/containers/office/prefix/drive_c/Program Files", container),
        )
        assertEquals("Z:\\usr\\bin", GuestPaths.toGuest("/usr/bin", container))
    }

    @Test
    fun `the longest matching mapping wins`() {
        assertEquals("D:\\report.docx", GuestPaths.toGuest("/sdcard/Documents/report.docx", container))
        assertEquals("E:\\plan.xlsx", GuestPaths.toGuest("/sdcard/Documents/Work/plan.xlsx", container))
    }

    @Test
    fun `guest paths map back to the host`() {
        assertEquals("/sdcard/Documents/report.docx", GuestPaths.toHost("D:\\report.docx", container))
        assertEquals("/sdcard/Documents", GuestPaths.toHost("d:", container))
        assertEquals("/data/containers/office/prefix", GuestPaths.toHost("C:\\", container))
        assertNull(GuestPaths.toHost("Q:\\missing", container))
        assertNull(GuestPaths.toHost("not-a-windows-path", container))
    }

    @Test
    fun `a drive letter is normalised and validated`() {
        assertEquals("D:\\", DriveMapping('d', "/x").guestRoot)
        var threw = false
        try {
            DriveMapping('4', "/x")
        } catch (error: IllegalArgumentException) {
            threw = true
        }
        assertTrue(threw)
    }
}

class WineEnvironmentTest {

    private val container = Container(
        id = "office",
        name = "Office",
        prefixPath = "/data/containers/office/prefix",
        preset = Box64Preset.GENERAL,
    )

    private fun build(preset: Box64Preset = Box64Preset.GENERAL, extra: Map<String, String> = emptyMap()) =
        WineEnvironment.build(
            container = container.copy(preset = preset, extraEnv = extra),
            xDisplay = ":100",
            prootRootfs = "/data/rootfs",
            nativeLibraryDir = "/data/app/lib/arm64",
            audioSocket = "/data/tmp/pulse/native",
        )

    @Test
    fun `the environment is fully explicit - nothing is left to inheritance`() {
        val env = build()
        listOf(
            "DISPLAY", "WINEPREFIX", "WINEARCH", "WINEDEBUG", "WINEDLLOVERRIDES", "HOME", "USER", "PATH",
            "LD_LIBRARY_PATH", "LANG", "LC_ALL", "TMPDIR", "XDG_RUNTIME_DIR", "PULSE_SERVER",
            "PROOT_ROOTFS", "PROOT_LOADER", "PROOT_LOADER_32",
        ).forEach { key -> assertTrue("$key must be set explicitly", env.containsKey(key)) }
        assertEquals(":100", env["DISPLAY"])
        assertEquals("/root/.wine", env["WINEPREFIX"])
        assertEquals("/data/rootfs", env["PROOT_ROOTFS"])
        assertEquals("/data/app/lib/arm64/libproot-loader.so", env["PROOT_LOADER"])
        assertTrue(env["PATH"]!!.contains("/opt/wine/bin"))
        // Never let Wine download Gecko/Mono behind the user's back.
        assertEquals("mscoree,mshtml=", env["WINEDLLOVERRIDES"])
    }

    @Test
    fun `the caller's overrides win`() {
        val env = build(extra = mapOf("DISPLAY" to ":5", "WINEDEBUG" to "-all", "BOX64_LOG" to "1"))
        assertEquals(":5", env["DISPLAY"])
        assertEquals("-all", env["WINEDEBUG"])
        assertEquals("1", env["BOX64_LOG"])
    }

    @Test
    fun `each preset contributes its own box64 tuning`() {
        assertEquals(emptyList<String>(), WineEnvironment.presetVariables(Box64Preset.GENERAL).keys.toList())
        assertEquals("2", WineEnvironment.presetVariables(Box64Preset.OFFICE)["BOX64_DYNAREC_BIGBLOCK"])
        assertEquals("3", WineEnvironment.presetVariables(Box64Preset.DOTNET)["BOX64_DYNAREC_BIGBLOCK"])
        assertTrue(
            WineEnvironment.presetVariables(Box64Preset.ELECTRON)
                .getValue("BOX64_ENV").contains("CHROME_DEVEL_SANDBOX"),
        )
        assertTrue(build(preset = Box64Preset.OFFICE).containsKey("BOX64_DYNAREC_X87DOUBLE"))
    }

    @Test
    fun `the output is deterministic and sorted for diffing in diagnostics`() {
        val first = build(preset = Box64Preset.OFFICE, extra = mapOf("A" to "1"))
        val second = build(preset = Box64Preset.OFFICE, extra = mapOf("A" to "1"))
        assertEquals(first, second)
        assertEquals(first.keys.toList(), first.keys.sorted())
    }

    @Test
    fun `audio is optional`() {
        val env = WineEnvironment.build(container, ":100", "/rootfs", "/lib")
        assertFalse(env.containsKey("PULSE_SERVER"))
    }

    @Test
    fun `the wine command line uses the arch loader`() {
        assertEquals(
            listOf("wine64", "cmd", "/c", "echo hello"),
            WineEnvironment.wineCommand(container, "cmd", "/c", "echo hello"),
        )
        assertEquals("wine64", WineArch.WOW64.wineLoader)
    }
}

class NotInstalledRuntimeTest {

    @Test
    fun `every action explains which step is missing instead of failing silently`() {
        val runtime = NotInstalledRuntime()
        val container = Container.defaults("office", "Office", "/tmp/prefix")
        assertFalse(runtime.ready)
        assertEquals(ContainerStatus.STOPPED, runtime.statusOf(container))
        listOf(runtime.start(container), runtime.stop(container), runtime.restart(container)).forEach { result ->
            assertFalse(result.success)
            assertTrue(result.message.contains("R1"))
            assertTrue(result.message.contains("R2"))
        }
        assertEquals("(no runtime, no logs yet)", runtime.logExcerpt(container))
    }
}

/** The Container Manager must not start and stop the foreground service on every click. */
class ContainerServiceLifecycleTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Behaves like the real runtime: start makes it run, stop makes it stop. */
    private class FakeRuntime : ContainerRuntime {
        var status: ContainerStatus = ContainerStatus.STOPPED
        override val ready = true
        override fun start(container: Container): ContainerActionResult {
            status = ContainerStatus.RUNNING
            return ContainerActionResult.ok("started")
        }

        override fun stop(container: Container): ContainerActionResult {
            status = ContainerStatus.STOPPED
            return ContainerActionResult.ok("stopped")
        }

        override fun restart(container: Container): ContainerActionResult {
            status = ContainerStatus.RUNNING
            return ContainerActionResult.ok("restarted")
        }

        override fun statusOf(container: Container) = status
        override fun logExcerpt(container: Container, lines: Int) = ""
    }

    private fun state(runtime: ContainerRuntime, starts: MutableList<Int>, stops: MutableList<Int>) =
        ContainerManagerState(
            store = ContainerStore(tmp.root),
            runtime = runtime,
            onStartRuntime = { starts.add(1) },
            onStopRuntime = { stops.add(1) },
        )

    @Test
    fun `starting a container starts the service exactly once`() {
        val runtime = FakeRuntime()
        val starts = mutableListOf<Int>()
        val stops = mutableListOf<Int>()
        val manager = state(runtime, starts, stops)
        assertTrue(manager.create("Office"))
        val office = manager.containers.first()

        manager.start(office)
        assertEquals(1, starts.size)
        assertEquals(0, stops.size)
        assertEquals(ContainerStatus.RUNNING, manager.store.byId(office.id)?.status)
    }

    @Test
    fun `a failed start never touches the service`() {
        val failing = object : ContainerRuntime {
            override val ready = false
            override fun start(container: Container) = ContainerActionResult.fail("no runtime")
            override fun stop(container: Container) = ContainerActionResult.fail("no runtime")
            override fun restart(container: Container) = ContainerActionResult.fail("no runtime")
            override fun statusOf(container: Container) = ContainerStatus.ERROR
            override fun logExcerpt(container: Container, lines: Int) = ""
        }
        val starts = mutableListOf<Int>()
        val stops = mutableListOf<Int>()
        val manager = state(failing, starts, stops)
        assertTrue(manager.create("Office"))
        val office = manager.containers.first()
        manager.start(office)
        assertEquals(0, starts.size)
        assertEquals(0, stops.size)
        assertEquals(ContainerStatus.ERROR, manager.store.byId(office.id)?.status)
        assertTrue(manager.store.byId(office.id)?.lastError?.contains("no runtime") == true)
    }

    @Test
    fun `stopping the last running container stops the service, and only then`() {
        val runtime = FakeRuntime()
        val starts = mutableListOf<Int>()
        val stops = mutableListOf<Int>()
        val manager = state(runtime, starts, stops)
        assertTrue(manager.create("Office"))
        assertTrue(manager.create("Games"))
        val first = manager.containers.first { it.name == "Office" }
        val second = manager.containers.first { it.name == "Games" }

        manager.start(first)
        manager.start(second)
        assertEquals("the service is already up", 1, starts.size)

        manager.stop(first) // the fake runtime stops everything, so the service goes down here
        assertEquals(1, stops.size)
        manager.stop(second) // nothing was running: no second stop
        assertEquals(1, stops.size)
    }

    @Test
    fun `a runtime that throws is reported instead of crashing the window`() {
        val throwing = object : ContainerRuntime {
            override val ready = true
            override fun start(container: Container): ContainerActionResult = throw IllegalStateException("service not allowed")
            override fun stop(container: Container) = ContainerActionResult.ok("")
            override fun restart(container: Container) = ContainerActionResult.ok("")
            override fun statusOf(container: Container) = ContainerStatus.ERROR
            override fun logExcerpt(container: Container, lines: Int) = ""
        }
        val manager = state(throwing, mutableListOf(), mutableListOf())
        assertTrue(manager.create("Office"))
        val office = manager.containers.first()
        manager.start(office)
        assertTrue(manager.message!!.contains("IllegalStateException"))
        assertEquals(ContainerStatus.ERROR, manager.store.byId(office.id)?.status)
    }
}
