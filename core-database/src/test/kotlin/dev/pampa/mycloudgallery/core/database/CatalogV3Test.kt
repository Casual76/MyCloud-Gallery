package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.ME
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.full
import dev.pampa.mycloudgallery.core.database.Db.obs
import dev.pampa.mycloudgallery.core.database.Db.phone
import dev.pampa.mycloudgallery.core.database.Db.quick
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.FreezeReason
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaGroupKind
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import dev.pampa.mycloudgallery.core.model.VersionId
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.AdoptRefusal
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.Observation
import dev.pampa.mycloudgallery.core.model.domain.RemoteIdentity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il catalogo della v3 (Fase 4, `docs/fase4-progetto.md` §1.3, §2.3, D7, D18, D21): osservazioni del NAS con il cursore
 * nella stessa transazione, scorciatoia del timbro, assenze con tetto e congelamento, share non raggiungibile, riferimenti
 * ai byte del NAS, flag di presenza in tutte le combinazioni, gruppi `PROBABLE_DUPLICATE`, adozione delle identita'.
 */
@RunWith(AndroidJUnit4::class)
class CatalogV3Test : CatalogTestBase() {

  private val epoch = T0 + 1_000

  private fun nas(path: String, share: String = "alessio", fileId: Long? = null): Location.Nas =
    Location.Nas(ME, share, NasPath.of(path), fileId)

  private fun nasObs(
    path: String,
    fingerprint: Fingerprint,
    share: String = "alessio",
    modifiedAt: Long = T0,
    fileId: Long? = null,
    kind: MediaKind = MediaKind.PHOTO,
  ) = Observation(nas(path, share, fileId), FileStamp(fingerprint.size, modifiedAt), fingerprint, kind, MediaSource.OTHER)

  private fun video(location: Location, fingerprint: Fingerprint) =
    Observation(location, FileStamp(fingerprint.size, T0), fingerprint, MediaKind.VIDEO, MediaSource.CAMERA)

  /** Due contenuti della stessa dimensione con testa e coda uguali, diversi solo nel mezzo: stesso `qh1`, hash diversi. */
  private fun twins(): Pair<Fingerprint, Fingerprint> {
    val a = Db.content(77)
    val b = a.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
    return Fingerprint.ofContent(a) to Fingerprint.ofContent(b)
  }

  private suspend fun flags(id: MediaId) = db.mediaDao().get(id.value)!!.cellFlags

  private suspend fun badge(id: MediaId) = PresenceBadge.of(flags(id))

  private fun scan(epoch: Long? = this.epoch, resume: String? = null, freeze: NasFreeze? = null) =
    NasScanState(ME, "alessio", epoch, resume?.let(NasPath::of), lastFullScanAt = null, freeze = freeze)

  // --- Osservazioni del NAS e cursore ------------------------------------------------------------------------------

  @Test
  fun unLottoDelNasEIlSuoCursoreSonoOTuttoONiente() = runBlocking<Unit> {
    val batch = (1..3).map { nasObs("Foto/IMG_$it.jpg", full(it)) }
    assertThrows(IllegalStateException::class.java) {
      runBlocking { catalog.recordAll(batch, MediaCatalog.WhenUndecided.KEEP_SEPARATE, nasCursor = scan(resume = "Foto"), seenAt = epoch) { error("guasto") } }
    }
    assertThat(db.mediaDao().count()).isEqualTo(0)
    assertThat(catalog.nasScanState(ME, "alessio")).isNull()

    val outcomes = catalog.recordAll(batch, MediaCatalog.WhenUndecided.KEEP_SEPARATE, nasCursor = scan(resume = "Foto"), seenAt = epoch)
    assertThat(outcomes.map { it.decision }).containsExactly(LinkDecision.NewMedia, LinkDecision.NewMedia, LinkDecision.NewMedia)
    assertThat(catalog.nasScanState(ME, "Alessio")!!.resumeAfter).isEqualTo(NasPath.of("Foto"))
    assertThat(db.locationDao().byKey(nas("Foto/IMG_1.jpg").key.value)!!.lastSeenAt).isEqualTo(epoch)
    // I media solo-NAS di un'area privata sono del proprietario dell'area e sono "solo NAS".
    assertThat(badge(outcomes.first().mediaId!!)).isEqualTo(PresenceBadge.NAS_ONLY)
  }

