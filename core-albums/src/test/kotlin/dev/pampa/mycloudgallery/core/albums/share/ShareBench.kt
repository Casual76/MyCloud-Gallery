package dev.pampa.mycloudgallery.core.albums.share

import dev.pampa.mycloudgallery.core.albums.contract.AlbumMover
import dev.pampa.mycloudgallery.core.albums.contract.AlbumPublisher
import dev.pampa.mycloudgallery.core.albums.contract.AlbumUploader
import dev.pampa.mycloudgallery.core.albums.contract.CatalogFacts
import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.MediaPlace
import dev.pampa.mycloudgallery.core.albums.contract.SafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmationIssuer
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemRecord
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemState
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.albums.contract.SharedOrigins
import dev.pampa.mycloudgallery.core.albums.contract.TicketStatus
import dev.pampa.mycloudgallery.core.albums.shared.AlbumDevice
import dev.pampa.mycloudgallery.core.albums.shared.AlbumWorld
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.albums.testing.FakeMedia
import dev.pampa.mycloudgallery.core.albums.testing.FakeSafeCopyRequester
import dev.pampa.mycloudgallery.core.albums.testing.FakeSharedOrigins
import dev.pampa.mycloudgallery.core.albums.testing.InMemoryShareDiary
import dev.pampa.mycloudgallery.core.albums.testing.SimulatedDeath
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.nasops.contract.MoveCompletionSink
import dev.pampa.mycloudgallery.core.trust.data.AlbumValue
import java.util.Locale
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CancellationException

/**
 * IL BANCO DELLA CONDIVISIONE (P4; docs/fase7-progetto.md §2.5, §2.6, §5.5): l'orchestratore vero ([ShareOrchestrator]) con
 * - il motore vero delle operazioni di core-nasops (con la porta di completamento) sul NAS finto o sul simulatore SMB ([ShareNasTarget]);
 * - il pubblicatore, la replica e gli album condivisi veri di P2 ([AlbumWorld]: famiglia firmata, area comune degli album), con un secondo account
 *   ([other]) che legge cio' che vedono i familiari;
 * - il catalogo finto ([library]) che la porta di completamento aggiorna (stesso `MediaId`, D29) e il caricamento dal telefono del banco.
 *
 * [restart] e' la morte del processo: orchestratore, motore delle operazioni e dispositivo degli album si ricostruiscono sugli stessi diari, store,
 * NAS e catalogo (la conferma in memoria si perde). [violations] controlla gli invarianti del §5.5 a meta' o alla fine.
 */
class ShareBench(newTarget: (MoveCompletionSink) -> ShareNasTarget) : AutoCloseable {
  val world = AlbumWorld()
  val library = FakeAlbumLibrary(privateShare = ShareNasTarget.PRIVATE, commonShare = ShareNasTarget.COMMON)
  val me: AlbumDevice = world.device("test", library = library)
  val other: AlbumDevice = world.device("test2", library = FakeAlbumLibrary(privateShare = "test2", commonShare = ShareNasTarget.COMMON))
  val completions = LibraryCompletions(library)
  val target: ShareNasTarget = newTarget(completions)
  val phone = LinkedHashMap<MediaId, ByteArray>()
  val mover = NasAlbumMover(target) { world.clocks.monotonicMs() }
  val uploader = PhoneUploader(target, library, phone) { world.clocks.monotonicMs() }
  val tickets = BenchTickets(mover, uploader)
  val diary = InMemoryShareDiary()
  val origins = FakeSharedOrigins()
  val safeCopies = FakeSafeCopyRequester()

  /** I contenuti all'inizio, per media (invarianti 1-4) e dove stavano. */
  val originals = LinkedHashMap<MediaId, ByteArray>()
  private val elsewhere = HashSet<MediaId>()
  private val verifiedEntries = HashSet<String>()

  /** Le porte avvolte (guasti del banco): di serie quelle vere. */
  var wrapMover: (AlbumMover) -> AlbumMover = { it }
  var wrapUploader: (AlbumUploader) -> AlbumUploader = { it }
  var wrapTickets: (TicketStatus) -> TicketStatus = { it }
  var wrapPublisher: (AlbumPublisher) -> AlbumPublisher = { it }
  var wrapSafeCopies: (SafeCopyRequester) -> SafeCopyRequester = { it }
  var wrapOrigins: (SharedOrigins) -> SharedOrigins = { it }
  var wrapFacts: (CatalogFacts) -> CatalogFacts = { it }

