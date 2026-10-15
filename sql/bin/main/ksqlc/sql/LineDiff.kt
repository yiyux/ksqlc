package ksqlc.sql

enum class DiffKind { SAME, ADDED, REMOVED, CHANGED }

/** One row of a side-by-side diff: [left] is absent for an added line, [right] for a removed one; CHANGED has both. */
data class DiffLine(val kind: DiffKind, val left: String?, val right: String?)

/** Largest middle section (lines × lines) compared exactly; beyond it the middle is shown as one removed and one added block. */
private const val MAX_CELLS = 4_000_000L

/**
 * Line-by-line difference between [a] and [b] as side-by-side rows. Common start and end lines are peeled off first;
 * what is left in the middle goes through a longest-common-subsequence table. A run of removed lines followed by a run
 * of added ones is paired up into CHANGED rows.
 * shortcut: the table is O(n·m) memory, so a huge middle falls back to "all removed, all added"; use Myers' algorithm if object definitions that big matter.
 */
fun diffLines(a: List<String>, b: List<String>): List<DiffLine> {
    var start = 0
    while (start < a.size && start < b.size && a[start] == b[start]) start++
    var endA = a.size; var endB = b.size
    while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

    val raw = ArrayList<DiffLine>()
    for (i in 0 until start) raw += DiffLine(DiffKind.SAME, a[i], b[i])
    val n = endA - start; val m = endB - start
    if (n.toLong() * m > MAX_CELLS) {
        for (i in start until endA) raw += DiffLine(DiffKind.REMOVED, a[i], null)
        for (j in start until endB) raw += DiffLine(DiffKind.ADDED, null, b[j])
    } else {
        // lcs[i][j] = length of the common subsequence of the tails a[start+i..], b[start+j..]
        val lcs = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            lcs[i][j] = if (a[start + i] == b[start + j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[start + i] == b[start + j] -> { raw += DiffLine(DiffKind.SAME, a[start + i], b[start + j]); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { raw += DiffLine(DiffKind.REMOVED, a[start + i], null); i++ }
                else -> { raw += DiffLine(DiffKind.ADDED, null, b[start + j]); j++ }
            }
        }
        while (i < n) { raw += DiffLine(DiffKind.REMOVED, a[start + i], null); i++ }
        while (j < m) { raw += DiffLine(DiffKind.ADDED, null, b[start + j]); j++ }
    }
    for (i in endA until a.size) raw += DiffLine(DiffKind.SAME, a[i], b[endB + (i - endA)])
    return pairChanges(raw)
}

private fun pairChanges(raw: List<DiffLine>): List<DiffLine> {
    val out = ArrayList<DiffLine>(raw.size)
    var i = 0
    while (i < raw.size) {
        if (raw[i].kind != DiffKind.REMOVED) { out += raw[i++]; continue }
        var r = i; while (r < raw.size && raw[r].kind == DiffKind.REMOVED) r++
        var a = r; while (a < raw.size && raw[a].kind == DiffKind.ADDED) a++
        val removed = raw.subList(i, r); val added = raw.subList(r, a)
        for (k in 0 until maxOf(removed.size, added.size)) {
            out += when {
                k < removed.size && k < added.size -> DiffLine(DiffKind.CHANGED, removed[k].left, added[k].right)
                k < removed.size -> removed[k]
                else -> added[k]
            }
        }
        i = a
    }
    return out
}
