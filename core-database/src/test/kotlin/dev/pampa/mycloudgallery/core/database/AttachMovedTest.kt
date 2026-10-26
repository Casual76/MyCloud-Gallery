package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.entity.AlbumEntity
import dev.pampa.mycloudgallery.core.database.entity.AlbumItemEntity
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.QuickHash
import dev.pampa.mycloudgallery.core.model.SafetyStatus
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La porta di completamento degli spostamenti (Fase 7, D29, E-SH2; `MediaCatalog.attachMoved`): il file arrivato nell'area comune e'
 * collegato allo **stesso** media prima che la sorgente vada nel cestino; **idempotente**, in **una transazione** e con **un solo**
 * cambiamento dell'indice della timeline.
 */
@RunWith(AndroidJUnit4::class)
class AttachMovedTest : CatalogTestBase() {

  private val source = nasLocation("Backup/S25/2026/03/IMG_1.jpg")
  private val dest = nasLocation("Album condivisi/Oslo 2026/IMG_1.jpg", share = "NAS", fileId = 4242)

  private fun stamp(fp: Fingerprint, modifiedAt: Long = T0 + 5) = FileStamp(fp.size, modifiedAt)

  /** Un media proprio con la copia sul telefono e nell'area privata, riletta e verificata (al sicuro). */
  private suspend fun safeMedia(fp: Fingerprint = smallFull(1)): MediaId {
    val id = catalog.record(phoneObservation("IMG_1.jpg", 101, fp, T0 - 1)).mediaId!!
    val location = catalog.attachCopy(id, catalog.versionOf(id), source, FileStamp(fp.size, T0), verifiedAtMillis = T0)
    assertThat(catalog.safety(id)!!.status).isEqualTo(SafetyStatus.SAFE)
    assertThat(catalog.nasLocationAt(source)!!.locationId).isEqualTo(location)
    return id
  }

  @Test
  fun loSpostamentoCollegaLaStessaRigaAlNuovoPostoSenzaPerdereNiente() = runBlocking<Unit> {
    val fp = smallFull(1)
    val id = safeMedia(fp)
    catalog.setPersonalFlags(id, PersonalFlags(favorite = true))
    db.albumDao().insert(AlbumEntity("a1", "Oslo", coverMediaId = id.value, createdAt = T0, updatedAt = T0))
    db.albumDao().upsertItem(AlbumItemEntity("a1", id.value, 1000, T0))
    val sourceRow = catalog.nasLocationAt(source)!!
    val changes = Changes(this, catalog)
    val seq = catalog.changeSeq()

    val outcome = catalog.attachMoved(id, dest, stamp(fp), from = source.key, sha256 = fp.fullHash)

    assertThat(outcome).isEqualTo(AttachMovedOutcome.Attached)
    // La riga e' la stessa (stesso id di posizione, stessa versione): ha cambiato posto, e il vecchio posto non c'e' piu'.
    val moved = catalog.nasLocationAt(dest)!!
    assertThat(moved.locationId).isEqualTo(sourceRow.locationId)
    assertThat(moved.state).isEqualTo(LocationState.PRESENT)
    assertThat(catalog.nasLocationAt(source)).isNull()
    assertThat(catalog.media(id)!!.locations.filter { it.isNas }.map { it.id }).containsExactly(sourceRow.locationId)
    // Verificata: l'hash che il motore ha letto e' quello della versione.
    assertThat(db.locationDao().get(sourceRow.locationId.value)!!.verifiedAt).isNotNull()
    // Sul media tutto e' rimasto: preferito, album, copertina. La copia privata non c'e' piu': non e' piu' "al sicuro" (D28).
    assertThat(db.personalDao().get(id.value)!!.favorite).isTrue()
    assertThat(db.albumDao().items("a1").map { it.mediaId }).containsExactly(id.value)
    assertThat(catalog.safety(id)!!.status).isNotEqualTo(SafetyStatus.SAFE)
    assertThat(db.mediaDao().get(id.value)!!.copyAreas).isEqualTo(CopyAreas.COMMON)
    // Un solo cambiamento dell'indice, annunciato dopo il commit.
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
    assertThat(changes.next().seq).isEqualTo(seq + 1)
    assertThat(changes.none()).isTrue()
    changes.job.cancel()
  }

  @Test
  fun ilSecondoGiroNonScriveNienteENonAnnunciaNiente() = runBlocking<Unit> {
    val fp = smallFull(1)
    val id = safeMedia(fp)
    assertThat(catalog.attachMoved(id, dest, stamp(fp), from = source.key, sha256 = fp.fullHash)).isEqualTo(AttachMovedOutcome.Attached)
    val rows = db.locationDao().inFolder(dest.folderKey)
    val seq = catalog.changeSeq()
    val changes = Changes(this, catalog)

    repeat(3) {
      assertThat(catalog.attachMoved(id, dest, stamp(fp), from = source.key, sha256 = fp.fullHash)).isEqualTo(AttachMovedOutcome.AlreadyAttached)
    }

    assertThat(db.locationDao().inFolder(dest.folderKey)).isEqualTo(rows)
    assertThat(catalog.changeSeq()).isEqualTo(seq)
    assertThat(changes.none()).isTrue()
    changes.job.cancel()
  }

