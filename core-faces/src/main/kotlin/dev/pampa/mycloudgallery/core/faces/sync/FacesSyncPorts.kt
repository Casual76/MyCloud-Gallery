package dev.pampa.mycloudgallery.core.faces.sync

import dev.pampa.mycloudgallery.core.faces.contract.FacesArchive
import dev.pampa.mycloudgallery.core.faces.contract.FacesArchiveLimits
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.trust.TrustLimits
import dev.pampa.mycloudgallery.core.trust.crypto.Signer
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.ClusterEntry
import dev.pampa.mycloudgallery.core.trust.data.FacesSnapshotDoc
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import dev.pampa.mycloudgallery.core.trust.registry.RegistryState

// LE PORTE E GLI STATI DI sync/ (Fase 8, P3). Le lega :app (P13): l'identita' di questo dispositivo, l'archivio di una generazione (P6), la
// regola "M3 dei volti" sopra il catalogo. La replica e il pubblicatore tengono il loro stato dietro due store ([FacesReplicaStore],
// [FacesPublishStore]): **finche' P6 non consegna gli adattatori Room sopra il database dei volti valgono le implementazioni in memoria di
// questo file** (dopo una morte del processo la replica rilegge le teste e il pubblicatore rilegge la sua sul NAS: costa traffico, non dati).

/** Chi sono per i dati dei volti: il dispositivo, la sua chiave di firma e il registro di famiglia accettato (null = famiglia non pronta). */
interface FacesSyncIdentity {
  val deviceId: String
  val signer: Signer

  fun registry(): RegistryState?
}

/** L'archivio dei volti della generazione [generation] (P6, `store/`), o null se non si apre (chiave persa, E-F19; generazione chiusa). */
fun interface FacesArchives {
  suspend fun open(generation: FaceGeneration): FacesArchive?
}

/**
 * "M3 dei volti" (§2.4; il punto aperto di P0 su `FacesItem`): le chiavi di [keys] che il catalogo di questo dispositivo conosce come media
 * **del NAS di questo account** (area privata o comune, mai solo del telefono) con timbro e `qh1` freschi. Solo per quelle si pubblica una
 * voce `faces`. La lega :app (P13) sopra `MediaCatalog` e l'indice; il null-object [NONE] non ne ammette nessuna (niente si pubblica).
 */
fun interface FacesAttestation {
  suspend fun publishable(keys: Collection<ContentKey>): Set<ContentKey>

  companion object {
    val NONE = FacesAttestation { emptySet() }
  }
}

/** I tetti di un giro della replica (formato §7; isolamento degli errori e tetti per dispositivo come la revisione A della Fase 6). */
data class FacesSyncLimits(
  /** Byte di segmenti scaricati per giro (il resto al giro dopo). */
  val maxBytesPerRound: Long = TrustLimits.MAX_FACES_BYTES_PER_ROUND.toLong(),
  /** Byte di segmenti scaricati per dispositivo e per giro (a rotazione: un dispositivo enorme non ferma gli altri). */
  val maxBytesPerDevice: Long = TrustLimits.MAX_FACES_BYTES_PER_DEVICE_ROUND.toLong(),
  /** Cartelle di dispositivi lette per generazione e per giro. */
  val maxDevices: Int = TrustLimits.MAX_FACES_DEVICES_PER_GENERATION,
  /** Letture per nome delle teste per giro (tutti i dispositivi insieme). */
  val maxHeadReads: Int = TrustLimits.MAX_HEAD_READS_PER_SHARE,
  /** Corpi `faces` e voci `people` tenuti per dispositivo (le voci `cluster` hanno i tetti dell'istantanea). */
  val maxEntriesPerDevice: Int = TrustLimits.MAX_FACES_HEAD_ENTRIES,
  /**
   * R5: richieste di segmenti per giro (tutti i dispositivi insieme), oltre al tetto dei byte: una testa che elenca 8.192 segmenti mai scritti
   * non costa 8.192 richieste a ogni giro. Un giro onesto ne fa poche decine (un segmento porta fino a 512 corpi).
   */
  val maxSegmentReadsPerRound: Int = 1_024,
  /** R5: richieste di segmenti per dispositivo e per giro (a rotazione, il resto al giro dopo). */
  val maxSegmentReadsPerDevice: Int = 256,
  /** R6: i tetti dei corpi degli altri dispositivi nell'archivio ([FacesArchiveLimits]; le prove li abbassano). */
  val maxBodiesPerAuthor: Int = FacesArchiveLimits.MAX_BODIES_PER_AUTHOR,
  val maxBytesPerAuthor: Long = FacesArchiveLimits.MAX_BYTES_PER_AUTHOR,
  val maxBodiesTotal: Int = FacesArchiveLimits.MAX_BODIES_TOTAL,
  val maxBytesTotal: Long = FacesArchiveLimits.MAX_BYTES_TOTAL,
)

