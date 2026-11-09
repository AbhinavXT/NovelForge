package com.abhinavxt.novelforge.data.tts

import android.content.Context
import com.abhinavxt.novelforge.data.codex.NameExtractor
import com.abhinavxt.novelforge.data.database.CodexDao
import com.abhinavxt.novelforge.data.database.TranslationDao
import com.google.gson.Gson

/** One speaker of a multi-speaker voice model. [female] is null when the model doesn't say. */
data class Speaker(val id: Int, val label: String, val female: Boolean?, val accent: Char? = null)

/**
 * Who reads what in one book, for one voice model (speaker numbers mean different voices in each).
 * Narration gets [narrator]; a quote gets its character's voice, else [otherDialogue], else the narrator's.
 */
data class VoiceCast(
    val enabled: Boolean = false,
    val narrator: Int = 0,
    val otherDialogue: Int? = null,
    val characters: Map<String, Int> = emptyMap()
) {
    fun voiceFor(piece: Dialogue.Piece): Int =
        if (!enabled || !piece.quoted) narrator
        else piece.speaker?.let { characters[it] } ?: otherDialogue ?: narrator
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

    private val ACCENTS = mapOf(
        'a' to "American", 'b' to "British", 'e' to "Spanish", 'f' to "French", 'h' to "Hindi",
        'i' to "Italian", 'j' to "Japanese", 'p' to "Portuguese", 'z' to "Chinese"
    )

    /** [count] speakers of [modelId]; named when the model is one we know, numbered otherwise. */
    fun forModel(modelId: String?, count: Int): List<Speaker> {
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
    fun autoCast(characters: List<String>, genders: Map<String, Boolean>, speakers: List<Speaker>, narrator: Int): Map<String, Int> {
        val accent = speakers.getOrNull(narrator)?.accent
        val pool = speakers.filter { it.id != narrator && (accent == null || it.accent == accent) }
            .ifEmpty { speakers.filter { it.id != narrator } }
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
    private val gson = Gson()

    fun load(novelId: String, modelId: String): VoiceCast =
        prefs.getString("$novelId|$modelId", null)
            ?.let { runCatching { gson.fromJson(it, VoiceCast::class.java) }.getOrNull() }
            ?: VoiceCast()

    fun save(novelId: String, modelId: String, cast: VoiceCast) {
        prefs.edit().putString("$novelId|$modelId", gson.toJson(cast)).apply()
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
