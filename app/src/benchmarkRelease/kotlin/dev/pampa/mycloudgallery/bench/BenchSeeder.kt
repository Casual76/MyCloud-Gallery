package dev.pampa.mycloudgallery.bench

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.pampa.mycloudgallery.core.database.GalleryDatabase
import dev.pampa.mycloudgallery.core.database.dao.TimelineIndexDao
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.LocationType
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import java.util.Locale
import java.util.SplittableRandom
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** La replica sintetica del dataset, in numeri: finisce nello stato letto dal collaudo. */
internal class BenchReplicaReport(
  val blobs: Int,
  val blobsS: Int,
  val blobsM: Int,
  val blobsHq: Int,
  /** Quanti modelli distinti (un modello serve tante chiavi di contenuto). */
  val templates: Int,
  val packBytes: Long,
  val indexBytes: Long,
  val averageBytesS: Int,
  val averageBytesM: Int,
  val averageBytesHq: Int,
  /** Millisecondi spesi a generare i modelli in questa corsa (0 se c'erano gia'). */
  val templateMs: Long,
  val check: BenchReplicaCheck,
)

/** L'esito della creazione del dataset: solo numeri, finiscono nello stato letto dal collaudo. */
internal class BenchSeedReport(
  val rows: Int,
  val realMedia: Int,
  val realImages: Int,
  val realVideos: Int,
  /** Megapixel dell'immagine piu' grande dei media veri (0 senza media). */
  val maxMegapixels: Double,
  /** Vero se la riga piu' recente punta all'immagine piu' grande (il soggetto del test di zoom a 200 MP). */
  val zoomSubjectNewest: Boolean,
  val seedMs: Long,
  /** Righe lette da `TimelineIndexDao.indexCursor()` rileggendo il file con un'istanza nuova del database. */
  val indexRows: Int,
  val indexReadMs: Long,
  /** Su 200 identita' dell'indice con una posizione sul telefono, quante `phoneRefs` ne risolvono (devono essere tutte). */
  val phoneRefsResolved: Int,
  val phoneRefsAsked: Int,
  // --- Fase 4A (P10): la libreria mista, contata dall'indice riletto (flag di presenza, non dai conti del seeder) ------
  val phoneOnlyRows: Int,
  val nasOnlyRows: Int,
  val bothRows: Int,
  val headPhoneRows: Int,
  val headNasRows: Int,
  /** Righe solo-NAS con l'HQ in replica. */
  val hqRows: Int,
  val profile: BenchProfile,
  val replica: BenchReplicaReport,
  // --- Fase 9 (P12): coordinate, giorni "di un anno fa" e scaffale dei ricordi seminato ---------------------------------------
  /** Righe con una posizione (latitudine e longitudine): il 60% della Libreria, quelle che hanno un luogo. */
  val positionRows: Int = 0,
  /** Righe che hanno preso una data attorno a "oggi" di 1 e di 3 anni fa (`BenchAnniversary`). */
  val anniversaryRows: Int = 0,
  /** Ricordi nello scaffale seminato. */
  val shelfMemories: Int = 0,
  /** "Oggi" alla creazione, `aaaammgg`: il dataset si rifa' quando e' troppo vecchio (`BenchAnniversary.VALID_DAYS`). */
  val seedDay: Int = 0,
)

/**
 * Riempie un database del catalogo (lo schema vero di `GalleryDatabase`) con 100 mila righe sintetiche (§5.2) che
 * puntano **a rotazione ai media veri del dispositivo**, nella forma che l'indice della timeline legge: `media` con i
 * campi derivati, `media_version`, `media_location` (telefono, `_ID` vero, versione del MediaStore del cursore; NAS: account,
 * share, percorso), `version_metadata`. Tutto locale, nessuna rete.
 *
 * - Forma deterministica: seme fisso, 15 anni di storia con giorni "a raffica" (feste, viaggi) e giornate vuote, video
 *   come i media veri, screenshot 10%, WhatsApp 20%, Quick Share 2%, preferiti 2%, date stimate 3%, luoghi 60% (id veri
 *   di GeoNames per le intestazioni), fusi diversi nei "viaggi", panorami dove la proporzione lo e'. **La sequenza dei
 *   numeri casuali e' quella del 13/10**: date, luoghi, proporzioni e flag di ogni riga sono gli stessi, cosi' la guardia
 *   della Fase 4 (§5.6) confronta la stessa griglia.
 * - **Libreria mista (P10).** Per ogni riga si decide la presenza: le piu' recenti per ordine di data sono forzate dal
 *   profilo ([BenchDataset.HEAD_PHONE_ROWS] sul telefono in `MIXED`, [BenchDataset.HEAD_NAS_ROWS] solo-NAS in `NAS_FIRST`);
 *   le altre per hash della riga: 60% solo-NAS, 6% telefono e NAS, il resto solo telefono. Una riga con il NAS ha una
 *   posizione `nas` (account, share, percorso), la chiave di contenuto vera (`ContentKey.of(qh1, dimensione)`), un ThumbHash
 *   e i blob S e M nell'indice della replica ([BenchReplicaStore]); un'alta quota ha anche l'HQ.
 * - La riga piu' recente punta all'immagine piu' grande (se l'ha il dispositivo): la Libreria si apre in fondo (D1), quindi
 *   il test di zoom a 200 MP la trova nell'ultima cella senza cercarla.
 * - Mai il database vero (`gallery.db`): il nome e' [BenchProfile.databaseName]. E mai nemmeno quel file direttamente (C1): l'app lo ha aperto in questo
 *   stesso processo. Si scrive il file del seminatore e lo scambio avviene al prossimo avvio ([BenchDatabaseFiles]).
 * - Nessuna riga nascosta e nessun gruppo: la timeline le contiene tutte, come in una Libreria "normale".
 */