  fun ports(): ShareOrchestratorPorts = ShareOrchestratorPorts(
    diary = diary,
    facts = wrapFacts(library),
    shared = me.shared,
    allowed = { album, op, media -> me.shared.allowed(album, op, media) },
    mover = wrapMover(mover),
    uploader = wrapUploader(uploader),
    tickets = wrapTickets(tickets),
    publisher = wrapPublisher(me.publisher),
    safeCopies = wrapSafeCopies(safeCopies),
    origins = wrapOrigins(origins),
    family = me.albumFamily,
    gate = me.gate,
    clocks = world.clocks,
    publishedId = { album, media ->
      me.replica.view(album)?.let { v -> media.takeIf { it in v.merged.items } ?: v.attested.entries.firstOrNull { it.value == media }?.key }
    },
    knownFolders = { me.replica.views.value.values.map { it.merged.folder }.toSet() },
    folderContents = { folder ->
      library.all().filter { it.place == MediaPlace.COMMON && it.path != null && it.path.substringBeforeLast('/', "").equals(folder.path.value, ignoreCase = true) }
        .map { it.id }.toSet()
    },
    lastPosition = { album -> me.replica.view(album)?.merged?.items?.values?.maxOfOrNull { it.position } },
    afterStage = { album -> me.replica.recompute(listOf(album)) },
  )

  var orchestrator: ShareOrchestrator = newOrchestrator()
    private set

  private var nonces = 0

  private fun newOrchestrator() = ShareOrchestrator(ports(), newNonce = { ByteArray(16) { (it * 11 + 97 * ++nonces).toByte() } })

  /** La morte del processo: tutto cio' che vive in memoria si ricostruisce; diari, store, NAS e catalogo restano. */
  suspend fun restart() {
    target.restart()
    me.restart()
    me.replica.recompute()
    orchestrator = newOrchestrator()
  }

  /** Le porte avvolte nuove (dopo aver cambiato i `wrap...`): un orchestratore nuovo, nello stesso processo. */
  fun rewire() {
    orchestrator = newOrchestrator()
  }

  // --- I media del banco ------------------------------------------------------------------------------------------------------------

  fun content(n: Int): ByteArray = ByteArray(64 + n * 7) { ((n * 131 + it * 17) xor (it shr 3)).toByte() }

  private fun seed(n: Int, place: MediaPlace, path: String?, hidden: Boolean, onPhone: Boolean, mine: Boolean, video: Boolean): MediaId {
    val bytes = content(n)
    val id = FakeAlbumLibrary.id(n)
    library.put(
      FakeMedia(
        id, BASE + n * 3_600_000L, place = place, onPhone = onPhone, mine = mine, path = path, size = bytes.size.toLong(), hidden = hidden,
        kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO,
      ),
    )
    originals[id] = bytes
    if (place == MediaPlace.OTHERS_PRIVATE) elsewhere += id
    when (place) {
      MediaPlace.MY_PRIVATE -> target.put(ShareNasTarget.PRIVATE, path!!, bytes)
      MediaPlace.COMMON -> target.put(ShareNasTarget.COMMON, path!!, bytes)
      MediaPlace.PHONE_ONLY -> phone[id] = bytes
      MediaPlace.OTHERS_PRIVATE -> Unit
    }
    return id
  }

  /** Un mio media nell'area privata, in `folder/<nome>` (il nome si puo' ripetere fra cartelle: E-SH4). */
  fun privateMedia(n: Int, folder: String = "Foto", name: String = "img_$n.jpg", hidden: Boolean = false, onPhone: Boolean = false, video: Boolean = false) =
    seed(n, MediaPlace.MY_PRIVATE, "$folder/$name", hidden, onPhone, mine = true, video = video)

  /** Un mio media solo sul telefono (E-SH1). */
  fun phoneMedia(n: Int, hidden: Boolean = false) = seed(n, MediaPlace.PHONE_ONLY, null, hidden, onPhone = true, mine = true, video = false)

  /** Un media gia' nell'area comune (E-SH11), mio o di un familiare. */
  fun commonMedia(n: Int, folder: String = "Famiglia", mine: Boolean = true, name: String = "c_$n.jpg") =
    seed(n, MediaPlace.COMMON, "$folder/$name", false, onPhone = false, mine = mine, video = false)

  /** Un media nell'area privata di un altro account: non si puo' aggiungere. */
  fun othersMedia(n: Int) = seed(n, MediaPlace.OTHERS_PRIVATE, "Foto/o_$n.jpg", false, onPhone = false, mine = false, video = false)

  /** E-D13, E-SH18: il file di [id] cambiato dal PC al suo posto (i byte nuovi diventano quelli "giusti" per gli invarianti). */
  fun modifyFromPc(id: MediaId, bytes: ByteArray) {
    val m = library[id]!!
    target.modify(if (m.place == MediaPlace.COMMON) ShareNasTarget.COMMON else ShareNasTarget.PRIVATE, m.path!!, bytes)
    originals[id] = bytes
  }

