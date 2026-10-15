package dev.pampa.mycloudgallery.core.personal.remote

import dev.pampa.mycloudgallery.core.index.contract.RemoteFailure
import dev.pampa.mycloudgallery.core.index.contract.RemoteResult
import dev.pampa.mycloudgallery.core.index.publish.IndexIdentity
import dev.pampa.mycloudgallery.core.nas.contract.NasError
import dev.pampa.mycloudgallery.core.nas.contract.NasIoException
import dev.pampa.mycloudgallery.core.personal.codec.PersonalPublishPlan
import dev.pampa.mycloudgallery.core.personal.codec.PersonalSegments
import dev.pampa.mycloudgallery.core.personal.contract.PersonalEntry
import dev.pampa.mycloudgallery.core.trust.RejectReason
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.data.ClassedSegment
import dev.pampa.mycloudgallery.core.trust.data.DeviceHead
import dev.pampa.mycloudgallery.core.trust.data.HeadKind
import dev.pampa.mycloudgallery.core.trust.data.HeadMark
import dev.pampa.mycloudgallery.core.trust.data.HeadPublish
import dev.pampa.mycloudgallery.core.trust.data.HeadRead
import dev.pampa.mycloudgallery.core.trust.data.HeadReadBudget
import dev.pampa.mycloudgallery.core.trust.data.HeadSeries
import dev.pampa.mycloudgallery.core.trust.data.SegmentClass
import dev.pampa.mycloudgallery.core.trust.data.SegmentRef
import dev.pampa.mycloudgallery.core.trust.nas.NasFileSystem
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import dev.pampa.mycloudgallery.core.trust.nas.NasRead
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// La lettura e la pubblicazione dei dati personali sul NAS (docs/fase4-progetto.md §2.9, docs/formato-nas.md §5.5): teste di tipo
// `personal` in `devices/<io>/personal/head/<versione>` e segmenti di classe `personal` in `.../personal/seg/<impronta>`, SOLO nella
// share privata dichiarata. Stesse regole dell'indice (core-index) ma non il suo percorso: `NasRemoteIndex` e `OutboxIndexPublisher`
// lavorano sulle teste `index` e sui record `meta`. Le firme, il registro, l'anti-rollback, il posto e l'account sono controllati da
// `HeadSeries` / `HeadVerifier` di core-trust, che per le teste `personal` rifiutano gia' un altro account (`NOT_OWNER`) e l'area
// comune (`WRONG_LOCATION`).

/** Una testa `personal` **accettata**: firma di un dispositivo dello stesso account, posto giusto, anti-rollback rispetto al [mark] noto. */
data class PersonalHeadView(
  val deviceId: String,
  val version: Long,
  val createdAtNasMillis: Long,
  /** Da salvare nella stessa operazione che applica le voci (anti-rollback, E-I5). */
  val mark: HeadMark,
  /** In ordine di impronta; tutti di classe `personal` (lo garantisce `DeviceHead`). */
  val segments: List<SegmentRef>,
  /** La testa cita un registro piu' nuovo del nostro: va riletto. */
  val registryBehind: Boolean,
)

/** La testa piu' recente di un dispositivo dopo quella nota. */
sealed interface PersonalHeadFetch {
  /** Nessuna testa piu' nuova di quella nota: di solito UNA lettura che risponde "non trovato". */
  data object NoChange : PersonalHeadFetch

  /** Il dispositivo non ha mai pubblicato dati personali in questa share. */
  data object None : PersonalHeadFetch

  data class Accepted(val head: PersonalHeadView) : PersonalHeadFetch

  /** Rifiutata: altro account, area comune, firma, revocato, rollback, formato piu' nuovo... Si tiene lo stato che si ha. */
  data class Rejected(val reason: RejectReason) : PersonalHeadFetch
}

/** Un segmento di una testa accettata, scaricato e decodificato. */
sealed interface PersonalSegmentFetch {
  /** Le voci del segmento (ancora da giudicare con `PersonalValidity`) e quanti byte ha pesato. */
  class Ok(val entries: List<PersonalEntry>, val bytes: Int) : PersonalSegmentFetch

  /** Elencato ma assente sul NAS: la testa si ignora per ora, lo stato gia' applicato resta. */
  data object Missing : PersonalSegmentFetch

  /** Dimensione o impronta diverse, classe sbagliata, tabella incoerente, voce fuori forma (E-I14): scartato intero. */
  data class Rejected(val reason: RejectReason) : PersonalSegmentFetch
}