/**
 * R5, R7 (correzioni della Fase 8): un segmento **in attesa**: assente, illeggibile, con l'impronta o la dimensione sbagliate, o rimandato per il
 * tetto dell'archivio. [attempts] tentativi gia' fatti, [retryAtNas] l'ora del NAS prima della quale non si richiede (attese crescenti, da
 * [FIRST_WAIT_MS] a [MAX_WAIT_MS]). Durevole con lo stato della replica: una morte del processo non lo fa richiedere a ogni giro.
 */
data class SegmentWait(val attempts: Int, val retryAtNas: Long) {
  /** Vero se adesso ([nasNow]) il segmento si puo' richiedere (un'ora del NAS tornata molto indietro non lo tiene fermo oltre [MAX_WAIT_MS]). */
  fun due(nasNow: Long): Boolean = nasNow >= retryAtNas || retryAtNas - nasNow > MAX_WAIT_MS

  companion object {
    const val FIRST_WAIT_MS: Long = 15L * 60 * 1000
    const val MAX_WAIT_MS: Long = 24L * 60 * 60 * 1000

    /** Letture sbagliate (impronta o dimensione) dopo le quali il rifiuto e' definitivo (R7): la prima e due riprove. */
    const val MAX_BAD_READS: Int = 3

    /** L'attesa dopo il tentativo [attempts] (1, 2, ...): 15 minuti, poi il doppio a ogni volta, al piu' 24 ore. */
    fun delayAfter(attempts: Int): Long = minOf(FIRST_WAIT_MS shl (attempts.coerceIn(1, 8) - 1), MAX_WAIT_MS)
  }
}

/**
 * Quello che la replica ricorda di un dispositivo dell'account nella generazione corrente: l'ultima testa accettata ([mark], anti-rollback ed
 * equivocazione), i suoi segmenti, quali sono gia' stati applicati o rifiutati per sempre ([done]; un segmento e' il suo contenuto: una testa
 * nuova riscarica solo quelli cambiati), quante voci ha portato ognuno ([counts], per i tetti), le voci dei suoi segmenti `cluster`
 * ([cluster]) e l'ultima istantanea completa e coerente che ha pubblicato ([snapshot]).
 */
data class DeviceReplica(
  val mark: HeadMark? = null,
  val segments: List<ClassedSegment> = emptyList(),
  val snapshotMark: SnapshotMark? = null,
  val done: Set<String> = emptySet(),
  val counts: Map<String, Int> = emptyMap(),
  val cluster: Map<String, List<ClusterEntry>> = emptyMap(),
  val snapshot: FacesSnapshotDoc? = null,
  /** La testa da cui viene [snapshot] (lo spareggio fra due istantanee con la stessa `seq` dello stesso custode). */
  val snapshotHead: HeadMark? = null,
  /** La testa la cui istantanea e' gia' stata giudicata (completa o no): non si rigiudica a ogni giro. */
  val checked: HeadMark? = null,
  /** R5, R7: i segmenti in attesa (non fatti) e quando si richiedono. */
  val waits: Map<String, SegmentWait> = emptyMap(),
) {
  /** Tutti i segmenti della testa sono stati applicati o rifiutati. */
  val complete: Boolean get() = segments.all { it.ref.hash in done }

  override fun toString(): String = "DeviceReplica(v${mark?.version}, ${done.size}/${segments.size} segmenti, istantanea=${snapshot != null})"
}

/** Lo stato della replica in una generazione: i dispositivi e gli autori revocati gia' tolti dall'archivio. */
data class FacesReplicaState(val devices: Map<String, DeviceReplica> = emptyMap(), val revoked: Set<String> = emptySet()) {
  override fun toString(): String = "FacesReplicaState(${devices.size} dispositivi, ${revoked.size} revocati)"

