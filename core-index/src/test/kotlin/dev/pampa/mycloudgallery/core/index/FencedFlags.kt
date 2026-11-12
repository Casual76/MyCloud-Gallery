package dev.pampa.mycloudgallery.core.index

import dev.pampa.mycloudgallery.core.common.featureflags.FeatureFlags

// Il passo unico del 12/11/2026 (D35, par. 2.10 di docs/fase10-progetto.md, consenso in docs/decisioni.md) ha spento le quattro recinzioni
// "solo share di prova" (F4-D31, F5-D12, F7-D30, F8-D34) in RELEASE e nelle costanti. I cancelli restano tutti: per provare che con una recinzione
// accesa il cancello blocca servono flag con la recinzione accesa, che la produzione non sa piu' costruire (costruttore privato, `forBuild` senza
// parametri per D12, D30 e D34). Questo aiutante li fabbrica SOLO nelle prove, con la riflessione sul costruttore privato (come `TestTaps` di
// :app): il codice di produzione non vede questo file e non guadagna nessun varco. Una copia per insieme di prove (core-common, :app,
// core-faces, core-index, core-personal): le prove di un modulo non vedono quelle degli altri.

/** Flag di prova con le recinzioni "solo share di prova" accese, com'erano nella release fino al passo unico del 12/11/2026. */
internal object FencedFlags {

  private val constructor = FeatureFlags::class.java.declaredConstructors
    .single { c -> !c.isSynthetic && c.parameterTypes.size == 8 && c.parameterTypes.all { it == java.lang.Boolean.TYPE } }
    .also { it.isAccessible = true }

  /**
   * [base] (di serie i valori di release di adesso) con le recinzioni chieste **accese**; una recinzione non chiesta (`false`) resta com'e' in
   * [base]. Fallisce se il costruttore non mette i valori dove li aspetta (la riflessione non ha i nomi dei parametri).
   */
  fun of(
    base: FeatureFlags = FeatureFlags.RELEASE,
    index: Boolean = true,
    backup: Boolean = true,
    albums: Boolean = true,
    faces: Boolean = true,
  ): FeatureFlags {
    val wantIndex = index || base.indexPublishTestSharesOnly
    val wantBackup = backup || base.backupTestSharesOnly
    val wantAlbums = albums || base.sharedAlbumsTestSharesOnly
    val wantFaces = faces || base.facesSyncTestSharesOnly
    val flags = constructor.newInstance(
      base.wol, base.nasLibrary, wantIndex, wantBackup, base.backupBlocksTestAccounts, wantAlbums, wantFaces, base.mapOnline,
    ) as FeatureFlags
    check(
      flags.wol == base.wol && flags.nasLibrary == base.nasLibrary && flags.indexPublishTestSharesOnly == wantIndex &&
        flags.backupTestSharesOnly == wantBackup && flags.backupBlocksTestAccounts == base.backupBlocksTestAccounts &&
        flags.sharedAlbumsTestSharesOnly == wantAlbums && flags.facesSyncTestSharesOnly == wantFaces && flags.mapOnline == base.mapOnline,
    ) { "FencedFlags: il costruttore di FeatureFlags non ha piu' l'ordine atteso ($flags)" }
    return flags
  }
}