internal object BenchSeeder {

  /** Un volume e una versione del MediaStore: quella vera, cosi' `phoneRefs` (che li confronta) risolve ogni riga. */
  fun seed(
    context: Context,
    real: List<BenchRealMedia>,
    rows: Int,
    msVersion: String,
    now: Long,
    profile: BenchProfile,
    smoke: Boolean,
  ): BenchSeedReport {
    // C1: mai il file dell'app (`profile.databaseName`), che in questo processo e' gia' aperto dal grafo. Il dataset nasce in un file solo del
    // seminatore e diventa quello dell'app al prossimo avvio (`BenchDatabaseFiles`).
    BenchDatabaseFiles.discardBuilding(context, profile)
    val building = BenchDatabaseFiles.buildingName(profile)
    val hasReal = real.isNotEmpty()
    val media = if (hasReal) real else FALLBACK_MEDIA
    val zoomIndex = media.indices.maxByOrNull { index -> if (media[index].video) -1.0 else media[index].megapixels } ?: 0
    val zoomSubject = hasReal && !media[zoomIndex].video

    // I modelli delle miniature prima del database: se si rifanno, il tempo si vede a parte (non e' "creazione delle righe").
    val templates = BenchReplicaBuilder.templates(context, smoke)

    val startNs = System.nanoTime()
    val plan = plan(media, zoomIndex.takeIf { zoomSubject }, rows, now, profile)
    val index = BenchReplicaIndexWriter(rows)
    val sampleKeys = ArrayList<Long>(SAMPLE_KEYS)
    var hqRows = 0
    val totals = WriteTotals()

    val db = GalleryDatabase.build(context, building)
    try {
      val sdb = db.openHelper.writableDatabase
      sdb.beginTransaction()
      try {
        hqRows = writeAll(sdb, media, plan, templates, index, sampleKeys, rows, msVersion, now, totals)
        sdb.setTransactionSuccessful()
      } finally {
        sdb.endTransaction()
      }
    } finally {
      db.close()
    }
    index.writeTo(BenchReplicaStore.indexFile(context, profile))
    val seedMs = (System.nanoTime() - startNs) / 1_000_000

    // P12: lo scaffale dei ricordi seminato (solo il profilo misto: e' quello di tutti gli scenari che aprono Raccolte).
    val anchor = java.time.Instant.ofEpochMilli(now).atZone(TimeZone.getDefault().toZoneId()).toLocalDate()
    val shelfMemories = if (profile == BenchProfile.MIXED) {
      BenchShelf.write(java.io.File(context.noBackupFilesDir, BenchShelf.DIR), anchor) { day -> shelfMembers(plan, rows, day) }
    } else {
      0
    }

    val store = BenchReplicaStore.open(context, profile, templates.packFile)
    val check = if (store != null) BenchReplicaVerifier.check(store, sampleKeys.toLongArray()) else BenchReplicaCheck(sampleKeys.size, 0, 0.0, 0.0)
    val replica = BenchReplicaReport(
      blobs = index.size,
      blobsS = index.count(BenchBlobClass.THUMB_S),
      blobsM = index.count(BenchBlobClass.THUMB_M),
      blobsHq = index.count(BenchBlobClass.HQ),
      templates = templates.templates.size,
      packBytes = templates.packBytes,
      indexBytes = store?.indexBytes ?: 0L,
      averageBytesS = templates.averageBytesS,
      averageBytesM = templates.averageBytesM,
      averageBytesHq = templates.averageBytesHq,
      templateMs = templates.generationMs,
      check = check,
    )

    // Rilettura con un'istanza nuova, come fara' l'app: stesso file, stesso schema, le query vere di P1.
    val reopened = GalleryDatabase.build(context, building)
    val report = try {
      val dao = reopened.timelineIndexDao()
      val readStart = System.nanoTime()
      val phoneIds = ArrayList<String>(CHECK_IDS)
      var count = 0
      var phoneOnly = 0
      var nasOnly = 0
      var both = 0
      dao.indexCursor().use { c ->
        val iId = c.getColumnIndexOrThrow("media_id")
        val iFlags = c.getColumnIndexOrThrow("cell_flags")
        while (c.moveToNext()) {
          val flags = c.getInt(iFlags)
          val onPhone = CellFlags.has(flags, CellFlags.ON_PHONE)
          val onNas = CellFlags.has(flags, CellFlags.ON_NAS)
          when {
            onPhone && onNas -> both++
            onNas -> nasOnly++
            else -> phoneOnly++
          }
          if (onPhone && phoneIds.size < CHECK_IDS) phoneIds += c.getString(iId)
          count++
        }
      }
      val readMs = (System.nanoTime() - readStart) / 1_000_000
      // Una sola volta, su un thread di collaudo: la funzione di P1 e' `suspend`.
      val resolved = runBlocking { dao.phoneRefs(phoneIds).map { it.mediaId }.toSet().size }
      // Tutto nel file principale prima di chiudere: il dataset pronto e' un file solo.
      BenchDatabaseFiles.checkpoint(reopened.openHelper.writableDatabase)
      BenchSeedReport(
        rows = rows,
        realMedia = if (hasReal) real.size else 0,
        realImages = if (hasReal) real.count { !it.video } else 0,
        realVideos = if (hasReal) real.count { it.video } else 0,
        maxMegapixels = if (zoomSubject) media[zoomIndex].megapixels else 0.0,
        zoomSubjectNewest = zoomSubject && profile == BenchProfile.MIXED,
        seedMs = seedMs,
        indexRows = count,
        indexReadMs = readMs,
        phoneRefsResolved = resolved,
        phoneRefsAsked = phoneIds.size,
        phoneOnlyRows = phoneOnly,
        nasOnlyRows = nasOnly,
        bothRows = both,
        headPhoneRows = if (profile == BenchProfile.MIXED) headSize(rows, BenchDataset.HEAD_PHONE_ROWS) else 0,
        headNasRows = if (profile == BenchProfile.NAS_FIRST) headSize(rows, BenchDataset.HEAD_NAS_ROWS) else 0,
        hqRows = hqRows,
        profile = profile,
        replica = replica,
        positionRows = totals.positionRows,
        anniversaryRows = plan.anniversaryRows,
        shelfMemories = shelfMemories,
        seedDay = dayNumber(anchor),
      )
    } finally {
      reopened.close()
    }
    // L'ultimo passo, una rinomina: da qui il dataset aspetta il prossimo avvio. Il file dell'app non e' stato toccato.
    BenchDatabaseFiles.publish(context, profile)
    return report
  }