/** Una testa propria appena scritta. */
class PersonalHeadPublished(val version: Long, val mark: HeadMark, val adopted: Boolean) {
  override fun toString(): String = "PersonalHeadPublished(v$version)"
}

/** Il NAS dei dati personali di UN account, nella share privata dichiarata. */
interface PersonalRemote {

  /** Un giro nuovo: il budget di letture delle teste riparte (M9: 1.024 per share e per giro). */
  fun startRound()

  /** I dispositivi con una cartella in `devices/` che il registro accettato riconosce come approvati o dismessi (questo compreso). */
  suspend fun devices(): RemoteResult<List<String>>

  suspend fun latestHead(deviceId: String, previous: HeadMark?): RemoteResult<PersonalHeadFetch>

  suspend fun segment(head: PersonalHeadView, ref: SegmentRef): RemoteResult<PersonalSegmentFetch>

  /**
   * Scrive i segmenti di [plan] che non sono in [alreadyPublished] (il nome e' l'impronta: una ripetizione trova "esiste gia'" con
   * gli stessi byte), poi la testa `personal` dopo la versione [after] con **tutti** i segmenti del piano. [now] e' l'ora del NAS.
   */
  suspend fun publish(plan: PersonalPublishPlan, alreadyPublished: Set<String>, after: Long, now: Long): RemoteResult<PersonalHeadPublished>
}

/**
 * Il NAS vero (o il suo simulatore: qualunque [NasFileSystem]). [share] e' la share privata dichiarata localmente per l'account
 * (`LocalDeviceProfile.privateShare`, M7): mai un'altra.
 *
 * Ogni errore del NAS diventa [RemoteResult.Failed], ogni dato ostile un rifiuto con il suo motivo; mai un'eccezione verso fuori per
 * un dato remoto. Le sole operazioni sul NAS sono `list`, `read` e `publishExclusive`.
 */
