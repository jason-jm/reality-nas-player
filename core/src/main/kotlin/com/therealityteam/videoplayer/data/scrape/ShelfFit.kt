package com.therealityteam.videoplayer.data.scrape

/** Whether a database hit fits the shelf a file was filed on, judged by the hit's genres. */
object ShelfFit {
    private val DOCUMENTARY = Regex("""(?i)documentary|纪录|紀錄|记录|ドキュメンタリー""")

    /**
     * A documentary shelf takes a hit whose genres say documentary — or say nothing at all, since a
     * small production often carries no genres. A drama, however well its name agrees, is not it.
     */
    fun documentary(genres: List<String>): Boolean =
        genres.isEmpty() || genres.any { DOCUMENTARY.containsMatchIn(it) }

    private val MUSIC = Regex("""(?i)music|音乐|音樂|ミュージック|음악""")

    /**
     * A music shelf takes a hit whose genres say music or documentary — or nothing at all, since a
     * recorded concert on TMDB usually carries no genres. A shelf's category folders are names the
     * databases answer with anything: on a real library `Music/Audio/` went to the comedy Audio
     * Tour (83 files), `Music/Lesson/` to a 1994 romance (62), `Music/Keyboard/` to a thriller (35),
     * and a personal `Music/ACG/Me/` folder to Me Before You.
     */
    fun music(genres: List<String>): Boolean =
        genres.isEmpty() || genres.any { MUSIC.containsMatchIn(it) || DOCUMENTARY.containsMatchIn(it) }

    /**
     * Whether a hit is named by the clip's own name, rather than merely containing it. A music
     * shelf's files are clips, and a database answers a clip's phrase with whatever holds it —
     * `jamming` with Jamming on an Old Saya, `Chapter 1` with That Chapter, `Luna Sea` with one of
     * the band's other concerts. A recording or a lesson is filed under what its file says, in one of
     * two ways: the hit's name is mostly the file's (Joe Pass: Solo Jazz Guitar), or the file's name
     * is in the hit's, whole and three words strong (Casino Lights '99 - Live At The Montreux Jazz
     * Festival). Either way the two must share a word that names something: `LIVE DOCUMENT`, a disc
     * of Macross F's Budokan concert, is not Arashi's Live & Document. One Latin word names a kind
     * of clip or a brand (Guitar, Others, Roland), never a work, and a lone letter is no word (S.E.N.S.
     * is not P.E.N.S.).
     */
    fun namesTheClip(asked: String, names: List<String>): Boolean {
        val words = Matcher.contentWords(asked).filter { it.length >= 2 || it.all(Char::isDigit) }.toSet()
        if (words.size <= 1 && !hasCjk(asked)) return false
        val joined = words.joinToString("")
        fun has(word: String) = word in words || (hasCjk(word) && joined.contains(word))
        return names.any { name ->
            val theirs = Matcher.contentWords(name)
            val shared = theirs.filter(::has)
            if (shared.all { it in CLIP_WORDS }) return@any false
            val mine = words.count { it in theirs }
            shared.size * 3 >= theirs.size * 2 || (mine >= 3 && mine * 3 >= words.size * 2)
        }
    }

    /** Words every concert, disc and clip says, and so none names one. */
    private val CLIP_WORDS = setOf(
        "live", "concert", "concerts", "tour", "dvd", "disc", "disk", "cd", "bd", "bluray", "music", "video",
        "videos", "mv", "pv", "clip", "document", "documentary", "special", "edition", "best", "collection",
        "complete", "part", "vol", "show", "performance", "recording", "bonus", "making", "encore", "digest",
        "official", "bootleg", "demo", "lesson", "lessons", "tutorial", "opening", "ending", "version", "full",
    )

    private fun hasCjk(s: String) = s.any { it in '\u3040'..'\u30FF' || it in '\u4E00'..'\u9FFF' || it in '\uAC00'..'\uD7AF' }

    private val NEVER_DOCUMENTARY = Regex("""(?i)animation|动画|動畫|アニメ|talk|脱口秀|談話|真人秀|reality""")

    /**
     * What a documentary shelf never holds, however well a name agrees: an animation, a talk show, a
     * reality show. NHK's 明治 had become the anime 明治撃剣－1874－ and CCTV's 华尔街 a US talk show;
     * the films about real events filed there (总统班底, 摇尾狗, 国家要案) are dramas, and stay.
     */
    fun neverOnDocumentaryShelf(genres: List<String>): Boolean = genres.any { NEVER_DOCUMENTARY.containsMatchIn(it) }
}
