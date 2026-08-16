package com.abhinavxt.novelforge.data.source.nf

// QuickNovel compatibility layer (package: data.source.nf) — source registry.
//
// One NfSourceAdapter per ported provider. Only providers that are active in
// upstream QuickNovel (util/Apis.kt) and not already covered by a native
// NovelForge source are registered here.
//
// Already native in NovelForge (NOT registered again):
//   RoyalRoad, ReadNovelFull, FreeWebNovel, LibRead, NovelFull.net, PawRead, WtrLab
// Excluded (EPUB-download model, incompatible with the chapter-based Source
// interface): PlanetaEpub
// Excluded (disabled upstream as dead/broken): BestLightNovel, Comrademao,
//   Efremnet, LightNovelPub, ReadAnyBook, ReadLightNovel
//
// IMPORTANT: the id prefix is baked into novel/chapter IDs stored in the Room
// database. Never rename a prefix once a build has shipped to a device.

import com.abhinavxt.novelforge.data.source.Source
import com.abhinavxt.novelforge.data.source.providers.*

object NfSources {

    fun all(): List<Source> = listOf(
        // --- English ---
        NfSourceAdapter(AllNovelProvider(), "qanv"),
        NfSourceAdapter(FanMtlnProvider(), "qfmtl"),
        NfSourceAdapter(GraycityProvider(), "qgray"),
        NfSourceAdapter(HiraethTranslationProvider(), "qhir"),
        NfSourceAdapter(LightNovelTranslationsProvider(), "qlnt"),
        NfSourceAdapter(LightNovelWorldProvider(), "qlnw"),
        NfSourceAdapter(LnoriProvider(), "qlno"),
        NfSourceAdapter(MtlNovelProvider(), "qmtln"),
        NfSourceAdapter(NoBadNovelProvider(), "qnbn"),
        NfSourceAdapter(NovelFullProvider(), "qnful"),
        NfSourceAdapter(NovelLightProvider(), "qnlig"),
        NfSourceAdapter(RanobesProvider(), "qrnb"),
        NfSourceAdapter(ReadfromnetProvider(), "qrfn"),
        NfSourceAdapter(ReadhiveProvider(), "qrhv"),
        NfSourceAdapter(SonicMTLProvider(), "qsonic"),
        NfSourceAdapter(WattpadProvider(), "qwatt"),
        NfSourceAdapter(WuxiaBoxProvider(), "qwbox"),
        NfSourceAdapter(WuxiaClickProvider(), "qwclick"),

        // Removed sources — provider files deleted, prefixes retired.
        // Never reuse a retired prefix: it may still be baked into Room rows
        // on-device; getSourceFromNovelId() returns null for them and all
        // call sites degrade gracefully (skip update/download, detail null).
        //
        // 2026-07 (non-English): qiwn, qmeio, qmore, qskr (Indonesian);
        //   qkol, qrwy (Arabic); qdvn, qnvl, qsky (Spanish); qnman (Portuguese)
        // 2026-07 (English cull): qcg ChrysanthemumGarden, qfen FenrirReal,
        //   qlnm LnMTL, qnbin NovelBin, qnbud NovelBuddy, qnfire NovelFire,
        //   qnvo NovelsOnline, qnlv NovLove, qrofb ReadOnlineFreeBook,
        //   qscrib Scribblehub. NovLove's removal also freed its base classes
        //   MeioNovelProvider/MoreNovelProvider (deleted).
    )
}