  // --- Piano, conferma, avvio ------------------------------------------------------------------------------------------------------

  suspend fun plan(request: ShareRequest): SharePlan = (orchestrator.plan(request) as? SharePlanOutcome.Ready)?.plan
    ?: error("piano non pronto: ${orchestrator.plan(request)}")

  fun confirm(plan: SharePlan) = ShareConfirmationIssuer.issue(plan, world.clocks.monotonicMs())

  suspend fun start(plan: SharePlan): ShareStartOutcome = orchestrator.start(plan, confirm(plan))

  /** Un album condiviso nuovo con [media], pianificato, confermato e avviato. */
  suspend fun share(title: String, media: List<MediaId>, hidden: HiddenChoice = HiddenChoice.LEAVE_OUT): SharePlan {
    val plan = plan(ShareRequest.NewAlbum(title, dev.pampa.mycloudgallery.core.model.ShareLevel.ADD, media, hidden))
    check(start(plan) is ShareStartOutcome.Started) { "condivisione non avviata" }
    return plan
  }

  // --- I giri ---------------------------------------------------------------------------------------------------------------------

  /** Un giro dei lavori: la ripresa della condivisione, quella delle operazioni di core-nasops, di nuovo la condivisione; poi passa il tempo. */
  suspend fun round() {
    orchestrator.resumeAll(1.hours)
    target.operations.resumeAll(1.hours)
    orchestrator.resumeAll(1.hours)
    world.clocks.advance(STEP_MS)
    target.advance(STEP_MS)
  }

  /** Giri finche' il diario della condivisione non ha piu' niente di aperto (al massimo [rounds]), poi i familiari leggono. */
  suspend fun drain(rounds: Int = 12) {
    repeat(rounds) {
      if (diary.openOps().isEmpty() && it > 0) return@repeat
      round()
    }
    world.settle(2)
  }

  /** Come [drain], ma un errore di un giro (una porta che lancia fuori dalle prove passeggere, il catalogo) non ferma il banco: il giro dopo riprende. */
  suspend fun drainTolerant(rounds: Int = 14) {
    repeat(rounds) {
      try {
        round()
      } catch (e: CancellationException) {
        throw e
      } catch (e: SimulatedDeath) {
        restart()
      } catch (e: Exception) {
        world.clocks.advance(STEP_MS)
        target.advance(STEP_MS)
      }
    }
    world.settle(2)
  }

  fun items(): List<ShareItemRecord> = diary.allItems()

  /** Il media del catalogo e il file che il NAS ha al suo percorso. */
  fun fileOf(id: MediaId): ByteArray? {
    val m = library[id] ?: return null
    val path = m.path ?: return null
    return when (m.place) {
      MediaPlace.COMMON -> target.bytes(ShareNasTarget.COMMON, path)
      MediaPlace.MY_PRIVATE -> target.bytes(ShareNasTarget.PRIVATE, path)
      else -> null
    }
  }

  /** Le voci `item:` vive del mio contributo, pubblicate (sul NAS degli album). */
  suspend fun publishedItems(album: AlbumId): Map<MediaId, AlbumValue.Item> {
    val own = me.publishStore.load(album) ?: return emptyMap()
    return own.published.values.mapNotNull { p ->
      val field = p.entry.field as? dev.pampa.mycloudgallery.core.trust.data.AlbumField.Item ?: return@mapNotNull null
      val value = p.entry.value as? AlbumValue.Item ?: return@mapNotNull null
      field.media to value
    }.toMap()
  }

  /** Gli elementi che il familiare [other] vede (dopo un giro della sua replica). */
  suspend fun seenByOther(album: AlbumId): List<MediaId> = other.shared.visibleItems(album)

  // --- Gli invarianti del §5.5 ------------------------------------------------------------------------------------------------------

  private fun visible(share: String): Map<String, ByteArray> =
    target.files(share).filterNot { it.lowercase(Locale.ROOT).startsWith(".mycloudgallery/") }.associateWith { target.bytes(share, it)!! }

  private fun trashed(share: String): List<ByteArray> =
    target.files(share).filter { it.lowercase(Locale.ROOT).startsWith(".mycloudgallery/trash/") && "/files/" in it.lowercase(Locale.ROOT) }
      .mapNotNull { target.bytes(share, it) }