  companion object {
    val EMPTY = FacesReplicaState()
  }
}

/** Dove la replica tiene il suo stato (P6 lo mette nel database dei volti; qui in memoria). Ogni [save] e' atomico e durevole. */
interface FacesReplicaStore {
  suspend fun load(generation: FaceGeneration): FacesReplicaState

  suspend fun save(generation: FaceGeneration, state: FacesReplicaState)
}

class InMemoryFacesReplicaStore : FacesReplicaStore {
  private val states = HashMap<FaceGeneration, FacesReplicaState>()

  @Volatile var saves: Int = 0
    private set

  override suspend fun load(generation: FaceGeneration): FacesReplicaState = synchronized(this) { states[generation] ?: FacesReplicaState.EMPTY }

  override suspend fun save(generation: FaceGeneration, state: FacesReplicaState): Unit = synchronized(this) {
    // Una sola generazione alla volta: quella vecchia non serve piu' (e non si deve leggere, D30).
    states.keys.retainAll(setOf(generation))
    states[generation] = state
    saves++
  }
}

/** L'ultima testa pubblicata da qui: numero, segno, ora del NAS, segmenti elencati, istantanea, quante voci. */
data class FacesPublication(
  val version: Long,
  val mark: HeadMark,
  val publishedAtNas: Long,
  val segments: Set<String>,
  val snapshot: SnapshotMark?,
  val entries: Int,
) {
  override fun toString(): String = "FacesPublication(v$version, ${segments.size} segmenti)"
}

/** L'istantanea che il custode ha chiesto di pubblicare, gia' nelle voci del formato (D21). */
data class StagedSnapshot(val mark: SnapshotMark, val entries: List<ClusterEntry>) {
  override fun toString(): String = "StagedSnapshot(seq=${mark.seq}, ${entries.size} voci)"
}

/**
 * Lo stato del pubblicatore in una generazione: le chiavi dei corpi calcolati qui ([keys]: si pubblicano se il corpo di qui vince e passa
 * "M3 dei volti"), l'istantanea da pubblicare, l'ultima testa, le modifiche in coda ([pending]), il diario dei segmenti pubblicati da qui
 * ([diary], la prova "e' mio" della pulizia dei propri file, P8), se la propria testa sul NAS e' gia' stata riletta ([adopted]) e se una
 * testa potrebbe essere sul NAS senza che lo si sappia ([uncertain]).
 */
data class FacesPublishState(
  val keys: Set<ContentKey> = emptySet(),
  val snapshot: StagedSnapshot? = null,
  val publication: FacesPublication? = null,
  val pending: Int = 0,
  val diary: Set<String> = emptySet(),
  val adopted: Boolean = false,
  /**
   * Una testa e' forse sul NAS senza che lo si sappia (un guasto dopo la scrittura, una morte del processo prima di salvare): la prossima
   * pubblicazione non si fida di "niente e' cambiato" e ripassa dalla serie, che adotta la testa se e' quella giusta o ne scrive una piu' alta.
   */
  val uncertain: Boolean = false,
) {
  override fun toString(): String = "FacesPublishState(${keys.size} chiavi, in coda $pending, ${publication ?: "mai pubblicato"})"

  companion object {
    val EMPTY = FacesPublishState()

    /** Il diario tiene le impronte piu' recenti (la pulizia ha 7 giorni di grazia e le ultime 3 teste: oltre non serve). */
    const val MAX_DIARY: Int = 50_000
  }
}

/** Dove il pubblicatore tiene il suo stato (P6 lo mette nel database dei volti: e' l'outbox; qui in memoria). Ogni [save] e' durevole. */
interface FacesPublishStore {
  suspend fun load(generation: FaceGeneration): FacesPublishState

  suspend fun save(generation: FaceGeneration, state: FacesPublishState)
}

class InMemoryFacesPublishStore : FacesPublishStore {
  private val states = HashMap<FaceGeneration, FacesPublishState>()

  override suspend fun load(generation: FaceGeneration): FacesPublishState = synchronized(this) { states[generation] ?: FacesPublishState.EMPTY }

  override suspend fun save(generation: FaceGeneration, state: FacesPublishState): Unit = synchronized(this) {
    states.keys.retainAll(setOf(generation))
    states[generation] = state
  }
}
