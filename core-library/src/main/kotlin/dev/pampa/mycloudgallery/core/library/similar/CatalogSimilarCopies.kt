package dev.pampa.mycloudgallery.core.library.similar

import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.database.SeparateOutcome
import dev.pampa.mycloudgallery.core.database.SimilarCandidate
import dev.pampa.mycloudgallery.core.database.SimilarGroupInfo
import dev.pampa.mycloudgallery.core.database.SimilarGroupOutcome
import dev.pampa.mycloudgallery.core.database.SimilarMember
import dev.pampa.mycloudgallery.core.database.SimilarRun
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VisualSignature
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// Fase 5A, P15 (docs/fase5-progetto.md §2.12, D36, E-L10...E-L13): il rilevatore delle copie simili sul catalogo vero. Manutenzione a
// tappe e riprendibile: ogni giro scorre i candidati per `sort_time` dall'indice (`MediaCatalog.similarRuns`), firma solo quelli che
// hanno un compagno compatibile (`SignatureSource`), decide con il pianificatore puro e scrive con `groupSimilar` / `separate`. Il
// gruppo e' un dato derivato locale: ogni dispositivo lo ricalcola dagli stessi dati; nessuna azione sul NAS parte da qui.

/**
 * @param catalog il catalogo (letture e scritture dei gruppi: l'unico posto che scrive il grafo dei media).
 * @param source da dove vengono i pixel dei candidati (telefono: l'originale; NAS: la miniatura dell'indice; video: la porta di P7).
 * @param choices le scelte dell'utente ("Separa", copertina scelta): dati personali sincronizzati.
 * @param timeSource l'orologio **monotono** del budget (mai l'orologio da muro).
 * @param pageSize i candidati letti per pagina dall'indice (una pagina non spezza mai un secondo).
 * @param initiallySettled falso di serie: finche' un giro (o [refreshProgress]) non ha guardato, non si sa se c'e' qualcosa da
 *   confrontare, e il riepilogo del primo backup aspetta. Vero solo per le prove.
 * @param compute dove si fa il calcolo della firma (CPU: qualche millisecondo per foto): mai sul thread principale.
 */