  @Test
  fun laScorciatoiaDelTimboUsaIlFileIdEAggiornaSoloVistoA() = runBlocking<Unit> {
    catalog.recordAll(listOf(nasObs("Foto/A.jpg", full(1), fileId = 7), nasObs("Foto/B.jpg", full(2))), seenAt = T0)
    val known = catalog.knownNasFiles(ME, "ALESSIO", NasPath.of("Foto"))
    assertThat(known.keys).containsExactly(nas("Foto/A.jpg").key, nas("Foto/B.jpg").key)
    assertThat(known.getValue(nas("Foto/A.jpg").key).stamp).isEqualTo(NasStamp(full(1).size, T0, 7))
    assertThat(catalog.knownNasFiles(ME, "alessio", null)).isEmpty()
    assertThat(catalog.countPresentNas(ME, "alessio")).isEqualTo(2)

    val seen = listOf(
      SeenNasFile(nas("foto/a.JPG", fileId = 7), NasStamp(full(1).size, T0, 7)), // stesso file, maiuscole diverse
      SeenNasFile(nas("Foto/B.jpg"), NasStamp(full(2).size, T0, 99)), // FileId letto ora: si impara
      SeenNasFile(nas("Foto/C.jpg"), NasStamp(10, T0)), // sconosciuto
    )
    val confirmed = catalog.confirmAllPresentNas(seen, seenAt = epoch, cursor = scan(resume = "Foto"))
    assertThat(confirmed).containsExactly(true, true, false).inOrder()
    assertThat(db.locationDao().byKey(nas("Foto/B.jpg").key.value)!!.fileId).isEqualTo(99)
    assertThat(db.locationDao().byKey(nas("Foto/A.jpg").key.value)!!.nasPath).isEqualTo("foto/a.JPG")
    assertThat(db.locationDao().byKey(nas("Foto/A.jpg").key.value)!!.lastSeenAt).isEqualTo(epoch)
    assertThat(catalog.nasScanState(ME, "alessio")!!.resumeAfter).isEqualTo(NasPath.of("Foto"))

    // Timbro diverso (data, o FileId noto da entrambe le parti e diverso): niente scorciatoia.
    val other = catalog.confirmAllPresentNas(
      listOf(SeenNasFile(nas("Foto/A.jpg"), NasStamp(full(1).size, T0 + 1, 7)), SeenNasFile(nas("Foto/B.jpg"), NasStamp(full(2).size, T0, 100))),
      seenAt = epoch,
    )
    assertThat(other).containsExactly(false, false)
  }

  @Test
  fun leAssenzeDiUnaShareHannoUnTettoESiFermanoConLaShareCongelata() = runBlocking<Unit> {
    catalog.recordAll((1..5).map { nasObs("Foto/IMG_$it.jpg", full(it)) } + nasObs("x.jpg", full(9), share = "NAS"), seenAt = T0)
    catalog.confirmAllPresentNas(listOf(SeenNasFile(nas("Foto/IMG_5.jpg"), NasStamp(full(5).size, T0))), seenAt = epoch)

    // Congelata (D10): nessuna assenza, anche se chi chiama ci prova.
    catalog.saveNasScanState(scan(freeze = NasFreeze(FreezeReason.VANISHED, epoch, 5, 4, 0, 0)))
    assertThat(catalog.markVanishedInShare(ME, "alessio", epoch, cap = 10)).isEqualTo(0)
    assertThat(catalog.countPresentNas(ME, "alessio")).isEqualTo(5)

    catalog.saveNasScanState(scan(freeze = null))
    assertThat(catalog.markVanishedInShare(ME, "alessio", epoch, cap = 3)).isEqualTo(3)
    assertThat(catalog.markVanishedInShare(ME, "alessio", epoch, cap = 3)).isEqualTo(1)
    assertThat(catalog.markVanishedInShare(ME, "alessio", epoch, cap = 3)).isEqualTo(0)
    // La quinta e' stata vista nel giro; l'altra share non si tocca.
    assertThat(db.locationDao().byKey(nas("Foto/IMG_5.jpg").key.value)!!.state).isEqualTo(LocationState.PRESENT.code)
    assertThat(catalog.countPresentNas(ME, "NAS")).isEqualTo(1)
    assertThat(db.locationDao().byKey(nas("Foto/IMG_1.jpg").key.value)!!.absentSince).isEqualTo(now)
  }

