package dev.pampa.mycloudgallery.core.database

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.DAY
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.full
import dev.pampa.mycloudgallery.core.database.Db.nas
import dev.pampa.mycloudgallery.core.database.Db.obs
import dev.pampa.mycloudgallery.core.database.Db.phone
import dev.pampa.mycloudgallery.core.database.Db.stamp
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.MediaTrait
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.PlaceRef
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.ThumbHash
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.TimelineRules
import dev.pampa.mycloudgallery.core.model.domain.WorkRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Il catalogo della v2 (Fase 3, `docs/fase3-progetto.md` §1.2 e §2.1): campi derivati dell'indice,
 * lotti in una transazione, scorciatoia del timbro, assenze con tetto, cambiamenti annunciati dopo il
 * commit, gruppi, istanti dei flag personali, riferimenti ai byte.
 */
@RunWith(AndroidJUnit4::class)
class CatalogV2Test : CatalogTestBase() {

  /** Impronte piccole: 500 osservazioni senza leggere 150 MB di byte casuali. */
  private fun small(seed: Int): Fingerprint = Fingerprint.ofContent(Db.content(seed, size = 2_000))

  private fun phoneObs(name: String, id: Long, fingerprint: Fingerprint, modifiedAt: Long = T0, generation: Long? = 7, volume: String = "external_primary") =
    Observation(phone(name, id, volume = volume), FileStamp(fingerprint.size, modifiedAt, generation), fingerprint, MediaKind.PHOTO, MediaSource.CAMERA)

  private suspend fun row(id: MediaId) = db.mediaDao().get(id.value)!!

  private suspend fun current(id: MediaId) = catalog.media(id)!!.currentVersionId

  private fun utc(zoned: String): Long = ZonedDateTime.parse(zoned).toInstant().toEpochMilli()

  /** Raccoglie i cambiamenti annunciati: iscritto prima della prima scrittura. */
  private class Changes(scope: CoroutineScope, catalog: MediaCatalog) {
    val channel = Channel<CatalogChange>(Channel.UNLIMITED)
    val job: Job = scope.launch(start = CoroutineStart.UNDISPATCHED) { catalog.changes.collect { channel.send(it) } }

    suspend fun next(): CatalogChange = withTimeout(5_000) { channel.receive() }

    suspend fun none(): Boolean = withTimeoutOrNull(200) { channel.receive() } == null
  }

  // --- Campi derivati ------------------------------------------------------------------------------

