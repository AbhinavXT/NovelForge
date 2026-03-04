package com.example.novelreader.data.tts

import android.content.Context
import com.example.novelreader.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Manages TTS voice model downloads and storage.
 *
 * Models are stored at: {filesDir}/tts_models/{model_id}/
 * Each model folder contains .onnx files, tokens.txt, and optionally
 * lexicon.txt, espeak-ng-data/, etc.
 *
 * ## Available model catalogs:
 * - Piper voices: github.com/k2-fsa/sherpa-onnx/releases (tag: tts-models)
 * - KittenTTS:    github.com/k2-fsa/sherpa-onnx/releases
 * - Kokoro:       github.com/k2-fsa/sherpa-onnx/releases
 */
class TTSModelManager(context: Context) {

    companion object {
        private const val TAG = "TTSModelManager"
        private const val MODELS_DIR = "tts_models"

        // Base URL for sherpa-onnx model releases
        private const val RELEASE_BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models"

        /**
         * Curated list of recommended models.
         * Full catalog: https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/
         * Try voices: https://huggingface.co/spaces/k2-fsa/text-to-speech
         */
        val AVAILABLE_MODELS = listOf(

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ KOKORO — Best quality, larger models
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "kokoro-en-v0_19",
                displayName = "Kokoro English v0.19 (11 speakers)",
                family = ModelFamily.KOKORO,
                language = "en",
                sizeBytes = 350_000_000,
                description = "Highest quality English. 11 voices (male & female). Best on flagship phones.",
                downloadUrl = "$RELEASE_BASE/kokoro-en-v0_19.tar.bz2",
            ),
            TTSModelInfo(
                id = "kokoro-multi-lang-v1_0",
                displayName = "Kokoro Multi-Language v1.0",
                family = ModelFamily.KOKORO,
                language = "multi",
                sizeBytes = 400_000_000,
                description = "English + Chinese + more. 11 speakers. Premium quality.",
                downloadUrl = "$RELEASE_BASE/kokoro-multi-lang-v1_0.tar.bz2",
            ),
            TTSModelInfo(
                id = "kokoro-multi-lang-v1_1",
                displayName = "Kokoro Multi-Language v1.1",
                family = ModelFamily.KOKORO,
                language = "multi",
                sizeBytes = 400_000_000,
                description = "Latest multi-language Kokoro. English + Chinese. 11 speakers.",
                downloadUrl = "$RELEASE_BASE/kokoro-multi-lang-v1_1.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ KITTEN — Ultra-lightweight, fast on any device
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "kitten-nano-en-v0_1-fp16",
                displayName = "KittenTTS Nano v0.1",
                family = ModelFamily.KITTEN,
                language = "en",
                sizeBytes = 25_000_000,
                description = "Ultra-light English. Runs on any device, even low-end phones.",
                downloadUrl = "$RELEASE_BASE/kitten-nano-en-v0_1-fp16.tar.bz2",
            ),
            TTSModelInfo(
                id = "kitten-nano-en-v0_2-fp16",
                displayName = "KittenTTS Nano v0.2",
                family = ModelFamily.KITTEN,
                language = "en",
                sizeBytes = 25_000_000,
                description = "Improved nano model. Better pronunciation than v0.1.",
                downloadUrl = "$RELEASE_BASE/kitten-nano-en-v0_2-fp16.tar.bz2",
            ),
            TTSModelInfo(
                id = "kitten-mini-en-v0_1-fp16",
                displayName = "KittenTTS Mini v0.1",
                family = ModelFamily.KITTEN,
                language = "en",
                sizeBytes = 50_000_000,
                description = "Better quality than Nano, still very fast. Good balance.",
                downloadUrl = "$RELEASE_BASE/kitten-mini-en-v0_1-fp16.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — English (US)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-en_US-amy-low",
                displayName = "Amy (US, Low) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 16_000_000,
                description = "Fast, lightweight US English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-amy-low.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-amy-medium",
                displayName = "Amy (US, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Balanced quality US English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-amy-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-lessac-low",
                displayName = "Lessac (US, Low) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 16_000_000,
                description = "Lightweight clear US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-lessac-low.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-lessac-medium",
                displayName = "Lessac (US, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Clear US English male voice. Great for reading novels.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-lessac-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-lessac-high",
                displayName = "Lessac (US, High) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 100_000_000,
                description = "Highest quality Lessac. Best Piper male voice for reading.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-lessac-high.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-ryan-medium",
                displayName = "Ryan (US, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Warm US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-ryan-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-ryan-high",
                displayName = "Ryan (US, High) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 100_000_000,
                description = "High quality warm US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-ryan-high.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-joe-medium",
                displayName = "Joe (US, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Natural US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-joe-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-john-medium",
                displayName = "John (US, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Smooth US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-john-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-kristin-medium",
                displayName = "Kristin (US, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Clear US English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-kristin-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-kathleen-low",
                displayName = "Kathleen (US, Low) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 16_000_000,
                description = "Lightweight US English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-kathleen-low.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-norman-medium",
                displayName = "Norman (US, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Deep US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-norman-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-kusal-medium",
                displayName = "Kusal (US, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Distinctive US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-kusal-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-danny-low",
                displayName = "Danny (US, Low) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 16_000_000,
                description = "Lightweight US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-danny-low.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-hfc_female-medium",
                displayName = "HFC Female (US, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Expressive US English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-hfc_female-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-hfc_male-medium",
                displayName = "HFC Male (US, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Expressive US English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-hfc_male-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-libritts_r-medium",
                displayName = "LibriTTS-R (US, Medium, multi-speaker)",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Multi-speaker US English. Many voice variants via speaker ID.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-libritts_r-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-ljspeech-medium",
                displayName = "LJSpeech (US, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Classic US English female voice from LJSpeech dataset.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-ljspeech-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_US-ljspeech-high",
                displayName = "LJSpeech (US, High) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 100_000_000,
                description = "High quality classic US English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_US-ljspeech-high.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — English (British)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-en_GB-alan-medium",
                displayName = "Alan (British, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "British English male voice. Good for audiobooks.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-alan-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_GB-alba-medium",
                displayName = "Alba (British, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "British English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-alba-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_GB-aru-medium",
                displayName = "Aru (British, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "British English male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-aru-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_GB-cori-high",
                displayName = "Cori (British, High) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 100_000_000,
                description = "High quality British English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-cori-high.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_GB-jenny_dioco-medium",
                displayName = "Jenny (British, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Warm British English female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-jenny_dioco-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_GB-northern_english_male-medium",
                displayName = "Northern Male (British, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "Northern British accent male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-northern_english_male-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_GB-semaine-medium",
                displayName = "Semaine (British, Medium)",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 64_000_000,
                description = "British English voice from the Semaine corpus.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-semaine-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-en_GB-southern_english_female-low",
                displayName = "Southern Female (British, Low) ♀",
                family = ModelFamily.PIPER,
                language = "en",
                sizeBytes = 16_000_000,
                description = "Lightweight Southern British accent female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-en_GB-southern_english_female-low.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — Hindi (हिन्दी)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-hi_IN-priyamvada-medium",
                displayName = "Priyamvada (Hindi, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "hi",
                sizeBytes = 64_000_000,
                description = "Hindi female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-hi_IN-priyamvada-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-hi_IN-pratham-medium",
                displayName = "Pratham (Hindi, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "hi",
                sizeBytes = 64_000_000,
                description = "Hindi male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-hi_IN-pratham-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-hi_IN-rohan-medium",
                displayName = "Rohan (Hindi, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "hi",
                sizeBytes = 64_000_000,
                description = "Hindi male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-hi_IN-rohan-medium.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — German (Deutsch)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-de_DE-thorsten-high",
                displayName = "Thorsten (German, High) ♂",
                family = ModelFamily.PIPER,
                language = "de",
                sizeBytes = 100_000_000,
                description = "Highest quality German male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-de_DE-thorsten-high.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-de_DE-thorsten-medium",
                displayName = "Thorsten (German, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "de",
                sizeBytes = 64_000_000,
                description = "Good quality German male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-de_DE-thorsten-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-de_DE-eva_k-x_low",
                displayName = "Eva (German, X-Low) ♀",
                family = ModelFamily.PIPER,
                language = "de",
                sizeBytes = 16_000_000,
                description = "Lightweight German female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-de_DE-eva_k-x_low.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — French (Français)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-fr_FR-siwis-medium",
                displayName = "Siwis (French, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "fr",
                sizeBytes = 64_000_000,
                description = "Clear French female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-fr_FR-siwis-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-fr_FR-tom-medium",
                displayName = "Tom (French, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "fr",
                sizeBytes = 64_000_000,
                description = "French male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-fr_FR-tom-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-fr_FR-gilles-low",
                displayName = "Gilles (French, Low) ♂",
                family = ModelFamily.PIPER,
                language = "fr",
                sizeBytes = 16_000_000,
                description = "Lightweight French male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-fr_FR-gilles-low.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-fr_FR-upmc-medium",
                displayName = "UPMC (French, Medium)",
                family = ModelFamily.PIPER,
                language = "fr",
                sizeBytes = 64_000_000,
                description = "French voice from UPMC corpus.",
                downloadUrl = "$RELEASE_BASE/vits-piper-fr_FR-upmc-medium.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — Spanish (Español)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-es_ES-davefx-medium",
                displayName = "Davefx (Spanish Spain, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "es",
                sizeBytes = 64_000_000,
                description = "Spanish (Spain) male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-es_ES-davefx-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-es_MX-ald-medium",
                displayName = "Ald (Spanish Mexico, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "es",
                sizeBytes = 64_000_000,
                description = "Mexican Spanish male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-es_MX-ald-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-es_MX-claude-high",
                displayName = "Claude (Spanish Mexico, High) ♂",
                family = ModelFamily.PIPER,
                language = "es",
                sizeBytes = 100_000_000,
                description = "High quality Mexican Spanish male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-es_MX-claude-high.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — Italian (Italiano)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-it_IT-paola-medium",
                displayName = "Paola (Italian, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "it",
                sizeBytes = 64_000_000,
                description = "Italian female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-it_IT-paola-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-it_IT-riccardo-x_low",
                displayName = "Riccardo (Italian, X-Low) ♂",
                family = ModelFamily.PIPER,
                language = "it",
                sizeBytes = 16_000_000,
                description = "Lightweight Italian male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-it_IT-riccardo-x_low.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — Portuguese (Português)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-pt_BR-faber-medium",
                displayName = "Faber (Brazilian Portuguese, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "pt",
                sizeBytes = 64_000_000,
                description = "Brazilian Portuguese male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-pt_BR-faber-medium.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — Russian (Русский)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-ru_RU-irina-medium",
                displayName = "Irina (Russian, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "ru",
                sizeBytes = 64_000_000,
                description = "Russian female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-ru_RU-irina-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-ru_RU-denis-medium",
                displayName = "Denis (Russian, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "ru",
                sizeBytes = 64_000_000,
                description = "Russian male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-ru_RU-denis-medium.tar.bz2",
            ),

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // ★ PIPER — Other popular languages
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            TTSModelInfo(
                id = "vits-piper-nl_NL-miro-high",
                displayName = "Miro (Dutch, High) ♂",
                family = ModelFamily.PIPER,
                language = "nl",
                sizeBytes = 100_000_000,
                description = "High quality Dutch male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-nl_NL-miro-high.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-pl_PL-gosia-medium",
                displayName = "Gosia (Polish, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "pl",
                sizeBytes = 64_000_000,
                description = "Polish female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-pl_PL-gosia-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-uk_UA-ukrainian_tts-medium",
                displayName = "Ukrainian TTS (Medium)",
                family = ModelFamily.PIPER,
                language = "uk",
                sizeBytes = 64_000_000,
                description = "Ukrainian voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-uk_UA-ukrainian_tts-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-sv_SE-nst-medium",
                displayName = "NST (Swedish, Medium)",
                family = ModelFamily.PIPER,
                language = "sv",
                sizeBytes = 64_000_000,
                description = "Swedish voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-sv_SE-nst-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-tr_TR-fettah-medium",
                displayName = "Fettah (Turkish, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "tr",
                sizeBytes = 64_000_000,
                description = "Turkish male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-tr_TR-fettah-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-vi_VN-vais1000-medium",
                displayName = "VAIS1000 (Vietnamese, Medium)",
                family = ModelFamily.PIPER,
                language = "vi",
                sizeBytes = 64_000_000,
                description = "Vietnamese voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-vi_VN-vais1000-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-ar_JO-kareem-medium",
                displayName = "Kareem (Arabic, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "ar",
                sizeBytes = 64_000_000,
                description = "Arabic male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-ar_JO-kareem-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-hu_HU-anna-medium",
                displayName = "Anna (Hungarian, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "hu",
                sizeBytes = 64_000_000,
                description = "Hungarian female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-hu_HU-anna-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-cs_CZ-jirka-medium",
                displayName = "Jirka (Czech, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "cs",
                sizeBytes = 64_000_000,
                description = "Czech male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-cs_CZ-jirka-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-fi_FI-harri-medium",
                displayName = "Harri (Finnish, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "fi",
                sizeBytes = 64_000_000,
                description = "Finnish male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-fi_FI-harri-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-ro_RO-mihai-medium",
                displayName = "Mihai (Romanian, Medium) ♂",
                family = ModelFamily.PIPER,
                language = "ro",
                sizeBytes = 64_000_000,
                description = "Romanian male voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-ro_RO-mihai-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-da_DK-talesyntese-medium",
                displayName = "Talesyntese (Danish, Medium)",
                family = ModelFamily.PIPER,
                language = "da",
                sizeBytes = 64_000_000,
                description = "Danish voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-da_DK-talesyntese-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-no_NO-talesyntese-medium",
                displayName = "Talesyntese (Norwegian, Medium)",
                family = ModelFamily.PIPER,
                language = "no",
                sizeBytes = 64_000_000,
                description = "Norwegian voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-no_NO-talesyntese-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-ka_GE-natia-medium",
                displayName = "Natia (Georgian, Medium) ♀",
                family = ModelFamily.PIPER,
                language = "ka",
                sizeBytes = 64_000_000,
                description = "Georgian female voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-ka_GE-natia-medium.tar.bz2",
            ),
            TTSModelInfo(
                id = "vits-piper-ne_NP-google-medium",
                displayName = "Google Nepali (Medium)",
                family = ModelFamily.PIPER,
                language = "ne",
                sizeBytes = 64_000_000,
                description = "Nepali voice.",
                downloadUrl = "$RELEASE_BASE/vits-piper-ne_NP-google-medium.tar.bz2",
            ),
        )