  @Test
  fun unaShareNonPiuAccessibileRendeLePosizioniNonRaggiungibiliATappe() = runBlocking<Unit> {
    val ids = catalog.recordAll((1..5).map { nasObs("Foto/IMG_$it.jpg", full(it)) }, seenAt = T0).map { it.mediaId!! }
    assertThat(catalog.markShareUnavailable(ME, "alessio", cap = 3)).isEqualTo(3)
    assertThat(catalog.markShareUnavailable(ME, "alessio", cap = 3)).isEqualTo(2)
    assertThat(catalog.markShareUnavailable(ME, "alessio", cap = 3)).isEqualTo(0)
    ids.forEach { id ->
      assertThat(catalog.media(id)!!.locations.single().state).isEqualTo(LocationState.UNAVAILABLE)
      assertThat(badge(id)).isNull()
    }
    // Torna raggiungibile: la scorciatoia del timbro la rimette presente.
    assertThat(catalog.confirmAllPresentNas(listOf(SeenNasFile(nas("Foto/IMG_1.jpg"), NasStamp(full(1).size, T0))), epoch)).containsExactly(true)
    assertThat(badge(ids.first())).isEqualTo(PresenceBadge.NAS_ONLY)
  }

  /** E-N1: spostato dal PC con la data di modifica cambiata, ma lo stesso `FileId`: stesso media, nessun doppione. */
  @Test
  fun unoSpostamentoDalPcSiRiconosceDalFileId() = runBlocking<Unit> {
    val id = catalog.recordAll(listOf(nasObs("Foto/A.jpg", quick(1), fileId = 42)), seenAt = T0).single().mediaId!!
    catalog.markVanishedInShare(ME, "alessio", epoch, cap = 10)

    val moved = catalog.recordAll(
      listOf(nasObs("Archivio/A.jpg", quick(1), modifiedAt = T0 + 5_000, fileId = 42)),
      MediaCatalog.WhenUndecided.KEEP_SEPARATE,
      seenAt = epoch,
    ).single()
    assertThat(moved.decision).isInstanceOf(LinkDecision.Moved::class.java)
    assertThat(moved.mediaId).isEqualTo(id)
    assertThat(db.mediaDao().count()).isEqualTo(1)
  }

  // --- Riferimenti ai byte e presenza -------------------------------------------------------------------------------

  @Test
  fun iRiferimentiAlNasPortanoPosizioneEChiaveDiContenuto() = runBlocking<Unit> {
    val nasOnly = catalog.recordAll(listOf(nasObs("Foto/A.jpg", full(1))), seenAt = T0).single()
    val phoneOnly = catalog.record(obs(phone("IMG_2.jpg", 2), full(2))).mediaId!!
    val gone = catalog.recordAll(listOf(nasObs("Foto/C.jpg", full(3))), seenAt = T0).single().mediaId!!
    catalog.markVanished(nas("Foto/C.jpg").key)

    val refs = catalog.nasRefs(listOf(nasOnly.mediaId!!, phoneOnly, gone))

    assertThat(refs.keys).containsExactly(nasOnly.mediaId)
    val ref = refs.getValue(nasOnly.mediaId!!)
    assertThat(ref.locationId).isEqualTo(nasOnly.locationId!!.value)
    assertThat(ref.contentKey).isEqualTo(ContentKey.of(full(1)).short)
    assertThat(catalog.nasLocation(nasOnly.locationId!!)).isEqualTo(nas("Foto/A.jpg"))
  }

