package com.abhinavxt.novelforge.data.tts

import android.content.Context
import com.abhinavxt.novelforge.data.codex.NameExtractor
import com.abhinavxt.novelforge.data.database.CodexDao
import com.abhinavxt.novelforge.data.database.TranslationDao
import com.google.gson.Gson

/** One speaker of a multi-speaker voice model. [female] is null when the model doesn't say. */
data class Speaker(val id: Int, val label: String, val female: Boolean?, val accent: Char? = null) {
    fun toCastVoice() = CastVoice(id.toString(), label, female, accent)
}

/** A voice a character can be given: [id] is a Sherpa speaker number, a Piper model folder or a system voice name. */
data class CastVoice(val id: String, val label: String, val female: Boolean? = null, val accent: Char? = null)

/** Which kind of voice a cast hands out; decides how the engine switches to it. */
enum class CastKind { SHERPA_SPEAKER, PIPER_MODEL, GOOGLE_VOICE }

/**
 * The voices the active engine can give characters, and where the book's cast for them is stored.
 * [narrator] is the voice the narrator already reads in; [narratorPickable] is true only when it is
 * one of the voices (a multi-speaker model), false when it is simply the voice the reader chose.
 */
data class CastVoices(
    val kind: CastKind,
    val key: String,
    val voices: List<CastVoice>,
    val narrator: String,
    val narratorPickable: Boolean
) {
    /** Characters can only be cast with someone else to give them. */
    val usable: Boolean get() = voices.size >= MIN_VOICES

    companion object {
        const val MIN_VOICES = 2
    }
}

/**
 * Who reads what in one book, for one voice model (speaker numbers mean different voices in each).
 * Narration gets [narrator]; a quote gets its character's voice, else [otherDialogue], else the narrator's.
 *
 * Voices are strings so one shape serves every engine. A cast saved by older versions stored speaker
 * numbers (`"narrator":2`), which read back as "2" and mean the same thing.
 */
data class VoiceCast(
    val enabled: Boolean = false,
    val narrator: String = "0",
    val otherDialogue: String? = null,
    val characters: Map<String, String> = emptyMap()
) {
    fun voiceFor(piece: Dialogue.Piece): String =
        if (!enabled || !piece.quoted) narrator
        else piece.speaker?.let { characters[it] } ?: otherDialogue ?: narrator

    /**
     * This cast for an engine whose narrator is [narratorId] and whose voices are [available]:
     * a voice that is no longer there (an uninstalled system voice, a deleted model) reads as the narrator.
     */
    fun restrictedTo(available: Set<String>, narratorId: String): VoiceCast = copy(
        narrator = narratorId,
        otherDialogue = otherDialogue?.takeIf { it in available },
        characters = characters.filterValues { it in available }
    )
}

object Speakers {

    /** Sherpa-ONNX's speaker tables for the Kokoro models (k2-fsa.github.io/sherpa/onnx/tts). */
    private val KOKORO: Map<String, List<String>> = mapOf(
        "kokoro-en-v0_19" to "af af_bella af_nicole af_sarah af_sky am_adam am_michael bf_emma bf_isabella bm_george bm_lewis",
        "kokoro-multi-lang-v1_0" to "af_alloy af_aoede af_bella af_heart af_jessica af_kore af_nicole af_nova af_river af_sarah " +
            "af_sky am_adam am_echo am_eric am_fenrir am_liam am_michael am_onyx am_puck am_santa bf_alice bf_emma bf_isabella " +
            "bf_lily bm_daniel bm_fable bm_george bm_lewis ef_dora em_alex ff_siwis hf_alpha hf_beta hm_omega hm_psi if_sara " +
            "im_nicola jf_alpha jf_gongitsune jf_nezumi jf_tebukuro jm_kumo pf_dora pm_alex pm_santa zf_xiaobei zf_xiaoni " +
            "zf_xiaoxiao zf_xiaoyi zm_yunjian zm_yunxi zm_yunxia zm_yunyang",
        "kokoro-multi-lang-v1_1" to ("af_maple af_sol bf_vale " +
            "001 002 003 004 005 006 007 008 017 018 019 021 022 023 024 026 027 028 032 036 038 039 040 042 043 044 046 047 048 " +
            "049 051 059 060 067 070 071 072 073 074 075 076 077 078 079 083 084 085 086 087 088 090 092 093 094 099"
                .split(" ").joinToString(" ") { "zf_$it" } + " " +
            "009 010 011 012 013 014 015 016 020 025 029 030 031 033 034 035 037 041 045 050 052 053 054 055 056 057 058 061 062 " +
            "063 064 065 066 068 069 080 081 082 089 091 095 096 097 098 100"
                .split(" ").joinToString(" ") { "zm_$it" })
    ).mapValues { it.value.split(" ") }

    private const val LIBRITTS_PICKABLE = 100

    private val ACCENTS = mapOf(
        'a' to "American", 'b' to "British", 'e' to "Spanish", 'f' to "French", 'h' to "Hindi",
        'i' to "Italian", 'j' to "Japanese", 'p' to "Portuguese", 'z' to "Chinese"
    )

