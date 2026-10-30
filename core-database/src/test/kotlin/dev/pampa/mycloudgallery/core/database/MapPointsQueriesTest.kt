package dev.pampa.mycloudgallery.core.database

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.database.dao.MapPointRow
import dev.pampa.mycloudgallery.core.database.dao.MapPointsQueries
import dev.pampa.mycloudgallery.core.database.entity.MediaPersonalEntity
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.PlaceRef
import java.util.Random
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La query dei punti della mappa (Fase 9, P3; `docs/fase9-progetto.md` §2.10, D27, D31; E-MAP5, E-MAP6, E-MAP7, E-MAP8, E-H9): coordinate
 * **effettive** con la stessa regola del pannello info (correzioni di luogo comprese), **solo celle visibili** (nascosti, membri di gruppo, gruppi con un
 * nascosto a meta', media senza copie mai), il solo NAS, il conteggio dei senza posizione, e su **100 mila righe** i tempi e il piano
 * (`EXPLAIN QUERY PLAN`, senza statistiche come sul telefono e con) senza `USE TEMP B-TREE`. Libreria sintetica, nessun dato vero.
 */
@RunWith(AndroidJUnit4::class)
class MapPointsQueriesTest : CatalogTestBase() {

  private val queries get() = MapPointsQueries(db)

  private val exifTime = Db.T0 - 1_000_000

  private fun metadata(gps: Pair<Double, Double>?, place: Int?, durationMillis: Long? = null) = MediaMetadata(
    mimeType = "image/jpeg",
    width = 4000,
    height = 3000,
    durationMillis = durationMillis,
    captureTime = CaptureTime(exifTime, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET),
    location = gps?.let { GeoPoint(it.first, it.second) },
    place = place?.let { PlaceRef(it, 1) },
  )

  /** Un media del telefono con i metadati letti da META (GPS e luogo se dati). */
  private suspend fun phone(n: Int, gps: Pair<Double, Double>?, place: Int? = null, source: MediaSource = MediaSource.CAMERA): MediaId {
    val id = catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), Db.T0 - n, source = source)).mediaId!!
    catalog.updateMetadata(catalog.versionOf(id), metadata(gps, place))
    return id
  }

  /** Un media che esiste **solo sul NAS** (E-MAP6). */
  private suspend fun nasOnly(n: Int, gps: Pair<Double, Double>?): MediaId {
    val id = catalog.record(nasObservation("Backup/s25/IMG_$n.jpg", smallFull(n))).mediaId!!
    catalog.updateMetadata(catalog.versionOf(id), metadata(gps, null))
    return id
  }

  private suspend fun all(pageSize: Int = 2_000): List<MapPointRow> {
    val out = ArrayList<MapPointRow>()
    var after: MediaId? = null
    while (true) {
      val page = queries.page(after, pageSize)
      out += page
      if (page.size < pageSize) return out
      after = MediaId(page.last().mediaId)
    }
  }

  private suspend fun ids(pageSize: Int = 2_000): Set<MediaId> = all(pageSize).map { MediaId(it.mediaId) }.toSet()

  private fun plan(sql: String, args: Array<Any?>): String =
    db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args).use { c ->
      buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
    }.joinToString(" | ")

  // --- coordinate effettive ---------------------------------------------------------------------------------------------------

  /**
   * E-MAP8: la mappa usa le coordinate del pannello info. Per ogni media la riga ha **lo stesso** valore che il pannello legge (la riga dei metadati
   * della versione corrente, come `CatalogMediaDetailsSource`): con la correzione di luogo (`metadata_correction`) sopra quella dell'EXIF, anche dopo
   * che META rilegge il file, e senza il punto se la correzione toglie il luogo.
   */
  @Test
  fun leCoordinateEffettiveSonoQuelleDelPannelloInfoConLeCorrezioniDiLuogo() = runBlocking<Unit> {
    val plain = phone(1, 43.0 to 11.0, place = 3_000_001)
    val moved = phone(2, 43.5 to 11.5)
    val cleared = phone(3, 44.0 to 12.0)
    val none = phone(4, null)
    val added = phone(5, null)
    val onNas = nasOnly(6, 41.9 to 12.5)
    catalog.correctInApp(moved, catalog.versionOf(moved), InAppCorrection(geo = InAppGeo.Set(45.0, 9.0)))
    catalog.correctInApp(cleared, catalog.versionOf(cleared), InAppCorrection(geo = InAppGeo.Clear))
    catalog.correctInApp(added, catalog.versionOf(added), InAppCorrection(geo = InAppGeo.Set(-33.5, 151.25)))
    // META rilegge i file (che non sono cambiati): le correzioni restano.
    catalog.updateMetadata(catalog.versionOf(moved), metadata(43.5 to 11.5, null))
    catalog.updateMetadata(catalog.versionOf(cleared), metadata(44.0 to 12.0, null))

    val rows = all().associateBy { MediaId(it.mediaId) }
    assertThat(rows.keys).containsExactly(plain, moved, added, onNas)
    assertThat(rows.getValue(moved).latitude).isEqualTo(45.0)
    assertThat(rows.getValue(moved).longitude).isEqualTo(9.0)
    assertThat(rows.getValue(added).latitude).isEqualTo(-33.5)
    assertThat(rows.getValue(onNas).longitude).isEqualTo(12.5)
    assertThat(rows.getValue(plain).placeId).isEqualTo(3_000_001)
    assertThat(queries.countWithoutPosition()).isEqualTo(2)

    // La regola e' una sola: per ogni media la riga e' uguale a cio' che legge il pannello (`version_metadata` della versione corrente).
    for (media in listOf(plain, moved, cleared, none, added, onNas)) {
      val info = db.versionDao().metadata(catalog.versionOf(media).value)!!
      val row = rows[media]
      if (info.latitude == null || info.longitude == null) {
        assertThat(row).isNull()
      } else {
        assertThat(row!!.latitude).isEqualTo(info.latitude)
        assertThat(row.longitude).isEqualTo(info.longitude)
      }
    }
    // Le correzioni sono davvero nella tabella delle correzioni (la prova non e' vuota).
    assertThat(db.metadataCorrectionDao().get(catalog.versionOf(moved).value)!!.geo).isEqualTo("set")
    assertThat(db.metadataCorrectionDao().get(catalog.versionOf(cleared).value)!!.geo).isEqualTo("clear")
  }

  /** I campi della riga: giorno locale, flag, sorgente, tipo, durata e luogo; le coordinate assurde le **consegna** (le scarta chi legge, E-MAP7). */
  @Test
  fun laRigaHaITreCampiDellaCopertinaEIlLuogoEConsegnaLeCoordinateAssurde() = runBlocking<Unit> {
    val shot = phone(1, 43.0 to 11.0, place = 3_000_005, source = MediaSource.SCREENSHOT)
    val absurd = phone(2, 10.0 to 10.0)
    catalog.setPersonalFlags(shot, PersonalFlags(favorite = true))
    db.openHelper.writableDatabase.execSQL(
      "UPDATE version_metadata SET latitude = 0.0, longitude = 0.0 WHERE version_id = ?",
      arrayOf<Any?>(catalog.versionOf(absurd).value),
    )
    val rows = all().associateBy { MediaId(it.mediaId) }
    val row = rows.getValue(shot)
    assertThat(row.source).isEqualTo(MediaSource.SCREENSHOT.code)
    assertThat(row.kind).isEqualTo(MediaKind.PHOTO.code)
    assertThat(row.placeId).isEqualTo(3_000_005)
    assertThat(CellFlags.has(row.cellFlags, CellFlags.FAVORITE)).isTrue()
    assertThat(row.localDay).isEqualTo(db.mediaDao().get(shot.value)!!.localDay)
    assertThat(row.durationMs).isNull()
    assertThat(rows.getValue(absurd).latitude).isEqualTo(0.0)
    assertThat(rows.getValue(absurd).longitude).isEqualTo(0.0)
    assertThat(row.toString()).isEqualTo("MapPointRow")
  }

  // --- solo celle visibili ----------------------------------------------------------------------------------------------------

  @Test
  fun soloLeCelleVisibiliNascostiGruppiEMediaSenzaCopieNonEntranoMai() = runBlocking<Unit> {
    val visible = phone(1, 43.0 to 11.0)
    val hidden = phone(2, 43.1 to 11.1)
    val gone = phone(3, 43.2 to 11.2)
    val burstCover = phone(4, 43.3 to 11.3)
    val burstMember = phone(5, 43.3 to 11.3)
    val halfCover = phone(6, 43.4 to 11.4)
    val halfMember = phone(7, 43.4 to 11.4)
    val halfOther = phone(8, 43.4 to 11.4)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    catalog.markPhoneLocationsVanished(catalog.media(gone)!!.locations.map { it.id })
    catalog.group(MediaGroupKind.BURST, listOf(burstCover, burstMember), burstCover)
    catalog.group(MediaGroupKind.BURST, listOf(halfCover, halfMember, halfOther), halfCover)

    // Il membro dietro la copertina non e' una cella; il nascosto, l'assente neppure; il gruppo senza nascosti e' rappresentato dalla copertina.
    assertThat(ids()).containsExactly(visible, burstCover, halfCover)

    // Un nascosto **a meta'** in un gruppo (il segno non e' arrivato a tutti: per le raffiche non si propaga): il gruppo esce per intero.
    catalog.setPersonalFlags(halfOther, PersonalFlags(hidden = true))
    assertThat(db.personalDao().get(halfCover.value)?.hidden ?: false).isFalse()
    assertThat(ids()).containsExactly(visible, burstCover)

    // Nascondere subito toglie, e rimostrare rimette.
    catalog.setPersonalFlags(visible, PersonalFlags(hidden = true))
    assertThat(ids()).containsExactly(burstCover)
    catalog.setPersonalFlags(visible, PersonalFlags(hidden = false))
    catalog.setPersonalFlags(halfOther, PersonalFlags(hidden = false))
    assertThat(ids()).containsExactly(visible, burstCover, halfCover)
  }

  /**
   * Proprieta' (E-H9, D31): dopo 40 giri di operazioni casuali (nascondere, rimostrare, un nascosto solo a meta' di un gruppo, sparire) **nessun nascosto
   * esce mai** dalla query, e le righe sono esattamente le celle visibili che un conto indipendente (dalle tabelle, non dalla query) dice.
   */
  @Test
  fun unNascostoPerfettoNonEsceMaiDallaQuery() = runBlocking<Unit> {
    val media = (1..36).map { phone(it, (30.0 + it) to (10.0 + it)) }
    val groups = listOf(media.subList(0, 3), media.subList(3, 5), media.subList(5, 9), media.subList(9, 12))
    for (g in groups) catalog.group(MediaGroupKind.BURST, g, g.first())
    val random = Random(11)
    val gone = HashSet<MediaId>()
    repeat(40) { round ->
      repeat(1 + random.nextInt(4)) {
        val m = media[random.nextInt(media.size)]
        when (random.nextInt(5)) {
          0, 1 -> catalog.setPersonalFlags(m, PersonalFlags(hidden = true))
          2, 3 -> catalog.setPersonalFlags(m, PersonalFlags(hidden = false))
          else -> if (round % 9 == 8 && gone.add(m)) catalog.markPhoneLocationsVanished(catalog.media(m)!!.locations.map { it.id })
        }
      }
      val expected = media.filter { m ->
        val row = db.mediaDao().get(m.value)!!
        val groupHidden = row.groupId != null && media.any { o ->
          db.mediaDao().get(o.value)!!.groupId == row.groupId && (db.personalDao().get(o.value)?.hidden ?: false)
        }
        row.presence != "none" && !(db.personalDao().get(m.value)?.hidden ?: false) && (row.cellFlags and CellFlags.GROUP_MEMBER) == 0 && !groupHidden
      }.toSet()
      val got = ids(pageSize = 7)
      assertWithMessage("giro $round").that(got).isEqualTo(expected)
      for (m in media) if (db.personalDao().get(m.value)?.hidden == true) assertWithMessage("giro $round: un nascosto e' uscito").that(m in got).isFalse()
    }
  }

  @Test
  fun leRighePaginanoPerCursoreSenzaPerdereNeRipetere() = runBlocking<Unit> {
    val media = (1..25).map { phone(it, (20.0 + it / 2.0) to (5.0 + it)) }
    val whole = all(pageSize = 1_000)
    // In ordine d'inserimento (non d'id): e' l'ordine in cui la tabella si legge in sequenza.
    assertThat(whole.map { it.mediaId }).containsExactlyElementsIn(media.map { it.value }).inOrder()
    for (size in listOf(1, 2, 7, 25, 26)) assertThat(all(pageSize = size).map { it.mediaId }).containsExactlyElementsIn(whole.map { it.mediaId }).inOrder()
    assertThat(queries.page(MediaId(whole.last().mediaId), 10)).isEmpty()
    val failure = runCatching { queries.page(null, 0) }
    assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
  }

  /** Un cursore che non c'e' piu' (il media e' stato cancellato fra due pagine) non da' una pagina vuota: la lettura fallisce, e l'indice si rifa. */
  @Test
  fun unCursoreCheNonCEPiuFaFallireLaLetturaEnonLaTroncaInSilenzio() = runBlocking<Unit> {
    phone(1, 40.0 to 10.0)
    val failure = runCatching { queries.page(MediaId(UUID(1, 2).toString()), 10) }
    assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(failure.exceptionOrNull()!!.message).doesNotContain(UUID(1, 2).toString())
  }

  /** E-MAP5: i visibili senza coordinate si contano; i nascosti e gli assenti no. */
  @Test
  fun iSenzaPosizioneSiContanoSoloTraLeCelleVisibili() = runBlocking<Unit> {
    phone(1, 43.0 to 11.0)
    phone(2, null)
    phone(3, null)
    val hiddenWithout = phone(4, null)
    val goneWithout = phone(5, null)
    catalog.setPersonalFlags(hiddenWithout, PersonalFlags(hidden = true))
    catalog.markPhoneLocationsVanished(catalog.media(goneWithout)!!.locations.map { it.id })
    assertThat(queries.countWithoutPosition()).isEqualTo(2)
  }

  // --- 100 mila righe: tempi e piano --------------------------------------------------------------------------------------------

  /**
   * Una Libreria da [count] media con coordinate scritta in SQL grezzo (media, versione, metadati, nascosti, gruppi): niente copie, la presenza e' scritta.
   * Ogni cento media (dal 36 al 38) c'e' un gruppo di tre: la copertina e due membri; in un gruppo su quattro il terzo e' nascosto **solo lui** (il
   * gruppo esce per intero). In piu': nascosti, assenti e senza posizione.
   */
  private class MapRows(val count: Int) {
    val ids = arrayOfNulls<String>(count)
    val lat = DoubleArray(count)
    val lon = DoubleArray(count)
    val visibleWithCoordinates = BooleanArray(count)
    var withoutPosition = 0
    var groups = 0
    var groupsWithHidden = 0

    fun insertInto(sql: androidx.sqlite.db.SupportSQLiteDatabase) {
      val random = Random(5)
      sql.beginTransaction()
      try {
        val media = sql.compileStatement(
          "INSERT INTO media (media_id, kind, source, owner_account, group_id, current_version_id, created_at, updated_at, sort_time, presence, safety, " +
            "local_day, aspect_milli, duration_ms, place_id, cell_flags, thumb_hash, backup_todo, origin_device, copy_areas) " +
            "VALUES (?, ?, ?, 'alessio', ?, ?, 0, 0, ?, ?, 'no_nas_copy', ?, 1333, NULL, ?, ?, NULL, 0, NULL, 0)",
        )
        val version = sql.compileStatement(
          "INSERT INTO media_version (version_id, media_id, kind, parent_version_id, size, quick_hash, full_hash, corrections, created_at) " +
            "VALUES (?, ?, 'original', NULL, ?, ?, NULL, '', 0)",
        )
        val metadata = sql.compileStatement(
          "INSERT INTO version_metadata (version_id, mime_type, width, height, orientation, duration_ms, taken_at_utc, taken_offset_min, " +
            "taken_confidence, taken_source, latitude, longitude, altitude, updated_at) VALUES (?, 'image/jpeg', 4000, 3000, 0, NULL, ?, 120, " +
            "'exact', 'exif_offset', ?, ?, NULL, 0)",
        )
        val hidden = sql.compileStatement("INSERT INTO media_personal (media_id, favorite, hidden, updated_at) VALUES (?, 0, 1, 0)")
        val group = sql.compileStatement("INSERT INTO media_group (group_id, kind, cover_media_id, created_at) VALUES (?, 'burst', ?, 0)")
        var groupId = ""
        for (i in 0 until count) {
          val mediaId = UUID(random.nextLong(), random.nextLong()).toString()
          val versionId = UUID(random.nextLong(), random.nextLong()).toString()
          ids[i] = mediaId
          val slot = i % 100
          val inGroup = slot in 36..38
          val groupNo = i / 100
          val groupHasHidden = inGroup && groupNo % 4 == 0
          if (slot == 36) {
            groupId = UUID(random.nextLong(), random.nextLong()).toString()
            group.clearBindings()
            group.bindString(1, groupId)
            group.bindString(2, mediaId)
            group.executeInsert()
            groups++
            if (groupHasHidden) groupsWithHidden++
          }
          val noPosition = i % 9 == 4
          val isHidden = i % 50 == 7 || (groupHasHidden && slot == 38)
          val gone = i % 211 == 5
          val member = slot == 37 || slot == 38
          lat[i] = 35.0 + random.nextDouble() * 20.0
          lon[i] = 5.0 + random.nextDouble() * 20.0
          val visible = !isHidden && !gone && !member && !groupHasHidden
          visibleWithCoordinates[i] = visible && !noPosition
          if (visible && noPosition) withoutPosition++
          media.clearBindings()
          media.bindString(1, mediaId)
          media.bindString(2, if (i % 12 == 0) "video" else "photo")
          media.bindString(3, if (i % 25 == 3) "screenshot" else "camera")
          if (inGroup) media.bindString(4, groupId) else media.bindNull(4)
          media.bindString(5, versionId)
          media.bindLong(6, 1_700_000_000_000L + i)
          media.bindString(7, if (gone) "none" else "phone_and_nas")
          media.bindLong(8, 20_230_101L + i % 300)
          if (i % 5 < 2) media.bindLong(9, 1_000L + i % 200) else media.bindNull(9)
          media.bindLong(10, if (member) CellFlags.GROUP_MEMBER.toLong() else 0L)
          media.executeInsert()
          version.clearBindings()
          version.bindString(1, versionId)
          version.bindString(2, mediaId)
          version.bindLong(3, 1_000L + i)
          version.bindString(4, "%064x".format(i))
          version.executeInsert()
          metadata.clearBindings()
          metadata.bindString(1, versionId)
          metadata.bindLong(2, 1_700_000_000_000L + i)
          if (noPosition) {
            metadata.bindNull(3)
            metadata.bindNull(4)
          } else {
            metadata.bindDouble(3, lat[i])
            metadata.bindDouble(4, lon[i])
          }
          metadata.executeInsert()
          if (isHidden) {
            hidden.clearBindings()
            hidden.bindString(1, mediaId)
            hidden.executeInsert()
          }
        }
        sql.setTransactionSuccessful()
      } finally {
        sql.endTransaction()
      }
    }
  }

  /**
   * Il **migliore** di [runs] giri dopo due di riscaldamento, non la mediana. Con altre build sul PC (tre insieme: 431 ms contro 217 da solo) la mediana segue il
   * carico di chi sta compilando, e il test cadeva senza che la query fosse cambiata; il migliore dei giri e' il tempo che la query sa fare quando nessuno le
   * ruba la CPU. Una regressione vera (la query che torna a ordinare in memoria, un indice perso) rallenta **tutti** i giri e il test cade lo stesso: la
   * soglia non e' cambiata (vedi `docs/decisioni.md`, «Test stabili sotto carico»).
   */
  private suspend fun best(runs: Int = 9, block: suspend () -> Unit): Long {
    repeat(2) { block() }
    return (1..runs).minOf {
      val started = System.nanoTime()
      block()
      (System.nanoTime() - started) / 1_000_000
    }
  }

  @Test
  fun centoMilaRigheSottoI400MillisecondiSenzaOrdinareInMemoria() = runBlocking<Unit> {
    val lib = MapRows(120_000)
    lib.insertInto(db.openHelper.writableDatabase)

    for (analyzed in listOf(false, true)) {
      if (analyzed) db.openHelper.writableDatabase.execSQL("ANALYZE")
      for ((label, after) in listOf("prima pagina" to 0L, "pagina con cursore" to 50_000L)) {
        val text = plan(MapPointsQueries.PAGE_SQL, MapPointsQueries.pageArgs(after, 2_000))
        println("[${if (analyzed) "ANALYZE" else "senza statistiche"}] punti, $label: $text")
        assertWithMessage("$label, analyzed=$analyzed: $text").that(text).doesNotContain("TEMP B-TREE")
        assertWithMessage("$label, analyzed=$analyzed: $text").that(text).contains("SEARCH m USING INTEGER PRIMARY KEY (rowid>?)")
        assertWithMessage("$label, analyzed=$analyzed: $text").that(text).doesNotContain("SCAN vm")
        // La sotto-interrogazione del gruppo parte dal gruppo (indice di `group_id`) e cerca il nascosto per chiave: mai una scansione dei nascosti.
        assertWithMessage("$label, analyzed=$analyzed: $text").that(text).contains("SEARCH gm USING INDEX index_media_group_id (group_id=?)")
        assertWithMessage("$label, analyzed=$analyzed: $text").that(text).doesNotContain("SCAN gp")
      }
      println("[${if (analyzed) "ANALYZE" else "senza statistiche"}] senza posizione: ${plan(MapPointsQueries.WITHOUT_POSITION_SQL, emptyArray())}")
    }

    // Il risultato e' quello atteso: tutte e sole le celle visibili con coordinate, in ordine d'inserimento, con i valori scritti.
    val expected = (0 until lib.count).filter { lib.visibleWithCoordinates[it] }
    val got = all()
    assertThat(got.size).isAtLeast(100_000)
    assertThat(got.map { it.mediaId }).isEqualTo(expected.map { lib.ids[it] })
    val byId = (0 until lib.count).associateBy { lib.ids[it]!! }
    for (row in got.shuffled(Random(1)).take(500)) {
      val i = byId.getValue(row.mediaId)
      assertThat(row.latitude).isEqualTo(lib.lat[i])
      assertThat(row.longitude).isEqualTo(lib.lon[i])
    }
    assertThat(queries.countWithoutPosition()).isEqualTo(lib.withoutPosition)
    // La Libreria ha davvero gruppi, anche con un nascosto a meta': la prova dei tempi li include.
    assertThat(lib.groups).isEqualTo(1_200)
    assertThat(lib.groupsWithHidden).isEqualTo(300)

    // Il tempo: tutte le pagine da 2.000 (circa 50), il migliore di nove giri dopo due di riscaldamento.
    val millis = best { all() }
    println("Punti: ${got.size} righe in ${(got.size + 1_999) / 2_000} pagine da 2.000 in $millis ms (il migliore di 9)")
    assertWithMessage("${got.size} righe in $millis ms").that(millis).isLessThan(400)
    val withoutMillis = best { queries.countWithoutPosition() }
    println("Conteggio dei senza posizione su ${lib.count} media: $withoutMillis ms")
    assertWithMessage("senza posizione: $withoutMillis ms").that(withoutMillis).isLessThan(400)
  }

  /** Il test della proprieta' non deve passare per caso: la riga per un nascosto scritta a mano (senza il catalogo) la filtra lo stesso. */
  @Test
  fun unNascostoScrittoAManoNellaTabellaDeiPersonaliEFiltrato() = runBlocking<Unit> {
    val a = phone(1, 40.0 to 10.0)
    val b = phone(2, 41.0 to 11.0)
    db.personalDao().upsert(MediaPersonalEntity(b.value, favorite = false, hidden = true, updatedAt = 1))
    assertThat(ids()).containsExactly(a)
  }
}