  @Test
  fun iCampiDerivatiSeguonoMetadatiTipoStatiEDatiPersonali() = runBlocking<Unit> {
    val id = catalog.record(obs(phone("IMG_1.jpg", 10), full(1), capture = utc("2026-03-12T23:30Z"))).mediaId!!
    // Prima di META: giorno provvisorio nel fuso del telefono (Roma: gia' il 13), quadrato, nessun flag.
    assertThat(row(id).localDay).isEqualTo(20260313)
    assertThat(row(id).aspectMilli).isEqualTo(1000)
    assertThat(row(id).cellFlags).isEqualTo(0)

    // META: scattata a New York la sera del 12, EXIF 8 (ruotata di 270), panorama Ultra HDR con luogo e segnaposto.
    val capture = CaptureTime(utc("2026-03-12T23:30-05:00"), -300, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    val thumb = ThumbHash.of(ByteArray(25) { it.toByte() })
    catalog.updateMetadata(
      current(id),
      MediaMetadata(
        mimeType = "image/jpeg",
        width = 4000,
        height = 3000,
        orientationDegrees = 270,
        captureTime = capture,
        traits = setOf(MediaTrait.PANORAMA, MediaTrait.ULTRA_HDR),
        place = PlaceRef(5_128_581, 1),
        thumbHash = thumb,
      ),
      kind = MediaKind.MOTION_PHOTO,
    )
    row(id).let { r ->
      assertThat(r.localDay).isEqualTo(20260312)
      assertThat(r.sortTime).isEqualTo(capture.utcMillis)
      assertThat(r.aspectMilli).isEqualTo(750)
      assertThat(r.placeId).isEqualTo(5_128_581)
      assertThat(r.thumbHash!!.toList()).isEqualTo(thumb.bytes().toList())
      assertThat(r.kind).isEqualTo(MediaKind.MOTION_PHOTO.code)
      assertThat(r.cellFlags).isEqualTo(CellFlags.HAS_PLACE or CellFlags.PANORAMA or CellFlags.ULTRA_HDR or CellFlags.MOTION)
    }
    // I metadati si rileggono uguali (luogo e segnaposto compresi).
    assertThat(catalog.media(id)!!.let { db.versionDao().metadata(it.currentVersionId.value)!!.toDomain() }.place).isEqualTo(PlaceRef(5_128_581, 1))

    // E-M15: EXIF 5 e 7 (specchiate) scambiano i lati come 90 e 270.
    listOf(5, 7, 6, 3).forEach { exif ->
      val o = MediaMetadata.orientationOf(exif)
      catalog.updateMetadata(current(id), MediaMetadata(width = 4000, height = 3000, orientationDegrees = o.degrees, mirrored = o.mirrored, captureTime = capture))
      assertThat(row(id).aspectMilli).isEqualTo(if (o.swapsSides) 750 else 1333)
    }
    assertThat(db.versionDao().metadata(current(id).value)!!.toDomain().orientation.exif).isEqualTo(3)

    // Preferito, non leggibile (META rinuncia, E-M10), data stimata, modifica.
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true))
    val meta = catalog.workStates(id).first { it.stage == WorkStage.META }
    var failing = meta
    repeat(5) { failing = WorkRules.failed(failing, WorkErrorCode("DECODE_FAILED"), now) }
    catalog.updateWork(id, failing)
    catalog.updateMetadata(current(id), MediaMetadata(captureTime = capture.copy(confidence = CaptureTimeConfidence.ESTIMATED, source = CaptureTimeSource.FILE_NAME)))
    assertThat(row(id).cellFlags).isEqualTo(CellFlags.FAVORITE or CellFlags.UNREADABLE or CellFlags.DATE_ESTIMATED or CellFlags.MOTION)

