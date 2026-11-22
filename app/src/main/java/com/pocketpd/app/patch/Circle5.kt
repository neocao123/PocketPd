package com.pocketpd.app.patch

/**
 * Music math for the app-only [circle5] widget: the circle of fifths,
 * 12 positions clockwise (1=C, 2=G ... 12=F), three concentric rings —
 * outer major, middle the relative minor, inner the leading-tone
 * diminished. Positions are 1-based everywhere.
 *
 * Keys are SIGNED: +1..+12 = major at that position, -1..-12 = the
 * relative minor there. C major and a minor share one set of seven
 * chords but their DEGREES start at different cells — C major numbers
 * from C (I=C), a minor from a (i=am, III=C) — so the sign must ride
 * along everywhere a degree is resolved or highlighted.
 */
object Circle5 {
    const val MAX_SIZE = 7

    /** Major key names clockwise from C (positions 1..12). */
    val MAJOR = arrayOf("C", "G", "D", "A", "E", "B", "F#", "Db", "Ab", "Eb", "Bb", "F")

    /** Relative-minor names of the same positions (am is the minor of C...). */
    val MINOR = arrayOf("am", "em", "bm", "f#m", "c#m", "g#m", "d#m", "bbm", "fm", "cm", "gm", "dm")

    /** Key for a `chord`/`key` value token, SIGNED: positive 1..12 names a
     *  major key by circle position, negative -1..-12 the relative MINOR at
     *  that position ("am" / -1 = a minor, sharing C major's seven chords
     *  but numbered from a). Note names ("c", "am") carry the mode, numbers
     *  carry it in their sign. */
    private val KEYS: Map<String, Int> = buildMap {
        MAJOR.forEachIndexed { i, n -> put(n.lowercase(), i + 1) }
        MINOR.forEachIndexed { i, n -> put(n, -(i + 1)) }
        put("gb", 7); put("c#", 8); put("g#", 9); put("d#", 10); put("a#", 11)
        put("gbm", -4); put("dbm", -5); put("abm", -6); put("ebm", -7); put("a#m", -8); put("e#m", -9)
    }

    fun parseKey(tok: Any?): Int? = when (tok) {
        is String -> KEYS[tok.lowercase()] ?: tok.toFloatOrNull()?.let { numKey(it) }
        is Number -> numKey(tok.toFloat())
        else -> null
    }

    private fun numKey(v: Float): Int? {
        if (v == 0f || !v.isFinite()) return null
        val pos = ((kotlin.math.abs(v).toInt() - 1) % 12 + 12) % 12 + 1
        return if (v < 0f) -pos else pos
    }

    /** Circle position (1..12) of a signed key. */
    fun keyPos(key: Int): Int = ((kotlin.math.abs(key) - 1) % 12 + 12) % 12 + 1

    /** True when the signed key is a minor key. */
    fun isMinor(key: Int): Boolean = key < 0

    /** Pitch class of a position's major root (C = 0). */
    fun outerPc(pos: Int): Int = ((pos - 1) % 12 + 12) % 12 * 7 % 12

    /** Common-practice note name for a pitch class (the dial's mixed
     *  sharp/flat spelling). */
    fun pcName(pc: Int): String =
        PC_NAMES[((pc % 12) + 12) % 12]

    private val PC_NAMES = arrayOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")

    // diatonic stacks; size picks the first n intervals (3 = triad,
    // 4 = seventh — half-diminished on the diminished ring, like vii° in a
    // major key), higher sizes keep stacking the same quality
    private val MAJOR_IV = intArrayOf(0, 4, 7, 10, 14, 17, 20)
    private val MINOR_IV = intArrayOf(0, 3, 7, 10, 14, 17, 20)
    private val DIM_IV = intArrayOf(0, 3, 6, 10, 13, 16, 19)

    /** Chord midi notes (tonic at C4=60): ring 0 outer major, 1 middle
     *  minor, 2 inner diminished; pos 1..12; [size] notes, ascending. */
    fun chord(ring: Int, pos: Int, size: Int): FloatArray {
        val pc = outerPc(pos)
        val root = when (ring) { 1 -> (pc + 9) % 12; 2 -> (pc + 11) % 12; else -> pc }
        val iv = when (ring) { 1 -> MINOR_IV; 2 -> DIM_IV; else -> MAJOR_IV }
        val n = size.coerceIn(1, MAX_SIZE)
        return FloatArray(n) { (60 + root + iv[it]).toFloat() }
    }

    private fun wrapped(p: Int, off: Int): Int = ((p - 1 + off) % 12 + 12) % 12 + 1

    /**
     * Ring (0 major / 1 minor / 2 dim) and position of diatonic degree
     * [degree] (1..7, wrapped) of the SIGNED [key].
     *
     * Major key at position k: I outer k, ii minor k-1, iii minor k+1,
     * IV outer k-1 (a fourth up = a fifth DOWN the circle), V outer k+1,
     * vi minor k, vii° inner k.
     *
     * The relative minor at the same position shares those seven chords
     * but numbers them from its own tonic (a minor: i=am ii°=b° III=C
     * iv=dm v=em VI=F VII=G) — the major scale's degrees rotated by 5, so
     * minor degree d is major degree ((d + 5 - 1) % 7) + 1.
     */
    fun degreeCell(key: Int, degree: Int): IntArray {
        var d = ((degree - 1) % 7 + 7) % 7 + 1
        if (isMinor(key)) d = (d + 5 - 1) % 7 + 1
        val k = keyPos(key)
        return when (d) {
            1 -> intArrayOf(0, k)
            2 -> intArrayOf(1, wrapped(k, -1))
            3 -> intArrayOf(1, wrapped(k, 1))
            4 -> intArrayOf(0, wrapped(k, -1))
            5 -> intArrayOf(0, wrapped(k, 1))
            6 -> intArrayOf(1, k)
            else -> intArrayOf(2, k)
        }
    }

    /** Diatonic degree (1..7) of a cell under the signed [key], or null
     *  when the cell is outside the key's seven chords. Degree 1 is the
     *  key's own tonic — am for a minor, C for C major. */
    fun degreeOf(key: Int, ring: Int, pos: Int): Int? {
        for (d in 1..7) {
            val c = degreeCell(key, d)
            if (c[0] == ring && c[1] == pos) return d
        }
        return null
    }
}