  // --- Il piano: una passata sola, con la stessa sequenza di numeri casuali del 13/10 --------------------------------------

  private const val PHONE_ONLY: Byte = 0
  private const val NAS_ONLY: Byte = 1
  private const val BOTH: Byte = 2

  /** Cio' che si sa di ogni riga prima di scriverla, in colonne di primitivi (100 mila oggetti non servono). */
  private class Plan(rows: Int) {
    val realIndex = IntArray(rows)
    val sortTime = LongArray(rows)
    val offsetMin = IntArray(rows)
    val localDay = IntArray(rows)
    val aspect = IntArray(rows)
    val flags = IntArray(rows)
    val placeId = IntArray(rows)
    val presence = ByteArray(rows)
    val hq = BooleanArray(rows)

    /** Il giorno locale (`aaaammgg`) delle righe che hanno preso una data di anniversario (P12), 0 per le altre. */
    val anniversaryDay = IntArray(rows)
    var anniversaryRows = 0
  }

  /** Quello che la scrittura conta per il rapporto. */
  private class WriteTotals {
    var positionRows = 0
  }

  private fun dayNumber(day: java.time.LocalDate): Int = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth

  /** Gli id dei media del telefono (non solo-NAS: le loro miniature si disegnano davvero) che hanno preso la data [day] di anniversario. */
  private fun shelfMembers(plan: Plan, rows: Int, day: java.time.LocalDate): List<String> {
    val wanted = dayNumber(day)
    val out = ArrayList<String>()
    for (i in 0 until rows) {
      if (plan.anniversaryDay[i] == wanted && plan.presence[i] != NAS_ONLY) out += uuid(MEDIA_TAG, i)
    }
    return out
  }