  @Test
  fun unCaricamentoDalTelefonoAggiungeLaPosizioneEFaRestareLaCopiaDelTelefono() = runBlocking<Unit> {
    val fp = smallFull(2)
    val id = catalog.record(phoneObservation("IMG_2.jpg", 102, fp, T0 - 2)).mediaId!!
    val seq = catalog.changeSeq()

    assertThat(catalog.attachMoved(id, dest, stamp(fp), sha256 = fp.fullHash)).isEqualTo(AttachMovedOutcome.Attached)

    val locations = catalog.media(id)!!.locations
    assertThat(locations.map { it.isPhone }).containsExactly(true, false)
    assertThat(locations.all { it.state == LocationState.PRESENT }).isTrue()
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
    assertThat(catalog.attachMoved(id, dest, stamp(fp), sha256 = fp.fullHash)).isEqualTo(AttachMovedOutcome.AlreadyAttached)
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
  }

  @Test
  fun seLaScansioneHaGiaVistoIlFileLaSorgenteDiventaAssenteInUnCambiamentoSolo() = runBlocking<Unit> {
    val fp = smallFull(1)
    val id = safeMedia(fp)
    // La scansione dell'area comune vede il file nuovo per prima: ha gli stessi byte, quindi e' un'altra posizione dello stesso media.
    val scanned = catalog.record(nasObservation(dest.path.value, fp, share = "NAS", modifiedAt = T0 + 5, fileId = 4242))
    assertThat(scanned.mediaId).isEqualTo(id)
    val seq = catalog.changeSeq()
    val sourceRow = catalog.nasLocationAt(source)!!

    assertThat(catalog.attachMoved(id, dest, stamp(fp), from = source.key, sha256 = fp.fullHash)).isEqualTo(AttachMovedOutcome.Attached)

    assertThat(catalog.nasLocationAt(source)!!.state).isEqualTo(LocationState.ABSENT)
    assertThat(catalog.nasLocationAt(source)!!.locationId).isEqualTo(sourceRow.locationId)
    assertThat(catalog.nasLocationAt(dest)!!.state).isEqualTo(LocationState.PRESENT)
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
    assertThat(catalog.attachMoved(id, dest, stamp(fp), from = source.key, sha256 = fp.fullHash)).isEqualTo(AttachMovedOutcome.AlreadyAttached)
    assertThat(catalog.changeSeq()).isEqualTo(seq + 1)
  }

  @Test
  fun cioCheNonSiPuoCollegareNonScriveNienteEDiceIlMotivo() = runBlocking<Unit> {
    val fp = smallFull(1)
    val id = safeMedia(fp)
    val other = catalog.record(nasObservation(dest.path.value, smallFull(9), share = "NAS", fileId = 4242)).mediaId!!
    val seq = catalog.changeSeq()
    val before = db.locationDao().get(catalog.nasLocationAt(source)!!.locationId.value)

    // Il posto d'arrivo e' gia' di un altro media.
    assertThat(catalog.attachMoved(id, dest, stamp(fp), from = source.key, sha256 = fp.fullHash)).isEqualTo(AttachMovedOutcome.DestinationOfOtherMedia)
    // Un posto libero, ma byte diversi: dimensione o hash.
    val free = nasLocation("Album condivisi/Oslo 2026/IMG_2.jpg", share = "NAS")
    assertThat(catalog.attachMoved(id, free, FileStamp(fp.size + 1, T0), from = source.key)).isEqualTo(AttachMovedOutcome.SizeMismatch)
    assertThat(catalog.attachMoved(id, free, stamp(fp), from = source.key, sha256 = Sha256("c".repeat(64)))).isEqualTo(AttachMovedOutcome.ContentMismatch)
    assertThat(catalog.attachMoved(MediaId.random(), free, stamp(fp))).isEqualTo(AttachMovedOutcome.UnknownMedia)

    assertThat(catalog.changeSeq()).isEqualTo(seq)
    assertThat(db.locationDao().get(catalog.nasLocationAt(source)!!.locationId.value)).isEqualTo(before)
    assertThat(catalog.nasLocationAt(free)).isNull()
    assertThat(catalog.nasLocationAt(dest)!!.locationId).isEqualTo(catalog.media(other)!!.locations.single().id)
  }

  @Test
  fun unIdAssorbitoDaUnUnioneCollegaIlFileAlSopravvissuto() = runBlocking<Unit> {
    val full = smallFull(5)
    val a = catalog.record(phoneObservation("IMG_5a.jpg", 105, full, T0 - 5)).mediaId!!
    val b = catalog.record(phoneObservation("IMG_5b.jpg", 205, full.copy(quickHash = QuickHash("f".repeat(64))), T0 - 5)).mediaId!!
    val merged = catalog.merge(a, b) as MediaCatalog.MergeOutcome.Merged

    assertThat(catalog.attachMoved(merged.loser, dest, FileStamp(full.size, T0), sha256 = full.fullHash)).isEqualTo(AttachMovedOutcome.Attached)

    assertThat(catalog.media(merged.survivor)!!.locations.any { it.location.key == dest.key }).isTrue()
  }
}
