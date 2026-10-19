package dev.pampa.mycloudgallery.core.backup.free

import dev.pampa.mycloudgallery.core.backup.contract.BackupFence
import dev.pampa.mycloudgallery.core.backup.contract.BackupGateState
import dev.pampa.mycloudgallery.core.backup.contract.BackupStore
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceExclusion
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceItem
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceRules
import dev.pampa.mycloudgallery.core.backup.contract.PhoneRef
import dev.pampa.mycloudgallery.core.backup.contract.VersionKeeper
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.Media
import dev.pampa.mycloudgallery.core.model.MediaLocation
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasAreaLookup
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.domain.SafetyRules
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

// Fase 5B, P9 (docs/fase5-progetto.md §2.5 "Escluso perche'", D21, D22, D13, E-B14, E-B22, E-S11): le REGOLE di "Libera spazio" su una copia
// del telefono, **solo da dati locali** (catalogo, diario del backup, versioni di P8). Le usano il piano, la verifica sul momento (che le
// riapplica prima di rileggere) e il controllo finale prima di ogni dialogo; "Elimina solo dal telefono" ne legge l'esito per media.
//
// Una copia del telefono si puo' liberare solo se **ogni versione da proteggere** (quella del telefono, l'originale e l'attuale, fra quelle
// che esistono ancora: `SafetyRules`) ha nell'area privata **del proprietario** una copia presente, con l'hash completo noto e una rilettura
// fatta qui (`verified_at`, L28). Un hash dell'indice non conta (F4-D8). Se una versione ha solo la copia dell'area comune parte prima il
// guardiano (D9, E-B14). Mai: non proprio, non verificato, in caricamento o in errore, con una versione scoperta, con l'area congelata, fuori
// dalla recinzione di D13.

/** La scena in cui si decide: chi sono, la mia area privata, il cancello, la recinzione, oggi (dall'ora del NAS, mai dal telefono). */
internal class FreeSpaceScene(
  val me: AccountId?,
  val privateShare: String?,
  val areas: NasAreaLookup,
  /** Il cancello del backup per l'area privata; null = nessuna area privata. */
  val gate: BackupGateState?,
  val fence: BackupFence?,
  /** Oggi, nel fuso del telefono ma dall'ora del NAS; null = ora del NAS non nota. */
  val today: LocalDate?,
) {
  /** Il guardiano scrive solo con il cancello aperto (la pausa, R4, ferma chi scrive; la sola lettura non lascia scrivere). */
  val guardianAllowed: Boolean get() = gate == BackupGateState.OPEN
}

/** Una copia privata scelta per una versione da proteggere: da rileggere contro [expected] (la verifica sul momento). */
internal class PlannedCheck(
  val versionId: VersionId,
  val location: LocationId,
  val share: String,
  val path: SharePath,
  /** Il timbro che il catalogo conosce: la rilettura vuole questo all'apertura. */
  val stamp: NasStamp,
  val expected: Sha256,
  val size: Long,
) {
  override fun toString(): String = "PlannedCheck($size B)"
}

/** L'esito delle regole per una copia del telefono ([phoneLocation]). */
internal sealed interface CopyOutcome {
  val phoneLocation: LocationId

  /**
   * Liberabile (dopo il guardiano, se [guardVersions] non e' vuoto). [required] sono le versioni da proteggere, [checks] le copie da rileggere
   * (una per versione con una copia privata verificata), [verifiedByVersion] **tutte** le copie private verificate di ogni versione (il
   * controllo finale accetta una qualunque di queste).
   */
  class Ready(
    override val phoneLocation: LocationId,
    val item: FreeSpaceItem,
    val phoneStamp: FileStamp,
    val required: List<VersionId>,
    val checks: List<PlannedCheck>,
    val verifiedByVersion: Map<VersionId, Set<LocationId>>,
    val guardVersions: Set<VersionId>,
    val guardBytes: Long,
  ) : CopyOutcome

  class Excluded(override val phoneLocation: LocationId, val reason: FreeSpaceExclusion) : CopyOutcome
}

/**
 * Le regole (D21, D22). [store] dice se un elemento del diario del backup e' aperto (in caricamento); [versions] e' il custode delle versioni
 * di P8: per un media con piu' versioni il suo piano deve dire **anche lui** che originale e attuale sono al sicuro (difesa in profondita':
 * due fonti indipendenti devono essere d'accordo, altrimenti l'elemento resta sul telefono).
 */
internal class FreeSpaceEvaluator(private val store: BackupStore, private val versions: VersionKeeper) {

