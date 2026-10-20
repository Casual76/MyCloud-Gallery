package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import dev.pampa.mycloudgallery.core.model.Presence
import dev.pampa.mycloudgallery.core.model.VersionKind
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le operazioni del catalogo per la 5B (P14): "Libera spazio" (`phoneMedia`, `markPhoneLocationsVanished`), "Modifica con…" (`mediaOfPhoneRow`,
 * `addEditedVersion`, `linkCopyAsVersion`), le correzioni solo nell'app (`correctInApp`, che sopravvive alla rilettura di META) e i file in arrivo
 * (`incomingMedia`). Database vero in memoria (Robolectric).
 */
@RunWith(AndroidJUnit4::class)
class CatalogFiveBTest : CatalogTestBase() {

  private suspend fun phoneMedia(n: Int, capture: Long = T0 - n, path: String = "DCIM/Camera/") =
    catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), capture, path = path)).mediaId!!

  @Test
  fun phoneMediaSonoAPagineDalPiuRecenteESoloConUnaCopiaSulTelefono() = runBlocking<Unit> {
    val phones = (1..5).map { phoneMedia(it) }
    // Un media solo sul NAS non c'e'.
    catalog.record(nasObservation("Foto/x.jpg", smallFull(50)))
    val first = catalog.phoneMedia(null, 2)
    assertThat(first.map { it.mediaId }).containsExactly(phones[0], phones[1]).inOrder()
    val second = catalog.phoneMedia(CatalogPhoneCursor(first.last().sortTime, first.last().mediaId), 2)
    assertThat(second.map { it.mediaId }).containsExactly(phones[2], phones[3]).inOrder()
    val third = catalog.phoneMedia(CatalogPhoneCursor(second.last().sortTime, second.last().mediaId), 2)
    assertThat(third.map { it.mediaId }).containsExactly(phones[4])
    assertThat(catalog.phoneMedia(CatalogPhoneCursor(third.last().sortTime, third.last().mediaId), 2)).isEmpty()
  }

  @Test
  fun unaCopiaLiberataDaAndroidDiventaAssenteELaCellaResta() = runBlocking<Unit> {
    val media = phoneMedia(1)
    catalog.record(nasObservation("Backup/S25/IMG_1.jpg", smallFull(1)))
    val phone = catalog.media(media)!!.locations.first { it.location is Location.Phone }
    assertThat(catalog.markPhoneLocationsVanished(listOf(phone.id))).isEqualTo(1)
    // Idempotente: la seconda volta non c'e' niente da segnare.
    assertThat(catalog.markPhoneLocationsVanished(listOf(phone.id))).isEqualTo(0)
    val after = catalog.media(media)!!
    assertThat(after.locations.first { it.id == phone.id }.state).isEqualTo(LocationState.ABSENT)
    // E-P11: la copia sul NAS resta e la cella e' "solo NAS", non persa.
    assertThat(db.mediaDao().get(media.value)!!.presence).isEqualTo(Presence.NAS_ONLY.code)
  }

  @Test
  fun laRigaDelTelefonoSiRitrovaSoloConLaStessaVersioneDelMediaStore() = runBlocking<Unit> {
    val media = phoneMedia(1)
    assertThat(catalog.mediaOfPhoneRow("external_primary", 101, Db.STORE_V1)).isEqualTo(media)
    assertThat(catalog.mediaOfPhoneRow("external_primary", 101, "altra-versione")).isNull()
    assertThat(catalog.mediaOfPhoneRow("external_primary", 999, Db.STORE_V1)).isNull()
  }

  @Test
  fun unaVersioneModificataNasceSenzaPostoEDiventaLaCorrente() = runBlocking<Unit> {
    val media = catalog.record(nasObservation("Foto/IMG_1.jpg", smallFull(1))).mediaId!!
    val parent = catalog.versionOf(media)
    val edited = smallFull(2)
    val id = catalog.addEditedVersion(media, parent, edited.fullHash!!, edited.quickHash, edited.size)!!
    val after = catalog.media(media)!!
    assertThat(after.currentVersionId).isEqualTo(id)
    assertThat(after.version(id).kind).isEqualTo(VersionKind.EDITED)
    assertThat(after.version(id).parentId).isEqualTo(parent)
    assertThat(after.locationsOf(id)).isEmpty()
    // L'originale resta con il suo posto: mai una sostituzione.
    assertThat(after.locationsOf(parent)).hasSize(1)
    // Gli stessi byte di una versione gia' nota non sono una versione nuova; un genitore che non e' del media, nemmeno.
    assertThat(catalog.addEditedVersion(media, parent, edited.fullHash!!, edited.quickHash, edited.size)).isNull()
    val other = catalog.record(nasObservation("Foto/IMG_3.jpg", smallFull(3))).mediaId!!
    val third = smallFull(4)
    assertThat(catalog.addEditedVersion(media, catalog.versionOf(other), third.fullHash!!, third.quickHash, third.size)).isNull()
  }

  @Test
  fun laCopiaDiUnEditorDiventaUnaVersioneDelMediaDiPartenza() = runBlocking<Unit> {
    val original = phoneMedia(1)
    val parent = catalog.versionOf(original)
    val copy = phoneMedia(2)
    val copyVersion = catalog.versionOf(copy)
    val linked = catalog.linkCopyAsVersion(original, copy, parent)
    assertThat(linked).isEqualTo(copyVersion)
    val after = catalog.media(original)!!
    assertThat(after.versions.map { it.id }).containsExactly(parent, copyVersion)
    assertThat(after.currentVersionId).isEqualTo(copyVersion)
    assertThat(after.version(copyVersion).kind).isEqualTo(VersionKind.EDITED)
    assertThat(after.version(copyVersion).parentId).isEqualTo(parent)
    // La posizione della copia segue la sua versione; l'identita' della copia e' un alias.
    assertThat(after.locationsOf(copyVersion)).hasSize(1)
    assertThat(catalog.resolve(copy)).isEqualTo(original)
    assertThat(db.mediaDao().get(copy.value)).isNull()
  }

  @Test
  fun nelDubbioLaCopiaNonSiUnisce() = runBlocking<Unit> {
    val original = phoneMedia(1)
    val parent = catalog.versionOf(original)
    // Un nascosto non si unisce mai.
    val hidden = phoneMedia(2)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    assertThat(catalog.linkCopyAsVersion(original, hidden, parent)).isNull()
    // Lo stesso media, un genitore che non e' suo, un media sparito: no.
    assertThat(catalog.linkCopyAsVersion(original, original, parent)).isNull()
    val copy = phoneMedia(3)
    assertThat(catalog.linkCopyAsVersion(original, copy, catalog.versionOf(copy))).isNull()
    // Una "copia" che ha gia' piu' versioni non e' appena nata.
    val edited = smallFull(9)
    catalog.addEditedVersion(copy, catalog.versionOf(copy), edited.fullHash!!, edited.quickHash, edited.size)
    assertThat(catalog.linkCopyAsVersion(original, copy, parent)).isNull()
    assertThat(catalog.media(original)!!.versions).hasSize(1)
  }

  @Test
  fun unaCorrezioneSoloNellAppValeSubitoESopravviveAllaRiletturaDiMeta() = runBlocking<Unit> {
    val media = phoneMedia(1)
    val version = catalog.versionOf(media)
    catalog.updateMetadata(version, exifMetadata(takenAtUtc = T0 - 1_000_000))
    val corrected = T0 - 50_000_000
    assertThat(catalog.correctInApp(media, version, InAppCorrection(takenAtUtcMillis = corrected, offsetMinutes = 60, geo = InAppGeo.Set(45.0, 9.0)))).isTrue()
    val read = { runBlocking { catalog.versionMetadata(version)!! } }
    assertThat(read().captureTime!!.utcMillis).isEqualTo(corrected)
    assertThat(read().captureTime!!.source).isEqualTo(CaptureTimeSource.USER_CORRECTION)
    assertThat(read().location!!.latitude).isEqualTo(45.0)
    // META rilegge il file (che non e' cambiato): la correzione resta.
    catalog.updateMetadata(version, exifMetadata(takenAtUtc = T0 - 1_000_000))
    assertThat(read().captureTime!!.utcMillis).isEqualTo(corrected)
    assertThat(read().location!!.longitude).isEqualTo(9.0)
    // Una seconda correzione (solo la rotazione, poi via il luogo) si somma alla prima.
    catalog.correctInApp(media, version, InAppCorrection(orientationDegrees = 90, mirrored = false))
    catalog.correctInApp(media, version, InAppCorrection(geo = InAppGeo.Clear))
    catalog.updateMetadata(version, exifMetadata(takenAtUtc = T0 - 1_000_000))
    assertThat(read().orientationDegrees).isEqualTo(90)
    assertThat(read().location).isNull()
    assertThat(read().captureTime!!.utcMillis).isEqualTo(corrected)
    // L'ordine della Libreria segue la data corretta.
    assertThat(db.mediaDao().get(media.value)!!.sortTime).isEqualTo(corrected)
    // Una versione di un altro media, o una correzione vuota: niente.
    assertThat(catalog.correctInApp(phoneMedia(2), version, InAppCorrection(orientationDegrees = 180, mirrored = false))).isFalse()
    assertThat(catalog.correctInApp(media, version, InAppCorrection())).isFalse()
  }

  @Test
  fun unFileInArrivoNasceInvisibileERiusaUnaVersioneGiaNota() = runBlocking<Unit> {
    val fresh = smallFull(7)
    val created = catalog.incomingMedia(fresh, MediaKind.PHOTO, T0)
    assertThat(created.created).isTrue()
    val row = db.mediaDao().get(created.mediaId.value)!!
    // Nessuna posizione: la Libreria non lo mostra finche' il backup non attacca la copia verificata.
    assertThat(row.presence).isEqualTo(Presence.NONE.code)
    assertThat(catalog.media(created.mediaId)!!.locations).isEmpty()
    // La seconda volta (gli stessi byte) e' lo stesso media.
    assertThat(catalog.incomingMedia(fresh, MediaKind.PHOTO, T0)).isEqualTo(created.copy(created = false))
    // Una foto gia' sul telefono con gli stessi byte: il file in arrivo ne sara' una copia.
    val phone = phoneMedia(8)
    val known = catalog.incomingMedia(smallFull(8), MediaKind.PHOTO, T0)
    assertThat(known.created).isFalse()
    assertThat(known.mediaId).isEqualTo(phone)
    // Il caricamento verificato lo rende visibile, "solo NAS" e proprio.
    catalog.attachCopy(
      created.mediaId, created.versionId, nasLocation("Ricevute/IMG.jpg"), FileStamp(fresh.size, T0), verifiedAtMillis = T0,
    )
    assertThat(db.mediaDao().get(created.mediaId.value)!!.presence).isEqualTo(Presence.NAS_ONLY.code)
  }
}