  private fun headSize(rows: Int, wanted: Int): Int = wanted.coerceAtMost(rows / 10)

  private fun plan(media: List<BenchRealMedia>, zoomIndex: Int?, rows: Int, now: Long, profile: BenchProfile): Plan {
    val p = Plan(rows)
    val rnd = SplittableRandom(SEED)
    val zone = TimeZone.getDefault()
    val newest = now - 60_000L
    val spanMs = (15 * 365.25 * DAY_MS).toLong()
    // I giorni "a raffica" (feste, viaggi): il 38% delle righe cade in 240 giorni; il resto in 2.600 giorni "normali" su
    // 5.479: meta' delle giornate dei 15 anni restano vuote, come in una libreria vera.
    val hotDays = LongArray(HOT_DAYS) { newest - 3_600_000L - (rnd.nextDouble() * spanMs).toLong() }
    val activeDays = LongArray(ACTIVE_DAYS) { newest - 3_600_000L - (rnd.nextDouble() * spanMs).toLong() }

    for (i in 0 until rows) {
      val realIndex = if (i == 0 && zoomIndex != null) zoomIndex else i % media.size
      val m = media[realIndex]

      val sortTime = if (i == 0 && zoomIndex != null) {
        newest
      } else {
        val day = if (rnd.nextInt(100) < HOT_SHARE) hotDays[rnd.nextInt(HOT_DAYS)] else activeDays[rnd.nextInt(ACTIVE_DAYS)]
        (day - rnd.nextInt(DAY_MS.toInt())).coerceAtMost(newest - 1_000L)
      }
      val offsetMin = if (rnd.nextInt(100) < TRIP_SHARE) TRIP_OFFSETS[rnd.nextInt(TRIP_OFFSETS.size)] else zone.getOffset(sortTime) / 60_000

      val swap = m.rotation == 90 || m.rotation == 270
      val shownW = if (swap) m.height else m.width
      val shownH = if (swap) m.width else m.height
      val aspect = (shownW * 1000L / shownH.coerceAtLeast(1)).toInt().coerceIn(TimelineRules.MIN_ASPECT_MILLI, TimelineRules.MAX_ASPECT_MILLI)

      var flags = 0
      val placeId = if (rnd.nextInt(100) < PLACE_SHARE) PLACES[rnd.nextInt(PLACES.size)] else null
      if (placeId != null) flags = flags or CellFlags.HAS_PLACE
      if (rnd.nextInt(100) < FAVORITE_SHARE) flags = flags or CellFlags.FAVORITE
      if (rnd.nextInt(100) < ESTIMATED_SHARE) flags = flags or CellFlags.DATE_ESTIMATED
      if (aspect >= PANORAMA_ASPECT_MILLI) flags = flags or CellFlags.PANORAMA

      p.realIndex[i] = realIndex
      p.sortTime[i] = sortTime
      p.offsetMin[i] = offsetMin
      p.localDay[i] = TimelineRules.localDay(sortTime, offsetMin)
      p.aspect[i] = aspect
      p.flags[i] = flags
      p.placeId[i] = placeId ?: 0
    }

    // Fase 9 (P12): un gruppo di righe, scelte da un passo fisso (mai dalla sequenza casuale di sopra), prende le date attorno a "oggi" di 1 e di 3 anni fa:
    // i ricordi "N anni fa oggi" hanno cosi' i loro anniversari veri. Tutto il resto delle righe e' quello del 13/10.
    val zoneId = zone.toZoneId()
    val anchor = java.time.Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
    for (j in 0 until BenchAnniversary.total(rows)) {
      val i = BenchAnniversary.row(rows, j)
      if (i >= rows) break
      val time = BenchAnniversary.timeMs(anchor, rows, j, zoneId)
      val offset = zone.getOffset(time) / 60_000
      p.sortTime[i] = time
      p.offsetMin[i] = offset
      p.localDay[i] = TimelineRules.localDay(time, offset)
      p.anniversaryDay[i] = p.localDay[i]
      p.anniversaryRows++
    }

    // Presenza: la testa (le righe piu' recenti per data, come le ordina l'indice: sort_time e poi media_id, dal piu'
    // recente) la fissa il profilo; le altre dipendono da un hash della riga, senza toccare la sequenza casuale di sopra.
    val headPhone = if (profile == BenchProfile.MIXED) headSize(rows, BenchDataset.HEAD_PHONE_ROWS) else 0
    val headNas = if (profile == BenchProfile.NAS_FIRST) headSize(rows, BenchDataset.HEAD_NAS_ROWS) else 0
    val head = maxOf(headPhone, headNas)
    val forced = ByteArray(rows) { NONE_FORCED }
    if (head > 0) {
      val keys = LongArray(rows) { (p.sortTime[it] shl ROW_BITS) or it.toLong() }
      keys.sort()
      for (k in 0 until head) {
        val i = (keys[rows - 1 - k] and ROW_MASK).toInt()
        forced[i] = if (headNas > 0) NAS_ONLY else PHONE_ONLY
      }
    }
    for (i in 0 until rows) {
      p.presence[i] = when {
        forced[i] != NONE_FORCED -> forced[i]
        else -> {
          val h = hash100(i, 0)
          when {
            h < BenchDataset.NAS_ONLY_PERCENT -> NAS_ONLY
            h < BenchDataset.NAS_ONLY_PERCENT + BenchDataset.BOTH_PERCENT -> BOTH
            else -> PHONE_ONLY
          }
        }
      }
      p.hq[i] = p.presence[i] == NAS_ONLY && (forced[i] == NAS_ONLY || hash100(i, 1) < BenchDataset.HQ_PERCENT)
    }
    return p
  }