    /**
     * What a picker shows for the voice [current] of [voices]: its label, "Speaker N" for a speaker number
     * beyond the listed ones, and "Narrator's voice" when nothing is chosen or it can't be named.
     */
    fun pickLabel(voices: List<CastVoice>, current: String?): String {
        if (current == null) return NARRATOR_LABEL
        voices.firstOrNull { it.id == current }?.let { return it.label }
        return current.toIntOrNull()?.takeIf { it >= 0 }?.let { "Speaker ${it + 1}" } ?: NARRATOR_LABEL
    }

    const val NARRATOR_LABEL = "Narrator's voice"

    /** The speaker [id] of [modelId] when it is outside the (capped) list [forModel] returns. */
    fun labelFor(modelId: String?, id: Int): Speaker =
        forModel(modelId, id + 1).getOrNull(id) ?: Speaker(id, "Speaker ${id + 1}", null)

    /** [count] speakers of [modelId]; named when the model is one we know, numbered otherwise. */
    fun forModel(modelId: String?, count: Int): List<Speaker> {
        // LibriTTS-R has ~900 anonymous speakers: number them, and offer a manageable first set.
        if (modelId?.contains("libritts", ignoreCase = true) == true) {
            return (0 until minOf(count, LIBRITTS_PICKABLE)).map { Speaker(it, "Speaker ${it + 1}", null) }
        }
        val known = modelId?.let { id -> KOKORO.entries.firstOrNull { id.startsWith(it.key) }?.value }
        return (0 until count).map { i ->
            val code = known?.getOrNull(i) ?: return@map Speaker(i, "Voice ${i + 1}", null)
            val accent = code[0]
            val female = code[1] == 'f'
            val name = code.substringAfter('_').replaceFirstChar { it.uppercase() }
            val label = "$name · ${ACCENTS[accent] ?: accent} ${if (female) "female" else "male"}"
            Speaker(i, label, female, accent)
        }
    }

    /**
     * Gives each character a voice: one of their gender when it's known, in the narrator's accent when
     * the model has accents, and as few repeats as the model allows. The narrator's voice is kept for narration.
     */
    fun autoCast(characters: List<String>, genders: Map<String, Boolean>, speakers: List<Speaker>, narrator: Int): Map<String, Int> =
        autoCastVoices(characters, genders, speakers.map { it.toCastVoice() }, narrator.toString())
            .mapValues { it.value.toInt() }

    /** [autoCast] for any kind of voice; an empty map when nobody but the narrator is available. */
    fun autoCastVoices(characters: List<String>, genders: Map<String, Boolean>, voices: List<CastVoice>, narrator: String): Map<String, String> {
        val accent = voices.firstOrNull { it.id == narrator }?.accent
        val pool = voices.filter { it.id != narrator && (accent == null || it.accent == accent) }
            .ifEmpty { voices.filter { it.id != narrator } }
            .ifEmpty { return emptyMap() }
        val next = mutableMapOf<Boolean?, Int>()
        return characters.associateWith { name ->
            val female = genders[name]
            val choices = pool.filter { female == null || it.female == null || it.female == female }.ifEmpty { pool }
            val i = next.getOrDefault(female, 0)
            next[female] = i + 1
            choices[i % choices.size].id
        }
    }

    /** "female, she/her" → true, "male, he/him" → false, anything else → null. */
    fun genderFromNote(note: String): Boolean? {
        val words = note.lowercase().split(Regex("[^a-z]+")).toSet()
        val female = words.any { it in setOf("female", "woman", "girl", "she", "her") }
        val male = words.any { it in setOf("male", "man", "boy", "he", "him", "his") }
        return if (female != male) female else null
    }
}

/** Stores each book's cast, per voice model, and finds the book's characters. Not part of backups. */
class VoiceCasting(context: Context, private val codexDao: CodexDao, private val translationDao: TranslationDao) {

    private val prefs = context.getSharedPreferences("character_voices", Context.MODE_PRIVATE)

    companion object {
        private val gson = Gson()

        /** A stored cast, empty when there is none or it can't be read. */
        fun parse(json: String?): VoiceCast =
            json?.let { runCatching { gson.fromJson(it, VoiceCast::class.java) }.getOrNull() } ?: VoiceCast()

        fun toJson(cast: VoiceCast): String = gson.toJson(cast)

        fun googleKey(language: String) = "google_tts:$language"
        fun piperKey(modelId: String) = "piper:$modelId"
    }

    /**
     * [key] names what the cast is for: a multi-speaker model's id (as it always was), `piper:<model>`
     * for casting other downloaded Piper voices, `google_tts:<language>` for system voices.
     */
    fun load(novelId: String, key: String): VoiceCast = parse(prefs.getString("$novelId|$key", null))

    fun save(novelId: String, key: String, cast: VoiceCast) {
        prefs.edit().putString("$novelId|$key", toJson(cast)).apply()
    }

    /** The book's people from the codex, most mentioned first. */
    suspend fun characters(novelId: String, limit: Int = 40): List<String> =
        codexDao.getNamesOnce(novelId)
            .filter { NameExtractor.classify(it.name, it.speechHits) == NameExtractor.CodexType.PERSON }
            .sortedByDescending { it.occurrences }
            .take(limit)
            .map { it.name }

    /** Genders the translation glossary knows, by the name as it's read. */
    suspend fun genders(novelId: String): Map<String, Boolean> =
        translationDao.getGlossary(novelId).mapNotNull { term ->
            Speakers.genderFromNote(term.note)?.let { term.target to it }
        }.toMap()
}