class NasPersonalRemote(
  private val share: String,
  private val fileSystem: (String) -> NasFileSystem,
  private val identity: IndexIdentity,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : PersonalRemote {

  private val budgetLock = Any()
  private var budget = HeadReadBudget()

  override fun startRound() {
    synchronized(budgetLock) { budget = HeadReadBudget() }
  }

  private fun currentBudget(): HeadReadBudget = synchronized(budgetLock) { budget }

  override suspend fun devices(): RemoteResult<List<String>> {
    val state = identity.registry() ?: return RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    val fs = fileSystem(share)
    return when (val result = call { HeadSeries.devices(fs, state) }) {
      is Call.Ok -> RemoteResult.Ok(result.value.ids)
      is Call.Failed -> RemoteResult.Failed(result.failure)
    }
  }

  override suspend fun latestHead(deviceId: String, previous: HeadMark?): RemoteResult<PersonalHeadFetch> {
    val state = identity.registry() ?: return RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    val fs = fileSystem(share)
    val hint = previous == null
    val scan = when (val result = call { HeadSeries.readLatest(fs, state, deviceId, HeadKind.PERSONAL, previous, identity.nasAccount, currentBudget(), hint) }) {
      is Call.Ok -> result.value
      is Call.Failed -> return RemoteResult.Failed(result.failure)
    }
    val fetch = when (val read = scan.result) {
      HeadRead.NoChange -> PersonalHeadFetch.NoChange
      HeadRead.None -> PersonalHeadFetch.None
      is HeadRead.Rejected -> PersonalHeadFetch.Rejected(read.reason)
      HeadRead.OutOfBudget -> return RemoteResult.Failed(RemoteFailure.OTHER)
      is HeadRead.Accepted -> {
        val head = read.head.head
        PersonalHeadFetch.Accepted(
          PersonalHeadView(
            deviceId = head.deviceId,
            version = head.version,
            createdAtNasMillis = head.createdAt,
            mark = read.head.mark,
            segments = head.segments.map { it.ref },
            registryBehind = read.head.registryBehind,
          ),
        )
      }
    }
    return RemoteResult.Ok(fetch)
  }

  override suspend fun segment(head: PersonalHeadView, ref: SegmentRef): RemoteResult<PersonalSegmentFetch> {
    val fs = fileSystem(share)
    val path = NasLayout.segmentFile(head.deviceId, HeadKind.PERSONAL.wire, ref.hash)
    // Il tetto e' la dimensione dichiarata dalla testa firmata (al massimo 16 MiB): mai un byte in piu' in memoria.
    val read = when (val result = call { fs.read(path, ref.size.toInt()) }) {
      is Call.Ok -> result.value
      is Call.Failed -> return RemoteResult.Failed(result.failure)
    }
    val fetch = when (read) {
      NasRead.NotFound -> PersonalSegmentFetch.Missing
      NasRead.TooLarge -> PersonalSegmentFetch.Rejected(RejectReason.HASH_MISMATCH)
      is NasRead.Data -> when (val verdict = PersonalSegments.decode(read.bytes, ref)) {
        is Verdict.Accepted -> PersonalSegmentFetch.Ok(verdict.value, read.bytes.size)
        is Verdict.Rejected -> PersonalSegmentFetch.Rejected(verdict.reason)
      }
    }
    return RemoteResult.Ok(fetch)
  }

  override suspend fun publish(plan: PersonalPublishPlan, alreadyPublished: Set<String>, after: Long, now: Long): RemoteResult<PersonalHeadPublished> {
    val registry = identity.writableRegistry() ?: return RemoteResult.Failed(RemoteFailure.NOT_ALLOWED)
    val fs = fileSystem(share)
    for (segment in plan.segments) {
      if (segment.ref.hash in alreadyPublished) continue
      when (val result = call { fs.publishExclusive(NasLayout.segmentFile(identity.deviceId, HeadKind.PERSONAL.wire, segment.ref.hash), segment.bytes) }) {
        is Call.Ok -> Unit
        is Call.Failed -> return RemoteResult.Failed(result.failure)
      }
    }
    val listed = plan.segments.map { ClassedSegment(SegmentClass.PERSONAL, it.ref) }.sortedBy { it.ref.hash }
    var base = after
    repeat(MAX_ATTEMPTS) {
      val result = call {
        HeadSeries.publish(fs, registry, identity.signer, HeadKind.PERSONAL, base, identity.nasAccount) { version ->
          DeviceHead(registry.registry.familyId, identity.deviceId, fs.shareName, HeadKind.PERSONAL, version, registry.registry.version, now, listed)
            .sign(identity.signer)
        }
      }
      when (result) {
        is Call.Failed -> return RemoteResult.Failed(result.failure)
        is Call.Ok -> when (val published = result.value) {
          is HeadPublish.Published -> return RemoteResult.Ok(PersonalHeadPublished(published.version, published.mark, adopted = false))
          // Un crash dopo la scrittura e prima di ricordarla: una testa valida e mia a quel nome. Se elenca gli stessi segmenti e' la
          // nostra (si adotta), altrimenti se ne scrive una dopo.
          is HeadPublish.ExistingOwn ->
            if (published.head.head.segments == listed) return RemoteResult.Ok(PersonalHeadPublished(published.version, published.head.mark, adopted = true))
            else base = published.version
          is HeadPublish.Blocked, HeadPublish.NoRoom -> return RemoteResult.Failed(RemoteFailure.OTHER)
        }
      }
    }
    return RemoteResult.Failed(RemoteFailure.OTHER)
  }

  private sealed interface Call<out T> {
    class Ok<T>(val value: T) : Call<T>

    class Failed(val failure: RemoteFailure) : Call<Nothing>
  }

  /** Esegue [block] (bloccante, `NasFileSystem`) su [io]; un errore del NAS diventa un guasto, mai un'eccezione. */
  private suspend fun <T> call(block: () -> T): Call<T> =
    try {
      Call.Ok(withContext(io) { block() })
    } catch (e: IOException) {
      Call.Failed(if (e is NasIoException) failureOf(e.error) else RemoteFailure.UNREACHABLE)
    }

  private fun failureOf(error: NasError): RemoteFailure = when (error) {
    NasError.PermissionDenied -> RemoteFailure.ACCESS_DENIED
    is NasError.ShareMissing -> RemoteFailure.SHARE_MISSING
    NasError.NasFull -> RemoteFailure.DISK_FULL
    NasError.QuotaExceeded -> RemoteFailure.QUOTA
    NasError.FileBusy -> RemoteFailure.BUSY
    NasError.Timeout, NasError.ServiceDown, NasError.SessionExpired, NasError.Stalled -> RemoteFailure.UNREACHABLE
    is NasError.NotAllowedHere, NasError.LocalNetworkBlocked, is NasError.Auth, NasError.IdentityMismatch, is NasError.InsecureSession ->
      RemoteFailure.NOT_ALLOWED
    // Exists, NotFound e gli errori che core-nas aggiungera': si riprova al giro dopo.
    else -> RemoteFailure.OTHER
  }

  private companion object {
    const val MAX_ATTEMPTS = 3
  }
}