  /** Un hash della riga in 0..99, indipendente dalla sequenza casuale (due "sali" per due decisioni indipendenti). */
  private fun hash100(i: Int, salt: Int): Int {
    var x = (i.toLong() + 1L) * -7046029254386353131L + salt * 0x632BE59BD9B4E019L
    x = x xor (x ushr 29)
    x *= -4658895280553007687L
    x = x xor (x ushr 32)
    return ((x and 0x7FFFFFFFL) % 100).toInt()
  }

  // --- La scrittura --------------------------------------------------------------------------------------------------------

  /** Scrive le righe; restituisce quante solo-NAS hanno l'HQ. */
  private fun writeAll(
    sdb: SupportSQLiteDatabase,
    media: List<BenchRealMedia>,
    plan: Plan,
    templates: BenchTemplateSet,
    index: BenchReplicaIndexWriter,
    sampleKeys: MutableList<Long>,
    rows: Int,
    msVersion: String,
    now: Long,
    totals: WriteTotals,
  ): Int {
    // La chiave di contenuto puo' stare in una colonna dello schema v3 (P2): se c'e' la si scrive, altrimenti la si ricava
    // da `quick_hash` e `size` (`ContentKey.of`), che e' quello che il dataset scrive comunque.
    val keyColumns = listOf("content_key", "content_key_short", "ck_short")
    val tMedia = BenchTable(
      sdb, "media",
      listOf(
        "media_id", "kind", "source", "owner_account", "group_id", "current_version_id", "created_at", "updated_at", "sort_time",
        "presence", "safety", "local_day", "aspect_milli", "duration_ms", "place_id", "cell_flags", "thumb_hash",
      ),
      optional = keyColumns,
    )
    val tVersion = BenchTable(
      sdb, "media_version",
      listOf("version_id", "media_id", "kind", "parent_version_id", "size", "quick_hash", "full_hash", "corrections", "created_at"),
      optional = keyColumns,
    )
    val tLocation = BenchTable(
      sdb, "media_location",
      listOf(
        "version_id", "location_type", "location_key", "folder_key", "state", "volume", "relative_path", "display_name",
        "media_store_id", "media_store_version", "generation", "account_id", "share_name", "share_key", "nas_path", "size",
        "modified_at", "absent_since", "verified_at", "last_seen_at",
      ),
      optional = keyColumns,
    )
    val tMeta = BenchTable(
      sdb, "version_metadata",
      listOf(
        "version_id", "mime_type", "width", "height", "orientation", "duration_ms", "taken_at_utc", "taken_offset_min",
        "taken_confidence", "taken_source", "latitude", "longitude", "altitude", "updated_at",
      ),
    )

    val account = AccountId.of(NAS_ACCOUNT).value
    var hqRows = 0
    var nasSeen = 0
    val sampleEvery = (rows / SAMPLE_KEYS).coerceAtLeast(1)

    for (i in 0 until rows) {
      val m = media[plan.realIndex[i]]
      val sortTime = plan.sortTime[i]
      val presence = plan.presence[i]
      val onNas = presence != PHONE_ONLY
      val onPhone = presence != NAS_ONLY

      val kind = when {
        m.video -> MediaKind.VIDEO
        m.mime == "image/gif" -> MediaKind.ANIMATED_IMAGE
        m.mime.contains("dng") -> MediaKind.RAW_PHOTO
        else -> MediaKind.PHOTO
      }
      val source = when ((i.toLong() * 2_654_435_761L % 100).toInt()) {
        in 0 until 10 -> MediaSource.SCREENSHOT
        in 10 until 30 -> MediaSource.WHATSAPP
        in 30 until 32 -> MediaSource.QUICK_SHARE
        else -> MediaSource.CAMERA
      }

      val mediaId = uuid(MEDIA_TAG, i)
      val versionId = uuid(VERSION_TAG, i)
      val displayName = "%06d_%s".format(Locale.ROOT, i, safeName(m.name))
      val duration = if (m.video) m.durationMs else null
      val quickHash = "%064x".format(Locale.ROOT, i)

      // Presenza e flag. Per una riga con il NAS: la chiave di contenuto, il modello della miniatura e il ThumbHash.
      var flags = plan.flags[i]
      val contentKey = ContentKey.of(QuickHash(quickHash), m.size).short
      var template: BenchTemplate? = null
      if (onPhone) flags = flags or CellFlags.ON_PHONE
      if (onNas) {
        flags = flags or CellFlags.ON_NAS
        template = templates.template(BenchAspect.bucketOf(plan.aspect[i]), hash100(i, 2) + 100 * hash100(i, 3))
      }
      val safe = presence == BOTH && hash100(i, 4) < SAFE_OF_BOTH_PERCENT
      if (safe) flags = flags or CellFlags.SAFE

      val presenceCode = when (presence) {
        NAS_ONLY -> Presence.NAS_ONLY
        BOTH -> Presence.PHONE_AND_NAS
        else -> Presence.PHONE_ONLY
      }
      val commonShare = onNas && hash100(i, 5) < COMMON_SHARE_PERCENT
      val safety = when {
        safe -> SafetyStatus.SAFE
        !onNas -> SafetyStatus.NO_NAS_COPY
        commonShare -> SafetyStatus.ONLY_COMMON_COPY
        else -> SafetyStatus.PRIVATE_COPY_UNVERIFIED
      }

      tMedia.text("media_id", mediaId)
      tMedia.text("kind", kind.code)
      tMedia.text("source", source.code)
      tMedia.text("owner_account", null)
      tMedia.text("group_id", null)
      tMedia.text("current_version_id", versionId)
      tMedia.long("created_at", now)
      tMedia.long("updated_at", now)
      tMedia.long("sort_time", sortTime)
      tMedia.text("presence", presenceCode.code)
      tMedia.text("safety", safety.code)
      tMedia.int("local_day", plan.localDay[i])
      tMedia.int("aspect_milli", plan.aspect[i])
      tMedia.long("duration_ms", duration)
      tMedia.int("place_id", plan.placeId[i].takeIf { it != 0 })
      tMedia.int("cell_flags", flags)
      tMedia.blob("thumb_hash", template?.thumbHash)
      tMedia.optionalLong("content_key", contentKey)
      tMedia.commit()

      tVersion.text("version_id", versionId)
      tVersion.text("media_id", mediaId)
      tVersion.text("kind", VersionKind.ORIGINAL.code)
      tVersion.text("parent_version_id", null)
      tVersion.long("size", m.size)
      // Un hash rapido valido (64 cifre esadecimali minuscole, `QuickHash`), diverso per riga: con "bench-%08x" il
      // catalogo rifiutava la riga alla lettura dei dettagli e il viewer si chiudeva con un'eccezione (misura del 13/10).
      tVersion.text("quick_hash", quickHash)
      tVersion.text("full_hash", null)
      tVersion.text("corrections", "")
      tVersion.long("created_at", sortTime)
      tVersion.optionalLong("content_key", contentKey)
      tVersion.commit()

      if (onPhone) {
        val folder = FOLDERS.getValue(source)
        tLocation.text("version_id", versionId)
        tLocation.text("location_type", LocationType.PHONE.code)
        tLocation.text("location_key", "phone:${BenchDeviceMedia.VOLUME}:" + (folder + displayName).lowercase(Locale.ROOT))
        tLocation.text("folder_key", "phone:${BenchDeviceMedia.VOLUME}:" + folder.lowercase(Locale.ROOT))
        tLocation.text("state", LocationState.PRESENT.code)
        tLocation.text("volume", BenchDeviceMedia.VOLUME)
        tLocation.text("relative_path", folder)
        tLocation.text("display_name", displayName)
        tLocation.long("media_store_id", m.id)
        tLocation.text("media_store_version", msVersion)
        tLocation.long("generation", null)
        tLocation.text("account_id", null)
        tLocation.text("share_name", null)
        tLocation.text("share_key", null)
        tLocation.text("nas_path", null)
        tLocation.long("size", m.size)
        tLocation.long("modified_at", sortTime)
        tLocation.long("absent_since", null)
        tLocation.long("verified_at", null)
        tLocation.long("last_seen_at", now)
        tLocation.optionalLong("content_key", contentKey)
        tLocation.commit()
      }
      if (onNas) {
        val share = if (commonShare) "NAS" else NAS_PRIVATE_SHARE
        val shareKey = share.lowercase(Locale.ROOT)
        val year = plan.localDay[i] / 10_000
        val month = plan.localDay[i] / 100 % 100
        val nasName = "IMG_%06d.%s".format(Locale.ROOT, i, extensionOf(m))
        val folder = "Foto/%04d/%02d".format(Locale.ROOT, year, month)
        tLocation.text("version_id", versionId)
        tLocation.text("location_type", LocationType.NAS.code)
        tLocation.text("location_key", "nas:$account:$shareKey:" + "$folder/$nasName".lowercase(Locale.ROOT))
        tLocation.text("folder_key", "nas:$account:$shareKey:" + folder.lowercase(Locale.ROOT))
        tLocation.text("state", LocationState.PRESENT.code)
        tLocation.text("volume", null)
        tLocation.text("relative_path", null)
        tLocation.text("display_name", nasName)
        tLocation.long("media_store_id", null)
        tLocation.text("media_store_version", null)
        tLocation.long("generation", null)
        tLocation.text("account_id", account)
        tLocation.text("share_name", share)
        tLocation.text("share_key", shareKey)
        tLocation.text("nas_path", "$folder/$nasName")
        tLocation.long("size", m.size)
        tLocation.long("modified_at", sortTime)
        tLocation.long("absent_since", null)
        tLocation.long("verified_at", if (safe) now else null)
        tLocation.long("last_seen_at", now)
        tLocation.optionalLong("content_key", contentKey)
        tLocation.commit()
      }

      tMeta.text("version_id", versionId)
      tMeta.text("mime_type", m.mime)
      tMeta.int("width", m.width)
      tMeta.int("height", m.height)
      tMeta.int("orientation", exifOrientation(m.rotation))
      tMeta.long("duration_ms", duration)
      tMeta.long("taken_at_utc", sortTime)
      tMeta.int("taken_offset_min", plan.offsetMin[i])
      tMeta.text("taken_confidence", "exact")
      tMeta.text("taken_source", "exif_offset")
      // P12: il 60% delle righe (quelle che hanno un luogo) ha anche una posizione plausibile (`BenchGeo`).
      val spot = BenchGeo.position(i, plan.placeId[i])
      tMeta.real("latitude", spot?.latitude)
      tMeta.real("longitude", spot?.longitude)
      if (spot != null) totals.positionRows++
      tMeta.real("altitude", null)
      tMeta.long("updated_at", now)
      tMeta.commit()

      // La replica: le miniature S e M di ogni riga con il NAS, l'HQ di chi ce l'ha. La chiave e' la stessa che il catalogo
      // ricava dalla riga, quindi la ritrova anche dopo una rinomina (D4).
      if (template != null) {
        index.add(contentKey, BenchBlobClass.THUMB_S, template.s)
        index.add(contentKey, BenchBlobClass.THUMB_M, template.m)
        if (plan.hq[i]) {
          val big = templates.hqTemplate(template.bucket, hash100(i, 6))
          index.add(contentKey, BenchBlobClass.HQ, checkNotNull(big.hq))
          hqRows++
        }
        if (nasSeen++ % sampleEvery == 0 && sampleKeys.size < SAMPLE_KEYS) sampleKeys += contentKey
      }
    }
    tMedia.close()
    tVersion.close()
    tLocation.close()
    tMeta.close()

    // Il cursore del MediaStore: `phoneRefs` unisce le posizioni al cursore e vuole la stessa `ms_version`.
    val tCursor = BenchTable(
      sdb, "media_store_cursor",
      listOf("volume", "ms_version", "generation", "deleted_generation", "scan_epoch", "scan_resume_after_id", "last_full_scan_at", "last_reconcile_at"),
    )
    tCursor.text("volume", BenchDeviceMedia.VOLUME)
    tCursor.text("ms_version", msVersion)
    tCursor.long("generation", null)
    tCursor.long("deleted_generation", null)
    tCursor.long("scan_epoch", null)
    tCursor.long("scan_resume_after_id", null)
    tCursor.long("last_full_scan_at", now)
    tCursor.long("last_reconcile_at", now)
    tCursor.commit()
    tCursor.close()

    val tMeta2 = BenchTable(sdb, "catalog_meta", listOf("key", "value"))
    tMeta2.text("key", TimelineIndexDao.CHANGE_SEQ)
    tMeta2.long("value", 1L)
    tMeta2.commit()
    tMeta2.close()
    return hqRows
  }