        val LANGUAGE_DISPLAY_NAMES = mapOf(
            "en" to "English",
            "multi" to "Multi-Language",
            "hi" to "Hindi (हिन्दी)",
            "de" to "German (Deutsch)",
            "fr" to "French (Français)",
            "es" to "Spanish (Español)",
            "it" to "Italian (Italiano)",
            "pt" to "Portuguese (Português)",
            "ru" to "Russian (Русский)",
            "nl" to "Dutch (Nederlands)",
            "pl" to "Polish (Polski)",
            "uk" to "Ukrainian (Українська)",
            "sv" to "Swedish (Svenska)",
            "tr" to "Turkish (Türkçe)",
            "vi" to "Vietnamese (Tiếng Việt)",
            "ar" to "Arabic (العربية)",
            "hu" to "Hungarian (Magyar)",
            "cs" to "Czech (Čeština)",
            "fi" to "Finnish (Suomi)",
            "ro" to "Romanian (Română)",
            "da" to "Danish (Dansk)",
            "no" to "Norwegian (Norsk)",
            "ka" to "Georgian (ქართული)",
            "ne" to "Nepali (नेपाली)",
        )

        /** Controls display order in the UI — English and multi first */
        private val LANGUAGE_ORDER = listOf(
            "en", "multi", "hi", "de", "fr", "es", "it", "pt", "ru",
            "nl", "pl", "uk", "sv", "tr", "vi", "ar", "hu", "cs",
            "fi", "ro", "da", "no", "ka", "ne"
        )

