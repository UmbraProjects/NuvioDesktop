package com.nuvio.app.features.player.audiosync

import kotlin.math.abs

/**
 * fork:live-tv-subtitles - places a subtitle on a live channel by its words, not only by when someone is talking.
 * Whisper turns what has already played into words with times ([Heard]); runs of three words heard in a row that
 * also follow each other in the subtitle each give a placing (media = scale * film + shift), and when enough of
 * them agree, that is the placing. Speech detection alone confuses singing with talking and needs minutes; three
 * matching runs of words settle it, and a subtitle for another cut of the film simply finds none.
 */
internal object LiveWordMatch {
    /** A word heard, at media time [atMs] (its middle). */
    data class Heard(val atMs: Long, val word: String)

    /** A placing found: media = [scale] * film + [shiftMs], from [votes] runs of words (the next best had [runnerUp]). */
    data class Match(val scale: Double, val shiftMs: Double, val votes: Int, val runnerUp: Int, val fromMediaMs: Long)

    /** The film's own speed, 24 shown at 25 (two ways of counting it) and 23.976 at 24. */
    val SCALES = doubleArrayOf(1.0, 23.976 / 25.0, 24.0 / 25.0, 23.976 / 24.0)

    /** Words this common say nothing about where a line is, so a run of only these is not counted. */
    private val COMMON = setOf(
        "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "at", "it", "is", "i", "you", "he", "she", "we",
        "they", "me", "my", "your", "that", "this", "what", "no", "yes", "oh", "yeah", "ok", "okay", "be", "do", "so",
        "im", "its", "dont", "not", "was", "are", "for", "with", "just", "uh", "um", "hey", "go", "come", "all", "there",
    )

    private const val RUN = 3
    /** Placings within this of each other are one. */
    private const val CLUSTER_MS = 1_500.0
    /** At least this many runs must agree, and twice as many as on any other placing. */
    const val MIN_VOTES = 3

    /** Lowercase words without punctuation; sound descriptions ([door slams], (LAUGHS)) and speaker names dropped. */
    fun words(text: String): List<String> =
        text.replace(Regex("""\[[^]]*]|\([^)]*\)|<[^>]*>|\{[^}]*}"""), " ")
            .replace(Regex("""(?m)^\s*-?\s*[A-Z][A-Z .']{1,24}:"""), " ")
            .lowercase()
            .replace("’", "'")
            .replace("'", "")
            .split(Regex("""[^\p{L}\p{N}]+"""))
            .filter { it.isNotEmpty() }

    /** The subtitle as one stream of words, each at its estimated film time (spread evenly across its line). */
    class Script(lines: List<Triple<Long, Long, String>>) {
        val atMs: LongArray
        val word: Array<String>
        private val runs = HashMap<String, MutableList<Int>>()

        init {
            val times = ArrayList<Long>()
            val words = ArrayList<String>()
            for ((start, end, text) in lines.sortedBy { it.first }) {
                val inLine = words(text)
                inLine.forEachIndexed { i, w ->
                    times += start + ((end - start) * (i + 0.5) / inLine.size).toLong()
                    words += w
                }
            }
            atMs = times.toLongArray()
            word = words.toTypedArray()
            for (i in 0..word.size - RUN) {
                val key = key(word, i) ?: continue
                runs.getOrPut(key) { ArrayList() } += i
            }
        }

        fun runsOf(key: String): List<Int> = runs[key].orEmpty()
    }

    private fun key(words: Array<String>, from: Int): String? {
        val run = (from until from + RUN).map { words[it] }
        if (run.all { it in COMMON }) return null
        return run.joinToString(" ")
    }

    /**
     * The placing the [heard] words give [script], or null when too few runs agree. A run that occurs many times in
     * the subtitle (a catchphrase) votes for each place but counts once per placing.
     */
    fun match(script: Script, heard: List<Heard>): Match? {
        if (heard.size < RUN) return null
        val sorted = heard.sortedBy { it.atMs }
        val words = sorted.map { it.word }.toTypedArray()
        // (scale index, shift, heard index)
        val votes = ArrayList<Triple<Int, Double, Int>>()
        for (h in 0..words.size - RUN) {
            // Three words heard together: not split across a gap of more than a few seconds.
            if (sorted[h + RUN - 1].atMs - sorted[h].atMs > 6_000) continue
            val key = key(words, h) ?: continue
            val places = script.runsOf(key)
            if (places.isEmpty() || places.size > 4) continue
            val media = sorted[h + 1].atMs.toDouble()
            for (place in places) {
                val film = script.atMs[place + 1].toDouble()
                SCALES.forEachIndexed { s, scale -> votes += Triple(s, media - scale * film, h) }
            }
        }
        if (votes.isEmpty()) return null
        data class Cluster(val scale: Int, val shifts: List<Double>, val heard: Set<Int>)
        val clusters = ArrayList<Cluster>()
        for (s in SCALES.indices) {
            val forScale = votes.filter { it.first == s }.sortedBy { it.second }
            var from = 0
            for (to in forScale.indices) {
                while (forScale[to].second - forScale[from].second > CLUSTER_MS) from++
                val window = forScale.subList(from, to + 1)
                clusters += Cluster(s, window.map { it.second }, window.map { it.third }.toSet())
            }
        }
        fun spread(c: Cluster) = c.shifts.max() - c.shifts.min()
        // Most runs; on a tie (the same runs fit several speeds when they were heard close together) the tightest.
        val best = clusters.maxWithOrNull(compareBy<Cluster> { it.heard.size }.thenByDescending { spread(it) })
            ?: return null
        val bestShift = best.shifts.sorted()[best.shifts.size / 2]
        // What speaks for another placing: runs that do not also fit this one. The same runs at another speed are no
        // evidence against it.
        val runnerUp = clusters.filter { other ->
            other !== best && abs(other.shifts.sorted()[other.shifts.size / 2] - bestShift) > 5_000.0
        }.maxOfOrNull { (it.heard - best.heard).size } ?: 0
        val count = best.heard.size
        if (count < MIN_VOTES || count < 2 * runnerUp) return null
        return Match(SCALES[best.scale], bestShift, count, runnerUp, sorted[best.heard.min()].atMs)
    }
}
