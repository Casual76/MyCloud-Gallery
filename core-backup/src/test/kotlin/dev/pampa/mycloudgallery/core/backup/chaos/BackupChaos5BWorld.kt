package dev.pampa.mycloudgallery.core.backup.chaos

import dev.pampa.mycloudgallery.core.backup.contract.BackupCandidate
import dev.pampa.mycloudgallery.core.backup.contract.BackupSource
import dev.pampa.mycloudgallery.core.backup.contract.PendingTask
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskKind
import dev.pampa.mycloudgallery.core.backup.contract.PendingTaskState
import dev.pampa.mycloudgallery.core.backup.free.testing.InMemoryFreeSpaceDiary
import dev.pampa.mycloudgallery.core.backup.testing.FakeBackupClock
import dev.pampa.mycloudgallery.core.backup.testing.FakePhoneBytes
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryBackupStore
import dev.pampa.mycloudgallery.core.backup.testing.InMemoryPendingTasks
import dev.pampa.mycloudgallery.core.backup.versions.DirectoryStagedFiles
import dev.pampa.mycloudgallery.core.backup.versions.InMemoryVersionJournal
import dev.pampa.mycloudgallery.core.backup.versions.PhoneRowRewriter
import dev.pampa.mycloudgallery.core.backup.versions.VersionHold
import dev.pampa.mycloudgallery.core.backup.versions.VersionNames
import dev.pampa.mycloudgallery.core.backup.versions.VideoOriginalTasks
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.QuickHashAlgorithm
import dev.pampa.mycloudgallery.core.model.Sha256
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.nas.contract.NasResult
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.ops.backup.VersionPaths
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.trust.nas.SharePath
import java.io.File
import java.time.ZoneId
import java.util.Locale
import kotlin.random.Random

// Il MONDO della 5B (P13): i media con le loro versioni, il catalogo unico, i diari (delle versioni, di "Libera spazio", del guardiano), i file
// preparati, il telefono e il NAS (quelli del banco della 5A: [BackupChaosWorld]). Diari, catalogo e file preparati sopravvivono alla morte del
// processo (sono il disco); i motori si ricreano a ogni morte ([BackupChaos5BPhase]). Qui stanno anche le azioni "da fuori" (Samsung "Salva",
// il PC che modifica un file, il marciume di un bit, il MediaStore che si ricostruisce, il tempo, il riavvio) e la scansione che le registra nel
// catalogo (la lettura del telefono e quella del NAS): i motori non vedono mai niente di tutto questo se non attraverso il catalogo.

/** Che tipo di media semina il banco (D17, D18, D9, E-B14, E-E1-E-E3, E-B8). */
enum class FiveBCategory(val code: String) {
  /** L'originale sul telefono e al solito percorso dell'area privata, riletto e verificato. */
  SAFE_PHONE("safe_phone"),

  /** Gia' modificato: l'originale in `Versioni/`, l'attuale (anche sul telefono) al solito percorso. */
  EDITED_SAFE("edited_safe"),

  /** Non e' sul telefono: solo al solito percorso (E-E3, correzione di un solo-NAS). */
  SOLO_NAS("solo_nas"),

  /** La sola copia nell'area comune (il guardiano, D9): sul telefono c'e' ancora. */
  COMMON_ONLY("common_only"),

  /** Solo sul telefono: nessuna copia sul NAS (mai liberabile, nessuna versione). */
  PHONE_ONLY("phone_only"),
}

/** Un media del banco: il suo posto, le versioni che il banco sa (l'impronta di ciascuna) e i byte che servono a un "Ripristina originale". */
class MediaRec(
  val label: Int,
  val mediaId: MediaId,
  val kind: MediaKind,
  val name: String,
  val category: FiveBCategory,
  /** Il solito percorso del media (null = non ce l'ha ancora: solo telefono o solo area comune; nasce con la prima copia privata). */
  var usualPath: SharePath?,
  /** Le righe che il telefono ha avuto per questo media: l'ultima che esiste e' quella attuale (gli `_ID` si rinumerano, E-P12). */
  val phoneIds: MutableList<Long>,
  /** I byte dell'originale, per un Samsung "Ripristina originale". */
  val originalBytes: ByteArray?,
  val size: Int,
) {
  /** Per ogni versione l'impronta dei suoi byte secondo il banco. */
  val versionSha: MutableMap<VersionId, Sha256> = LinkedHashMap()

  /** Le impronte dei byte di questo media comparse come file visibile sul NAS durante la prova. */
  val seen: MutableSet<Sha256> = LinkedHashSet()

  /** Un bit e' marcito sul NAS conservando il timbro: la copia "verificata" non e' piu' integra. */
  var tampered: Boolean = false

  /** Le impronte dei byte che il PC ha messo al solito percorso di questo media. */
  val pcShas: MutableSet<Sha256> = LinkedHashSet()

  val text: String get() = "m$label"

  override fun toString(): String = "MediaRec(#$label, ${category.code})"
}

