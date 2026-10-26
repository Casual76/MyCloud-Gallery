package dev.pampa.mycloudgallery.core.albums.testing

import dev.pampa.mycloudgallery.core.albums.contract.AlbumClocks
import dev.pampa.mycloudgallery.core.albums.contract.AlbumFamily
import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumSyncReport
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteBlock
import dev.pampa.mycloudgallery.core.albums.contract.AlbumWriteGate
import dev.pampa.mycloudgallery.core.albums.contract.NasItemRef
import dev.pampa.mycloudgallery.core.albums.contract.ProposalNote
import dev.pampa.mycloudgallery.core.albums.contract.RuleProposal
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareFolder
import dev.pampa.mycloudgallery.core.albums.contract.SharedAlbumSync
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.contract.SmartAlbumEngine
import dev.pampa.mycloudgallery.core.albums.contract.SmartNameInterpreter
import dev.pampa.mycloudgallery.core.albums.contract.SmartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SmartPreview
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.TicketState
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.contract.TransferKey
import dev.pampa.mycloudgallery.core.albums.contract.TransferStart
import dev.pampa.mycloudgallery.core.albums.contract.TransferTicket
import dev.pampa.mycloudgallery.core.common.featureflags.TestShares
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate
import kotlin.time.Duration

// I fake semplici degli album (Fase 7, P0): orologi, famiglia, cancello, guardiano, cartelle d'origine, porte che falliscono se chiamate,
// motore e interprete degli album smart finti. Stanno in `testing` (src/main), come gli altri moduli: mai nel dex di release
// (tools/check.ps1).

/** Gli orologi finti: monotono e ora del NAS, mossi a mano. */
class FakeAlbumClocks(var monotonic: Long = 10_000_000L, var nas: Long? = 1_760_000_000_000L) : AlbumClocks {
  override fun monotonicMs(): Long = monotonic

  override fun nasNowMs(): Long? = nas

  fun advance(ms: Long) {
    monotonic += ms
    nas = nas?.plus(ms)
  }
}

/** La famiglia finta: un account, le due aree, admin o no, chi e' uscito dal registro. */
class FakeAlbumFamily(
  var account: AccountId? = AccountId("test"),
  var common: String? = "test-comune",
  var private: String? = "test",
  var admin: Boolean = false,
  val gone: MutableSet<AccountId> = mutableSetOf(),
) : AlbumFamily {
  override fun me(): AccountId? = account

  override fun commonShare(): String? = common

  override fun privateShare(): String? = private

  override fun amAdmin(): Boolean = admin

  override fun leftFamily(account: AccountId): Boolean = account in gone

  override fun backupFolderName(): String = "Backup/questo-telefono"
}

/**
 * Il cancello finto (D30, D38): con [testSharesOnly] (come la release) ogni share fuori da quelle di prova e' [AlbumWriteBlock.TEST_SHARES_ONLY];
 * [blocks] aggiunge un blocco per share (congelamento, sola lettura...).
 */
class FakeAlbumWriteGate(var testSharesOnly: Boolean = true, val blocks: MutableMap<String, AlbumWriteBlock> = mutableMapOf()) : AlbumWriteGate {
  override suspend fun blockFor(share: String): AlbumWriteBlock? =
    blocks[share] ?: if (testSharesOnly && !TestShares.isTestShare(share)) AlbumWriteBlock.TEST_SHARES_ONLY else null
}

/** Il guardiano finto (D28): ricorda le richieste; [failNext] fa lanciare la prossima (non registrata). */
class FakeSafeCopyRequester : SafeCopyRequester {
  val requested: MutableList<MediaId> = mutableListOf()
  var failNext: Boolean = false

  override suspend fun request(media: List<MediaId>) {
    if (failNext) {
      failNext = false
      throw IllegalStateException("guardiano non raggiungibile")
    }
    media.forEach { if (it !in requested) requested += it }
  }
}

/** Le cartelle d'origine finte (D31): una mappa, come la voce personale `shared_origin`. */
class FakeSharedOrigins : SharedOrigins {
  val origins: MutableMap<MediaId, ShareFolder> = mutableMapOf()

  override suspend fun record(media: MediaId, origin: ShareFolder) {
    origins[media] = origin
  }

  override suspend fun originOf(media: MediaId): ShareFolder? = origins[media]
}

/**
 * Le porte che **falliscono se chiamate** (§4.3, C10, E-S11): un dato remoto falso (definizione d'album, indice, cestino) non deve mai
 * produrre una chiamata a [AlbumMover], [AlbumUploader], [SafeCopyRequester] o [TicketStatus]. Ogni chiamata lancia e si conta in [calls].
 */
class FailingAlbumPorts : AlbumMover, AlbumUploader, SafeCopyRequester, TicketStatus {
  var calls: Int = 0
    private set

  private fun fail(what: String): Nothing {
    calls++
    throw AssertionError("Porta chiamata senza un'azione locale confermata: $what")
  }

  override suspend fun move(key: TransferKey, items: List<NasItemRef>, dest: ShareFolder, leave: Set<NasItemRef>, confirmation: ShareConfirmation): TransferStart =
    fail("AlbumMover.move")

  override suspend fun upload(key: TransferKey, media: List<MediaId>, dest: ShareFolder, confirmation: ShareConfirmation): TransferStart =
    fail("AlbumUploader.upload")

  override suspend fun request(media: List<MediaId>) {
    fail("SafeCopyRequester.request")
  }

  override suspend fun status(ticket: TransferTicket): TicketState = fail("TicketStatus.status")

  override suspend fun find(key: TransferKey): TransferTicket? = fail("TicketStatus.find")

  override suspend fun cancel(ticket: TransferTicket) {
    fail("TicketStatus.cancel")
  }
}

/** L'interprete finto: restituisce [proposal] (o regole vuote con le note) e ricorda i titoli chiesti. */
class FakeSmartNameInterpreter(var proposal: RuleProposal? = null) : SmartNameInterpreter {
  val asked: MutableList<String> = mutableListOf()

  override suspend fun propose(title: String, today: LocalDate): RuleProposal {
    asked += title
    return proposal ?: RuleProposal(SmartRules(null, null, null), emptyList(), emptyList(), setOf(ProposalNote.NO_DATE, ProposalNote.NO_PLACE))
  }
}

/** Il motore finto degli album smart: esiti e anteprime scritti dal test; conta le valutazioni. */
class FakeSmartAlbumEngine(
  var outcome: SmartOutcome = SmartOutcome.Done(0, 0, 0),
  var nextPreview: SmartPreview = SmartPreview(0, 0, tooBroad = false, sample = emptyList(), searchUsed = false),
) : SmartAlbumEngine {
  val evaluated: MutableList<AlbumId> = mutableListOf()

  override suspend fun evaluate(id: AlbumId, budget: Duration): SmartOutcome {
    evaluated += id
    return outcome
  }

  override suspend fun preview(rules: SmartRules): SmartPreview = nextPreview
}

/**
 * La replica finta (§2.5 "Ricevere"): un giro legge il mondo condiviso finto e lo dice con i numeri. Con [ports] (di solito
 * [FailingAlbumPorts]) prova che leggere non avvia mai niente: non le chiama mai.
 */
class FakeSharedAlbumSync(private val world: FakeSharedWorld, @Suppress("unused") private val ports: FailingAlbumPorts? = null) : SharedAlbumSync {
  var rounds: Int = 0
    private set

  override suspend fun sync(budget: Duration): AlbumSyncReport {
    rounds++
    return AlbumSyncReport(world.albumIds().size, world.contributions(), world.rejectedContributions, world.denied, 0, incomplete = false)
  }
}