  private fun uuid(tag: Long, i: Int): String = UUID(UUID_HI or tag, i.toLong()).toString()

  private fun safeName(name: String): String {
    val clean = name.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }.take(80)
    return clean.ifEmpty { "media.jpg" }
  }

  /** L'estensione del file sul NAS: quella del media vero se e' sensata, altrimenti da tipo MIME. */
  private fun extensionOf(m: BenchRealMedia): String {
    val fromName = m.name.substringAfterLast('.', "").lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(5)
    if (fromName.isNotEmpty()) return fromName
    return when {
      m.video -> "mp4"
      m.mime.contains("png") -> "png"
      m.mime.contains("heic") -> "heic"
      else -> "jpg"
    }
  }

  private fun exifOrientation(rotation: Int): Int = when (rotation) {
    90 -> 6
    180 -> 3
    270 -> 8
    else -> 1
  }

  // Senza media veri la variante si avvia lo stesso (il dataset ha la sua forma), ma le celle del telefono restano senza
  // immagine: lo stato dice `mediaReali = 0` e il collaudo lo segnala. Le celle del NAS hanno le loro miniature.
  private val FALLBACK_MEDIA: List<BenchRealMedia> = listOf(
    BenchRealMedia(1L, false, "image/jpeg", 4000, 3000, 0, 0L, 2_000_000L, 0L, "media.jpg"),
    BenchRealMedia(2L, false, "image/jpeg", 3000, 4000, 0, 0L, 2_000_000L, 0L, "media.jpg"),
    BenchRealMedia(3L, false, "image/jpeg", 4000, 2250, 0, 0L, 2_000_000L, 0L, "media.jpg"),
    BenchRealMedia(4L, true, "video/mp4", 1920, 1080, 0, 15_000L, 8_000_000L, 0L, "media.mp4"),
  )

  private val FOLDERS: Map<MediaSource, String> = mapOf(
    MediaSource.CAMERA to "DCIM/Camera/",
    MediaSource.SCREENSHOT to "DCIM/Screenshots/",
    MediaSource.WHATSAPP to "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/",
    MediaSource.QUICK_SHARE to "Download/Quick Share/",
    MediaSource.OTHER to "Pictures/",
  )

  /** Id veri di GeoNames (Roma, Milano, Napoli, Torino, Firenze, Bologna, Venezia, Palermo, Genova, Bari, Oslo, Londra, Parigi, Lisbona, Monaco, Madrid, Barcellona, Tokyo, New York). */
  private val PLACES: IntArray = intArrayOf(
    3169070, 3173435, 3172394, 3165524, 3176959, 3181928, 3164603, 2523920, 3176219, 3182351,
    3143244, 2643743, 2988507, 2267057, 2867714, 3117735, 3128760, 1850147, 5128581,
  )

  /** Fusi dei "viaggi" (E-M14): UTC-5, UTC-4, UTC+1, UTC+2, UTC+9. */
  private val TRIP_OFFSETS: IntArray = intArrayOf(-300, -240, 60, 120, 540)

  private const val SEED: Long = 0x4D43474C4942L
  private const val UUID_HI: Long = 0x4D43_4742_0000_0000L
  private const val MEDIA_TAG: Long = 1L
  private const val VERSION_TAG: Long = 2L
  private const val DAY_MS: Long = 86_400_000L
  private const val HOT_DAYS: Int = 240
  private const val ACTIVE_DAYS: Int = 2_600
  private const val HOT_SHARE: Int = 38
  private const val TRIP_SHARE: Int = 10
  private const val PLACE_SHARE: Int = 60
  private const val FAVORITE_SHARE: Int = 2
  private const val ESTIMATED_SHARE: Int = 3
  private const val PANORAMA_ASPECT_MILLI: Int = 2500
  private const val CHECK_IDS: Int = 200

  /** Chiavi di contenuto campione per il controllo della replica (tutte con S e M). */
  private const val SAMPLE_KEYS: Int = 64

  /** I bit del numero di riga nella chiave di ordinamento `(sort_time << 17) | riga` (fino a 131.071 righe). */
  private const val ROW_BITS: Int = 17
  private const val ROW_MASK: Long = (1L shl ROW_BITS) - 1
  private const val NONE_FORCED: Byte = -1

  /** Account e share del NAS sintetico: l'area privata dell'utente e l'area comune `NAS` (nomi dell'inventario). */
  private const val NAS_ACCOUNT: String = "alessio"
  private const val NAS_PRIVATE_SHARE: String = "alessio"
  private const val COMMON_SHARE_PERCENT: Int = 15
  private const val SAFE_OF_BOTH_PERCENT: Int = 33
}