  /** D18, §1.5: i bit di presenza in tutte le combinazioni, e il badge che ne viene. */
  @Test
  fun iFlagDiPresenzaSonoGiustiInTutteLeCombinazioni() = runBlocking<Unit> {
    val phoneOnly = catalog.record(obs(phone("IMG_1.jpg", 1), full(1))).mediaId!!
    val nasOnly = catalog.recordAll(listOf(nasObs("Foto/B.jpg", full(2))), seenAt = T0).single().mediaId!!
    val common = catalog.recordAll(listOf(nasObs("Famiglia/C.jpg", full(3), share = "NAS")), seenAt = T0).single().mediaId!!
    // Entrambi: lo stesso contenuto (hash completo) sul telefono e nell'area privata.
    val both = catalog.record(obs(phone("IMG_4.jpg", 4), full(4))).mediaId!!
    val bothNas = catalog.recordAll(listOf(nasObs("Backup/IMG_4.jpg", full(4))), seenAt = T0).single()
    assertThat(bothNas.mediaId).isEqualTo(both)

    assertThat(flags(phoneOnly) and PRESENCE).isEqualTo(CellFlags.ON_PHONE)
    assertThat(flags(nasOnly) and PRESENCE).isEqualTo(CellFlags.ON_NAS)
    assertThat(flags(common) and PRESENCE).isEqualTo(CellFlags.ON_NAS)
    assertThat(flags(both) and PRESENCE).isEqualTo(CellFlags.ON_PHONE or CellFlags.ON_NAS)
    assertThat(badge(phoneOnly)).isEqualTo(PresenceBadge.PHONE_ONLY)
    assertThat(badge(nasOnly)).isEqualTo(PresenceBadge.NAS_ONLY)
    assertThat(badge(both)).isEqualTo(PresenceBadge.BOTH)

    // Al sicuro: la copia privata riletta qui con lo stesso SHA-256 (D8).
    catalog.markVerified(bothNas.locationId!!, full(4).fullHash!!, now)
    assertThat(flags(both) and PRESENCE).isEqualTo(CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE)
    assertThat(badge(both)).isEqualTo(PresenceBadge.SAFE)

    // La copia del telefono sparisce: resta al sicuro sul NAS.
    catalog.markVanished(phone("IMG_4.jpg", 4).key)
    assertThat(flags(both) and PRESENCE).isEqualTo(CellFlags.ON_NAS or CellFlags.SAFE)

    // Il NAS non e' piu' accessibile: nessuna copia presente, nessun bit (e nessuna cella).
    catalog.markShareUnavailable(ME, "alessio")
    assertThat(flags(both) and PRESENCE).isEqualTo(0)
    assertThat(flags(nasOnly) and PRESENCE).isEqualTo(0)
    assertThat(catalog.indexRows(listOf(both, nasOnly))).isEmpty()
    // Il resto non cambia.
    assertThat(flags(phoneOnly) and PRESENCE).isEqualTo(CellFlags.ON_PHONE)
    assertThat(flags(common) and PRESENCE).isEqualTo(CellFlags.ON_NAS)
  }

  // --- D21: gruppi PROBABLE_DUPLICATE -------------------------------------------------------------------------------

