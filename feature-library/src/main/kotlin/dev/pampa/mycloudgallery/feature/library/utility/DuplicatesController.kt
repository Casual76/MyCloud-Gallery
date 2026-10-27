package dev.pampa.mycloudgallery.feature.library.utility

import android.content.IntentSender
import dev.pampa.mycloudgallery.core.library.duplicates.BlockReason
import dev.pampa.mycloudgallery.core.library.duplicates.CleanupPlan
import dev.pampa.mycloudgallery.core.library.duplicates.CopyArea
import dev.pampa.mycloudgallery.core.library.duplicates.GroupKey
import dev.pampa.mycloudgallery.core.library.duplicates.GroupProposal
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalCleanup
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalDuplicates
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalGroup
import dev.pampa.mycloudgallery.core.library.duplicates.IdenticalStatus
import dev.pampa.mycloudgallery.core.library.duplicates.NasTrashRef
import dev.pampa.mycloudgallery.core.library.duplicates.PhoneTrashRef
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarCluster
import dev.pampa.mycloudgallery.core.library.duplicates.SimilarShots
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaIntents
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.trash.UserTap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * La logica dei **Doppioni** (D25, D27; `docs/fase7-progetto.md` §2.1.1), senza Android a parte `IntentSender`.
 *
 * **Identici**: le copie dalla stessa parte con lo stesso contenuto, e per ognuno la proposta (una copia da tenere per parte, nell'ordine di D25, e
 * i "mai"). "Rivedi" cambia la copia da tenere ([keepCopy]); le regole valgono lo stesso. **Simili**: scatti diversi ma quasi uguali, dello stesso
 * giorno e a meno di 10 minuti, trovati a lotti dal giorno piu' recente ([SimilarShots.scan]); di ogni gruppo la migliore resta, e l'utente sceglie
 * ([toggleShot]); preferite e foto in un album non sono mai preselezionate per l'eliminazione (E-U3).
 *
 * **Niente qui cancella da solo.** La pulizia ([prepareIdentical], [prepareSimilar]) costruisce un piano e apre il **foglio di eliminazione** ([CleanupSheet]);
 * solo [confirm], con la prova del tocco di conferma che il foglio gli consegna ([UserTap]: qui non nasce, R18), manda le copie nei **cestini**: le copie del
 * NAS al cestino del NAS (30 giorni) con la porta [CleanupNasPort] (`NasOperations` in `:app`), quelle del telefono con il dialogo di sistema. Mai una
 * cancellazione definitiva. Se il NAS non accetta, il telefono non si tocca.
 *
 * **Il tocco rifa' il piano** (R5): fra la lettura della pagina e il tocco le copie cambiano (una tolta dal PC, una spostata nell'area comune da
 * un'altra condivisione). [confirm] chiede di nuovo il piano al catalogo e tiene solo cio' che il piano di adesso toglie ancora, con la stessa copia da
 * tenere; le copie del telefono si ricontrollano riga per riga (R18) prima del dialogo di sistema.
 *
 * Gli identificativi dei media non finiscono mai in un log: questa classe non ne scrive.
 */