        fun getLanguageDisplayName(code: String): String =
            LANGUAGE_DISPLAY_NAMES[code] ?: code.uppercase()
    }

    val modelsDir: File = File(context.filesDir, MODELS_DIR).also { it.mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // Download state
    private val _downloadProgress = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadState>> = _downloadProgress.asStateFlow()

    /**
     * Check which models are already downloaded.
     */
    fun getDownloadedModelIds(): Set<String> {
        if (!modelsDir.exists()) return emptySet()
        return modelsDir.listFiles()
            ?.filter { it.isDirectory && hasValidModel(it) }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
    }

    /**
     * Get full catalog with download status.
     */
    fun getModelCatalog(): List<TTSModelInfo> {
        val downloaded = getDownloadedModelIds()
        return AVAILABLE_MODELS.map { model ->
            model.copy(isDownloaded = downloaded.contains(model.id))
        }
    }

    /**
     * Get catalog grouped by language for UI display.
     */
    fun getModelCatalogByLanguage(): Map<String, List<TTSModelInfo>> {
        return getModelCatalog().groupBy { it.language }.toSortedMap(
            compareBy { LANGUAGE_ORDER.indexOf(it).takeIf { i -> i >= 0 } ?: 999 }
        )
    }

    /**
     * Download a model. Emits progress updates via [downloadProgress].
     */
    suspend fun downloadModel(modelId: String): Boolean = withContext(Dispatchers.IO) {
        val modelInfo = AVAILABLE_MODELS.find { it.id == modelId }
        if (modelInfo == null) {
            Logger.e(TAG, "Unknown model: $modelId")
            return@withContext false
        }

        updateDownloadState(modelId, DownloadState.Downloading(0f))

        try {
            val request = Request.Builder().url(modelInfo.downloadUrl).build()
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Logger.e(TAG, "Download failed: ${response.code}")
                updateDownloadState(modelId, DownloadState.Error("HTTP ${response.code}"))
                return@withContext false
            }

            val body = response.body ?: run {
                updateDownloadState(modelId, DownloadState.Error("Empty response"))
                return@withContext false
            }

            val totalBytes = body.contentLength()
            val tempFile = File(modelsDir, "${modelId}.download")

            // Download to temp file with progress
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Long = 0

                    while (true) {
                        val count = input.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        bytesRead += count

                        if (totalBytes > 0) {
                            val progress = bytesRead.toFloat() / totalBytes
                            updateDownloadState(modelId, DownloadState.Downloading(progress))
                        }
                    }
                }
            }

            // Extract archive
            updateDownloadState(modelId, DownloadState.Extracting)
            val modelDir = File(modelsDir, modelId)
            val success = extractArchive(tempFile, modelDir, modelInfo.archiveFormat)

            tempFile.delete()

            if (success && hasValidModel(modelDir)) {
                updateDownloadState(modelId, DownloadState.Completed)
                Logger.d(TAG, "Model downloaded: $modelId")
                return@withContext true
            } else {
                modelDir.deleteRecursively()
                updateDownloadState(modelId, DownloadState.Error("Extraction failed"))
                return@withContext false
            }

        } catch (e: Exception) {
            Logger.e(TAG, "Download error for $modelId", e)
            updateDownloadState(modelId, DownloadState.Error(e.message ?: "Unknown error"))
            return@withContext false
        }
    }

    /**
     * Delete a downloaded model to free storage.
     */
    fun deleteModel(modelId: String): Boolean {
        val dir = File(modelsDir, modelId)
        val result = dir.deleteRecursively()
        if (result) {
            updateDownloadState(modelId, DownloadState.NotDownloaded)
            Logger.d(TAG, "Deleted model: $modelId")
        }
        return result
    }

    /**
     * Get disk usage for a downloaded model.
     */
    fun getModelSize(modelId: String): Long {
        val dir = File(modelsDir, modelId)
        if (!dir.exists()) return 0
        return dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    // ── Private helpers ──────────────────────────────────────────

    private fun hasValidModel(dir: File): Boolean {
        val files = dir.listFiles() ?: return false
        return files.any { it.extension == "onnx" } &&
                files.any { it.name == "tokens.txt" }
    }

    private fun updateDownloadState(modelId: String, state: DownloadState) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            this[modelId] = state
        }
    }

    private fun extractArchive(archive: File, destDir: File, format: String): Boolean {
        return try {
            destDir.mkdirs()

            when (format) {
                "tar.bz2" -> extractTarBz2(archive, destDir)
                "tar.gz" -> extractTarGz(archive, destDir)
                "zip" -> extractZip(archive, destDir)
                else -> {
                    Logger.e(TAG, "Unknown archive format: $format")
                    false
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Extraction error", e)
            false
        }
    }

    /**
     * Extract .tar.bz2 archive.
     *
     * Uses ProcessBuilder to call system `tar` if available (Android has it),
     * with fallback to Apache Commons Compress if you add that dependency.
     */
    private fun extractTarBz2(archive: File, destDir: File): Boolean {
        // Android includes busybox tar on most devices
        return try {
            val process = ProcessBuilder(
                "tar", "xjf", archive.absolutePath,
                "-C", destDir.absolutePath,
                "--strip-components=1"  // Remove the top-level directory from the archive
            ).start()

            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val error = process.errorStream.bufferedReader().readText()
                Logger.e(TAG, "tar extraction failed: $error")
                // Fallback: try without --strip-components
                val process2 = ProcessBuilder(
                    "tar", "xjf", archive.absolutePath,
                    "-C", destDir.absolutePath
                ).start()
                val exitCode2 = process2.waitFor()
                if (exitCode2 == 0) {
                    // Move files from nested directory if needed
                    flattenSingleSubdir(destDir)
                }
                exitCode2 == 0
            } else {
                true
            }
        } catch (e: Exception) {
            Logger.e(TAG, "tar.bz2 extraction failed", e)
            false
        }
    }

    private fun extractTarGz(archive: File, destDir: File): Boolean {
        return try {
            val process = ProcessBuilder(
                "tar", "xzf", archive.absolutePath,
                "-C", destDir.absolutePath,
                "--strip-components=1"
            ).start()
            process.waitFor() == 0
        } catch (e: Exception) {
            Logger.e(TAG, "tar.gz extraction failed", e)
            false
        }
    }

    private fun extractZip(archive: File, destDir: File): Boolean {
        return try {
            java.util.zip.ZipInputStream(BufferedInputStream(archive.inputStream())).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val file = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        file.mkdirs()
                    } else {
                        file.parentFile?.mkdirs()
                        FileOutputStream(file).use { output ->
                            zis.copyTo(output)
                        }
                    }
                    entry = zis.nextEntry
                }
            }
            true
        } catch (e: Exception) {
            Logger.e(TAG, "zip extraction failed", e)
            false
        }
    }

    /**
     * If an archive extracted into a single subdirectory,
     * move its contents up to the parent.
     */
    private fun flattenSingleSubdir(dir: File) {
        val children = dir.listFiles() ?: return
        if (children.size == 1 && children[0].isDirectory) {
            val subDir = children[0]
            subDir.listFiles()?.forEach { file ->
                file.renameTo(File(dir, file.name))
            }
            subDir.delete()
        }
    }
}

// ── Data types ───────────────────────────────────────────────────

enum class ModelFamily(val displayName: String) {
    PIPER("Piper"),
    KITTEN("KittenTTS"),
    KOKORO("Kokoro"),
    MATCHA("Matcha-TTS")
}

data class TTSModelInfo(
    val id: String,
    val displayName: String,
    val family: ModelFamily,
    val language: String = "en",
    val sizeBytes: Long,
    val description: String,
    val downloadUrl: String,
    val archiveFormat: String = "tar.bz2",
    val isDownloaded: Boolean = false
) {
    val sizeDisplay: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> "%.0f MB".format(sizeBytes / 1_000_000.0)
            else -> "%.0f KB".format(sizeBytes / 1_000.0)
        }
}

sealed class DownloadState {
    data object NotDownloaded : DownloadState()
    data class Downloading(val progress: Float) : DownloadState()
    data object Extracting : DownloadState()
    data object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}