  @Test
  fun unVideoUgualeSuTelefonoENasEUnaCellaDaVerificareFinoAllHashCompleto() = runBlocking<Unit> {
    val content = Db.content(5)
    val quickFp = Fingerprint.ofContent(content).copy(fullHash = null)
    val phoneVideo = catalog.recordAll(listOf(video(phone("VID_1.mp4", 1), quickFp)), MediaCatalog.WhenUndecided.KEEP_SEPARATE).single().mediaId!!
    val nasVideo = catalog.recordAll(listOf(video(nas("Video/VID_1.mp4"), quickFp)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0)
      .single().mediaId!!

    // Una cella sola: la copia del telefono, "da verificare"; il video del NAS sta dietro.
    assertThat(nasVideo).isNotEqualTo(phoneVideo)
    val group = db.mediaDao().get(phoneVideo.value)!!.groupId!!
    assertThat(db.mediaDao().group(group)!!.kind).isEqualTo(MediaGroupKind.PROBABLE_DUPLICATE.code)
    assertThat(db.mediaDao().group(group)!!.coverMediaId).isEqualTo(phoneVideo.value)
    assertThat(catalog.indexRows(listOf(phoneVideo, nasVideo)).map { it.mediaId }).containsExactly(phoneVideo.value)
    assertThat(badge(phoneVideo)).isEqualTo(PresenceBadge.TO_VERIFY)
    // L'hash completo del NAS e' in coda sul NAS, non sul telefono.
    assertThat(catalog.pendingWork(WorkStage.FULL_HASH, WorkPlace.NAS, 10)).containsExactly(nasVideo)
    assertThat(catalog.pendingWork(WorkStage.FULL_HASH, WorkPlace.PHONE, 10)).isEmpty()

    // Arriva l'hash del NAS: si aspetta quello del telefono.
    val fullFp = Fingerprint.ofContent(content)
    catalog.recordAll(listOf(video(nas("Video/VID_1.mp4"), fullFp)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = epoch)
    assertThat(catalog.workStates(nasVideo).first { it.stage == WorkStage.FULL_HASH }.status).isEqualTo(WorkStatus.DONE)
    assertThat(badge(phoneVideo)).isEqualTo(PresenceBadge.TO_VERIFY)

    // Arriva quello del telefono (hash rimandati della Fase 3): stessi byte, un media solo con due posizioni.
    val last = catalog.recordAll(listOf(video(phone("VID_1.mp4", 1), fullFp)), MediaCatalog.WhenUndecided.KEEP_SEPARATE).single()
    val survivor = minOf(phoneVideo, nasVideo)
    assertThat(last.mediaId).isEqualTo(survivor)
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(survivor)!!.locations).hasSize(2)
    assertThat(catalog.media(survivor)!!.groupId).isNull()
    assertThat(db.mediaDao().group(group)).isNull()
    assertThat(badge(survivor)).isEqualTo(PresenceBadge.BOTH)
    assertThat(catalog.resolve(maxOf(phoneVideo, nasVideo))).isEqualTo(survivor)
  }

  @Test
  fun conGliHashCompletiDiversiIlGruppoProvvisorioSiSeparaSenzaUnire() = runBlocking<Unit> {
    val (a, b) = twins()
    assertThat(a.quickHash).isEqualTo(b.quickHash)
    val phoneVideo = catalog.recordAll(listOf(video(phone("VID_1.mp4", 1), a.copy(fullHash = null))), MediaCatalog.WhenUndecided.KEEP_SEPARATE)
      .single().mediaId!!
    val nasVideo = catalog.recordAll(listOf(video(nas("Video/VID_1.mp4"), b.copy(fullHash = null))), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0)
      .single().mediaId!!
    assertThat(badge(phoneVideo)).isEqualTo(PresenceBadge.TO_VERIFY)

    catalog.recordAll(listOf(video(nas("Video/VID_1.mp4"), b)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = epoch)
    catalog.recordAll(listOf(video(phone("VID_1.mp4", 1), a)), MediaCatalog.WhenUndecided.KEEP_SEPARATE)

    assertThat(db.mediaDao().count()).isEqualTo(2)
    assertThat(catalog.media(phoneVideo)!!.groupId).isNull()
    assertThat(catalog.media(nasVideo)!!.groupId).isNull()
    assertThat(badge(phoneVideo)).isEqualTo(PresenceBadge.PHONE_ONLY)
    assertThat(badge(nasVideo)).isEqualTo(PresenceBadge.NAS_ONLY)
    assertThat(catalog.indexRows(listOf(phoneVideo, nasVideo))).hasSize(2)
  }

  /** Una seconda copia sul NAS dello stesso video entra nello stesso gruppo: sempre una cella. */
  @Test
  fun unaSecondaCopiaSulNasEntraNelloStessoGruppo() = runBlocking<Unit> {
    val fp = quick(6)
    val phoneVideo = catalog.recordAll(listOf(video(phone("VID_1.mp4", 1), fp)), MediaCatalog.WhenUndecided.KEEP_SEPARATE).single().mediaId!!
    val first = catalog.recordAll(listOf(video(nas("Video/VID_1.mp4"), fp)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0).single().mediaId!!
    val second = catalog.recordAll(listOf(video(nas("Copia/VID_1.mp4", share = "NAS"), fp)), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0)
      .single().mediaId!!
    val group = db.mediaDao().get(phoneVideo.value)!!.groupId!!
    assertThat(db.mediaDao().groupMembers(group).map { it.mediaId }).containsExactly(phoneVideo.value, first.value, second.value)
    assertThat(catalog.indexRows(listOf(phoneVideo, first, second)).map { it.mediaId }).containsExactly(phoneVideo.value)
  }

  // --- D7: adozione delle identita' ---------------------------------------------------------------------------------

  private val minorMedia = MediaId("00000000-0000-4000-8000-000000000001")
  private val minorVersion = VersionId("00000000-0000-4000-8000-000000000002")
  private val majorMedia = MediaId("ffffffff-ffff-4fff-bfff-ffffffffffff")
  private val majorVersion = VersionId("ffffffff-ffff-4fff-bfff-fffffffffffe")

  @Test
  fun lIdRemotoMinoreSiAdottaConTuttiIRiferimentiEUnAlias() = runBlocking<Unit> {
    val local = catalog.recordAll(listOf(nasObs("Foto/A.jpg", full(1))), seenAt = T0).single().mediaId!!
    val album = decorate(local)

    val outcome = catalog.adoptIdentity(nas("Foto/A.jpg").key, RemoteIdentity(minorMedia, minorVersion, full(1)))

    assertThat(outcome).isEqualTo(AdoptOutcome.Adopted(minorMedia, mediaRenamed = true, versionRenamed = true))
    assertReferencesIntact(minorMedia, album)
    assertThat(catalog.resolve(local)).isEqualTo(minorMedia)
    val media = catalog.media(minorMedia)!!
    assertThat(media.currentVersionId).isEqualTo(minorVersion)
    assertThat(catalog.workStates(minorMedia).all { it.versionId == minorVersion }).isTrue()
    assertThat(db.embeddingDao().forMedia(minorMedia.value).single().versionId).isEqualTo(minorVersion.value)
    assertThat(catalog.indexRows(listOf(minorMedia)).map { it.mediaId }).containsExactly(minorMedia.value)
    db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.count).isEqualTo(0) }

    // Una seconda volta: gia' le stesse identita'.
    assertThat(catalog.adoptIdentity(nas("Foto/A.jpg").key, RemoteIdentity(minorMedia, minorVersion, full(1))))
      .isEqualTo(AdoptOutcome.AlreadyShared(minorMedia))
  }

  @Test
  fun lIdRemotoMaggioreNonSiAdottaEUnMediaColTelefonoTieneIlSuo() = runBlocking<Unit> {
    val local = catalog.recordAll(listOf(nasObs("Foto/A.jpg", full(1))), seenAt = T0).single().mediaId!!
    assertThat(catalog.adoptIdentity(nas("Foto/A.jpg").key, RemoteIdentity(majorMedia, majorVersion, full(1))))
      .isEqualTo(AdoptOutcome.KeptLocal(local))

    // D8: un media con una copia sul telefono non adotta identita' dall'indice.
    val withPhone = catalog.record(obs(phone("IMG_2.jpg", 2), full(2))).mediaId!!
    catalog.recordAll(listOf(nasObs("Backup/IMG_2.jpg", full(2))), seenAt = T0)
    assertThat(catalog.adoptIdentity(nas("Backup/IMG_2.jpg").key, RemoteIdentity(minorMedia, minorVersion, full(2))))
      .isEqualTo(AdoptOutcome.KeptLocal(withPhone))
    assertThat(catalog.media(minorMedia)).isNull()
  }

  /** D7: l'id remoto e' gia' legato qui a un contenuto diverso (una mia foto): rifiuto, conflitto, nulla cambia. */
  @Test
  fun lAdozioneERifiutataSeLIdRemotoELegatoAUnAltroContenuto() = runBlocking<Unit> {
    val mine = catalog.record(obs(phone("IMG_1.jpg", 1), full(1))).mediaId!!
    val myVersion = catalog.media(mine)!!.currentVersionId
    val nasFile = catalog.recordAll(listOf(nasObs("Foto/X.jpg", full(2))), seenAt = T0).single().mediaId!!

    val byMedia = catalog.adoptIdentity(nas("Foto/X.jpg").key, RemoteIdentity(mine, VersionId.random(), full(2)))
    val byVersion = catalog.adoptIdentity(nas("Foto/X.jpg").key, RemoteIdentity(minorMedia, myVersion, full(2)))

    assertThat(byMedia).isEqualTo(AdoptOutcome.Refused(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT))
    assertThat(byVersion).isEqualTo(AdoptOutcome.Refused(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT))
    assertThat(AdoptRefusal.ID_BOUND_TO_OTHER_CONTENT.isConflict).isTrue()
    assertThat(db.mediaDao().count()).isEqualTo(2)
    assertThat(catalog.media(mine)!!.locations.single().isPhone).isTrue()
    assertThat(catalog.media(nasFile)!!.locations.single().isNas).isTrue()

    // Un record che non descrive i byte del posto non e' un conflitto d'identita'.
    assertThat(catalog.adoptIdentity(nas("Foto/X.jpg").key, RemoteIdentity(minorMedia, minorVersion, full(3))))
      .isEqualTo(AdoptOutcome.Refused(AdoptRefusal.CONTENT_MISMATCH))
    assertThat(catalog.adoptIdentity(nas("Foto/Y.jpg").key, RemoteIdentity(minorMedia, minorVersion, full(3))))
      .isEqualTo(AdoptOutcome.NotFound)
  }

  /** E-I18: due id per lo stesso file convergono al minore, ma solo con la prova degli hash completi. */
  @Test
  fun dueIdPerGliStessiByteSiUnisconoSoloConGliHashCompleti() = runBlocking<Unit> {
    val a = catalog.recordAll(listOf(nasObs("Foto/A.jpg", quick(1))), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0).single().mediaId!!
    val b = catalog.recordAll(listOf(nasObs("Copia/A.jpg", quick(1), share = "NAS")), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = T0)
      .single().mediaId!!
    assertThat(a).isNotEqualTo(b)

    val unproven = catalog.adoptIdentity(nas("Copia/A.jpg", share = "NAS").key, RemoteIdentity(a, VersionId.random(), quick(1)))
    assertThat(unproven).isEqualTo(AdoptOutcome.Refused(AdoptRefusal.NOT_PROVEN_IDENTICAL))
    assertThat(AdoptRefusal.NOT_PROVEN_IDENTICAL.isConflict).isFalse()

    catalog.recordAll(listOf(nasObs("Foto/A.jpg", full(1)), nasObs("Copia/A.jpg", full(1), share = "NAS")), seenAt = epoch)
    val merged = catalog.adoptIdentity(nas("Copia/A.jpg", share = "NAS").key, RemoteIdentity(a, VersionId.random(), full(1)))
    assertThat(merged).isEqualTo(AdoptOutcome.Merged(minOf(a, b)))
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(minOf(a, b))!!.locations).hasSize(2)
  }

