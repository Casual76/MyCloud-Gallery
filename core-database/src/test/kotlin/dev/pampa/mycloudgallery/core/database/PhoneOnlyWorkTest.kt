package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonalFlags
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P11b, §2.2, D14): i media del **solo telefono** per l'analisi AI (`phoneOnlyWork`) e la sorgente dei file del NAS per le unita'
 * di lavoro (D12). Database vero in memoria (Robolectric).
 */
@RunWith(AndroidJUnit4::class)
class PhoneOnlyWorkTest : CatalogTestBase() {

  private suspend fun phone(n: Int, source: MediaSource = MediaSource.CAMERA, kind: MediaKind = MediaKind.PHOTO) =
    catalog.record(phoneObservation("IMG_$n.jpg", 100L + n, smallFull(n), T0 - n, source = source, kind = kind)).mediaId!!

  @Test
  fun isoloTelefonoVengonoAPagineDalPiuRecenteConLaChiaveDiContenuto() = runBlocking<Unit> {
    val phones = (1..5).map { phone(it) }
    // Un media solo sul NAS non c'e'; uno sul telefono E sul NAS (stessi byte) nemmeno: e' sul NAS, i suoi risultati sono dell'unita'.
    catalog.record(nasObservation("Foto/x.jpg", smallFull(50)))
    catalog.record(nasObservation("Backup/S25/IMG_2.jpg", smallFull(2)))

    val first = catalog.phoneOnlyWork(null, 2)
    assertThat(first.map { it.mediaId }).containsExactly(phones[0], phones[2]).inOrder()
    assertThat(first.first().contentKey).isEqualTo(ContentKey.of(smallFull(1)))
    assertThat(first.first().versionId).isEqualTo(catalog.versionOf(phones[0]))
    assertThat(first.first().size).isEqualTo(smallFull(1).size)
    assertThat(first.first().kind).isEqualTo(MediaKind.PHOTO)
    assertThat(first.first().source).isEqualTo(MediaSource.CAMERA)
    val second = catalog.phoneOnlyWork(first.last().cursor, 2)
    assertThat(second.map { it.mediaId }).containsExactly(phones[3], phones[4]).inOrder()
    assertThat(catalog.phoneOnlyWork(second.last().cursor, 2)).isEmpty()
  }

  @Test
  fun inascostiSiCalcolanoMaUnaCopiaSparitaONonDelTelefonoNo() = runBlocking<Unit> {
    val shown = phone(1)
    val hidden = phone(2)
    catalog.setPersonalFlags(hidden, PersonalFlags(hidden = true))
    val gone = phone(3)
    val location = catalog.media(gone)!!.locations.single()
    catalog.markPhoneLocationsVanished(listOf(location.id))

    assertThat(catalog.phoneOnlyWork(null, 10).map { it.mediaId }).containsExactly(shown, hidden)
  }

  @Test
  fun laSorgenteEIlTipoSonoQuelliDelMedia() = runBlocking<Unit> {
    val shot = phone(1, source = MediaSource.SCREENSHOT)
    val video = phone(2, kind = MediaKind.VIDEO)
    val rows = catalog.phoneOnlyWork(null, 10).associateBy { it.mediaId }
    assertThat(rows.getValue(shot).source).isEqualTo(MediaSource.SCREENSHOT)
    assertThat(rows.getValue(video).kind).isEqualTo(MediaKind.VIDEO)
  }

  @Test
  fun leRigheDiLavoroDelNasDiconoDaDoveVieneIlMedia() = runBlocking<Unit> {
    catalog.record(nasObservation("Foto/IMG_1.jpg", smallFull(1)))
    val rows = catalog.nasWorkRows(Db.ME, "alessio")
    // La sorgente delle osservazioni del NAS e' "altro": la scansione non sa distinguere uno screenshot dal nome.
    assertThat(rows.map { it.source }).containsExactly(MediaSource.OTHER)
  }
}