class DuplicatesController(
  private val identical: IdenticalDuplicates,
  private val similar: SimilarShots,
  private val cleanupNas: CleanupNasPort,
  private val intents: PhoneMediaIntents,
  private val fullHash: FullHashSwitch,
  private val scope: CoroutineScope,
  private val batchBudget: Duration = 400.milliseconds,
  private val phoneCheck: PhoneCopyCheck = NoPhoneCopyCheck,
  /** DP-1: quanto dura al piu' il giro che porta i file uguali fra cartelle del NAS a essere doppioni, all'apertura della pagina. */
  private val settleBudget: Duration = 2.seconds,
  /** DP-4: quanto dura al piu' la firma degli scatti recenti all'apertura della scheda Simili. */
  private val signBudget: Duration = 1500.milliseconds,
  /** DP-3: la pagina si rilegge cosi' tempo dopo che il catalogo ha smesso di cambiare. */
  private val changesDebounce: Duration = 1.seconds,
) {

  private val mutable = MutableStateFlow(DuplicatesState(nightly = NightlyView(available = fullHash !== NoFullHashSwitch)))

  /** Cosa mostrare. */
  val state: StateFlow<DuplicatesState> = mutable.asStateFlow()

  private var scanned: List<GroupProposal> = emptyList()
  private val keepChoices = LinkedHashMap<GroupKey, LocationId>()
  private var identicalJob: Job? = null
  private var similarJob: Job? = null
  private var nightlyJob: Job? = null
  private var changesJob: Job? = null

  /** Le scelte dell'utente sugli scatti: per gruppo (la chiave del gruppo), i media da eliminare. Sopravvivono a una nuova ricerca. */
  private val shotChoices = HashMap<String, Set<MediaId>>()

  /**
   * DP-3: le copie che una pulizia accettata ha gia' mandato nei cestini ma che il catalogo vede ancora (si aggiorna a operazione chiusa, con la
   * scansione): non si mostrano. Escono da qui quando una lettura non le trova piu'; se l'operazione non riesce tornano alla riapertura della pagina.
   */
  private val removedLocations = HashSet<LocationId>()

  /** Le copie del telefono del dialogo di sistema in corso: se l'utente conferma, escono dalla vista come quelle del NAS. */
  private var phonePending: Set<LocationId> = emptySet()

  /** I gruppi e i media del foglio aperto: il tocco di conferma ne rifa' il piano (R5). */
  private var sheetProposals: List<GroupProposal> = emptyList()
  private var sheetMedia: List<MediaId> = emptyList()

  /** Comincia: i due elenchi e l'interruttore. Idempotente. */
  fun start() {
    if (nightlyJob == null && fullHash !== NoFullHashSwitch) {
      nightlyJob = scope.launch { fullHash.enabled.collect { on -> mutable.update { it.copy(nightly = NightlyView(available = true, on = on)) } } }
    }
    if (identicalJob == null) refreshIdentical(settle = true)
    if (similarJob == null) scanSimilar()
    if (changesJob == null) changesJob = scope.launch { watchCatalog() }
  }

  /** DP-3: quando il catalogo cambia (un'operazione si chiude, la scansione del NAS finisce) i gruppi si rileggono. */
  @OptIn(FlowPreview::class)
  private suspend fun watchCatalog() {
    identical.changes().debounce(changesDebounce).collect {
      if (!mutable.value.busy && mutable.value.sheet == null) refreshIdentical()
    }
  }

  /** Ferma tutto (la pagina e' chiusa). */
  fun close() {
    identicalJob?.cancel()
    similarJob?.cancel()
    nightlyJob?.cancel()
    changesJob?.cancel()
    identicalJob = null
    similarJob = null
    nightlyJob = null
    changesJob = null
    removedLocations.clear()
  }

  fun selectTab(tab: DuplicatesTab) {
    mutable.update { it.copy(tab = tab) }
  }

  fun dismissNotice() {
    mutable.update { it.copy(notice = null) }
  }

  // --- Identici ---------------------------------------------------------------------------------------------------------------

  /** Rilegge i doppioni identici (anche dopo una pulizia). */
  fun refreshIdentical() = refreshIdentical(settle = false)

  /** [settle]: prima il giro dei file uguali fra cartelle del NAS (DP-1), che mette in coda l'hash completo e unisce cio' che e' provato uguale. */
  private fun refreshIdentical(settle: Boolean) {
    identicalJob?.cancel()
    mutable.update { it.copy(identical = it.identical.copy(scanning = true, failed = false)) }
    identicalJob = scope.launch {
      if (settle) {
        try {
          identical.settle(settleBudget)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          // Un giro che non riesce non toglie la lettura: i doppioni gia' provati si mostrano lo stesso.
        }
      }
      val scan = try {
        identical.scan()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        mutable.update { it.copy(identical = IdenticalView(scanning = false, failed = true)) }
        return@launch
      }
      scanned = scan.proposals
      // Una scelta di "Rivedi" su un gruppo sparito non conta piu'; una copia che il catalogo non ha piu' esce dall'elenco delle tolte (DP-3).
      val live = scanned.map { it.group.key }.toSet()
      keepChoices.keys.retainAll(live)
      val present = scanned.flatMapTo(HashSet()) { p -> p.group.copies.map { it.location } }
      removedLocations.retainAll(present)
      publishIdentical(scanning = false, failed = scan.failed, verifying = scan.verifying, restricted = scan.restricted)
    }
  }

  private fun publishIdentical(
    scanning: Boolean,
    failed: Boolean,
    verifying: Int = mutable.value.identical.verifying,
    restricted: Boolean = mutable.value.identical.restricted,
  ) {
    val groups = scanned.mapNotNull(::shownProposal)
    mutable.update { it.copy(identical = IdenticalView(scanning = scanning, failed = failed, groups = groups, verifying = verifying, restricted = restricted)) }
  }

  /** La proposta di [p] come la vede l'utente: senza le copie gia' mandate nei cestini (DP-3), con la copia da tenere che ha scelto ("Rivedi"). */
  private fun shownProposal(p: GroupProposal): GroupProposal? {
    val choice = keepChoices[p.group.key]
    val rest = if (removedLocations.isEmpty()) p.group.copies else p.group.copies.filter { it.location !in removedLocations }
    if (rest.size == p.group.copies.size) return if (choice == null) p else IdenticalCleanup.propose(p.group, choice)
    if (rest.size < 2) return null
    val g = p.group
    return IdenticalCleanup.propose(IdenticalGroup(g.media, g.version, g.part, g.fullHash, rest, g.safe, g.isVideo), choice)
  }

  /** "Rivedi": la copia da tenere del gruppo [key] e' [copy]. Le regole "mai" valgono lo stesso; una scelta che non e' del gruppo non cambia niente. */
  fun keepCopy(key: GroupKey, copy: LocationId) {
    val proposal = scanned.firstOrNull { it.group.key == key } ?: return
    // Una copia nell'area di un altro account non si sceglie (E-U1), e una scelta che non e' del gruppo non cambia niente: la precedente resta.
    if (proposal.group.copies.none { it.location == copy && it.area != CopyArea.OTHERS }) return
    keepChoices[key] = copy
    publishIdentical(scanning = mutable.value.identical.scanning, failed = false)
  }

  /** Torna alla proposta di partenza per il gruppo [key]. */
  fun resetKeep(key: GroupKey) {
    if (keepChoices.remove(key) != null) publishIdentical(scanning = mutable.value.identical.scanning, failed = false)
  }

  // --- Simili -----------------------------------------------------------------------------------------------------------------

  /** Cerca i doppioni simili a lotti, dal giorno piu' recente, mostrando i gruppi man mano. */
  fun scanSimilar() {
    similarJob?.cancel()
    mutable.update { it.copy(similar = SimilarView(scanning = true)) }
    similarJob = scope.launch {
      // DP-4: le firme degli scatti di oggi non aspettano la notte. Un lavoro breve all'apertura, poi la ricerca; quello che resta (pixel non ancora
      // leggibili, tempo finito) lo fa la manutenzione di notte, e la scheda lo dice invece di restare muta.
      val signing = try {
        similar.sign(signBudget)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      val later = signing != null && (!signing.finished || signing.waiting > 0)
      var before: Int? = null
      var undecided = 0
      val found = ArrayList<ClusterView>()
      do {
        val batch = try {
          similar.scan(before, batchBudget)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          mutable.update { it.copy(similar = SimilarView(scanning = false, failed = true, clusters = found.toList(), undecided = undecided, signingLater = later)) }
          return@launch
        }
        if (batch.failed) {
          mutable.update { it.copy(similar = SimilarView(scanning = false, failed = true, clusters = found.toList(), undecided = undecided, signingLater = later)) }
          return@launch
        }
        undecided += batch.undecided
        batch.clusters.mapTo(found) { cluster -> viewOf(cluster) }
        before = batch.nextBeforeDay
        mutable.update { it.copy(similar = SimilarView(scanning = before != null, clusters = found.toList(), undecided = undecided, signingLater = later)) }
      } while (before != null)
    }
  }

  private fun viewOf(cluster: SimilarCluster): ClusterView {
    val previous = shotChoices[cluster.key]?.filterTo(LinkedHashSet()) { it in cluster.media }
    // Una scelta ricordata vale solo se lascia almeno uno scatto; se no si riparte dalla proposta.
    val remove = previous?.takeIf { it.size < cluster.media.size } ?: cluster.removeByDefault
    return ClusterView(cluster, remove)
  }

  /**
   * Tocca uno scatto di un gruppo: da tenere diventa da eliminare e viceversa. **In un gruppo almeno uno resta**: togliere l'ultimo scatto da
   * tenere non si puo' ([DuplicatesNotice.LAST_SHOT_STAYS]). Una preferita o una foto in un album si elimina solo se l'utente la sceglie cosi' (E-U3).
   */
  fun toggleShot(clusterKey: String, media: MediaId) {
    val current = mutable.value.similar
    val index = current.clusters.indexOfFirst { it.cluster.key == clusterKey }
    if (index < 0) return
    val view = current.clusters[index]
    if (media !in view.cluster.media) return
    val next = if (media in view.remove) view.remove - media else view.remove + media
    if (next.size >= view.cluster.media.size) {
      mutable.update { it.copy(notice = DuplicatesNotice.LAST_SHOT_STAYS) }
      return
    }
    shotChoices[clusterKey] = next
    val clusters = current.clusters.toMutableList().also { it[index] = view.copy(remove = next) }
    mutable.update { it.copy(similar = current.copy(clusters = clusters)) }
  }

  /** Il gruppo [clusterKey] torna alla proposta di partenza. */
  fun resetCluster(clusterKey: String) {
    val current = mutable.value.similar
    val index = current.clusters.indexOfFirst { it.cluster.key == clusterKey }
    if (index < 0) return
    shotChoices.remove(clusterKey)
    val view = current.clusters[index]
    val clusters = current.clusters.toMutableList().also { it[index] = view.copy(remove = view.cluster.removeByDefault) }
    mutable.update { it.copy(similar = current.copy(clusters = clusters)) }
  }

  // --- Il foglio di eliminazione ----------------------------------------------------------------------------------------------

  /** Prepara la pulizia dei doppioni identici proposti e apre il foglio. Non cancella niente. */
  fun prepareIdentical() {
    if (mutable.value.busy) return
    mutable.update { it.copy(busy = true) }
    scope.launch {
      try {
        val view = mutable.value.identical
        val raw = identical.plan(view.cleanable)
        val plan = withoutPhoneIfNoTrash(raw)
        if (plan.isEmpty) {
          mutable.update { it.copy(notice = if (raw.isEmpty) DuplicatesNotice.NOTHING_TO_CLEAN else DuplicatesNotice.NO_PHONE_TRASH) }
          return@launch
        }
        // I doppioni identici: una sola scelta. Solo telefono se il NAS non c'e'; altrimenti "ovunque" = le copie di ogni posto da togliere.
        val scopeOf = if (plan.nas.isEmpty()) CleanupScope.PHONE_ONLY else CleanupScope.EVERYWHERE
        val (phoneKept, phoneLeft) = limitPhone(plan)
        sheetProposals = view.cleanable
        mutable.update {
          it.copy(
            sheet = CleanupSheet(CleanupSource.IDENTICAL, mapOf(scopeOf to phoneKept), scopeOf, othersAreasOf(view.groups), phoneLeft),
            // Senza il cestino di sistema il foglio ha solo il NAS: la pagina dice perche' le copie del telefono non ci sono.
            notice = if (plan.phone.size < raw.phone.size) DuplicatesNotice.NO_PHONE_TRASH else it.notice,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        mutable.update { it.copy(notice = DuplicatesNotice.CLEANUP_FAILED) }
      } finally {
        mutable.update { it.copy(busy = false) }
      }
    }
  }

  /** Prepara la pulizia degli scatti simili scelti e apre il foglio ("Solo dal telefono" / "Ovunque"). Non cancella niente. */
  fun prepareSimilar() {
    if (mutable.value.busy) return
    val media = mutable.value.similar.clusters.flatMap { it.remove }.distinct()
    if (media.isEmpty()) return
    mutable.update { it.copy(busy = true) }
    scope.launch {
      try {
        val places = similar.placesOf(media)
        val phoneOnly = withoutPhoneIfNoTrash(places.phoneOnly)
        val everywhere = withoutPhoneIfNoTrash(places.everywhere)
        if (everywhere.isEmpty) {
          mutable.update { it.copy(notice = if (places.everywhere.isEmpty) DuplicatesNotice.NOTHING_TO_CLEAN else DuplicatesNotice.NO_PHONE_TRASH) }
          return@launch
        }
        val (phoneOnlyKept, left) = limitPhone(phoneOnly)
        val (everywhereKept, _) = limitPhone(everywhere)
        // Senza copie sul telefono "Solo dal telefono" non fa niente: resta solo "Ovunque".
        val plans = LinkedHashMap<CleanupScope, CleanupPlan>()
        if (!phoneOnly.isEmpty) plans[CleanupScope.PHONE_ONLY] = phoneOnlyKept
        plans[CleanupScope.EVERYWHERE] = everywhereKept
        val chosen = if (CleanupScope.PHONE_ONLY in plans) CleanupScope.PHONE_ONLY else CleanupScope.EVERYWHERE
        sheetMedia = media
        mutable.update {
          it.copy(
            sheet = CleanupSheet(CleanupSource.SIMILAR, plans, chosen, phoneLeft = left),
            notice = if (everywhere.phone.size < places.everywhere.phone.size) DuplicatesNotice.NO_PHONE_TRASH else it.notice,
          )
        }
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        mutable.update { it.copy(notice = DuplicatesNotice.CLEANUP_FAILED) }
      } finally {
        mutable.update { it.copy(busy = false) }
      }
    }
  }

  /** Le share delle copie nell'area di un altro account (E-U1) dei gruppi che la pulizia tocca: si dicono nel foglio, non si toccano. */
  private fun othersAreasOf(groups: List<GroupProposal>): List<String?> =
    groups.filter { it.status == IdenticalStatus.CLEANABLE }
      .flatMap { p -> p.blocked.filter { it.reason == BlockReason.OTHER_AREA }.map { it.copy.share } }

  /**
   * Il cestino di sistema c'e' da Android 11. Prima, le copie del telefono non si possono mandare da qui (e niente le cancellerebbe da sole): restano
   * dove sono, il foglio ha il solo NAS e la pagina lo dice ([DuplicatesNotice.NO_PHONE_TRASH]).
   */
  private fun withoutPhoneIfNoTrash(plan: CleanupPlan): CleanupPlan =
    if (intents.canTrash || plan.phone.isEmpty()) plan else CleanupPlan(plan.groups, emptyList(), plan.nas, 0, plan.skipped + plan.phone.size)

  /** Un dialogo di sistema porta al massimo [PhoneMediaIntents.MAX_REQUEST_URIS] elementi: il resto resta per un altro giro, e il foglio lo dice. */
  private fun limitPhone(plan: CleanupPlan): Pair<CleanupPlan, Int> {
    val max = PhoneMediaIntents.MAX_REQUEST_URIS
    if (plan.phone.size <= max) return plan to 0
    return CleanupPlan(plan.groups, plan.phone.take(max), plan.nas, plan.unsafePhone, plan.skipped) to (plan.phone.size - max)
  }

  fun chooseScope(scope: CleanupScope) {
    mutable.update { state -> state.sheet?.takeIf { scope in it.plans }?.let { state.copy(sheet = it.copy(chosen = scope)) } ?: state }
  }

  fun dismissSheet() {
    mutable.update { it.copy(sheet = null) }
  }

  /**
   * L'utente ha toccato "Elimina" sul foglio e [tap] e' la prova di quel tocco (nata nel foglio, dopo il tempo minimo dalla sua comparsa: qui non si
   * fabbrica). Il piano si **rifa' sui dati di adesso** (R5): quello che il catalogo non propone piu' non parte. Le copie del NAS vanno nel cestino del NAS
   * (se il NAS non accetta non si fa niente altro); per le copie del telefono, ricontrollata riga per riga (R18), si restituisce la richiesta del dialogo
   * di sistema, che sta anche in [DuplicatesState.phoneRequest] (DP-7: il lavoro gira nello scope del ViewModel e sopravvive a una rotazione). Null se non
   * c'e' niente da chiedere al sistema (solo NAS, o niente).
   */
  suspend fun confirm(tap: UserTap): IntentSender? = scope.async { runConfirm(tap) }.await()

  private suspend fun runConfirm(tap: UserTap): IntentSender? {
    val sheet = mutable.value.sheet ?: return null
    if (mutable.value.busy) return null
    mutable.update { it.copy(busy = true) }
    try {
      val plan = replanned(sheet)
      if (plan.isEmpty) {
        mutable.update { it.copy(sheet = null, notice = DuplicatesNotice.NOTHING_TO_CLEAN) }
        return null
      }
      var notice: DuplicatesNotice? = null
      if (plan.nas.isNotEmpty()) {
        val outcome = try {
          cleanupNas.trash(plan.nas, tap)
        } catch (e: CancellationException) {
          throw e
        } catch (_: Exception) {
          NasTrashOutcome.FAILED
        }
        if (outcome != NasTrashOutcome.STARTED) {
          mutable.update { it.copy(sheet = null, notice = noticeFor(outcome)) }
          return null
        }
        notice = DuplicatesNotice.CLEANUP_STARTED
        // DP-3: le copie accettate escono subito dalla vista; la scansione del catalogo le togliera' davvero a operazione chiusa.
        removedLocations += plan.nas.mapNotNull { it.location }
        publishIdentical(scanning = mutable.value.identical.scanning, failed = false)
      }
      // R18: le copie del telefono sono ancora quelle del piano (stessa riga, stesso timbro)? Una sovrascritta dopo la lettura non e' piu' un doppione.
      val phone = if (plan.phone.isEmpty()) emptyList() else unchangedPhone(plan.phone)
      if (phone.size < plan.phone.size) notice = DuplicatesNotice.PHONE_CHANGED
      val request = if (phone.isEmpty()) null else intents.trashRequest(phone.map { it.ref }, trash = true)
      if (phone.isNotEmpty() && request == null) {
        // Le copie del NAS, se c'erano, sono gia' partite (e restano nei cestini): l'avviso non dice "non e' stato toccato niente".
        mutable.update { it.copy(sheet = null, notice = if (plan.nas.isNotEmpty()) (notice ?: DuplicatesNotice.CLEANUP_STARTED) else DuplicatesNotice.CLEANUP_FAILED) }
        if (plan.nas.isNotEmpty()) rescan()
        return null
      }
      phonePending = phone.mapNotNullTo(HashSet()) { it.location }
      mutable.update { it.copy(sheet = null, notice = notice ?: it.notice, phoneRequest = request) }
      // Solo NAS: la pulizia e' partita, la pagina si rilegge; con il telefono si rilegge a risposta del dialogo di sistema.
      if (request == null) rescan()
      return request
    } finally {
      mutable.update { it.copy(busy = false) }
    }
  }

  /**
   * Il piano di [sheet] rifatto sul catalogo di adesso (R5): le copie del NAS e del telefono del foglio che il piano di adesso toglie ancora, con lo
   * stesso timbro. Se il piano non si riesce a rifare, niente parte (fallire chiusi).
   */
  private suspend fun replanned(sheet: CleanupSheet): CleanupPlan {
    val shown = sheet.plan
    val fresh = try {
      when (sheet.source) {
        CleanupSource.IDENTICAL -> identical.plan(sheetProposals)
        CleanupSource.SIMILAR -> sheetMedia.let { media -> similar.placesOf(media).let { if (sheet.chosen == CleanupScope.PHONE_ONLY) it.phoneOnly else it.everywhere } }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      return CleanupPlan.EMPTY
    }
    val nas = shown.nas.filter { s -> fresh.nas.any { f -> sameNas(s, f) } }
    val phone = shown.phone.filter { s -> fresh.phone.any { f -> samePhone(s, f) } }
    return CleanupPlan(shown.groups, phone, nas, shown.unsafePhone, shown.skipped + (shown.copies - nas.size - phone.size))
  }

  private fun sameNas(a: NasTrashRef, b: NasTrashRef): Boolean =
    a.share == b.share && a.path == b.path && a.size == b.size && a.modifiedAt == b.modifiedAt && a.fileId == b.fileId

  private fun samePhone(a: PhoneTrashRef, b: PhoneTrashRef): Boolean =
    a.ref.volume == b.ref.volume && a.ref.mediaStoreId == b.ref.mediaStoreId && b.stamp.looksUnchangedFrom(a.stamp)

  /** Le copie del telefono che la riga del MediaStore dice ancora quelle del piano. Se non si sa, nessuna: fallire chiusi. */
  private suspend fun unchangedPhone(copies: List<PhoneTrashRef>): List<PhoneTrashRef> = try {
    phoneCheck.unchanged(copies)
  } catch (e: CancellationException) {
    throw e
  } catch (_: Exception) {
    emptyList()
  }

  /** Il dialogo di sistema e' stato lanciato (o la richiesta non serve piu'): lo stato la dimentica. */
  fun phoneRequestLaunched() {
    mutable.update { it.copy(phoneRequest = null) }
  }

  /** Il dialogo di sistema ha risposto: se l'utente ha confermato, le copie sono nel cestino e la pagina si rilegge. */
  fun onPhoneResult(confirmed: Boolean) {
    if (confirmed) {
      removedLocations += phonePending
      rescan()
    }
    phonePending = emptySet()
  }

  private fun rescan() {
    refreshIdentical()
    scanSimilar()
  }

  private fun noticeFor(outcome: NasTrashOutcome): DuplicatesNotice = when (outcome) {
    NasTrashOutcome.STARTED -> DuplicatesNotice.CLEANUP_STARTED
    NasTrashOutcome.REJECTED -> DuplicatesNotice.CLEANUP_REJECTED
    NasTrashOutcome.BLOCKED -> DuplicatesNotice.CLEANUP_BLOCKED
    NasTrashOutcome.FAILED -> DuplicatesNotice.CLEANUP_FAILED
  }

  // --- L'interruttore dell'hash completo notturno (D27) ---------------------------------------------------------------------

  /** Accende o spegne "Controlla di notte i video del NAS". La scelta e' una voce personale sincronizzata; lo stato segue la porta. */
  fun setNightly(on: Boolean) {
    if (fullHash === NoFullHashSwitch) return
    scope.launch {
      try {
        fullHash.set(on)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // L'interruttore mostra sempre cio' che la porta dice: se non ha salvato, resta com'era, e la pagina lo dice (DP-5: prima non faceva ne' diceva niente).
        mutable.update { it.copy(notice = DuplicatesNotice.NIGHTLY_NOT_SAVED) }
      }
    }
  }
}
