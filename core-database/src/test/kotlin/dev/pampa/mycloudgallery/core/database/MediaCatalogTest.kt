package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.DAY
import dev.pampa.mycloudgallery.core.database.Db.ME
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.full
import dev.pampa.mycloudgallery.core.database.Db.nas
import dev.pampa.mycloudgallery.core.database.Db.obs
import dev.pampa.mycloudgallery.core.database.Db.phone
import dev.pampa.mycloudgallery.core.database.Db.quick
import dev.pampa.mycloudgallery.core.database.Db.stamp
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.NasArea
import dev.pampa.mycloudgallery.core.model.NasShare
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.VersionKind
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.LinkDecision
import dev.pampa.mycloudgallery.core.model.domain.LinkEvidence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Gli scenari del "Fatto quando" della sottofase 1.3, sul database vero: creazione, collegamento di
 * posizioni, spostamento, nuova versione, correzione, rimozione di una posizione, cambio di ID
 * MediaStore. In ognuno album, copertina, preferito, vettore e stati di lavoro restano attaccati.
 */
@RunWith(AndroidJUnit4::class)
class MediaCatalogTest : CatalogTestBase() {

  private val photoName = "20261012_101010.jpg"
  private val backupPath = "Backup/S25/2026/10/$photoName"

  private data class Setup(val mediaId: MediaId, val album: AlbumId, val phoneLocation: LocationId, val nasLocation: LocationId)

  /** Una foto del telefono salvata e verificata nell'area privata, con tutti i riferimenti. */
  private suspend fun backedUpPhoto(): Setup {
    val created = catalog.record(obs(phone(photoName, 10), full(1)))
    val id = created.mediaId!!
    val version = catalog.media(id)!!.currentVersionId
    val nasLocation = catalog.attachCopy(id, version, nas(backupPath), stamp(full(1)), verifiedAtMillis = now)
    val album = decorate(id)
    return Setup(id, album, created.locationId!!, nasLocation)
  }

  private suspend fun row(id: MediaId) = db.mediaDao().get(id.value)!!

  // --- Creazione -----------------------------------------------------------------------------------

  @Test
  fun creazioneDiUnMediaDalTelefono() = runBlocking<Unit> {
    val outcome = catalog.record(obs(phone(photoName, 10), full(1), capture = T0 - DAY))

    assertThat(outcome.decision).isEqualTo(LinkDecision.NewMedia)
    val id = outcome.mediaId!!
    val media = catalog.media(id)!!
    assertThat(media.versions.single().kind).isEqualTo(VersionKind.ORIGINAL)
    assertThat(media.owner).isEqualTo(ME)
    assertThat(media.locations.single().location).isEqualTo(phone(photoName, 10))
    val states = catalog.workStates(id)
    assertThat(states.map { it.stage })
      .containsExactly(WorkStage.META, WorkStage.THUMB, WorkStage.ANALYSIS_COPY, WorkStage.EMBED, WorkStage.OCR)
    assertThat(states.all { it.status == WorkStatus.PENDING && it.versionId == media.currentVersionId }).isTrue()
    assertThat(row(id).presence).isEqualTo(Presence.PHONE_ONLY.code)
    assertThat(row(id).safety).isEqualTo(SafetyStatus.NO_NAS_COPY.code)
    assertThat(row(id).sortTime).isEqualTo(T0 - DAY)
    assertThat(db.timelineDao().observeFirstPage(10).first().map { it.mediaId }).containsExactly(id.value)
  }

  // --- Collegamento di posizioni -------------------------------------------------------------------

  @Test
  fun collegamentoDellaCopiaSulNasEDelleCopieIdentiche() = runBlocking<Unit> {
    val s = backedUpPhoto()
    assertThat(row(s.mediaId).presence).isEqualTo(Presence.PHONE_AND_NAS.code)
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.SAFE.code)

    // La scansione del NAS ritrova il file del backup: stesso file, nessun media nuovo.
    val scanned = catalog.record(obs(nas(backupPath), quick(1)))
    assertThat(scanned.decision).isInstanceOf(LinkDecision.SameFile::class.java)
    assertThat(scanned.mediaId).isEqualTo(s.mediaId)

