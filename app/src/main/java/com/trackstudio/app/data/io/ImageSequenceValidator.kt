package com.trackstudio.app.data.io

/**
 * Pure string/number logic for recognizing "this is one image sequence",
 * e.g. shot_0001.png, shot_0002.png ... shot_1200.png. No Android
 * dependencies on purpose - trivial to unit test, and it's cheap enough to
 * run on the main thread on the (small) list of display names, unlike the
 * actual per-frame image decode which never should be.
 */
object ImageSequenceValidator {

    private val TRAILING_DIGITS = Regex("""^(.*?)(\d+)(\.[A-Za-z0-9]+)$""")

    data class ParsedName(
        val original: String,
        val prefix: String,
        val numberString: String,
        val extension: String,
        val number: Long
    )

    /**
     * Orders [names] by the trailing numeric run in each filename (natural
     * sort), not lexicographically - "frame_2.png" must sort before
     * "frame_10.png". Names that don't match the pattern are pushed to the
     * end, stably, in their original order.
     */
    fun sortNaturally(names: List<String>): List<String> {
        val (matched, unmatched) = names.partition { TRAILING_DIGITS.matches(it) }
        val sorted = matched
            .map { parse(it)!! }
            .sortedBy { it.number }
            .map { it.original }
        return sorted + unmatched
    }

    private fun parse(name: String): ParsedName? {
        val match = TRAILING_DIGITS.matchEntire(name) ?: return null
        val (prefix, digits, ext) = match.destructured
        return ParsedName(
            original = name,
            prefix = prefix,
            numberString = digits,
            extension = ext,
            number = digits.toLongOrNull() ?: return null
        )
    }

    /**
     * Given the (already naturally-sorted) file names of an imported batch,
     * decide whether it looks like one coherent sequence and return a
     * human-readable pattern description, e.g. "shot_####.png (1-240)".
     * Returns null if the batch doesn't look like a sequence at all (e.g.
     * completely unrelated file names), which the caller should surface as
     * a warning rather than a hard failure - editors sometimes do want to
     * import a loose batch of stills.
     */
    fun describeSequence(sortedNames: List<String>): String? {
        if (sortedNames.isEmpty()) return null
        val parsed = sortedNames.mapNotNull { parse(it) }
        if (parsed.size != sortedNames.size) {
            // Mixed matched/unmatched names - not a clean numbered sequence.
            return null
        }

        val commonPrefix = parsed.first().prefix
        val commonExt = parsed.first().extension
        val samePrefixAndExt = parsed.all { it.prefix == commonPrefix && it.extension == commonExt }
        if (!samePrefixAndExt) return null

        val digitWidth = parsed.first().numberString.length
        val hashes = "#".repeat(digitWidth)
        val first = parsed.first().number
        val last = parsed.last().number

        return "$commonPrefix$hashes$commonExt ($first-$last)"
    }
}