  /**
   * Le copie del telefono **presenti** di [fm] (con una riga del MediaStore valida) e il loro esito. Vuota se il media non ne ha.
   * [excludeRecentDays] = 0 salta il filtro "ultimi giorni" (la verifica e il controllo finale lo hanno gia' passato con il piano).
   */
  suspend fun evaluate(fm: FreeSpaceMedia, scene: FreeSpaceScene, excludeRecentDays: Int): List<CopyOutcome> {
    val media = fm.media
    val rows = media.locations.mapNotNull { loc ->
      val phone = loc.location as? Location.Phone ?: return@mapNotNull null
      if (!loc.isPresent || phone.mediaStoreId == null || phone.mediaStoreVersion == null) return@mapNotNull null
      loc to phone
    }
    if (rows.isEmpty()) return emptyList()
    val mediaLevel = mediaExclusion(fm, scene)
    return rows.map { (loc, phone) ->
      fenceExclusion(phone, scene)?.let { return@map CopyOutcome.Excluded(loc.id, it) }
      mediaLevel?.let { return@map CopyOutcome.Excluded(loc.id, it) }
      copyOutcome(fm, loc, phone, scene, excludeRecentDays)
    }
  }

  /** D13: con la recinzione (debug, account di prova) solo le cartelle `mcg-prova`; senza account noto niente (fallire chiusi). */
  private fun fenceExclusion(phone: Location.Phone, scene: FreeSpaceScene): FreeSpaceExclusion? {
    val fence = scene.fence ?: return null
    val me = scene.me ?: return FreeSpaceExclusion.OUTSIDE_TEST_FENCE
    return if (fence.allowsPhoneFolder(me.value, phone.relativePath)) null else FreeSpaceExclusion.OUTSIDE_TEST_FENCE
  }

  /** Cio' che vale per tutto il media: cancello, proprietario, caricamento o errore. */
  private suspend fun mediaExclusion(fm: FreeSpaceMedia, scene: FreeSpaceScene): FreeSpaceExclusion? {
    when (scene.gate) {
      // E-B22, D32: area congelata o scritture ferme. "Libera spazio" si fida dell'area privata: ferma con lei.
      BackupGateState.FROZEN, BackupGateState.WRITES_STOPPED -> return FreeSpaceExclusion.AREA_FROZEN
      // Nessun backup attivo (nessuna area privata, famiglia non pronta, D12 o R2: "Backup in prova"): niente e' al sicuro per questo flusso.
      null, BackupGateState.NO_PRIVATE_AREA, BackupGateState.NOT_READY, BackupGateState.TEST_ONLY -> return FreeSpaceExclusion.NOT_VERIFIED
      BackupGateState.OPEN, BackupGateState.PAUSED, BackupGateState.READ_ONLY_AREA -> Unit
    }
    val me = scene.me ?: return FreeSpaceExclusion.NOT_VERIFIED
    // Non proprio: nessuna copia sotto il mio controllo da cui ripartire (il contratto: i media non propri non entrano mai in un piano).
    if (fm.media.owner != me) return FreeSpaceExclusion.NOT_VERIFIED
    if (fm.problem?.marksMedia == true) return FreeSpaceExclusion.IN_PROGRESS_OR_ERROR
    // Un elemento aperto nel diario del backup per una qualunque versione: caricamento, guardiano, versione o scambio in corso.
    for (version in fm.media.versions) {
      if (store.openFor(version.id) != null) return FreeSpaceExclusion.IN_PROGRESS_OR_ERROR
    }
    return null
  }

