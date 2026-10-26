package dev.pampa.mycloudgallery.core.albums.chaos

import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AttestRequest
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.FlushReason
import dev.pampa.mycloudgallery.core.albums.contract.MediaFacts
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.PublishOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationRules
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumChange
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.contract.StageTicket
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferRefusal
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.albums.share.ShareBench
import dev.pampa.mycloudgallery.core.albums.share.ShareNasTarget
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.nasops.contract.NasFileRef
import java.util.IdentityHashMap
import kotlinx.coroutines.flow.StateFlow

// LE PORTE CON I COLPI E GLI OSSERVATORI. Le porte dell'orchestratore si avvolgono con `ShareBench.wrap...` (i ganci di P4): per ognuna prima
// l'osservatore (che guarda gli argomenti e non cambia niente), poi i colpi. Gli osservatori sono parte del banco, accesi in ogni giro: guardano
// gli eventi nel momento in cui accadono ("la voce si accoda solo per un file che c'e' adesso", "il catalogo conosce la destinazione prima che la
// sorgente vada nel cestino", "la conferma e' di questo piano, di questo processo, non scaduta"), perche' una finestra di pochi microsecondi non
// lascia traccia nello stato a fine passo.

/** Le conferme emesse dal "foglio" del banco e in quale processo: una conferma di un processo morto non vale piu' (§2.5, decisione 9 di P4). */
class ConfirmationBook {
  /** Il numero del processo corrente (cresce a ogni riavvio). */
  var epoch: Int = 0
    private set

  private val issuedIn = IdentityHashMap<ShareConfirmation, Int>()

  /** Un tocco sul foglio: la conferma del piano, nata in questo processo. */
  fun issue(bench: ShareBench, plan: SharePlan): ShareConfirmation = bench.confirm(plan).also { issuedIn[it] = epoch }

  fun epochOf(confirmation: ShareConfirmation): Int? = issuedIn[confirmation]

  /** Il processo muore e ne nasce un altro: le conferme vecchie restano nel registro, ma non valgono piu'. */
  fun nextProcess() {
    epoch++
  }
}

/** Chi guarda gli eventi mentre accadono e annota le violazioni (la fase e' scritta da chi guida il giro). */
class ShareChaosObserver(private val book: ConfirmationBook) {
  lateinit var bench: ShareBench

  val violations: MutableList<Violation> = ArrayList()

  /** Chi guida il giro scrive qui in che punto siamo ("passo 2, giro 3"). */
  var phase: String = "giro"

  var observed: Long = 0
    private set

  private fun add(invariant: ShareInvariant, text: String) {
    if (violations.none { it.invariant == invariant && it.text == text }) violations += Violation(invariant, phase, text)
  }

  /** Un trasferimento sta per partire (spostamento o caricamento): la conferma e' di questo piano, di questo processo, fresca. */
  fun onTransfer(key: TransferKey, confirmation: ShareConfirmation) {
    observed++
    val opId = key.value.substringBefore(':')
    val op = bench.diary.allOps().firstOrNull { it.id.value == opId }
    when {
      op == null -> add(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION, "trasferimento senza un'operazione nel diario della condivisione")
      confirmation.planHash != op.planHash -> add(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION, "conferma di un altro piano")
    }
    val age = bench.world.clocks.monotonicMs() - confirmation.issuedAtMonotonicMs
    if (age !in 0..ShareConfirmationRules.MAX_AGE_MS) add(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION, "conferma scaduta o dal futuro ($age ms)")
    val issued = book.epochOf(confirmation)
    when {
      issued == null -> add(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION, "conferma che il foglio non ha emesso")
      issued != book.epoch -> add(ShareInvariant.NO_ACTION_WITHOUT_CONFIRMATION, "conferma di un processo morto, riusata")
    }
  }

  /** Il motore chiama la porta di completamento: la sorgente c'e' ancora e la destinazione ha i byte giusti (il catalogo sa prima del cestino). */
  fun onCompletion(source: NasFileRef, dest: NasFileRef) {
    observed++
    if (bench.target.bytes(source.share, source.path.value) == null) {
      add(ShareInvariant.NO_LOSS, "completamento dopo il cestino: la sorgente non c'e' piu' quando il catalogo conosce la destinazione")
    }
    val moved = bench.library.all().firstOrNull { bench.library.nasRefOf(it)?.let { ref -> ref.share == source.share && ref.path == source.path } == true }
    val expected = moved?.let { bench.originals[it.id] }
    val there = bench.target.bytes(dest.share, dest.path.value)
    if (there == null) {
      add(ShareInvariant.NO_LOSS, "completamento senza la destinazione sul NAS")
    } else if (expected != null && !there.contentEquals(expected)) {
      add(ShareInvariant.HASH, "completamento con una destinazione che non ha l'hash atteso")
    }
  }

