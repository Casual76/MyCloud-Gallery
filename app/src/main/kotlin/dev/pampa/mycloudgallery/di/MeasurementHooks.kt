package dev.pampa.mycloudgallery.di

import dagger.BindsOptionalOf
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.pampa.mycloudgallery.core.family.contract.FamilySetupRepository
import dev.pampa.mycloudgallery.core.index.contract.ReplicaBlobs
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneLibrarySync
import dev.pampa.mycloudgallery.core.personal.contract.PersonalStore
import dev.pampa.mycloudgallery.memories.MemoriesGraph

/**
 * Cio' che una **variante di misura** (`benchmarkRelease`, `nonMinifiedRelease`; docs/bench/README.md §6) sostituisce
 * dell'app vera: la famiglia gia' "pronta" senza NAS, la lettura del telefono ferma e il database del dataset sintetico.
 *
 * **Come arriva qui (revisione di sicurezza, R6).** Non con `ServiceLoader`: un file `META-INF/services/...` e' un canale
 * dichiarativo e invisibile, che qualunque dipendenza (o un file messo per sbaglio in `src/main/resources`) poteva usare per
 * far partire l'app vera con la famiglia "pronta", saltando onboarding e approvazione. Qui l'unica via e' un binding Hilt
 * esplicito, e **l'unico posto che lo contiene e' `src/benchmarkRelease`** (`BenchHooksModule`, compilato nelle due varianti
 * di misura e in nessun'altra). Nelle altre varianti, release compresa, [MeasurementHooksModule] da' un `Optional` vuoto e
 * `src/main` non nomina nessuna classe di misura. `tools/check.ps1` verifica in piu' che l'APK di release non porti nessun
 * file `META-INF/services/dev.pampa.*`.
 */
interface MeasurementHooks {

  /** La famiglia fondata e il dispositivo approvato: la Libreria si raggiunge subito, senza NAS e senza rete. */
  val family: FamilySetupRepository

  /** La lettura del telefono spenta: non deve riconciliare il dataset sintetico con i pochi media veri. */
  val phoneSync: PhoneLibrarySync

  /** Il file del catalogo della variante: il dataset sintetico non sta mai in `gallery.db`. */
  val databaseName: String

  /**
   * La replica locale sintetica della variante di misura (P10, docs/bench/README.md §6-bis): le miniature dei media
   * solo-NAS del dataset, servite da `NasThumbnailSource` senza NAS. Null altrove (e finche' il dataset non c'e').
   */
  val replicaBlobs: ReplicaBlobs? get() = null

  /**
   * Fase 9 (P12, §5.4): la mappa dettagliata (le tessere di OpenFreeMap) in una variante di misura. **Mai**: il benchmark non genera traffico di rete e
   * accenderla e' una scelta dell'utente, non del collaudo. Con `false` il cancello di build della mappa e' spento (`MapOnline.Unavailable`) e la carta e' solo
   * quella di base offline, qualunque cosa dicano le impostazioni del dispositivo (che il collaudo non tocca). Vero (di serie) nelle varianti normali.
   */
  val mapOnlineAllowed: Boolean get() = true

  /**
   * Fase 9 (P12): lo store dei dati personali **locale e vuoto** con cui i ricordi si montano senza NAS (le scelte "visto", "non mostrare", "Mostra meno" stanno
   * li'; in una variante di misura non c'e' NAS e quindi nessun archivio personale). Null altrove: i ricordi aspettano i dati personali veri.
   */
  val memoriesPersonalStore: PersonalStore? get() = null

  /** Fase 9 (P12): i ricordi sono nati (con il processo, senza I/O): la variante di misura puo' agganciarci il suo pilota della generazione. Niente altrove. */
  fun onMemoriesGraph(memories: MemoriesGraph) = Unit
}

/** Dichiara che le [MeasurementHooks] possono mancare: cosi' le varianti normali compilano senza nessuna implementazione. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MeasurementHooksModule {
  @BindsOptionalOf
  abstract fun measurementHooks(): MeasurementHooks
}

/**
 * Quale stato del dispositivo usare. Le [hooks] di una variante di misura vincono su tutto; poi la scorciatoia dello smoke
 * in debug ([debugOverride], sempre null in release); altrimenti quello vero ([real]).
 */
internal fun chooseFamilySetup(
  hooks: MeasurementHooks?,
  debugOverride: FamilySetupRepository?,
  real: FamilySetupRepository,
): FamilySetupRepository = hooks?.family ?: debugOverride ?: real