  /**
   * Gli invarianti del §5.5 (1-6, 9 sempre; 7 e 8 alla fine): nessun file a meta' visibile; nessun doppione nell'area comune; nessuna perdita;
   * byte giusti; nessun elemento mancante in app (ogni media del catalogo ha una posizione che esiste davvero, con i suoi byte); nessuna voce
   * senza file; nessuna operazione di core-nasops senza una conferma della condivisione; il lavoro arriva in fondo; i nascosti lasciati fuori
   * restano dove sono. [hiddenLeftOut]: i media che un piano ha lasciato fuori.
   */
  suspend fun violations(final: Boolean, hiddenLeftOut: Set<MediaId> = emptySet(), transientOnly: Boolean = false): List<String> {
    val v = ArrayList<String>()
    val known = originals.values.map { it.toList() }.toSet()
    val private = visible(ShareNasTarget.PRIVATE)
    val common = visible(ShareNasTarget.COMMON)
    val trash = trashed(ShareNasTarget.PRIVATE) + trashed(ShareNasTarget.COMMON)
    for ((path, bytes) in private + common) if (bytes.toList() !in known) v += "1: file visibile non intero o sconosciuto ($path)"
    for (share in listOf(ShareNasTarget.PRIVATE, ShareNasTarget.COMMON)) {
      for (path in target.files(share).filter { it.lowercase(Locale.ROOT).startsWith(".mycloudgallery/tmp/") }) {
        if (final) v += "1: temporaneo rimasto ($share/$path)"
      }
    }
    for ((id, bytes) in originals) {
      if (id in elsewhere) continue
      val inCommon = common.values.count { it.contentEquals(bytes) }
      if (inCommon > 1) v += "2: ${inCommon} copie nell'area comune (${id.value.takeLast(4)})"
      val anywhere = (private.values + common.values).any { it.contentEquals(bytes) } || phone[id]?.contentEquals(bytes) == true
      val inTrash = trash.any { it.contentEquals(bytes) }
      if (!anywhere && !inTrash) v += "3: perso (${id.value.takeLast(4)})"
      if (inTrash && !anywhere) v += "3: solo nel cestino (${id.value.takeLast(4)})"
    }
    for ((id, bytes) in originals) {
      if (id in elsewhere) continue
      val m = library[id]
      if (m == null) {
        v += "5: media sparito dal catalogo (${id.value.takeLast(4)})"
        continue
      }
      if (m.place == MediaPlace.PHONE_ONLY || m.place == MediaPlace.OTHERS_PRIVATE) continue
      val file = fileOf(id)
      if (file == null) v += "5: il catalogo punta a un file che non c'e' (${id.value.takeLast(4)}, ${m.place.code})"
      else if (!file.contentEquals(bytes)) v += "4: byte diversi al posto del catalogo (${id.value.takeLast(4)})"
    }
    // 6. Una voce, quando compare pubblicata, rimanda a un file che c'e' nell'area comune con i byte giusti. Dopo puo' restare senza file (dal PC
    // si cancella, "Riporta" da un album chiuso: chi legge non la mostra, D9); alla fine, in un album attivo, ogni voce viva ha il suo file.
    for (album in me.publishStore.albums()) {
      val active = me.replica.view(album)?.merged?.state == dev.pampa.mycloudgallery.core.albums.contract.SharedState.ACTIVE
      for ((media, item) in publishedItems(album)) {
        val key = "${album.value}/${media.value}/${item.path.value}"
        if (key in verifiedEntries && !(final && active)) continue
        val bytes = target.bytes(ShareNasTarget.COMMON, item.path.value)
        val expected = originals[media]
        if (bytes == null) v += "6: voce pubblicata senza il file (${media.value.takeLast(4)})"
        else if (expected != null && !bytes.contentEquals(expected)) v += "6: voce pubblicata con un file diverso (${media.value.takeLast(4)})"
        else verifiedEntries += key
      }
    }
    for (id in hiddenLeftOut) {
      if (library[id]?.place != MediaPlace.MY_PRIVATE || fileOf(id)?.contentEquals(originals[id]!!) != true) v += "9: nascosto lasciato fuori ma spostato"
      if (common.values.any { it.contentEquals(originals[id]!!) }) v += "9: nascosto nell'area comune"
    }
    if (final) {
      val ops = target.store.ops(dev.pampa.mycloudgallery.core.nasops.contract.OpState.entries.toSet())
      for (op in ops) if (op.planHash !in mover.keys.values) v += "7: operazione di core-nasops senza una conferma della condivisione"
      val shareOps = diary.allOps().map { it.id.value }.toSet()
      for (key in mover.keys.keys) if (key.value.substringBefore(':') !in shareOps) v += "7: chiave di un trasferimento senza operazione nel diario"
      for (item in items()) {
        if (!item.state.terminal) v += "8: elemento non finito (#${item.index}, ${item.state.code})"
        if (transientOnly && item.state == ShareItemState.FAILED) v += "8: errore permanente per un guasto passeggero (${item.failure?.code})"
      }
    }
    return v
  }

  override fun close() = target.close()

  companion object {
    const val BASE = 1_767_225_600_000L
    const val STEP_MS = 6 * 60_000L
  }
}
