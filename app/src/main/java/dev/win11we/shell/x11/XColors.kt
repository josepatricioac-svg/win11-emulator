package dev.win11we.shell.x11

/**
 * The named-colour table for LookupColor / AllocNamedColor / StoreNamedColor.
 * A working subset of the X11 rgb.txt database: the CSS/X11 basics plus the grey
 * spellings and the shades toolkits actually ask for. Values are the standard
 * rgb.txt ones (16-bit per channel, as the protocol requires).
 *
 * Pure Kotlin and deliberately allocation-light: lookup is case-insensitive and
 * ignores spaces/underscores, like X.
 */
object XColors {
    /** rgb.txt entries: name -> 0xRRGGBB (8-bit per channel). */
    private val TABLE: Map<String, Int> = linkedMapOf(
        "black" to 0x000000,
        "white" to 0xFFFFFF,
        "red" to 0xFF0000,
        "green" to 0x00FF00,
        "blue" to 0x0000FF,
        "yellow" to 0xFFFF00,
        "cyan" to 0x00FFFF,
        "magenta" to 0xFF00FF,
        "gray" to 0xBEBEBE,
        "grey" to 0xBEBEBE,
        "darkgray" to 0xA9A9A9,
        "darkgrey" to 0xA9A9A9,
        "dimgray" to 0x696969,
        "dimgrey" to 0x696969,
        "lightgray" to 0xD3D3D3,
        "lightgrey" to 0xD3D3D3,
        "gainsboro" to 0xDCDCDC,
        "whitesmoke" to 0xF5F5F5,
        "snow" to 0xFFFAFA,
        "navy" to 0x000080,
        "navyblue" to 0x000080,
        "royalblue" to 0x4169E1,
        "steelblue" to 0x4682B4,
        "skyblue" to 0x87CEEB,
        "slateblue" to 0x6A5ACD,
        "midnightblue" to 0x191970,
        "cornflowerblue" to 0x6495ED,
        "darkblue" to 0x00008B,
        "darkgreen" to 0x006400,
        "forestgreen" to 0x228B22,
        "seagreen" to 0x2E8B57,
        "springgreen" to 0x00FF7F,
        "limegreen" to 0x32CD32,
        "greenyellow" to 0xADFF2F,
        "olivedrab" to 0x6B8E23,
        "olive" to 0x808000,
        "darkolivegreen" to 0x556B2F,
        "teal" to 0x008080,
        "darkslategray" to 0x2F4F4F,
        "darkslategrey" to 0x2F4F4F,
        "slategray" to 0x708090,
        "slategrey" to 0x708090,
        "maroon" to 0xB03060,
        "darkred" to 0x8B0000,
        "firebrick" to 0xB22222,
        "indianred" to 0xCD5C5C,
        "brown" to 0xA52A2A,
        "sienna" to 0xA0522D,
        "chocolate" to 0xD2691E,
        "saddlebrown" to 0x8B4513,
        "tan" to 0xD2B48C,
        "wheat" to 0xF5DEB3,
        "khaki" to 0xF0E68C,
        "gold" to 0xFFD700,
        "goldenrod" to 0xDAA520,
        "orange" to 0xFFA500,
        "darkorange" to 0xFF8C00,
        "coral" to 0xFF7F50,
        "tomato" to 0xFF6347,
        "salmon" to 0xFA8072,
        "pink" to 0xFFC0CB,
        "hotpink" to 0xFF69B4,
        "purple" to 0xA020F0,
        "violet" to 0xEE82EE,
        "plum" to 0xDDA0DD,
        "orchid" to 0xDA70D6,
        "lavender" to 0xE6E6FA,
        "indigo" to 0x4B0082,
        "beige" to 0xF5F5DC,
        "ivory" to 0xFFFFF0,
        "linen" to 0xFAF0E6,
        "azure" to 0xF0FFFF,
        "mintcream" to 0xF5FFFA,
        "honeydew" to 0xF0FFF0,
        "aquamarine" to 0x7FFFD4,
        "turquoise" to 0x40E0D0,
        "silver" to 0xC0C0C0,
        "bisque" to 0xFFE4C4,
        "mistyrose" to 0xFFE4E1,
        "antiquewhite" to 0xFAEBD7,
        "papayawhip" to 0xFFEFD5,
        "lemonchiffon" to 0xFFFACD,
        "cornsilk" to 0xFFF8DC,
        "thistle" to 0xD8BFD8,
        "burlywood" to 0xDEB887,
        "peru" to 0xCD853F,
        "gray0" to 0x000000,
        "grey0" to 0x000000,
        "gray25" to 0x404040,
        "grey25" to 0x404040,
        "gray50" to 0x7F7F7F,
        "grey50" to 0x7F7F7F,
        "gray75" to 0xBFBFBF,
        "grey75" to 0xBFBFBF,
        "gray100" to 0xFFFFFF,
        "grey100" to 0xFFFFFF,
    )

    /** Normalises like X: case-insensitive, spaces and underscores ignored. */
    private fun normalise(name: String): String = buildString(name.length) {
        for (c in name) {
            if (c != ' ' && c != '_') append(c.lowercaseChar())
        }
    }

    /** @return 0xRRGGBB, or null when the name is unknown (BadName, as in X). */
    fun lookup(name: String): Int? = TABLE[normalise(name)]

    /**
     * Parses the numeric `rgb:RR/GG/BB` and legacy `#RGB`/`#RRGGBB` forms that
     * X clients send when the name is not in rgb.txt. Returns 0xRRGGBB or null.
     */
    fun parseRgbString(name: String): Int? {
        val text = name.trim()
        if (text.startsWith("rgb:")) {
            val parts = text.substring(4).split('/')
            if (parts.size != 3) return null
            val r = parts[0].toIntOrNull(16) ?: return null
            val g = parts[1].toIntOrNull(16) ?: return null
            val b = parts[2].toIntOrNull(16) ?: return null
            return (scale16(r, parts[0].length) shl 16) or
                (scale16(g, parts[1].length) shl 8) or scale16(b, parts[2].length)
        }
        if (text.startsWith("#")) {
            val hex = text.substring(1)
            return when (hex.length) {
                3 -> hex.toIntOrNull(16)?.let {
                    ((it and 0xF00) shl 12) or ((it and 0xF00) shl 8) or
                        ((it and 0x0F0) shl 8) or ((it and 0x0F0) shl 4) or
                        ((it and 0x00F) shl 4) or (it and 0x00F)
                }
                6 -> hex.toIntOrNull(16)
                12 -> { // rgb:RRRR/GGGG/BBBB without the prefix: take the high bytes
                    val v = hex.toLongOrNull(16) ?: return null
                    (((v shr 40) and 0xFF00) or ((v shr 24) and 0x00FF00) or ((v shr 8) and 0x0000FF)).toInt()
                }
                else -> null
            }
        }
        return null
    }

    /** A 1-, 2-, 3- or 4-digit hex channel widened to 8 bits. */
    private fun scale16(value: Int, digits: Int): Int = when (digits) {
        1 -> (value and 0xF) * 0x11
        2 -> value and 0xFF
        3 -> (value and 0xFFF) shr 4
        4 -> (value and 0xFFFF) shr 8
        else -> value and 0xFF
    }

    /** Name first, numeric form second; null when neither matches (BadName). */
    fun resolve(name: String): Int? = lookup(name) ?: parseRgbString(name)

    /** Converts 0xRRGGBB to the 16-bit-per-channel triple the protocol wants. */
    fun to16(rgb: Int): Triple<Int, Int, Int> {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        return Triple(r * 0x101, g * 0x101, b * 0x101)
    }
}