  private suspend fun copyOutcome(
    fm: FreeSpaceMedia,
    loc: MediaLocation,
    phone: Location.Phone,
    scene: FreeSpaceScene,
    excludeRecentDays: Int,
  ): CopyOutcome {
    val media = fm.media
    val phoneVersion = loc.versionId
    val required = requiredVersions(media, phoneVersion)
    val checks = ArrayList<PlannedCheck>()
    val verifiedByVersion = LinkedHashMap<VersionId, Set<LocationId>>()
    val guard = LinkedHashSet<VersionId>()
    var guardBytes = 0L

    fun missing(version: VersionId): CopyOutcome =
      CopyOutcome.Excluded(loc.id, if (version == phoneVersion) FreeSpaceExclusion.NOT_VERIFIED else FreeSpaceExclusion.VERSION_NOT_SAFE)

    for (versionId in required) {
      val version = media.version(versionId)
      val nasHere = media.locationsOf(versionId).filter { it.isPresent && it.isNas }
      val mine = nasHere.filter { (it.location as Location.Nas).account == scene.me }
      val private = mine.filter { isMyPrivate(it, scene) }
      val hash = version.fingerprint.fullHash
      // Verificata = riletta qui con lo SHA-256 della versione (che quindi deve essere noto). Il percorso deve essere un percorso di media.
      val verified = if (hash == null) emptyList() else private.filter { it.verifiedAtMillis != null && mediaPathOf(it) != null }
      when {
        verified.isNotEmpty() -> {
          val chosen = verified.sortedWith(compareByDescending<MediaLocation> { it.verifiedAtMillis }.thenBy { it.id.value }).first()
          val nas = chosen.location as Location.Nas
          checks += PlannedCheck(versionId, chosen.id, nas.share, mediaPathOf(chosen)!!, nas.stampOf(chosen.stamp), hash!!, version.fingerprint.size)
          verifiedByVersion[versionId] = verified.mapTo(LinkedHashSet()) { it.id }
        }
        // Una copia privata mai riletta (o senza hash noto): non e' al sicuro. La riverifica la sistemera'; qui non si libera.
        private.isNotEmpty() -> return missing(versionId)
        // E-B14: solo la copia comune (o di area sconosciuta) dell'account: prima il guardiano crea la copia privata.
        mine.isNotEmpty() && scene.guardianAllowed -> {
          guard += versionId
          guardBytes += version.fingerprint.size
        }
        else -> return missing(versionId)
      }
    }

    // P8: per un media con piu' versioni anche il custode delle versioni deve dire che ogni versione da proteggere e' al sicuro.
    if (media.versions.size > 1) {
      val plan = versions.plan(media.id)
      if (plan.current?.versionId != media.currentVersionId) return CopyOutcome.Excluded(loc.id, FreeSpaceExclusion.VERSION_NOT_SAFE)
      for (versionId in required) {
        val entry = plan.versions.firstOrNull { it.versionId == versionId } ?: return CopyOutcome.Excluded(loc.id, FreeSpaceExclusion.VERSION_NOT_SAFE)
        if (versionId !in guard && !entry.safe) return missing(versionId)
      }
    }

    if (isRecent(fm.localDay, excludeRecentDays, scene.today)) return CopyOutcome.Excluded(loc.id, FreeSpaceExclusion.RECENT)

    val item = FreeSpaceItem(
      mediaId = media.id,
      versionId = phoneVersion,
      phone = PhoneRef(phone.volume, phone.mediaStoreId!!, phone.mediaStoreVersion!!),
      mime = fm.mime,
      size = loc.stamp.size,
      safeCopy = checks.firstOrNull { it.versionId == phoneVersion }?.stamp,
      isVideo = media.kind.isVideo,
      localDay = fm.localDay,
    )
    return CopyOutcome.Ready(loc.id, item, loc.stamp, required, checks, verifiedByVersion, guard, guardBytes)
  }

  private fun isMyPrivate(loc: MediaLocation, scene: FreeSpaceScene): Boolean {
    val nas = loc.location as? Location.Nas ?: return false
    val share = scene.privateShare ?: return false
    return nas.account == scene.me && scene.areas.areaOf(nas.account, nas.shareKey) == NasArea.PRIVATE &&
      nas.share.lowercase(Locale.ROOT) == share.lowercase(Locale.ROOT)
  }

  companion object {

    /**
     * Le versioni da proteggere per liberare una copia di [phoneVersion]: quella stessa (i byte che si tolgono), poi l'originale e l'attuale
     * fra quelle di cui esiste ancora una copia (`SafetyRules`: una versione che non c'e' piu' da nessuna parte non blocca per sempre).
     */
    fun requiredVersions(media: Media, phoneVersion: VersionId): List<VersionId> {
      val existing = media.locations.filter { it.state.exists }.map { it.versionId }.toSet()
      val protect = SafetyRules.protectedVersions(media).filter { it in existing }.ifEmpty { existing.toList() }
      return (listOf(phoneVersion) + protect.sortedBy { it.value }).distinct()
    }

    /** Il percorso di media di una copia del NAS (mai dentro `.mycloudgallery/`), o null se non lo e'. */
    fun mediaPathOf(loc: MediaLocation): SharePath? {
      val nas = loc.location as? Location.Nas ?: return null
      return try {
        SharePath.parseMediaPath(nas.path.value)
      } catch (_: RuntimeException) {
        null
      }
    }

    /** I giorni da [localDay] (`aaaammgg`) a [today], o null se uno dei due manca o la data non esiste. */
    fun daysAgo(localDay: Int?, today: LocalDate?): Long? {
      if (localDay == null || today == null) return null
      return try {
        ChronoUnit.DAYS.between(LocalDate.of(localDay / 10_000, localDay / 100 % 100, localDay % 100), today)
      } catch (_: DateTimeException) {
        null
      }
    }

    /**
     * Q9: negli ultimi [days] giorni. Senza l'ora del NAS un media datato si tratta come recente (fallire chiusi: resta sul telefono); un media
     * senza data non e' "recente" (come il fake di P0-B).
     */
    fun isRecent(localDay: Int?, days: Int, today: LocalDate?): Boolean {
      if (days <= 0 || localDay == null) return false
      if (today == null) return true
      val ago = daysAgo(localDay, today) ?: return false
      return ago < days
    }

    /** Q9: "piu' vecchi di un anno". Senza data o senza l'ora del NAS no. */
    fun isOlderThanAYear(localDay: Int?, today: LocalDate?): Boolean = (daysAgo(localDay, today) ?: -1) >= FreeSpaceRules.OLDER_THAN_DAYS
  }
}