/**
 * I difetti veri dei servizi di P8 e P3 che il banco ha trovato e che nessuno ha ancora corretto (`docs/prova-500.md` Parte C §C6). Il banco li
 * **evita** per default, perche' `check.ps1` deve restare verde: ogni evitamento e' una voce di [Backup5BConfig.avoid], scritta qui e non nascosta
 * in un filtro, e `ChaosKnownDefectsTest` prova che **con l'evitamento spento il banco li trova** (un banco che salta un caso senza dirlo
 * farebbe credere di averlo provato). Quando un difetto e' corretto si toglie la voce da qui, dal default e dal punto del banco che la usa.
 */
enum class KnownDefect(val code: String) {
  /** P8, `NasVersionEngine.refreshPhone`: un lavoro `VERSION` dal telefono resta `PLANNED` per sempre dopo un MediaStore ricostruito e un'altra modifica. */
  F1("f1"),

  /** P3/P8: una correzione sposta in `Versioni/` la copia di un elemento del guardiano non ancora `LINKED`: il guardiano ne rifa' una (doppione). */
  F2("f2"),

  /** P8, `restore`/`archive` per identita': una modifica del PC **della stessa dimensione** fra la rinomina e la ripresa e' scambiata per il file del lavoro. */
  F3("f3"),
}

/** Come gira la fase 5B di un giro (P13). */
class Backup5BConfig(
  /** Senza il caricamento della 5A: solo i media che semina la 5B (l'area di prova del NAS vero). */
  val only: Boolean = false,
  val minMedia: Int = 3,
  val maxMedia: Int = 5,
  val minSteps: Int = 3,
  val maxSteps: Int = 5,
  val mix: FaultMix = FaultMix.FIVE_B,
  val maxFaultsPerStep: Int = 4,
  /** Se non null, il massimo di colpi per passo (il runner sul NAS vero: uno solo). */
  val stepFaults: Int? = null,
  /** Gli eventi tra un passo e l'altro (il PC, il tempo, il riavvio, il marciume di un bit, il MediaStore): il NAS vero non ne ha di tutti. */
  val events: Boolean = true,
  /** Con la cache un file non cambiato non si rilegge nelle verifiche intermedie (il NAS vero). L'ultima lettura e' sempre completa. */
  val hashCache: Boolean = false,
  /** La taglia di un media in byte. */
  val sizeOf: (Random) -> Int = { 20_000 + it.nextInt(50_000) },
  val stack: FiveBStackFactory = RealFiveBStack,
  /** Le riprese dopo cui un lavoro ancora aperto e' "non arriva in fondo". */
  val resumeBound: Int = 14,
  val phoneEventPercent: Int = 12,
  /** I difetti noti che il banco evita (vedi [KnownDefect]); `emptySet()` li lascia scattare. */
  val avoid: Set<KnownDefect> = KnownDefect.entries.toSet(),
) {
  val scope: RuleScope get() = RuleScope.FIVE_B
}