class CatalogSimilarCopies(
  private val catalog: MediaCatalog,
  private val source: SignatureSource,
  private val choices: SimilarChoices,
  private val timeSource: TimeSource = TimeSource.Monotonic,
  private val pageSize: Int = PAGE_SIZE,
  initiallySettled: Boolean = false,
  private val compute: CoroutineDispatcher = Dispatchers.Default,
) : SimilarCopies {

  init {
    require(pageSize > 0) { "La pagina ha almeno un candidato." }
  }

  private val state = MutableStateFlow(if (initiallySettled) SimilarCopiesProgress.SETTLED else SimilarCopiesProgress(pending = UNKNOWN, running = false))
  override val progress: StateFlow<SimilarCopiesProgress> = state.asStateFlow()

  private val waiting = MutableStateFlow<Set<Long>>(emptySet())

  /**
   * Le chiavi di contenuto (`MediaRef.Nas.contentKey`) delle copie del NAS che aspettano la miniatura dell'indice per essere firmate:
   * `:app` le unisce a `UnitPriority.visible`, cosi' la Preparazione le fa per prime (le unita' con media "visibili" vengono prima).
   * Vuoto quando tutte le miniature ci sono.
   */
  val awaitingThumbnails: StateFlow<Set<Long>> = waiting.asStateFlow()

  /** Un giro alla volta. */
  private val runLock = Mutex()

  /** Le scritture (decisione + applicazione di un secondo, "Mostra come principale", "Separa"): brevi, mai durante una decodifica. */
  private val writeLock = Mutex()

  /** Da dove riprende il prossimo giro (secondo, epoca); null = il giro e' finito, il prossimo riparte da zero. */
  private var cursorSecond: Long? = 0L
  private var groupCursor: MediaGroupId? = null

  /** Quanti candidati senza firma restavano a fine dell'ultimo giro finito (chi non ha compagni compatibili non si firma mai). */
  private var baselineUnsigned: Int? = null

  /** Le versioni che non si sono lette o non si firmano (illeggibili, animate, RAW): non si riprovano in questa vita del processo. */
  private val givenUp = LinkedHashSet<VersionId>()

  override suspend fun run(budget: Duration): SimilarCopiesReport = runLock.withLock {
    val started = timeSource.markNow()
    val counters = Counters()
    state.value = state.value.copy(running = true)
    var finished = false
    try {
      // 1. I candidati: firma, decide, scrive. Almeno un secondo per chiamata, anche con un budget minimo: il giro avanza sempre.
      var from = cursorSecond ?: 0L
      var processed = 0
      scan@ while (true) {
        val page = catalog.similarRuns(from, pageSize)
        val apart = choices.separatedPairs(page.runs.flatMap { secondRun -> secondRun.candidates.map { it.mediaId } })
        val signatures = loadSignatures(page.runs)
        for (secondRun in page.runs) {
          if (processed > 0 && started.elapsedNow() >= budget) {
            cursorSecond = secondRun.second
            break@scan
          }
          processRun(secondRun, signatures, apart, counters)
          processed++
        }
        val next = page.nextSecond
        if (next == null) {
          cursorSecond = null
          finished = true
          break
        }
        from = next
        cursorSecond = next
        if (processed > 0 && started.elapsedNow() >= budget) break
      }
      // 2. La copertina dei gruppi che ci sono, a giri, se il tempo avanza.
      if (finished && started.elapsedNow() < budget) maintainCovers(started, budget)
    } finally {
      val pending = if (finished) counters.waiting.size else maxOf(1, estimatePending())
      if (finished) baselineUnsigned = estimatePending()
      state.value = SimilarCopiesProgress(pending = pending, running = false)
      if (finished) waiting.value = counters.waitingKeys
    }
    GalleryLog.d(TAG) {
      "giro: firmati=${counters.signed} gruppi=${counters.grouped} sciolti=${counters.dissolved} ambigui=${counters.ambiguous} " +
        "in attesa=${counters.waiting.size} finito=$finished"
    }
    SimilarCopiesReport(counters.signed, counters.grouped, counters.dissolved, counters.ambiguous, state.value.pending)
  }

  /**
   * Rilegge quanto c'e' ancora da confrontare senza fare un giro: dopo che META ha letto l'ora di scatto di media nuovi (telefono o
   * NAS) il confronto non e' piu' finito, e il riepilogo del primo backup lo aspetta. Prima del primo giro finito non si sa: aspetta.
   */
  suspend fun refreshProgress() {
    val baseline = baselineUnsigned
    val unsigned = estimatePending()
    val pending = when {
      baseline == null -> maxOf(1, unsigned)
      unsigned > baseline -> waiting.value.size + (unsigned - baseline)
      else -> state.value.pending.coerceAtMost(waiting.value.size)
    }
    state.value = state.value.copy(pending = pending)
  }

  private suspend fun estimatePending(): Int = maxOf(0, catalog.similarUnsigned(VisualSignatures.ALGORITHM) - givenUp.size)

  // --- Per il riepilogo del primo backup (§2.11 punto 6) --------------------------------------------------------------------------

  private var nasPeers: Pair<Long, Set<MediaId>>? = null

  /**
   * Fra [ids], i media che hanno **una copia simile sul NAS**: stanno in un gruppo di copie simili in cui un'altra copia ha una posizione
   * sul NAS (area privata o comune). E' cio' che il riepilogo del primo backup chiede ("M con una copia simile sul NAS, si caricano e si
   * vedranno insieme"): `:app` lo lega a `SimilarCopyLookup` di core-backup con una riga. Si legge dopo che il confronto e' finito
   * ([progress] `settled`). L'elenco dei gruppi si ricalcola solo quando il catalogo cambia (`changeSeq`), non a ogni chiamata.
   */
  suspend fun withSimilarOnNas(ids: Collection<MediaId>): Set<MediaId> {
    if (ids.isEmpty()) return emptySet()
    val seq = catalog.changeSeq()
    val cached = nasPeers
    val index = if (cached != null && cached.first == seq) cached.second else buildNasPeers().also { nasPeers = seq to it }
    return ids.filterTo(LinkedHashSet()) { it in index }
  }

  private suspend fun buildNasPeers(): Set<MediaId> {
    val out = HashSet<MediaId>()
    var after: MediaGroupId? = null
    while (true) {
      val groups = catalog.similarGroupIds(after, GROUP_BATCH)
      if (groups.isEmpty()) break
      for (group in groups) {
        val info = catalog.similarGroup(group) ?: continue
        val onNas = info.members.filter { it.onNasPrivate || it.onNasCommon }
        for (member in info.members) if (onNas.any { it.mediaId != member.mediaId }) out += member.mediaId
      }
      after = groups.last()
    }
    return out
  }

  // --- Un secondo -------------------------------------------------------------------------------------------------------------

  private class Counters {
    var signed = 0
    var grouped = 0
    var dissolved = 0
    var ambiguous = 0
    val waiting = LinkedHashSet<MediaId>()
    val waitingKeys = LinkedHashSet<Long>()
  }

  private suspend fun loadSignatures(runs: List<SimilarRun>): MutableMap<VersionId, VisualSignature> {
    val ids = runs.flatMap { run -> run.candidates.map { it.versionId } }
    return catalog.signaturesOf(ids).toMutableMap()
  }

  private suspend fun processRun(
    run: SimilarRun,
    signatures: MutableMap<VersionId, VisualSignature>,
    apart: SeparatedPairs,
    counters: Counters,
  ) {
    // Firma solo chi ha un compagno compatibile e ne e' privo.
    for (candidate in SimilarGroupPlanner.needSignature(run, apart)) {
      val current = signatures[candidate.versionId]
      if (current != null && current.algorithm == VisualSignatures.ALGORITHM) continue
      if (candidate.versionId in givenUp) continue
      val pixels = try {
        source.pixelsOf(candidate)
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        // Una sorgente che sbaglia non ferma il rilevatore: quel candidato non si firma (in questa vita del processo).
        SignaturePixels.Never
      }
      when (pixels) {
        is SignaturePixels.Ready -> {
          val signature = try {
            withContext(compute) { VisualSignatures.of(pixels.frames.take(if (candidate.isVideo) VisualSignatures.VIDEO_FRAMES else 1)) }
          } catch (e: IllegalArgumentException) {
            rememberGivenUp(candidate.versionId)
            continue
          }
          if (catalog.saveSignature(candidate.versionId, signature)) {
            signatures[candidate.versionId] = signature
            counters.signed++
          }
        }
        is SignaturePixels.Later -> {
          counters.waiting += candidate.mediaId
          pixels.contentKey?.let { counters.waitingKeys += it }
        }
        SignaturePixels.Never -> rememberGivenUp(candidate.versionId)
      }
    }
    // La decisione e la scrittura sono una sezione breve, con le separazioni lette adesso (un "Separa" appena fatto vale).
    writeLock.withLock {
      val freshApart = choices.separatedPairs(run.candidates.map { it.mediaId })
      val plan = SimilarGroupPlanner.plan(run, signatures, freshApart)
      apply(run, plan, counters)
    }
  }

  private fun rememberGivenUp(version: VersionId) {
    if (givenUp.size >= MAX_GIVEN_UP) givenUp.clear()
    givenUp += version
  }

  /** Scrive il piano: i gruppi nuovi o cambiati, poi chi esce. Un gruppo gia' uguale non si tocca. */
  private suspend fun apply(run: SimilarRun, plan: RunPlan, counters: Counters) {
    counters.ambiguous += plan.ambiguous.size
    val byId = run.candidates.associateBy { it.mediaId }
    val presentOf = run.candidates.filter { it.group != null }.groupBy { it.group!! }
    for (members in plan.groups) {
      val groups = members.mapNotNull { byId.getValue(it).group }.toSet()
      val unchanged = groups.size == 1 && members.all { byId.getValue(it).group == groups.single() } &&
        presentOf.getValue(groups.single()).size == members.size
      if (unchanged) continue
      // Un gruppo che si allarga porta con se' le copie che adesso non sono candidati (il NAS spento): non restano sole.
      val all = LinkedHashSet<MediaId>(members)
      for (g in groups) {
        val present = presentOf.getValue(g).map { it.mediaId }
        if (members.containsAll(present)) catalog.similarGroup(g)?.members?.forEach { all += it.mediaId }
      }
      val cover = chooseCoverFor(all.toList())
      when (val outcome = catalog.groupSimilar(all, cover)) {
        is SimilarGroupOutcome.Grouped -> {
          counters.grouped++
          counters.dissolved += outcome.dissolved
        }
        is SimilarGroupOutcome.Refused -> counters.ambiguous += 1
      }
    }
    for (id in plan.leaving) {
      when (val out = catalog.separate(id)) {
        is SeparateOutcome.Separated -> {
          if (out.dissolved) counters.dissolved++
          else if (out.coverChanged) out.group?.let { g -> catalog.similarGroup(g)?.let { restoreCover(it) } }
        }
        SeparateOutcome.NotInGroup -> Unit
      }
    }
  }

  // --- La copertina ------------------------------------------------------------------------------------------------------------

  /** La copertina di [members]: la scelta dell'utente se c'e', altrimenti la regola. */
  private suspend fun chooseCoverFor(members: List<MediaId>): MediaId {
    choices.chosenCover(members)?.let { chosen -> catalog.resolve(chosen)?.takeIf { it in members }?.let { return it } }
    return CoverRules.choose(factsOf(members))
  }

  /** I dati della regola per [ids], dal catalogo. */
  private suspend fun factsOf(ids: List<MediaId>): List<CoverFacts> = ids.mapNotNull { id ->
    val media = catalog.media(id) ?: return@mapNotNull null
    val metadata = catalog.versionMetadata(media.currentVersionId)
    val bytes = media.currentVersion.fingerprint.size
    val duration = metadata?.durationMillis
    CoverFacts(
      id = media.id,
      pixels = (metadata?.width ?: 0).toLong() * (metadata?.height ?: 0),
      bytes = bytes,
      format = CoverRules.formatOf(metadata?.mimeType),
      bitrate = if (media.kind.isVideo && duration != null && duration > 0) bytes * 8_000L / duration else null,
      cameraOriginal = cameraOriginal(media),
      isVideo = media.kind.isVideo,
    )
  }

  /**
   * "L'originale della fotocamera" (§2.12 regola 3): la copia viene dal telefono (c'e' ancora sul telefono, o e' il file che il nostro
   * backup ha caricato in `Backup/`, cioe' l'originale), oppure il catalogo la riconosce come uno scatto della fotocamera. Una copia
   * del NAS che non e' di queste (un vecchio backup di un'altra app, una conversione) non lo e'.
   */
  private fun cameraOriginal(media: Media): Boolean =
    media.locations.any { it.isPhone && it.isPresent } ||
      media.locations.any { (it.location as? Location.Nas)?.path?.value?.startsWith(BACKUP_ROOT, ignoreCase = true) == true } ||
      media.source == MediaSource.CAMERA

  /** Dopo che un membro e' uscito: se la copertina e' cambiata, rimette quella voluta (scelta dell'utente o regola). */
  private suspend fun restoreCover(info: SimilarGroupInfo) {
    val members = info.members.map { it.mediaId }
    val wanted = chooseCoverFor(members)
    if (info.cover != wanted) catalog.setSimilarCover(info.group, wanted)
  }

  /**
   * La manutenzione delle copertine: a giri sui gruppi che ci sono, con le stesse regole (cambia il telefono, arriva la copia del NAS,
   * l'utente sceglie su un altro dispositivo). Si ferma a fine budget e riprende da dove era.
   */
  private suspend fun maintainCovers(started: kotlin.time.TimeMark, budget: Duration) {
    while (started.elapsedNow() < budget) {
      val ids = catalog.similarGroupIds(groupCursor, COVER_BATCH)
      if (ids.isEmpty()) {
        groupCursor = null
        return
      }
      for (id in ids) {
        if (started.elapsedNow() >= budget) return
        groupCursor = id
        writeLock.withLock {
          catalog.similarGroup(id)?.let { info ->
            // Le copie di uno scatto stanno nello stesso secondo: se una data e' stata corretta e non sta piu' con le altre, esce
            // (non per un'assenza: un NAS spento non scioglie mai un gruppo).
            val kept = membersOfTheSameSecond(info)
            if (kept.size < info.members.size) {
              for (member in info.members) if (member.mediaId !in kept) catalog.separate(member.mediaId)
              catalog.similarGroupOf(kept.first())?.let { restoreCover(it) }
            } else {
              val wanted = chooseCoverFor(info.members.map { it.mediaId })
              if (info.cover != wanted) catalog.setSimilarCover(id, wanted)
            }
          }
        }
      }
    }
  }

  /** I membri del gruppo che stanno nel secondo di scatto piu' frequente (a parita', il piu' antico): gli altri non sono piu' copie. */
  private fun membersOfTheSameSecond(info: SimilarGroupInfo): List<MediaId> {
    val bySecond = info.members.groupBy { Math.floorDiv(it.sortTime, 1_000L) }
    if (bySecond.size <= 1) return info.members.map { it.mediaId }
    val best = bySecond.entries.maxWithOrNull(compareBy<Map.Entry<Long, List<SimilarMember>>>({ it.value.size }, { -it.key }))!!
    return best.value.map { it.mediaId }
  }

  // --- Quello che vede e fa l'utente -----------------------------------------------------------------------------------------

  override suspend fun groupOf(mediaId: MediaId): SimilarCopiesGroup? {
    val info = catalog.similarGroupOf(mediaId) ?: return null
    if (info.members.size < 2) return null
    val ids = info.members.map { it.mediaId }
    val facts = factsOf(ids).associateBy { it.id }
    val cover = info.cover?.takeIf { it in ids } ?: CoverRules.choose(facts.values.toList())
    val chosen = choices.chosenCover(ids)
    val copies = info.members.map { member ->
      val fact = facts[member.mediaId]
      toCopy(member, cameraOriginal = fact?.cameraOriginal ?: member.onPhone, bitrate = fact?.bitrate)
    }.sortedWith(
      compareByDescending<SimilarCopy> { it.mediaId == cover }
        .thenByDescending { it.width.toLong() * it.height }
        .thenByDescending { it.bytes }
        .thenBy { it.mediaId.value },
    )
    return SimilarCopiesGroup(info.group, cover, copies, coverChosenByUser = chosen != null && chosen == cover, hidden = info.hidden)
  }

  private fun toCopy(member: SimilarMember, cameraOriginal: Boolean, bitrate: Long?): SimilarCopy {
    val swap = member.orientationDegrees == 90 || member.orientationDegrees == 270
    val width = (if (swap) member.height else member.width) ?: 0
    val height = (if (swap) member.width else member.height) ?: 0
    val places = buildSet {
      if (member.onPhone) add(CopyPlace.PHONE)
      if (member.onNasPrivate) add(CopyPlace.NAS_PRIVATE)
      if (member.onNasCommon) add(CopyPlace.NAS_COMMON)
    }
    return SimilarCopy(member.mediaId, member.kind, places, width, height, member.bytes, member.mime, bitrate, cameraOriginal)
  }

  override suspend fun chooseCover(mediaId: MediaId): ChoiceOutcome = writeLock.withLock {
    val info = catalog.similarGroupOf(mediaId) ?: return@withLock ChoiceOutcome.NOT_IN_GROUP
    val id = catalog.resolve(mediaId) ?: return@withLock ChoiceOutcome.NOT_IN_GROUP
    val others = info.members.map { it.mediaId }.filter { it != id }
    // Prima il dato personale (sopravvive a una morte del processo), poi l'effetto sul catalogo.
    choices.saveCover(id, others)
    if (catalog.setSimilarCover(info.group, id)) ChoiceOutcome.APPLIED else ChoiceOutcome.NOT_IN_GROUP
  }

  override suspend fun separate(mediaId: MediaId): ChoiceOutcome = writeLock.withLock {
    val info = catalog.similarGroupOf(mediaId) ?: return@withLock ChoiceOutcome.NOT_IN_GROUP
    val id = catalog.resolve(mediaId) ?: return@withLock ChoiceOutcome.NOT_IN_GROUP
    val others = info.members.map { it.mediaId }.filter { it != id }
    // Prima la separazione salvata: se il processo muore dopo, il giro dopo non rifa' il gruppo.
    choices.saveSeparation(id, others)
    // Scrivendo le voci la proiezione puo' aver gia' tolto la copia dal gruppo (NotInGroup): la separazione e' fatta lo stesso.
    catalog.separate(id)
    // Le copie rimaste tornano alla loro copertina (la scelta dell'utente se c'e', altrimenti la regola) se la copertina era quella uscita.
    others.firstNotNullOfOrNull { catalog.similarGroupOf(it) }?.let { restoreCover(it) }
    ChoiceOutcome.APPLIED
  }

  private companion object {
    const val TAG = "SimilarCopies"
    const val PAGE_SIZE = 500
    const val COVER_BATCH = 50
    const val GROUP_BATCH = 200
    const val MAX_GIVEN_UP = 10_000

    /** "Non si sa ancora": prima del primo giro il confronto non e' finito. */
    const val UNKNOWN = 1

    /** La radice delle copie che il nostro backup carica sul NAS (`Backup/<dispositivo>/AAAA/MM/`): sono gli originali del telefono. */
    const val BACKUP_ROOT = "Backup/"
  }
}