    catalog.record(obs(phone("IMG_1.jpg", 10), full(2), modifiedAt = T0 + DAY))
    assertThat(CellFlags.has(row(id).cellFlags, CellFlags.EDITED)).isTrue()
  }

  @Test
  fun unVideoPortaLaDurataNellIndice() = runBlocking<Unit> {
    val id = catalog.record(
      Observation(phone("VID_1.mp4", 11), stamp(full(3)), full(3), MediaKind.VIDEO, MediaSource.CAMERA),
    ).mediaId!!
    catalog.updateMetadata(current(id), MediaMetadata(mimeType = "video/mp4", width = 1920, height = 1080, durationMillis = 61_500))
    assertThat(row(id).durationMs).isEqualTo(61_500)
    assertThat(row(id).aspectMilli).isEqualTo(1778)
    assertThat(catalog.indexRows(listOf(id)).single().durationMs).isEqualTo(61_500)
  }

  /** E-P5: il giorno provvisorio si fissa con il fuso del telefono alla lettura; cambiare fuso dopo non lo sposta. */
  @Test
  fun cambiareIlFusoDelTelefonoDopoNonSpostaIlGiorno() = runBlocking<Unit> {
    zone = ZoneId.of("Asia/Tokyo")
    val id = catalog.record(obs(phone("IMG_2.jpg", 12), full(4), capture = utc("2026-07-01T20:00Z"))).mediaId!!
    assertThat(row(id).localDay).isEqualTo(20260702) // 05:00 del 2 luglio a Tokyo

    zone = ZoneId.of("Europe/Rome")
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true)) // ricalcola i derivati
    assertThat(row(id).localDay).isEqualTo(20260702)
    assertThat(TimelineRules.localDay(utc("2026-07-01T20:00Z"), zone)).isEqualTo(20260701)
  }

  // --- Lotti in una transazione --------------------------------------------------------------------

  @Test
  fun cinquecentoOsservazioniInUnaTransazioneConUnSoloCambiamento() = runBlocking<Unit> {
    val changes = Changes(this, catalog)
    val observations = (0 until 500).map { phoneObs("IMG_$it.jpg", 1_000L + it, small(it)) }
    val cursor = MediaStoreCursor("external_primary", msVersion = Db.STORE_V1, scanEpoch = T0, scanResumeAfterId = 1_499)

    val started = System.nanoTime()
    val outcomes = catalog.recordAll(observations, cursor = cursor)
    val millis = (System.nanoTime() - started) / 1_000_000
    println("recordAll di 500 osservazioni: $millis ms")

    assertThat(outcomes).hasSize(500)
    assertThat(outcomes.all { it.decision == LinkDecision.NewMedia }).isTrue()
    assertThat(db.mediaDao().count()).isEqualTo(500)
    assertThat(catalog.mediaStoreCursor("external_primary")).isEqualTo(cursor)
    val change = changes.next()
    assertThat(change.seq).isEqualTo(1)
    assertThat(change.structural).isTrue()
    assertThat(change.ids).containsExactlyElementsIn(outcomes.map { it.mediaId })
    assertThat(changes.none()).isTrue()
    assertThat(catalog.changeSeq()).isEqualTo(1)
    // Budget largo (5 volte l'atteso su JVM): il lotto non e' una transazione per riga.
    assertThat(millis).isLessThan(15_000)

    // Lo stesso lotto riletto: stessi media, nessun media nuovo.
    val again = catalog.recordAll(observations)
    assertThat(again.map { it.mediaId }).isEqualTo(outcomes.map { it.mediaId })
    assertThat(db.mediaDao().count()).isEqualTo(500)
    changes.job.cancel()
  }

  /** O tutto o niente: un errore a meta' lotto non lascia media, cursore ne' cambiamenti. */
  @Test
  fun unErroreAMetaLottoAnnullaTutto() = runBlocking<Unit> {
    var calls = 0
    val broken = MediaCatalog(db, Db.ME, zone = { zone }) { if (++calls > 120) error("orologio rotto") else now }
    val changes = Changes(this, broken)
    val observations = (0 until 100).map { phoneObs("IMG_$it.jpg", 1_000L + it, small(it)) }

    val result = runCatching { broken.recordAll(observations, cursor = MediaStoreCursor("external_primary", scanEpoch = T0, scanResumeAfterId = 1_099)) }

    assertThat(result.isFailure).isTrue()
    assertThat(db.mediaDao().count()).isEqualTo(0)
    assertThat(catalog.mediaStoreCursor("external_primary")).isNull()
    assertThat(catalog.changeSeq()).isEqualTo(0)
    assertThat(changes.none()).isTrue()
    changes.job.cancel()
  }

  // --- Scorciatoia del timbro ----------------------------------------------------------------------

  @Test
  fun laScorciatoiaDelTimbroConfermaSenzaLeggereByte() = runBlocking<Unit> {
    val fp = small(1)
    val id = catalog.record(phoneObs("IMG_1.jpg", 10, fp, generation = 7)).mediaId!!
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()
    val location = phone("IMG_1.jpg", 10)

    // Stesso timbro: confermata; cambia solo "visto a", nessun cambiamento dell'indice.
    assertThat(catalog.confirmPresent(location, FileStamp(fp.size, T0, 7), seenAt = T0 + 5)).isTrue()
    assertThat(db.locationDao().byKey(location.key.value)!!.lastSeenAt).isEqualTo(T0 + 5)
    assertThat(catalog.changeSeq()).isEqualTo(seq)
    assertThat(changes.none()).isTrue()

    // Timbro diverso, o posto sconosciuto: niente scorciatoia.
    assertThat(catalog.confirmPresent(location, FileStamp(fp.size, T0 + 1, 7), seenAt = T0 + 6)).isFalse()
    assertThat(catalog.confirmPresent(location, FileStamp(fp.size + 1, T0, 7), seenAt = T0 + 6)).isFalse()
    assertThat(catalog.confirmPresent(phone("IMG_2.jpg", 11), FileStamp(fp.size, T0, 7), seenAt = T0 + 6)).isFalse()

    // E-P22: versione del MediaStore cambiata, _ID e generazioni rinumerati. Senza ignorare la generazione
    // no; ignorandola si', e la cache del MediaStore si aggiorna (il riferimento ai byte cambia: si annuncia).
    val renumbered = phone("IMG_1.jpg", 9_999, storeVersion = "store-v2")
    assertThat(catalog.confirmPresent(renumbered, FileStamp(fp.size, T0, 2), seenAt = T0 + 7)).isFalse()
    assertThat(catalog.confirmPresent(renumbered, FileStamp(fp.size, T0, 2), seenAt = T0 + 7, ignoreGeneration = true)).isTrue()
    val cached = catalog.media(id)!!.locations.single().location as Location.Phone
    assertThat(cached.mediaStoreId).isEqualTo(9_999)
    assertThat(cached.mediaStoreVersion).isEqualTo("store-v2")
    val change = changes.next()
    assertThat(change.ids).containsExactly(id)
    assertThat(change.structural).isFalse()
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(id)!!.versions).hasSize(1)

    // Una posizione assente non si conferma a occhio (E-P11 passa dall'impronta).
    catalog.markVanished(location.key)
    changes.next()
    assertThat(catalog.confirmPresent(renumbered, FileStamp(fp.size, T0, 2), seenAt = T0 + 8, ignoreGeneration = true)).isFalse()
    changes.job.cancel()
  }

  /** E-P6: microSD rimessa; la scorciatoia riporta presenti le righe non raggiungibili, stesso media. */
  @Test
  fun laScorciatoiaRiportaPresenteUnVolumeTornato() = runBlocking<Unit> {
    val fp = small(2)
    val sd = "1a2b-3c4d"
    val id = catalog.record(phoneObs("a.jpg", 7, fp, volume = sd)).mediaId!!
    catalog.markVolumeUnavailable(sd)
    assertThat(row(id).presence).isEqualTo(Presence.NONE.code)
    val changes = Changes(this, catalog)

    val confirmed = catalog.confirmAllPresent(
      listOf(PhoneStamp(phone("a.jpg", 7, volume = sd), FileStamp(fp.size, T0, 7)), PhoneStamp(phone("b.jpg", 8, volume = sd), FileStamp(1, T0, 7))),
      seenAt = T0 + 1,
    )

    assertThat(confirmed).containsExactly(true, false).inOrder()
    assertThat(row(id).presence).isEqualTo(Presence.PHONE_ONLY.code)
    val change = changes.next()
    assertThat(change.ids).containsExactly(id)
    assertThat(change.structural).isTrue()
    changes.job.cancel()
  }

  // --- Assenze a fine scansione, con tetto ---------------------------------------------------------

  @Test
  fun leAssenzeAFineScansioneHannoUnTetto() = runBlocking<Unit> {
    // Prima lettura, prima dell'inizio della nuova scansione.
    now = T0
    val ids = catalog.recordAll((0 until 5).map { phoneObs("IMG_$it.jpg", 100L + it, small(10 + it)) }).map { it.mediaId!! }
    val onSd = catalog.record(phoneObs("SD.jpg", 1, small(20), volume = "1a2b-3c4d")).mediaId!!
    val onNas = catalog.record(obs(nas("Foto/x.jpg"), full(21))).mediaId!!

    // Nuova scansione completa che inizia a `epoch`: vede IMG_0 (scorciatoia) e IMG_1 (impronta).
    now = T0 + DAY
    val epoch = now
    catalog.confirmPresent(phone("IMG_0.jpg", 100), FileStamp(small(10).size, T0, 7), seenAt = epoch)
    catalog.recordAll(listOf(phoneObs("IMG_1.jpg", 101, small(11))), seenAt = epoch)
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()

    assertThat(catalog.markVanishedNotSeenSince("external_primary", epoch, cap = 2)).isEqualTo(2)
    assertThat(catalog.markVanishedNotSeenSince("external_primary", epoch, cap = 2)).isEqualTo(1)
    assertThat(catalog.markVanishedNotSeenSince("external_primary", epoch, cap = 2)).isEqualTo(0)

    // Una transazione e un cambiamento per chiamata che ha segnato qualcosa.
    assertThat(changes.next().ids).hasSize(2)
    assertThat(changes.next().ids).hasSize(1)
    assertThat(changes.none()).isTrue()
    assertThat(catalog.changeSeq()).isEqualTo(seq + 2)
    val presence = ids.associateWith { row(it).presence }
    assertThat(presence.filterValues { it == Presence.PHONE_ONLY.code }.keys).containsExactly(ids[0], ids[1])
    assertThat(presence.filterValues { it == Presence.NONE.code }.keys).containsExactly(ids[2], ids[3], ids[4])
    val absent = db.locationDao().byKey(phone("IMG_4.jpg", 104).key.value)!!
    assertThat(absent.state).isEqualTo(LocationState.ABSENT.code)
    assertThat(absent.absentSince).isEqualTo(now)
    // Altri volumi e il NAS non c'entrano.
    assertThat(row(onSd).presence).isEqualTo(Presence.PHONE_ONLY.code)
    assertThat(row(onNas).presence).isEqualTo(Presence.NAS_ONLY.code)

    // Dopo la tolleranza (60 giorni) la pulizia ha anche lei un tetto per giro (E-P9); i media restano.
    now += 61 * DAY
    assertThat(catalog.purgeExpiredAbsences(cap = 2)).isEqualTo(2)
    assertThat(catalog.purgeExpiredAbsences(cap = 2)).isEqualTo(1)
    assertThat(catalog.purgeExpiredAbsences(cap = 2)).isEqualTo(0)
    assertThat(db.mediaDao().count()).isEqualTo(7)
    changes.job.cancel()
  }

  // --- Cambiamenti dopo il commit ------------------------------------------------------------------

  @Test
  fun iCambiamentiArrivanoDopoIlCommitConSequenzaMonotona() = runBlocking<Unit> {
    val seen = mutableListOf<Pair<CatalogChange, Long>>()
    val channel = Channel<CatalogChange>(Channel.UNLIMITED)
    // Chi riceve rilegge subito il database: deve gia' vedere il numero di sequenza scritto.
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      catalog.changes.collect { change ->
        seen += change to db.timelineIndexDao().changeSeq()
        channel.send(change)
      }
    }
    suspend fun next() = withTimeout(5_000) { channel.receive() }

    val id = catalog.record(obs(phone("IMG_1.jpg", 10), full(1))).mediaId!!
    assertThat(next().structural).isTrue()

    catalog.setPersonalFlags(id, PersonalFlags(favorite = true))
    next().let { assertThat(it.ids).containsExactly(id); assertThat(it.structural).isFalse() }
    assertThat(db.timelineIndexDao().indexRows(listOf(id.value)).single().cellFlags).isEqualTo(CellFlags.FAVORITE)

    catalog.setPersonalFlags(id, PersonalFlags(favorite = true, hidden = true))
    next().let { assertThat(it.structural).isTrue() }
    assertThat(db.timelineIndexDao().indexRows(listOf(id.value))).isEmpty()

    // Nessun cambiamento dell'indice: niente annuncio, la sequenza non sale.
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true, hidden = true))
    catalog.putEmbedding(id, dev.pampa.mycloudgallery.core.model.EmbeddingVector.ofFloats(Db.SPACE, 0, floatArrayOf(1f, 2f, 3f, 4f), null))
    // Una scrittura che fallisce non annuncia nulla.
    assertThat(runCatching { catalog.attachCopy(id, current(id), nas("x.jpg"), FileStamp(1, T0), null) }.isFailure).isTrue()

    catalog.setPersonalFlags(listOf(id), { it.copy(hidden = false) })
    next().let { assertThat(it.structural).isTrue() }

    job.cancel()
    assertThat(seen.map { it.first.seq }).containsExactly(1L, 2L, 3L, 4L).inOrder()
    assertThat(seen.all { (change, readBack) -> readBack == change.seq }).isTrue()
    assertThat(catalog.changeSeq()).isEqualTo(4)
  }

  /** Dentro un'altra transazione l'annuncio arriverebbe prima del commit vero: il catalogo rifiuta. */
  @Test
  fun leScrittureDelCatalogoNonStannoDentroUnAltraTransazione() = runBlocking<Unit> {
    val failure = runCatching { db.withTransaction { catalog.record(obs(phone("IMG_1.jpg", 10), full(1))) } }
    assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    assertThat(db.mediaDao().count()).isEqualTo(0)
  }

  // --- Gruppi (D20) --------------------------------------------------------------------------------

  @Test
  fun unGruppoMostraSoloChiLoRappresenta() = runBlocking<Unit> {
    val burst = (0 until 3).map { i -> catalog.record(obs(phone("IMG_BURST00$i.jpg", 20L + i), full(30 + i), capture = T0 + i)).mediaId!! }
    val single = catalog.record(obs(phone("IMG_9.jpg", 29), full(39), capture = T0 + 10)).mediaId!!
    val changes = Changes(this, catalog)

    val group = catalog.group(dev.pampa.mycloudgallery.core.model.MediaGroupKind.BURST, burst, cover = burst[1])
    assertThat(changes.next().let { it.structural && it.ids.containsAll(burst) }).isTrue()

    suspend fun indexed() = db.timelineIndexDao().indexRows(burst.map { it.value } + single.value).map { it.mediaId }
    assertThat(indexed()).containsExactly(single.value, burst[1].value).inOrder()
    assertThat(CellFlags.has(row(burst[1]).cellFlags, CellFlags.GROUP_COVER)).isTrue()
    assertThat(CellFlags.has(row(burst[0]).cellFlags, CellFlags.GROUP_MEMBER)).isTrue()
    assertThat(db.timelineIndexDao().groupSizes().single().let { it.groupId to it.size }).isEqualTo(group.value to 3)

    // La copertina nascosta: il gruppo resta nella Libreria con il membro visibile piu' recente.
    catalog.setPersonalFlags(burst[1], PersonalFlags(hidden = true))
    assertThat(indexed()).containsExactly(single.value, burst[2].value).inOrder()
    assertThat(db.timelineIndexDao().groupSizes().single().size).isEqualTo(2)
    assertThat(db.timelineIndexDao().hiddenRows().map { it.mediaId }).containsExactly(burst[1].value)

    // Un membro va in un altro gruppo: il primo resta con due; sciogliendolo tornano tutti singoli.
    val pair = catalog.group(dev.pampa.mycloudgallery.core.model.MediaGroupKind.RAW_JPEG_PAIR, listOf(burst[0], single), cover = single)
    assertThat(indexed()).containsExactly(single.value, burst[2].value).inOrder()
    assertThat(row(burst[0]).groupId).isEqualTo(pair.value)
    catalog.ungroup(group)
    assertThat(indexed()).containsExactly(single.value, burst[2].value).inOrder() // burst[1] nascosto, burst[0] nella coppia
    catalog.ungroup(pair)
    assertThat(indexed()).containsExactly(single.value, burst[2].value, burst[0].value).inOrder()
    assertThat(CellFlags.has(row(burst[0]).cellFlags, CellFlags.GROUP_MEMBER)).isFalse()
    assertThat(db.timelineIndexDao().groupSizes()).isEmpty()
    changes.job.cancel()
  }

  // --- Dati personali ------------------------------------------------------------------------------

  @Test
  fun ogniFlagPersonaleRicordaQuandoECambiato() = runBlocking<Unit> {
    val a = catalog.record(obs(phone("IMG_1.jpg", 10), full(1))).mediaId!!
    val b = catalog.record(obs(phone("IMG_2.jpg", 11), full(2))).mediaId!!

    now = T0 + 1
    catalog.setPersonalFlags(a, PersonalFlags(favorite = true))
    now = T0 + 2
    catalog.setPersonalFlags(a, PersonalFlags(favorite = true, hidden = true))
    db.personalDao().get(a.value)!!.let {
      assertThat(it.favoriteChangedAt).isEqualTo(T0 + 1)
      assertThat(it.hiddenChangedAt).isEqualTo(T0 + 2)
    }

    // Una selezione: una transazione, un cambiamento per tutti.
    val changes = Changes(this, catalog)
    now = T0 + 3
    catalog.setPersonalFlags(listOf(a, b)) { it.copy(favorite = false) }
    assertThat(changes.next().ids).containsExactly(a)
    db.personalDao().get(a.value)!!.let {
      assertThat(it.favoriteChangedAt).isEqualTo(T0 + 3)
      assertThat(it.hiddenChangedAt).isEqualTo(T0 + 2)
    }
    assertThat(db.personalDao().get(b.value)).isNull()
    changes.job.cancel()
  }

  // --- Riferimenti ai byte -------------------------------------------------------------------------

  @Test
  fun ilRiferimentoAiByteValeSoloConLaVersioneAttualeDelMediaStore() = runBlocking<Unit> {
    val fp = small(5)
    val id = catalog.record(phoneObs("IMG_1.jpg", 10, fp)).mediaId!!
    catalog.updateMetadata(current(id), MediaMetadata(mimeType = "image/heic"))
    val onNas = catalog.record(obs(nas("Foto/y.jpg"), full(6))).mediaId!!

    // Nessun cursore: non si sa con quale versione valgono gli _ID.
    assertThat(catalog.phoneRefs(listOf(id, onNas))).isEmpty()

    catalog.saveMediaStoreCursor(MediaStoreCursor("external_primary", msVersion = Db.STORE_V1))
    assertThat(catalog.phoneRefs(listOf(id, onNas))).containsExactly(id, MediaRef.Phone("external_primary", 10, "image/heic"))

    // E-P22: la versione del MediaStore cambia; finche' la scansione non rilegge la riga, nessun riferimento.
    // Chi teneva il riferimento vecchio lo sa dal cambiamento annunciato.
    val changes = Changes(this, catalog)
    catalog.saveMediaStoreCursor(MediaStoreCursor("external_primary", msVersion = "store-v2", scanEpoch = T0))
    assertThat(changes.next().let { it.ids to it.structural }).isEqualTo(setOf(id) to false)
    assertThat(catalog.phoneRefs(listOf(id))).isEmpty()
    // Lo stesso cursore con la stessa versione (solo la generazione avanza) non annuncia nulla.
    catalog.saveMediaStoreCursor(MediaStoreCursor("external_primary", msVersion = "store-v2", generation = 9, scanEpoch = T0))
    assertThat(changes.none()).isTrue()
    changes.job.cancel()
    catalog.confirmPresent(phone("IMG_1.jpg", 77, storeVersion = "store-v2"), FileStamp(fp.size, T0, 3), seenAt = T0, ignoreGeneration = true)
    assertThat(catalog.phoneRefs(listOf(id))).containsExactly(id, MediaRef.Phone("external_primary", 77, "image/heic"))
  }
}
