package dev.win11we.shell.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Minimal faithful ELF headers for test fixtures - the first 20 bytes carry
 * everything [ElfHeader.machine] reads (magic, endianness, e_machine). Payload
 * fixtures elsewhere (ContainerLauncherTest, SelfTestRunnerTest) build their
 * fake Wine loaders with this so the arch-driven box64 rule is tested against
 * the same bytes the real debs ship.
 */
internal object TestElf {
    fun header(machine: Int, littleEndian: Boolean = true): ByteArray {
        val bytes = ByteArray(64)
        bytes[0] = 0x7F
        bytes[1] = 'E'.code.toByte()
        bytes[2] = 'L'.code.toByte()
        bytes[3] = 'F'.code.toByte()
        bytes[4] = 2 // ELFCLASS64
        bytes[5] = if (littleEndian) 1 else 2
        bytes[6] = 1 // EV_CURRENT
        val lo = (machine and 0xFF).toByte()
        val hi = ((machine shr 8) and 0xFF).toByte()
        if (littleEndian) {
            bytes[18] = lo
            bytes[19] = hi
        } else {
            bytes[18] = hi
            bytes[19] = lo
        }
        return bytes
    }
}

class ElfHeaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `reads e_machine from a little-endian aarch64 ELF`() {
        val file = File(tmp.root, "native").apply { writeBytes(TestElf.header(ElfHeader.EM_AARCH64)) }
        assertEquals(183, ElfHeader.machine(file))
    }

    @Test
    fun `reads e_machine from a little-endian x86_64 ELF`() {
        val file = File(tmp.root, "translated").apply { writeBytes(TestElf.header(ElfHeader.EM_X86_64)) }
        assertEquals(62, ElfHeader.machine(file))
    }

    @Test
    fun `reads e_machine from a big-endian ELF`() {
        val file = File(tmp.root, "be").apply {
            writeBytes(TestElf.header(ElfHeader.EM_AARCH64, littleEndian = false))
        }
        assertEquals(183, ElfHeader.machine(file))
    }

    @Test
    fun `a non-ELF file answers null - a shell wrapper is not an aarch64 loader`() {
        val script = File(tmp.root, "wineserver").apply { writeText("#!/bin/sh\nexec wineserver64 \"$@\"\n") }
        assertNull(ElfHeader.machine(script))
    }

    @Test
    fun `a truncated header answers null instead of throwing`() {
        val short = File(tmp.root, "short").apply { writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte())) }
        assertNull(ElfHeader.machine(short))
    }

    @Test
    fun `a missing or unreadable file answers null instead of throwing`() {
        assertNull(ElfHeader.machine(File(tmp.root, "does-not-exist")))
        val dir = File(tmp.root, "a-directory").apply { mkdirs() }
        assertNull(ElfHeader.machine(dir))
    }
}