class FiveBWorld(
  val world: BackupChaosWorld,
  val config: Backup5BConfig,
  private val random: Random,
  root: File,
) {
  val catalog = ChaosMediaCatalog(world.share, world.commonShare)
  // Nella tabella vera i lavori delle versioni e gli elementi del guardiano stanno insieme: `busy` vede anche un elemento aperto del guardiano.
  val journal = InMemoryVersionJournal(others = { versionId -> guardStore.openFor(versionId) != null })
  val staged = DirectoryStagedFiles(File(root, "versions"))
  val freeDiary = InMemoryFreeSpaceDiary()
  val guardStore = InMemoryBackupStore()
  val pending = InMemoryPendingTasks()
  val rewriter = PhoneRowRewriter(world.phone)
  val zone: ZoneId = ZoneId.of("Europe/Rome")

  /** D20 sopra le attivita' in sospeso finte, idempotente per versione come `RoomPendingTasks`. */
  val tasks = VideoOriginalTasks { mediaId, versionId, bytes ->
    if (pending.everything.none { it.versionId == versionId && it.kind == PendingTaskKind.EDITED_VIDEO_ORIGINAL }) {
      pending.add { id -> PendingTask(id, PendingTaskKind.EDITED_VIDEO_ORIGINAL, PendingTaskState.OPEN, mediaId, versionId, bytes, world.clock.nasNow ?: 0) }
    }
  }

  /** Il conteggio dei riavvii del telefono (`Settings.Global.BOOT_COUNT`). */
  var bootCount: Long = 7

  val registry = LinkedHashMap<MediaId, MediaRec>()

  /** Le impronte di tutti i contenuti legittimi: quelle che il telefono, il PC o un editor hanno prodotto, e quelle dei file che c'erano. */
  val knownShas = LinkedHashSet<Sha256>()

  /** I byte che il PC ha sovrascritto di proposito: non si pretende che si conservino. */
  val lostShas = LinkedHashSet<Sha256>()

  /** I file che c'erano prima della fase, piu' quelli che il "PC" ha messo o modificato durante. */
  val before = LinkedHashMap<String, NasFileFact>()

  /** Quando ogni copia e' stata riletta dalla verifica sul momento (orologio monotono e riavvio di allora): l'invariante 14 la confronta con l'ora. */
  val verifiedAt = HashMap<LocationId, Pair<Long, Long>>()

  /** Le righe del telefono finite in un dialogo (`mediaStoreId`) e quelle che il dialogo simulato ha davvero tolto, per giro (giro, riga). */
  val handedRows = HashSet<Long>()
  val removedRows = LinkedHashSet<Pair<Long, Long>>()

  private var labelCounter = 0
  private var nextModified = 2_000L
  private var rebuilds = 0

  fun nowMs(): Long = world.clock.nasNow ?: FakeBackupClock.DEFAULT_NAS_NOW

  fun recOf(label: String): MediaRec? = registry.values.firstOrNull { it.text == label }

  fun recOf(mediaId: MediaId): MediaRec? = registry[mediaId]

  /** L'impronta dei byte di una versione secondo il banco; senza, quella del catalogo. */
  suspend fun shaOf(mediaId: MediaId, versionId: VersionId): Sha256? =
    registry[mediaId]?.versionSha?.get(versionId) ?: catalog.media(mediaId)?.version(versionId)?.fullHash

  // =========================================================================================================================
  // La semina
  // =========================================================================================================================

  /** Il NAS di un file adesso: dimensione, data e `fileId` come li dice un `stat`. */
  suspend fun stampOf(share: String, path: SharePath): NasStamp? =
    world.nas.reader.stat(share, path, Purpose.BACKGROUND).okOrNull()?.takeIf { !it.isDirectory }?.stamp

  /** L'impronta dei byte di un file del NAS adesso (una lettura completa fuori dai colpi). */
  suspend fun nasSha(share: String, path: SharePath): Sha256? {
    val file = (world.nas.reader.open(share, path, Purpose.BACKGROUND) as? NasResult.Ok)?.value ?: return null
    return file.use { f -> (world.nas.reader.readAll(f, { _, _ -> }, null) as? NasResult.Ok)?.value }
  }

  /** I byte di un file del NAS adesso (i file del banco sono piccoli). */
  suspend fun nasBytes(share: String, path: SharePath): ByteArray? {
    val file = (world.nas.reader.open(share, path, Purpose.BACKGROUND) as? NasResult.Ok)?.value ?: return null
    return file.use { f -> if (f.size <= 0) ByteArray(0) else f.readRange(0L until f.size) }
  }

  /** Il file [fact] e' un file che c'era (messo o modificato dal PC): un file che si e' gia' mosso (stesso `fileId`) lo e' sotto il nome nuovo. */
  private fun learn(fact: NasFileFact) {
    if (fact.fileId != null) before.entries.removeAll { it.value.fileId == fact.fileId && it.value.share.equals(fact.share, ignoreCase = true) }
    before[fact.key] = fact
  }

  private suspend fun factOf(share: String, path: SharePath, bytes: ByteArray): NasFileFact? {
    val stamp = stampOf(share, path) ?: return null
    return NasFileFact(share, path.value, bytes.size.toLong(), stamp.fileId, Sha256.of(bytes))
  }

  suspend fun seedAll(): List<MediaRec> {
    val count = config.minMedia + random.nextInt(config.maxMedia - config.minMedia + 1)
    val out = ArrayList<MediaRec>()
    // Almeno un media su cui lavorano le versioni e uno che "Libera spazio" puo' liberare.
    for (i in 0 until count) {
      val roll = random.nextInt(100)
      val category = when {
        i == 0 -> FiveBCategory.SAFE_PHONE
        i == 1 && count >= 4 -> FiveBCategory.EDITED_SAFE
        roll < 30 -> FiveBCategory.SAFE_PHONE
        roll < 55 -> FiveBCategory.EDITED_SAFE
        roll < 70 -> FiveBCategory.SOLO_NAS
        roll < 88 -> FiveBCategory.COMMON_ONLY
        else -> FiveBCategory.PHONE_ONLY
      }
      out += seed(category)
    }
    return out
  }

  suspend fun seed(category: FiveBCategory): MediaRec {
    val label = ++labelCounter
    val video = random.nextInt(100) < 20
    val kind = if (video) MediaKind.VIDEO else MediaKind.PHOTO
    val name = (if (video) "VIDEO5B_%02d.mp4" else "FOTO5B_%02d.jpg").format(Locale.ROOT, label)
    val mime = if (video) "video/mp4" else "image/jpeg"
    val localDay = when (random.nextInt(100)) {
      in 0 until 55 -> 20_240_101
      in 55 until 85 -> 20_261_012
      else -> null
    }
    val size = config.sizeOf(random)
    val share = world.share
    val usual = SharePath.of("Backup", world.folder!!.name, "2024", "01", name)
    val data = random.nextBytes(size)
    val sha = Sha256.of(data)
    knownShas += sha
    val sortTime = 10_000_000L - label
    val old = category == FiveBCategory.EDITED_SAFE
    // L'originale nell'area privata: per un media gia' modificato sta in `Versioni/`, l'attuale al solito percorso.
    val edited = if (old) random.nextBytes(size + 500 + random.nextInt(2_000)) else null
    val editedSha = edited?.let { Sha256.of(it) }
    edited?.let { knownShas += editedSha!! }
    val knownHash = category != FiveBCategory.COMMON_ONLY || random.nextInt(100) < 60
    val (mediaId, v0) = catalog.inner.addMedia(
      kind = kind, mime = mime, size = size.toLong(), fullHash = if (knownHash) sha else null,
      quickHash = QuickHashAlgorithm.ofContent(data),
    )
    catalog.setMeta(mediaId, MediaMeta(name, localDay, sortTime, mime))
    val hasPhone = category != FiveBCategory.SOLO_NAS
    val phoneId = if (hasPhone) world.nextFreshId() else null
    val rec = MediaRec(
      label, mediaId, kind, name, category,
      usualPath = if (category == FiveBCategory.COMMON_ONLY || category == FiveBCategory.PHONE_ONLY) null else usual,
      phoneIds = mutableListOf<Long>().also { ids -> phoneId?.let { ids += it } },
      originalBytes = data, size = size,
    )
    rec.versionSha[v0] = sha
    registry[mediaId] = rec
    val dayAgo = nowMs() - DAY_MS
    val verifiedNow = nowMs()
    when (category) {
      FiveBCategory.SAFE_PHONE, FiveBCategory.SOLO_NAS -> {
        world.nas.pcPut(share, usual.value, data, dayAgo)
        val stamp = stampOf(share, usual)!!
        catalog.inner.putNasCopy(mediaId, v0, share, usual, stamp, verifiedAt = verifiedNow)
        if (phoneId != null) {
          val ref = world.phone.add(phoneId, NasContent.of(data), nextModified++)
          catalog.inner.putPhoneCopy(mediaId, v0, ref, world.phone.stamp(phoneId)!!)
        }
      }
      FiveBCategory.EDITED_SAFE -> {
        val archived = VersionNames.inVersions(usual, VersionNames.ORIGINAL_LABEL)
        world.nas.pcPut(share, archived.value, data, dayAgo)
        world.nas.pcPut(share, usual.value, edited!!, dayAgo + 1_000)
        val v1 = catalog.inner.addVersion(mediaId, VersionKind.EDITED, edited.size.toLong(), editedSha, current = true)
        rec.versionSha[v1] = editedSha!!
        catalog.inner.putNasCopy(mediaId, v0, share, archived, stampOf(share, archived)!!, verifiedAt = verifiedNow)
        catalog.inner.putNasCopy(mediaId, v1, share, usual, stampOf(share, usual)!!, verifiedAt = verifiedNow)
        val ref = world.phone.add(phoneId!!, NasContent.of(edited), nextModified++)
        catalog.inner.putPhoneCopy(mediaId, v1, ref, world.phone.stamp(phoneId)!!)
      }
      FiveBCategory.COMMON_ONLY -> {
        val common = SharePath.of("Foto condivise", name)
        world.nas.pcPut(world.commonShare, common.value, data, dayAgo)
        catalog.inner.putNasCopy(mediaId, v0, world.commonShare, common, stampOf(world.commonShare, common)!!, verifiedAt = null)
        val ref = world.phone.add(phoneId!!, NasContent.of(data), nextModified++)
        catalog.inner.putPhoneCopy(mediaId, v0, ref, world.phone.stamp(phoneId)!!)
      }
      FiveBCategory.PHONE_ONLY -> {
        val ref = world.phone.add(phoneId!!, NasContent.of(data), nextModified++)
        catalog.inner.putPhoneCopy(mediaId, v0, ref, world.phone.stamp(phoneId)!!)
      }
    }
    return rec
  }

  // =========================================================================================================================
  // Il catalogo come lo vedono i motori
  // =========================================================================================================================

  /** Il candidato del backup per ogni versione sul telefono che non ha ancora una copia privata (come li darebbe il piano della 5A). */
  suspend fun candidates(): List<BackupCandidate> {
    val out = ArrayList<BackupCandidate>()
    for (id in catalog.allMedia()) {
      val media = catalog.media(id) ?: continue
      if (!media.mine) continue
      val meta = catalog.metaOf(id)
      for (v in media.versions) {
        val phone = v.phone.firstOrNull { it.present } ?: continue
        if (v.nas.any { it.present && it.share.equals(world.share, ignoreCase = true) }) continue
        out += BackupCandidate(
          mediaId = id, versionId = v.versionId, phone = phone.ref, stamp = phone.stamp, size = v.size, kind = media.kind,
          source = BackupSource.Camera, localDay = meta?.localDay, dateEstimated = false, fileName = meta?.name ?: "x.jpg",
          sortTime = meta?.sortTime ?: 0, quickHash = null, original = v.kind == VersionKind.ORIGINAL,
        )
      }
    }
    return out
  }

  /** Le righe del telefono presenti di un media. */
  fun phoneRowsOf(rec: MediaRec): List<Long> = rec.phoneIds.filter { world.phone.stamp(it) != null }

  fun currentRow(rec: MediaRec): Long? = rec.phoneIds.lastOrNull { world.phone.stamp(it) != null }

  // =========================================================================================================================
  // La scansione: la lettura del telefono e quella del NAS registrano nel catalogo cio' che e' cambiato da fuori
  // =========================================================================================================================

  /**
   * Il risultato di una scansione: i media il cui telefono e' tornato a una versione nota (Samsung "Ripristina originale", E-E2) e quelli
   * il cui file e' stato cambiato dal PC (E-E6).
   */
  class ScanOutcome(val reverted: List<MediaId>, val pcChanged: List<MediaId>) {
    companion object {
      val NONE = ScanOutcome(emptyList(), emptyList())
    }
  }

  /**
   * Come la lettura del telefono e la scansione del NAS: il catalogo ritrova le righe rinumerate, segna sparite quelle cancellate, registra
   * una versione nuova per un file riscritto (o il ritorno a una versione nota) e per un file del NAS cambiato dal PC. Un media con un lavoro
   * aperto non si tocca dalla scansione del NAS (il lavoro sta spostando i suoi file), tranne uno fermo per `SOURCE_CHANGED`: aspetta proprio
   * questo.
   */
  suspend fun rescan(): ScanOutcome {
    val reverted = ArrayList<MediaId>()
    val pcChanged = ArrayList<MediaId>()
    val jobs = journal.all()
    for (rec in registry.values) {
      val media = catalog.media(rec.mediaId) ?: continue
      // --- Il telefono ---
      val copy = media.versions.flatMap { v -> v.phone.map { v to it } }.firstOrNull { it.second.present }
      if (copy != null) {
        val (version, phone) = copy
        val row = currentRow(rec)
        if (row == null) {
          catalog.phoneVanished(phone.locationId)
        } else {
          val ref = world.phone.ref(row)!!
          val stamp = world.phone.stamp(row)!!
          if (phone.ref.mediaStoreId != row || phone.ref.mediaStoreVersion != ref.mediaStoreVersion) {
            catalog.inner.phoneRenumbered(rec.mediaId, phone.ref.mediaStoreId, ref)
          }
          if (stamp != phone.stamp) {
            val sha = world.phone.sha256(row)!!
            // Una versione nota per l'impronta del banco o per quella del catalogo (una correzione: la riga del telefono riscritta torna alla versione corretta).
            val known = media.versions.firstOrNull { (rec.versionSha[it.versionId] ?: it.fullHash) == sha }
            if (known != null) {
              catalog.inner.phoneReverted(rec.mediaId, ref, stamp, known.versionId)
              if (known.versionId != version.versionId) reverted += rec.mediaId
            } else {
              val v = catalog.inner.phoneChanged(rec.mediaId, ref, stamp, stamp.size, fullHash = null)
              rec.versionSha[v] = sha
              knownShas += sha
            }
          }
        }
      }
      // --- Il NAS: il PC ha cambiato il file al solito percorso ---
      // Il solito percorso di un media nato dal guardiano (solo area comune) nasce con la sua prima copia privata fuori da `Versioni/`.
      if (rec.usualPath == null) {
        media.versions.flatMap { it.nas }.firstOrNull { it.present && it.share.equals(world.share, ignoreCase = true) && !VersionPaths.isInVersions(it.path) }
          ?.let { rec.usualPath = it.path }
      }
      val usual = rec.usualPath ?: continue
      val mine = jobs.filter { it.mediaId == rec.mediaId && !it.state.terminal }
      if (mine.any { it.hold != VersionHold.SOURCE_CHANGED }) continue
      val fresh = catalog.media(rec.mediaId) ?: continue
      // Il catalogo ritarda sul NAS solo dove il PC ha messo le mani: uno scambio che ha riportato un file al solito percorso e il PC l'ha modificato
      // subito dopo (il lavoro non lo riconosce: `FAILED`/`USUAL_PATH_TAKEN`) lascia una copia "presente" in `Versioni/` che li' non c'e' piu'. La
      // scansione vera non vede il file nel posto vecchio e la dichiara assente (D10, `countUnseenNas`). Altrove il catalogo deve essere gia' giusto da
      // se': nessun'altra copia si "sistema" qui, cosi' l'invariante 7 resta rigoroso per tutto il resto.
      if (rec.pcShas.isNotEmpty()) {
        for (version in fresh.versions) {
          for (copy in version.nas) {
            if (copy.present && copy.share.equals(world.share, ignoreCase = true) && stampOf(copy.share, copy.path) == null) {
              catalog.inner.nasVanished(rec.mediaId, copy.share, copy.path)
            }
          }
        }
      }
      val atUsual = fresh.versions.flatMap { v -> v.nas.map { v to it } }
        .firstOrNull { it.second.present && it.second.share.equals(world.share, ignoreCase = true) && it.second.path.value.equals(usual.value, ignoreCase = true) }
      if (atUsual != null) {
        val stamp = stampOf(world.share, usual)
        if (stamp != null && (stamp.size != atUsual.second.stamp.size || stamp.modifiedAtMillis != atUsual.second.stamp.modifiedAtMillis) && pcSha[key(world.share, usual)] != null) {
          val v = catalog.inner.nasChanged(rec.mediaId, world.share, usual, stamp)
          rec.versionSha[v] = pcSha.getValue(key(world.share, usual))
          rec.pcShas += pcSha.getValue(key(world.share, usual))
          pcChanged += rec.mediaId
        }
      }
    }
    return ScanOutcome(reverted, pcChanged)
  }

  /** Le impronte dei byte che il PC ha messo in un file (per registrare la versione quando la scansione lo vede). */
  private val pcSha = HashMap<String, Sha256>()

  private fun key(share: String, path: SharePath) = share.lowercase(Locale.ROOT) + ":" + path.value.lowercase(Locale.ROOT)

  // =========================================================================================================================
  // Le azioni da fuori
  // =========================================================================================================================

  /** Samsung "Salva" (E-E1): byte nuovi sulla riga del telefono. Vero se c'era una riga. La scansione registra la versione nuova. */
  fun samsungSave(rec: MediaRec): Boolean {
    val row = currentRow(rec) ?: return false
    val bytes = random.nextBytes(rec.size + 100 + random.nextInt(3_000))
    knownShas += Sha256.of(bytes)
    world.phone.rewrite(row, NasContent.of(bytes), nextModified++)
    return true
  }

  /** Come [samsungSave], ma la riga cambia una seconda volta mentre una lettura la sta leggendo (E-B15): la prima versione non arriva mai sul NAS. */
  fun samsungSaveWhileRead(rec: MediaRec): Boolean {
    val row = currentRow(rec) ?: return false
    if (!samsungSave(rec)) return false
    val bytes = random.nextBytes(rec.size + 100 + random.nextInt(3_000))
    knownShas += Sha256.of(bytes)
    world.phone.duringRead(row, 1L + random.nextLong(rec.size.toLong()), FakePhoneBytes.MidRead.Rewritten(NasContent.of(bytes), nextModified++))
    return true
  }

  /** Samsung "Ripristina originale" (E-E2): la riga torna ai byte dell'originale. */
  fun samsungRevert(rec: MediaRec): Boolean {
    val row = currentRow(rec) ?: return false
    val original = rec.originalBytes ?: return false
    if (world.phone.sha256(row) == Sha256.of(original)) return false
    world.phone.rewrite(row, NasContent.of(original), nextModified++)
    return true
  }

  /** Un editor esterno su una copia in cache di un solo-NAS (E-E3): il file preparato e la versione nel catalogo. Restituisce versione, impronta e dimensione. */
  fun stageEdit(rec: MediaRec): Triple<VersionId, Sha256, Long>? {
    val bytes = random.nextBytes(rec.size + 200 + random.nextInt(2_000))
    val sha = Sha256.of(bytes)
    knownShas += sha
    val work = staged.newWorkFile()
    work.parentFile?.mkdirs()
    work.writeBytes(bytes)
    staged.keep(work, sha, rec.mediaId)
    val v = catalog.inner.addVersion(rec.mediaId, VersionKind.EDITED, bytes.size.toLong(), sha, current = true)
    rec.versionSha[v] = sha
    return Triple(v, sha, bytes.size.toLong())
  }

  /**
   * Il PC modifica sul posto il file al solito percorso [path] (E-E6): byte nuovi, stesso `fileId`, data nuova. Solo dove il NAS lo lascia fare. La
   * scansione lo registra come versione nuova; i vecchi byte non si pretendono piu' (li ha sovrascritti il PC, non l'app).
   */
  suspend fun pcEdit(share: String, path: SharePath, exactSize: Int? = null): Boolean {
    if (!world.nas.caps.pcModify) return false
    if (VersionPaths.isInVersions(path) || NasFileFact.isAppArea(path.value)) return false
    val stamp = stampOf(share, path) ?: return false
    val oldSha = nasSha(share, path) ?: return false
    var size = exactSize ?: (stamp.size * (80 + random.nextInt(41)) / 100).coerceAtLeast(300).toInt()
    if (exactSize == null) {
      // F3: una modifica sul posto della STESSA dimensione e' quella che i servizi scambiano per il loro file (`sameIdentity`: `fileId` e dimensione).
      // Evitata per default (anche per caso: 1 volta su 41); senza l'evitamento meta' delle modifiche del PC conserva la dimensione.
      if (KnownDefect.F3 in config.avoid) {
        if (size.toLong() == stamp.size) size += 1
      } else if (random.nextBoolean()) {
        size = stamp.size.toInt()
      }
    }
    val bytes = random.nextBytes(size)
    val sha = Sha256.of(bytes)
    world.nas.pcModify(share, path.value, bytes, stamp.modifiedAtMillis + 1_000L + random.nextInt(5_000))
    knownShas += sha
    lostShas += oldSha
    pcSha[key(share, path)] = sha
    registry.values.firstOrNull { it.usualPath?.value.equals(path.value, ignoreCase = true) && share.equals(world.share, ignoreCase = true) }?.pcShas?.add(sha)
    factOf(share, path, bytes)?.let(::learn)
    return true
  }

  /** Il PC crea un file al nome [path] se e' libero (un file di altri dove l'app stava per pubblicare). */
  suspend fun pcCreate(share: String, path: SharePath): Boolean {
    if (NasFileFact.isAppArea(path.value)) return false
    if (stampOf(share, path) != null) return false
    val bytes = random.nextBytes(800 + random.nextInt(2_000))
    world.nas.pcPut(share, path.value, bytes, nowMs())
    val sha = Sha256.of(bytes)
    knownShas += sha
    // Un file di altri al solito percorso di un media: l'app non lo sostituisce mai (D17), la versione nuova va in `Versioni/`.
    registry.values.firstOrNull { it.usualPath?.value.equals(path.value, ignoreCase = true) && share.equals(world.share, ignoreCase = true) }?.pcShas?.add(sha)
    factOf(share, path, bytes)?.let(::learn)
    return true
  }

  /** Il marciume di un bit (E-B8): un byte cambia sul NAS conservando dimensione, data e `fileId`. Solo il rileggere per intero se ne accorge. */
  suspend fun tamper(rec: MediaRec): Boolean {
    if (!world.nas.caps.pcModify) return false
    val usual = rec.usualPath ?: return false
    val stamp = stampOf(world.share, usual) ?: return false
    val bytes = nasBytes(world.share, usual) ?: return false
    if (bytes.isEmpty()) return false
    val oldSha = Sha256.of(bytes)
    val flipped = bytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
    world.nas.pcModify(world.share, usual.value, flipped, stamp.modifiedAtMillis)
    knownShas += Sha256.of(flipped)
    lostShas += oldSha
    rec.tampered = true
    factOf(world.share, usual, flipped)?.let(::learn)
    return true
  }

  /** Il MediaStore si ricostruisce (E-P12): versione nuova e `_ID` rinumerati; il catalogo li ritrova alla prossima scansione. */
  fun phoneRebuild() {
    val renumber = HashMap<Long, Long>()
    for (rec in registry.values) {
      val id = currentRow(rec) ?: continue
      val fresh = world.nextFreshId()
      renumber[id] = fresh
      rec.phoneIds += fresh
    }
    world.phone.rebuild("v5b-${++rebuilds}", renumber)
  }

  /** L'utente cancella una foto dal telefono (E-B4). */
  fun phoneDelete(rec: MediaRec): Boolean {
    val row = currentRow(rec) ?: return false
    world.phone.delete(row)
    return true
  }

  /** Il telefono si riavvia: il conteggio dei riavvii cresce e l'orologio monotono riparte. */
  fun reboot() {
    bootCount++
    world.clock.monotonic = 2_000L
  }

  /** Passano delle ore: l'orologio monotono (e l'ora del NAS, dove il NAS la lascia spostare). */
  fun timePasses(ms: Long) {
    if (world.nas.caps.advanceTime) world.advanceTime(ms) else world.clock.monotonic += ms
  }

  /** L'orologio di prima del riavvio non torna: dopo [reboot] le verifiche vecchie non contano (le scrive il banco con il riavvio di allora). */
  fun noteVerified(location: LocationId, ok: Boolean) {
    if (ok) verifiedAt[location] = world.clock.monotonicMs() to bootCount else verifiedAt.remove(location)
  }

  // =========================================================================================================================
  // Lo stato per il verificatore
  // =========================================================================================================================

  suspend fun capture(
    scan: ScanResult,
    notes: List<Note>,
    cited: Set<String>,
  ): FiveBState {
    val visible = scan.files.filter { !it.appArea && it.share.equals(world.share, ignoreCase = true) }.map { it.sha }.toSet()
    // I contenuti legittimi comprendono i byte che il catalogo ha registrato (una correzione, un file di un editor): vengono da un file vero.
    for (id in catalog.allMedia()) catalog.inner.media(id)?.versions?.mapNotNullTo(knownShas) { it.fullHash }
    val labels = registry.values.associate { it.mediaId to it.text }
    val mediaFacts = ArrayList<FiveBMediaFact>()
    for (rec in registry.values) {
      val vm = catalog.media(rec.mediaId) ?: continue
      for (v in vm.versions) {
        // Una correzione nasce nel catalogo con l'impronta dei byte che ha scritto lo scrittore dei metadati: il banco la adotta.
        if (rec.versionSha[v.versionId] == null && v.fullHash != null && v.kind == VersionKind.CORRECTED) rec.versionSha[v.versionId] = v.fullHash!!
        val sha = rec.versionSha[v.versionId] ?: v.fullHash
        if (sha != null && sha in visible) rec.seen += sha
      }
      mediaFacts += FiveBMediaFact(
        label = rec.text,
        mine = vm.mine,
        category = rec.category.code,
        current = vm.currentVersionId.value,
        versions = vm.versions.map { v ->
          val sha = rec.versionSha[v.versionId]
          FiveBVersionFact(v.versionId.value, v.kind.code, v.size, sha, v.fullHash, seenOnNas = (sha ?: v.fullHash)?.let { it in rec.seen } == true)
        },
        copies = vm.versions.flatMap { v ->
          v.nas.map { FiveBCopyFact(v.versionId.value, it.share, it.path.value, it.stamp.size, it.verifiedAtMillis != null, it.present) }
        },
        usualShare = world.share,
        usualPath = rec.usualPath?.value,
        tampered = rec.tampered,
        pcShas = rec.pcShas.toList(),
        phoneShas = phoneRowsOf(rec).mapNotNull { world.phone.sha256(it) },
        stagedShas = staged.pending().filter { it.mediaId == rec.mediaId }.map { it.sha256 },
      )
    }
    return FiveBState(
      deviceId = world.deviceId,
      privateShare = world.share,
      commonShare = world.commonShare,
      before = before.values.toList(),
      files = scan.files,
      knownShas = knownShas.toSet(),
      lostShas = lostShas.toSet(),
      media = mediaFacts,
      jobs = journal.all().map {
        FiveBJobFact(it.id.value, it.kind.code, it.state.code, labels[it.mediaId] ?: "?", it.versionId.value, it.hold?.code, it.problem?.code, it.temp?.value)
      },
      transitions = journal.transitions.map { FiveBTransitionFact(it.job.value, it.flow.code, it.from.code, it.to.code) },
      cited = cited,
      free = freeDiary.everything().map { (runId, item) ->
        val row = item.phone?.mediaStoreId
        FiveBFreeFact(
          runId.value, item.index, labels[item.mediaId] ?: "?", item.state.code,
          removedFromPhone = row != null && (runId.value to row) in removedRows,
          rowOnPhone = row != null && world.phone.stamp(row) != null,
        )
      },
      notes = notes,
      cacheHits = scan.cacheHits,
      filesHashed = scan.filesHashed,
    )
  }

  companion object {
    const val DAY_MS = 86_400_000L
  }
}