  // --- Code di lavoro -----------------------------------------------------------------------------------------------

  @Test
  fun leCodeDelTelefonoEDelNasSonoSeparate() = runBlocking<Unit> {
    val phoneOnly = catalog.record(obs(phone("IMG_1.jpg", 1), full(1), capture = T0)).mediaId!!
    val nasOnly = catalog.recordAll(listOf(nasObs("Foto/B.jpg", full(2), modifiedAt = T0 + 1)), seenAt = T0).single().mediaId!!
    val both = catalog.record(obs(phone("IMG_3.jpg", 3), full(3), capture = T0 + 2)).mediaId!!
    catalog.recordAll(listOf(nasObs("Backup/IMG_3.jpg", full(3))), seenAt = T0)

    assertThat(catalog.pendingWork(WorkStage.META, WorkPlace.PHONE, 10)).containsExactly(both, phoneOnly).inOrder()
    assertThat(catalog.pendingWork(WorkStage.META, WorkPlace.NAS, 10)).containsExactly(both, nasOnly).inOrder()

    assertThat(catalog.requestWork(listOf(nasOnly, both), WorkStage.HQ)).isEqualTo(2)
    assertThat(catalog.requestWork(listOf(nasOnly), WorkStage.HQ)).isEqualTo(0)
    assertThat(catalog.pendingWork(WorkStage.HQ, WorkPlace.NAS, 10)).containsExactly(both, nasOnly).inOrder()
  }

  private companion object {
    const val PRESENCE = CellFlags.ON_PHONE or CellFlags.ON_NAS or CellFlags.SAFE or CellFlags.TO_VERIFY
  }
}
