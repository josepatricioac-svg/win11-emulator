package dev.win11we.shell.ui.desktop

/**
 * The bloom wallpaper as data, so the live Compose wallpaper and the pre-rendered
 * mica/acrylic backdrops are the *same* artwork by construction. Original colours
 * and geometry - no Microsoft asset is sampled or shipped.
 *
 * Packed ARGB ints because the backdrop renderer draws through android.graphics
 * shaders; the Compose wallpaper converts to [androidx.compose.ui.graphics.Color].
 */
class BloomSpec private constructor(
    val background: IntArray,
    val glow: IntArray,
    val core: IntArray,
    val petals: List<BloomPetal>,
) {
    companion object {
        /** Bloom centre and radius, as fractions of the surface. */
        const val CENTER_X = 0.62f
        const val CENTER_Y = 0.46f
        const val RADIUS = 0.62f
        const val CORE_FRACTION = 0.16f
        const val PETAL_RADIUS = 0.46f

        private val TRANSPARENT = 0x00000000

        val Dark = BloomSpec(
            background = intArrayOf(0xFF0A1630.toInt(), 0xFF050B1C.toInt(), 0xFF02040C.toInt()),
            glow = intArrayOf(0x551E4C8A, TRANSPARENT),
            core = intArrayOf(0xB3CFEBFF.toInt(), TRANSPARENT),
            petals = listOf(
                BloomPetal(0x8C4CC2FF.toInt(), -35f, 0.52f),
                BloomPetal(0x737A5CFF, 55f, 0.58f),
                BloomPetal(0x592ED0C8, 150f, 0.50f),
                BloomPetal(0x594A78E8, 235f, 0.55f),
            ),
        )

        /** Light theme: the same bloom, pale - a light field with soft blue petals. */
        val Light = BloomSpec(
            background = intArrayOf(0xFFEDF3FC.toInt(), 0xFFE2EAF7.toInt(), 0xFFD5E0F2.toInt()),
            glow = intArrayOf(0x4D6FA8E8, TRANSPARENT),
            core = intArrayOf(0xB3FFFFFF.toInt(), TRANSPARENT),
            petals = listOf(
                BloomPetal(0x664CC2FF.toInt(), -35f, 0.52f),
                BloomPetal(0x597A5CFF, 55f, 0.58f),
                BloomPetal(0x402ED0C8, 150f, 0.50f),
                BloomPetal(0x404A78E8, 235f, 0.55f),
            ),
        )

        fun forDark(dark: Boolean): BloomSpec = if (dark) Dark else Light
    }
}

data class BloomPetal(val tint: Int, val degrees: Float, val spread: Float)
