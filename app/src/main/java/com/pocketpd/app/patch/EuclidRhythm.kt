package com.pocketpd.app.patch

/**
 * Pattern math for the app-only [euclid] widget: k hits distributed as
 * evenly as possible over n steps (Bjorklund's algorithm, Toussaint 2005),
 * then rotated around the circle. Validated against the worked example and
 * rhythm table on Wikipedia's "Euclidean rhythm" page (tresillo E(3,8),
 * cinquillo E(5,8), bossa E(5,16), Ewe E(7,12) all reproduce exactly).
 */
object EuclidRhythm {
    const val MAX_STEPS = 64

    /** Effective (clamped) parameter triple, for stable state comparison. */
    fun clamp(hits: Int, steps: Int, rotation: Int): Triple<Int, Int, Int> {
        val st = steps.coerceIn(1, MAX_STEPS)
        return Triple(hits.coerceIn(0, st), st, ((rotation % st) + st) % st)
    }

    /**
     * Pattern of [hitsIn] hits over [stepsIn] steps. The base form places a
     * hit at step 0 (Bjorklund's canonical readout), then the whole circle
     * is shifted clockwise by [rotationIn] steps:
     * pattern[i] = base[(i - rotation) mod n]. Excess hits saturate.
     */
    fun pattern(hitsIn: Int, stepsIn: Int, rotationIn: Int): IntArray {
        val (k, n, rot) = clamp(hitsIn, stepsIn, rotationIn)
        val base = bjorklund(k, n)
        val pat = IntArray(n)
        for (i in 0 until n) pat[i] = base[((i - rot) % n + n) % n]
        return pat
    }

    /**
     * Bjorklund via group merging: k one-groups face (n-k) zero-groups;
     * every round pairs the two classes one-onto-one (big's bits first,
     * then the small's) and the unpaired remainder of the larger class
     * becomes the new trailing class. Stops when at most one trailing
     * group is left; the concatenation is the canonical pattern.
     */
    private fun bjorklund(kIn: Int, n: Int): IntArray {
        if (kIn <= 0) return IntArray(n)
        if (kIn >= n) return IntArray(n) { 1 }
        var bigs = ArrayList<List<Int>>(kIn)
        var smalls = ArrayList<List<Int>>(n - kIn)
        repeat(kIn) { bigs.add(listOf(1)) }
        repeat(n - kIn) { smalls.add(listOf(0)) }
        while (smalls.size >= 2) {
            val pairs = minOf(bigs.size, smalls.size)
            val merged = ArrayList<List<Int>>(pairs)
            for (i in 0 until pairs) merged.add(bigs[i] + smalls[i])
            val leftover = ArrayList<List<Int>>(
                if (bigs.size >= smalls.size) bigs.subList(pairs, bigs.size)
                else smalls.subList(pairs, smalls.size)
            )
            bigs = merged
            smalls = leftover
        }
        val out = IntArray(n)
        var w = 0
        for (g in bigs) for (b in g) out[w++] = b
        for (g in smalls) for (b in g) out[w++] = b
        return out
    }
}