  /** Una voce `item:` sta per essere accodata: il file e' nell'area comune adesso, con i byte giusti, e il catalogo lo sa (invariante 6). */
  fun onStage(changes: List<SharedAlbumChange>) {
    for (item in changes.filterIsInstance<SharedAlbumChange.Item>()) {
      observed++
      val bytes = bench.target.bytes(ShareNasTarget.COMMON, item.path.value)
      val expected = bench.originals[item.media]
      when {
        bytes == null -> add(ShareInvariant.NO_ENTRY_WITHOUT_FILE, "voce accodata prima che il file sia nell'area comune")
        expected != null && !bytes.contentEquals(expected) -> add(ShareInvariant.NO_ENTRY_WITHOUT_FILE, "voce accodata con un file diverso dall'originale")
      }
      val attested: MediaId? = bench.library.attestedNow(listOf(item.contentKey))[item.contentKey]
      val m = attested?.let { bench.library[it] }
      if (m == null || m.place != MediaPlace.COMMON || m.path != item.path.value) {
        add(ShareInvariant.NO_ENTRY_WITHOUT_FILE, "voce accodata prima che il catalogo conosca il file (completamento)")
      }
    }
  }

  /** Una testa e' stata pubblicata: nessuna voce pubblicata rimanda a un file che non c'e' (invariante 6). */
  suspend fun onPublished() {
    observed++
    for (line in bench.violations(final = false).filter { it.startsWith("6:") }) {
      val v = Violation.fromBench(line, phase)
      if (violations.none { it.invariant == v.invariant && it.text == v.text }) violations += v
    }
  }
}

/** Le porte che il banco ha avvolto per l'ultimo orchestratore (le serve a chi vuole avvolgere ancora, come le mutazioni). */
class ChaosPortsHandle {
  var mover: AlbumMover? = null
  var uploader: AlbumUploader? = null
  var tickets: TicketStatus? = null
  var publisher: AlbumPublisher? = null
}

/** Avvolge le sette porte dell'orchestratore di un [ShareBench] con gli osservatori e i colpi. */
object ChaosPorts {

  fun install(bench: ShareBench, injector: ShareChaosInjector, observer: ShareChaosObserver, avoid: Set<KnownDefect> = emptySet()): ChaosPortsHandle {
    val handle = ChaosPortsHandle()
    val noSpace = { TransferStart.Refused(TransferRefusal.NO_SPACE) }

    bench.wrapMover = { inner ->
      AlbumMover { key, items, dest, leave, confirmation ->
        observer.onTransfer(key, confirmation)
        injector.around<TransferStart>(Surface.MOVER, noSpace) { inner.move(key, items, dest, leave, confirmation) }
      }.also { handle.mover = it }
    }
    bench.wrapUploader = { inner ->
      AlbumUploader { key, media, dest: ShareFolder, confirmation ->
        observer.onTransfer(key, confirmation)
        injector.around<TransferStart>(Surface.UPLOADER, noSpace) { inner.upload(key, media, dest, confirmation) }
      }.also { handle.uploader = it }
    }
    bench.wrapTickets = { inner ->
      object : TicketStatus {
        override suspend fun status(ticket: TransferTicket): TicketState = injector.around(Surface.TICKETS) { inner.status(ticket) }

        override suspend fun find(key: TransferKey): TransferTicket? =
          injector.around(Surface.TICKETS, deathOnly = KnownDefect.FIND_ERROR_IS_ABSENT in avoid) { inner.find(key) }

        override suspend fun cancel(ticket: TransferTicket) = injector.around(Surface.TICKETS) { inner.cancel(ticket) }
      }.also { handle.tickets = it }
    }
    bench.wrapPublisher = { inner ->
      object : AlbumPublisher {
        override suspend fun stage(album: AlbumId, changes: List<SharedAlbumChange>): StageTicket {
          observer.onStage(changes)
          return injector.around(Surface.PUBLISHER) { inner.stage(album, changes) }
        }

        override suspend fun flush(album: AlbumId, reason: FlushReason): PublishOutcome {
          val outcome = injector.around(Surface.PUBLISHER) { inner.flush(album, reason) }
          if (outcome is PublishOutcome.Published) observer.onPublished()
          return outcome
        }

        override suspend fun publishedUpTo(album: AlbumId): StageTicket = injector.around(Surface.PUBLISHER) { inner.publishedUpTo(album) }

        override val pending: StateFlow<Int> get() = inner.pending
      }.also { handle.publisher = it }
    }
    bench.wrapSafeCopies = { inner -> SafeCopyRequester { media -> injector.around(Surface.SAFE_COPIES) { inner.request(media) } } }
    bench.wrapOrigins = { inner ->
      object : SharedOrigins {
        override suspend fun record(media: MediaId, origin: ShareFolder) = injector.around(Surface.ORIGINS) { inner.record(media, origin) }

        override suspend fun originOf(media: MediaId): ShareFolder? = injector.around(Surface.ORIGINS) { inner.originOf(media) }
      }
    }
    bench.wrapFacts = { inner ->
      object : CatalogFacts {
        override suspend fun facts(media: Collection<MediaId>): Map<MediaId, MediaFacts> = injector.around(Surface.FACTS) { inner.facts(media) }

        override suspend fun attest(items: Collection<AttestRequest>): Map<ContentKey, MediaId> = injector.around(Surface.FACTS) { inner.attest(items) }
      }
    }
    bench.rewire()
    return handle
  }
}
