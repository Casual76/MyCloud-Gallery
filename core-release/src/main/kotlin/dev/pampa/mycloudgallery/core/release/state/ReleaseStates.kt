package dev.pampa.mycloudgallery.core.release.state

import dev.pampa.mycloudgallery.core.release.codec.SignedManifestVerifier
import dev.pampa.mycloudgallery.core.release.contract.AppVersion
import dev.pampa.mycloudgallery.core.release.contract.ManifestLimits
import dev.pampa.mycloudgallery.core.release.contract.ManifestOrigin
import dev.pampa.mycloudgallery.core.release.contract.ManifestRead
import dev.pampa.mycloudgallery.core.release.contract.OfferResult
import dev.pampa.mycloudgallery.core.release.contract.ReleaseClock
import dev.pampa.mycloudgallery.core.release.contract.ReleaseState
import dev.pampa.mycloudgallery.core.release.contract.ReleaseStateStore
import dev.pampa.mycloudgallery.core.release.keys.ReleaseTrust
import java.io.File

/**
 * La fabbrica del manifest per :app (P13; §1.4 "ReleaseGraph"). :app non puo' nominare il verificatore, il manifest ne' le chiavi
 * (Phase10ArchitectureTest, regola 3): riceve solo un [ReleaseState] (con `status` per i cancelli di scrittura e la radice della UI, `extraUrls`
 * per `ExtraModelSources`, `diag` per la Diagnostica) e un deposito su file.
 *
 * ```
 * val store = ReleaseStates.fileStore(File(context.noBackupFilesDir, "release"))
 * val state = ReleaseStates.create(store, AppVersion(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, canale), orologio, fiducia)
 * state.load()                                         // all'avvio, prima dei cancelli
 * state.offerRead(sorgente.read(), ManifestOrigin.NAS) // nel giro di sincronizzazione, al piu' una volta ogni 24 ore
 * ```
 * `fiducia` e' `ReleaseTrust.RELEASE` in `app/src/release` e `ReleaseTestKeys.DEBUG_TRUST` in `app/src/debug`; KT vale comunque solo con il
 * canale `DEBUG` ([ReleaseTrust.forChannel]).
 */
object ReleaseStates {
  fun create(
    store: ReleaseStateStore,
    version: AppVersion,
    clock: ReleaseClock,
    trust: ReleaseTrust = ReleaseTrust.RELEASE,
  ): ReleaseState = DefaultReleaseState(store, version, clock, SignedManifestVerifier(trust.forChannel(version.channel)))

  /** Il deposito atomico in [dir] (di norma `noBackupFilesDir/release/`). */
  fun fileStore(dir: File): ReleaseStateStore = FileReleaseStateStore(dir)
}

/**
 * Una lettura della sorgente nello stato (§2.8 "Lettura"): `Found` si offre; `TooLarge` (la sorgente non legge oltre il tetto) si conta come
 * `TOO_LARGE` offrendo una busta oltre il tetto, che ogni verificatore rifiuta per prima cosa; `Absent` (E-REL1) e `Unavailable` (NAS non
 * raggiungibile: si riprova al giro dopo) non cambiano niente e rispondono null.
 */
suspend fun ReleaseState.offerRead(read: ManifestRead, origin: ManifestOrigin): OfferResult? = when (read) {
  is ManifestRead.Found -> offer(read.bytes, origin)
  ManifestRead.TooLarge -> offer(ByteArray(ManifestLimits.MAX_BYTES + 1), origin)
  ManifestRead.Absent, ManifestRead.Unavailable -> null
}