    // Una copia identica fatta dal PC in un'altra cartella (E-L3): una posizione in piu'.
    val copy = catalog.record(obs(nas("Copie/foto.jpg"), full(1)))
    assertThat(copy.decision).isEqualTo(LinkDecision.NewLocation(s.mediaId, catalog.media(s.mediaId)!!.currentVersionId, LinkEvidence.FULL_HASH))
    assertThat(catalog.media(s.mediaId)!!.locations).hasSize(3)
    assertReferencesIntact(s.mediaId, s.album)
  }

  // --- Spostamento ---------------------------------------------------------------------------------

  /** E-N1: la cartella del backup rinominata dal PC. Nessun media nuovo, nessun lavoro da rifare. */
  @Test
  fun spostamentoFattoDalPcCambiaLaPosizioneNonLIdentita() = runBlocking<Unit> {
    val s = backedUpPhoto()
    now += DAY

    catalog.markVanished(nas(backupPath).key)
    val moved = catalog.record(obs(nas("Archivio/2026/ottobre/$photoName"), quick(1)))

    assertThat(moved.decision).isEqualTo(LinkDecision.Moved(s.nasLocation, s.mediaId, LinkEvidence.MOVE_DETECTED))
    val media = catalog.media(s.mediaId)!!
    assertThat(media.location(s.nasLocation).location).isEqualTo(nas("Archivio/2026/ottobre/$photoName"))
    // Stessi byte e stesso timbro: la verifica resta valida, il media resta al sicuro.
    assertThat(media.location(s.nasLocation).verifiedAtMillis).isNotNull()
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.SAFE.code)
    assertThat(catalog.workStates(s.mediaId).all { it.status == WorkStatus.DONE }).isTrue()
    assertReferencesIntact(s.mediaId, s.album)
  }

  // --- Nuova versione ------------------------------------------------------------------------------

  @Test
  fun unaModificaSulTelefonoEUnaNuovaVersioneDelloStessoMedia() = runBlocking<Unit> {
    val s = backedUpPhoto()
    val original = catalog.media(s.mediaId)!!.currentVersionId
    now += DAY

    val edit = catalog.record(obs(phone(photoName, 10), full(2), modifiedAt = now))

    assertThat(edit.decision).isEqualTo(LinkDecision.ContentChanged(s.phoneLocation, s.mediaId, original))
    val media = catalog.media(s.mediaId)!!
    assertThat(media.versions).hasSize(2)
    assertThat(media.currentVersion.kind).isEqualTo(VersionKind.EDITED)
    assertThat(media.location(s.phoneLocation).versionId).isEqualTo(media.currentVersionId)
    assertThat(media.location(s.nasLocation).versionId).isEqualTo(original)
    // I pixel sono cambiati: tutto da rifare sulla nuova versione; il vettore vecchio resta finche' non c'e' il nuovo.
    assertThat(catalog.workStates(s.mediaId).all { it.status == WorkStatus.PENDING && it.versionId == media.currentVersionId }).isTrue()
    assertThat(db.embeddingDao().forMedia(s.mediaId.value).single().versionId).isEqualTo(original.value)
    // La modifica e' solo sul telefono: non al sicuro finche' non va sul NAS.
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.NO_NAS_COPY.code)
    assertReferencesIntact(s.mediaId, s.album)

    catalog.attachCopy(s.mediaId, media.currentVersionId, nas("Backup/S25/2026/10/20261012_101010 (modificata).jpg"), stamp(full(2), now), now)
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.SAFE.code)
  }

  /** E-E2: "Ripristina originale" di Samsung Gallery riporta i byte dell'originale nello stesso file. */
  @Test
  fun ilRipristinoDellOriginaleNonCreaVersioniNuove() = runBlocking<Unit> {
    val s = backedUpPhoto()
    val original = catalog.media(s.mediaId)!!.currentVersionId
    catalog.record(obs(phone(photoName, 10), full(2), modifiedAt = T0 + 1))

    val restored = catalog.record(obs(phone(photoName, 10), quick(1), modifiedAt = T0 + 2))

    assertThat(restored.decision).isEqualTo(LinkDecision.RevertedToVersion(s.phoneLocation, s.mediaId, original))
    val media = catalog.media(s.mediaId)!!
    assertThat(media.currentVersionId).isEqualTo(original)
    assertThat(media.versions).hasSize(2)
    assertReferencesIntact(s.mediaId, s.album)
  }

  /** E-E7: la copia scaricata in `Pictures/MyCloud Gallery` modificata e' una versione, non un backup nuovo. */
  @Test
  fun modificareUnaCopiaScaricataCreaUnaVersioneDelMediaDelNas() = runBlocking<Unit> {
    val fromNas = catalog.record(obs(nas("Foto/2019/vecchia.jpg"), full(3))).mediaId!!
    val downloaded = phone("vecchia.jpg", 500, path = "Pictures/MyCloud Gallery/")
    catalog.attachCopy(fromNas, catalog.media(fromNas)!!.currentVersionId, downloaded, stamp(full(3)), verifiedAtMillis = null)

    val edit = catalog.record(obs(downloaded, full(4), modifiedAt = T0 + DAY))

    assertThat(edit.decision).isInstanceOf(LinkDecision.ContentChanged::class.java)
    assertThat(edit.mediaId).isEqualTo(fromNas)
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(fromNas)!!.versions).hasSize(2)
  }

  // --- Correzione ----------------------------------------------------------------------------------

  /**
   * E-L8: correzione della data scritta nel file come nuova versione. Sul NAS il file al solito
   * percorso diventa la versione corretta e l'originale resta conservato in un'altra cartella (5.4).
   */
  @Test
  fun unaCorrezioneDiDataEUnaVersioneCheRifaSoloIMetadati() = runBlocking<Unit> {
    val s = backedUpPhoto()
    val original = catalog.media(s.mediaId)!!.currentVersionId
    now += DAY

    catalog.relocate(s.nasLocation, nas("Backup/S25/2026/10/.versioni/$photoName"), stamp(full(1)), verifiedAtMillis = now)
    val corrected = full(5)
    val newDate = CaptureTime(T0 - 30 * DAY, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.USER_CORRECTION)
    val version = catalog.correct(s.mediaId, setOf(CorrectionField.DATE), corrected, emptyMap(), MediaMetadata(captureTime = newDate))
    catalog.attachCopy(s.mediaId, version, nas(backupPath), stamp(corrected, now), verifiedAtMillis = now)

    val media = catalog.media(s.mediaId)!!
    assertThat(media.currentVersionId).isEqualTo(version)
    assertThat(media.currentVersion.kind).isEqualTo(VersionKind.CORRECTED)
    assertThat(media.currentVersion.parentId).isEqualTo(original)
    // La data cambia, i pixel no: solo META da rifare, miniatura ed embedding restano validi.
    val states = catalog.workStates(s.mediaId).associateBy { it.stage }
    assertThat(states.getValue(WorkStage.META).status).isEqualTo(WorkStatus.PENDING)
    assertThat(states.filterKeys { it != WorkStage.META }.values.all { it.status == WorkStatus.DONE }).isTrue()
    // La timeline si sposta alla data corretta.
    assertThat(row(s.mediaId).sortTime).isEqualTo(T0 - 30 * DAY)
    // Originale e correzione hanno entrambi una copia privata verificata.
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.SAFE.code)
    assertReferencesIntact(s.mediaId, s.album)
  }

  // --- Rimozione di una posizione ------------------------------------------------------------------

  @Test
  fun cancellareDalTelefonoLasciaIlMediaSoloSulNas() = runBlocking<Unit> {
    val s = backedUpPhoto()

    catalog.markVanished(phone(photoName, 10).key)

    assertThat(row(s.mediaId).presence).isEqualTo(Presence.NAS_ONLY.code)
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.SAFE.code)
    assertThat(db.timelineDao().observeFirstPage(10).first().map { it.mediaId }).containsExactly(s.mediaId.value)

    // Dopo la tolleranza la posizione del telefono si dimentica; il media resta con la copia sul NAS.
    now += 61 * DAY
    assertThat(catalog.purgeExpiredAbsences()).isEqualTo(1)
    assertThat(catalog.media(s.mediaId)!!.locations.map { it.id }).containsExactly(s.nasLocation)
    assertReferencesIntact(s.mediaId, s.album)
  }

  /** E-P11 e E-B4: cestinata prima del backup (persa per l'app), poi ripristinata: stesso media. */
  @Test
  fun unaFotoNelCestinoDiGalleriaRitornaAlloStessoMedia() = runBlocking<Unit> {
    val id = catalog.record(obs(phone(photoName, 10), full(1))).mediaId!!
    val album = decorate(id)

    catalog.markVanished(phone(photoName, 10).key)
    assertThat(row(id).presence).isEqualTo(Presence.NONE.code)
    assertThat(row(id).safety).isEqualTo(SafetyStatus.LOST.code)
    assertThat(db.timelineDao().observeFirstPage(10).first()).isEmpty()

    val restored = catalog.record(obs(phone(photoName, 4_242), quick(1)))

    assertThat(restored.decision).isInstanceOf(LinkDecision.SameFile::class.java)
    assertThat((restored.decision as LinkDecision.SameFile).reappeared).isTrue()
    assertThat(restored.mediaId).isEqualTo(id)
    assertThat(row(id).presence).isEqualTo(Presence.PHONE_ONLY.code)
    assertReferencesIntact(id, album)
  }

  /** E-P6: microSD tolta e rimessa. */
  @Test
  fun unaMicroSdToltaNonCancellaNulla() = runBlocking<Unit> {
    val onSd = phone("a.jpg", 7, volume = "1a2b-3c4d")
    val id = catalog.record(obs(onSd, full(1))).mediaId!!

    catalog.markVolumeUnavailable("1a2b-3c4d")
    assertThat(row(id).presence).isEqualTo(Presence.NONE.code)
    assertThat(row(id).safety).isEqualTo(SafetyStatus.NO_NAS_COPY.code)

    val back = catalog.record(obs(onSd, quick(1)))
    assertThat(back.mediaId).isEqualTo(id)
    assertThat(row(id).presence).isEqualTo(Presence.PHONE_ONLY.code)
  }

  // --- Cambio di ID MediaStore (E-P12) -------------------------------------------------------------

  @Test
  fun unCambioDiIdMediaStoreNonCreaMediaNuovi() = runBlocking<Unit> {
    val s = backedUpPhoto()

    val renumbered = catalog.record(obs(phone(photoName, 1_000_000_353, storeVersion = "store-v2"), quick(1)))

    assertThat(renumbered.decision).isInstanceOf(LinkDecision.SameFile::class.java)
    assertThat(renumbered.locationId).isEqualTo(s.phoneLocation)
    val cached = catalog.media(s.mediaId)!!.location(s.phoneLocation).location as Location.Phone
    assertThat(cached.mediaStoreId).isEqualTo(1_000_000_353)
    assertThat(cached.mediaStoreVersion).isEqualTo("store-v2")
    assertReferencesIntact(s.mediaId, s.album)
  }

  /** Una ricostruzione del MediaStore: tutti gli ID cambiano e ognuno coincide con il vecchio ID di un altro file. */
  @Test
  fun unaRinumerazioneDiMassaConIdRiusatiNonCreaNeScambiaMedia() = runBlocking<Unit> {
    val count = 30
    val byName = (0 until count).associate { i -> "IMG_$i.jpg" to catalog.record(obs(phone("IMG_$i.jpg", 100L + i), full(100 + i))).mediaId!! }

    (0 until count).forEach { i ->
      val reusedId = 100L + (i + 1) % count
      val outcome = catalog.record(obs(phone("IMG_$i.jpg", reusedId, storeVersion = "store-v2"), quick(100 + i)))
      assertThat(outcome.decision).isInstanceOf(LinkDecision.SameFile::class.java)
      assertThat(outcome.mediaId).isEqualTo(byName.getValue("IMG_$i.jpg"))
    }
    assertThat(db.mediaDao().count()).isEqualTo(count)
  }

  // --- Maiuscole e doppioni ------------------------------------------------------------------------

  /** E-N7: lo stesso file visto con un'altra combinazione di maiuscole non diventa un doppione. */
  @Test
  fun maiuscoleDiverseNonCreanoDoppioniFantasma() = runBlocking<Unit> {
    val first = catalog.record(obs(nas("Foto/IMG_0001.JPG", share = "NAS"), full(1)))
    val second = catalog.record(obs(nas("foto/img_0001.jpg", share = "nas"), full(1)))

    assertThat(second.decision).isInstanceOf(LinkDecision.SameFile::class.java)
    assertThat(second.locationId).isEqualTo(first.locationId)
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(first.mediaId!!)!!.locations).hasSize(1)
  }

  /** E-L3: file identici in piu' cartelle del NAS sono un media solo. */
  @Test
  fun fileIdenticiInPiuCartelleSonoUnMediaSolo() = runBlocking<Unit> {
    val a = catalog.record(obs(nas("Vacanze/a.jpg"), full(7)))
    val b = catalog.record(obs(nas("Copia vacanze/a.jpg"), full(7)))
    val c = catalog.record(obs(nas("Telefono vecchio/a.jpg", share = "NAS"), full(7)))

    assertThat(listOf(b.mediaId, c.mediaId)).containsExactly(a.mediaId, a.mediaId)
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(a.mediaId!!)!!.locations).hasSize(3)
  }

  // --- Nel dubbio non si unisce; poi si unisce ------------------------------------------------------

  @Test
  fun nelDubbioRestanoSeparatiEPoiSiUnisconoSenzaPerdereRiferimenti() = runBlocking<Unit> {
    val a = catalog.record(obs(nas("A/x.jpg"), quick(9))).mediaId!!

    val waiting = catalog.record(obs(nas("B/x.jpg"), quick(9)))
    assertThat(waiting.decision).isInstanceOf(LinkDecision.NeedsFullHash::class.java)
    assertThat(db.mediaDao().count()).isEqualTo(1)

    val b = catalog.record(obs(nas("B/x.jpg"), quick(9)), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId!!
    assertThat(b).isNotEqualTo(a)
    val albumOfB = decorate(b)
    catalog.setPersonalFlags(a, PersonalFlags(hidden = true))

    // Le impronte complete dimostrano che sono gli stessi byte.
    catalog.record(obs(nas("A/x.jpg"), full(9)))
    catalog.record(obs(nas("B/x.jpg"), full(9)))
    val outcome = catalog.merge(a, b) as MediaCatalog.MergeOutcome.Merged

    val survivor = outcome.survivor
    assertThat(survivor).isEqualTo(minOf(a, b))
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.resolve(a)).isEqualTo(survivor)
    assertThat(catalog.resolve(b)).isEqualTo(survivor)
    assertThat(catalog.media(outcome.loser)!!.id).isEqualTo(survivor)
    val merged = catalog.media(survivor)!!
    assertThat(merged.versions).hasSize(1)
    assertThat(merged.locations).hasSize(2)
    // Album, copertina, preferito (di B) e nascosto (di A) passano al media unito.
    assertThat(db.albumDao().items(albumOfB.value).map { it.mediaId }).containsExactly(survivor.value)
    assertThat(db.albumDao().get(albumOfB.value)!!.coverMediaId).isEqualTo(survivor.value)
    val flags = db.personalDao().get(survivor.value)!!
    assertThat(flags.favorite).isTrue()
    assertThat(flags.hidden).isTrue()
    assertThat(db.embeddingDao().forMedia(survivor.value)).hasSize(1)
    assertThat(catalog.workStates(survivor).filter { it.status == WorkStatus.DONE }).hasSize(5)
  }

  @Test
  fun unaTerzaCopiaIdenticaUnisceDaSolaIDoppioni() = runBlocking<Unit> {
    val a = catalog.record(obs(nas("A/x.jpg"), quick(9))).mediaId!!
    val b = catalog.record(obs(nas("B/x.jpg"), quick(9)), MediaCatalog.WhenUndecided.KEEP_SEPARATE).mediaId!!
    catalog.record(obs(nas("A/x.jpg"), full(9)))
    catalog.record(obs(nas("B/x.jpg"), full(9)))

    val third = catalog.record(obs(nas("C/x.jpg"), full(9)))

    assertThat((third.decision as LinkDecision.NewLocation).alsoIdenticalTo).hasSize(1)
    assertThat(third.mediaId).isEqualTo(minOf(a, b))
    assertThat(db.mediaDao().count()).isEqualTo(1)
    assertThat(catalog.media(third.mediaId!!)!!.locations).hasSize(3)
  }

  @Test
  fun dueMediaConByteDiversiNonSiUniscono() = runBlocking<Unit> {
    val a = catalog.record(obs(nas("A/x.jpg"), full(1))).mediaId!!
    val b = catalog.record(obs(nas("B/y.jpg"), full(2))).mediaId!!
    assertThat(catalog.merge(a, b)).isInstanceOf(MediaCatalog.MergeOutcome.Refused::class.java)
    assertThat(db.mediaDao().count()).isEqualTo(2)
  }

  // --- "Al sicuro" (E-B8, E-B13, E-B14) ------------------------------------------------------------

  /** E-B13: condiviso (spostato nell'area comune), poi cancellato dal telefono; l'app ricrea la copia privata. */
  @Test
  fun unMediaCondivisoECancellatoDalTelefonoTornaAlSicuroConUnaCopiaPrivata() = runBlocking<Unit> {
    val s = backedUpPhoto()
    val version = catalog.media(s.mediaId)!!.currentVersionId

    catalog.relocate(s.nasLocation, nas("Album condivisi/Oslo 2026/$photoName", share = "NAS"), stamp(full(1)), verifiedAtMillis = now)
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.ONLY_COMMON_COPY.code)

    catalog.markVanished(phone(photoName, 10).key)
    assertThat(row(s.mediaId).presence).isEqualTo(Presence.NAS_ONLY.code)
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.ONLY_COMMON_COPY.code)

    catalog.attachCopy(s.mediaId, version, nas(backupPath), stamp(full(1)), verifiedAtMillis = now)
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.SAFE.code)
    assertReferencesIntact(s.mediaId, s.album)
  }

  /** E-B8: la rilettura della copia privata non coincide: la copia non conta piu'. */
  @Test
  fun unaCopiaNonIntegraNonRendeIlMediaAlSicuro() = runBlocking<Unit> {
    val s = backedUpPhoto()

    assertThat(catalog.markVerified(s.nasLocation, full(2).fullHash!!, now)).isFalse()
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED.code)

    assertThat(catalog.markVerified(s.nasLocation, full(1).fullHash!!, now + 1)).isTrue()
    assertThat(row(s.mediaId).safety).isEqualTo(SafetyStatus.SAFE.code)
  }

  @Test
  fun unMediaDiUnaShareSconosciutaDiventaMioQuandoLaShareRisultaPrivata() = runBlocking<Unit> {
    val id = catalog.record(obs(nas("x.jpg", share = "Famiglia"), full(1))).mediaId!!
    // Share sconosciuta: nessun proprietario, nessun obbligo di copia privata.
    assertThat(catalog.media(id)!!.owner).isNull()
    assertThat(row(id).safety).isEqualTo(SafetyStatus.NOT_MINE.code)

    catalog.registerShare(NasShare(ME, "famiglia", NasArea.PRIVATE))

    assertThat(catalog.media(id)!!.owner).isEqualTo(ME)
    assertThat(row(id).safety).isEqualTo(SafetyStatus.PRIVATE_COPY_UNVERIFIED.code)
  }
}
