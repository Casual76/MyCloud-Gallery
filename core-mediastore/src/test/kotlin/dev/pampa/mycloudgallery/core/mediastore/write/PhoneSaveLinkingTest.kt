package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncTestBase
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Il collegamento della copia scaricata (E-L2, D20; riga P8 del §4.4: "collegamento `SameFile`"): il writer vero davanti
 * a un catalogo vero e a una lettura del telefono vera. La posizione che `beforePublish` riceve e' quella che il
 * MediaStore avra' scritto, `attachCopy` la lega al media del NAS **prima** che la riga diventi visibile, e quando la
 * lettura del telefono la vede trova un posto gia' noto con gli stessi byte: `SameFile`, nessun media nuovo, nessuna
 * versione nuova, due posizioni.
 */
@RunWith(AndroidJUnit4::class)
class PhoneSaveLinkingTest : PhoneSyncTestBase() {

  private lateinit var provider: WritableMediaProvider
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private val bytes = FakeMediaContent.jpeg(6_000, seed = 11)
  private val fingerprint = Fingerprint.ofContent(bytes)
  private val source = PhoneSaveSource { sink -> bytes.toList().chunked(1_024).forEach { sink(it.toByteArray(), it.size) } }

  @Before
  fun registerProvider() {
    provider = WritableMediaProvider.register()
  }

  private suspend fun recordNasMedia() = catalog.record(
    Observation(
      location = Location.Nas(ME, "alessio", NasPath.of("Foto/2019/vecchia.jpg")),
      stamp = FileStamp(bytes.size.toLong(), T0),
      fingerprint = fingerprint,
      kind = MediaKind.PHOTO,
      source = MediaSource.CAMERA,
    ),
  ).mediaId!!

  /** La riga che il MediaStore mostrera' alla lettura del telefono per la copia appena scritta. */
  private fun rowOf(copy: PendingCopy) = photo(
    id = copy.location.mediaStoreId!!,
    taken = T0 + 5 * MINUTE,
    name = copy.location.displayName,
    path = copy.location.relativePath,
    size = copy.size,
  )

  @Test
  fun laCopiaScaricataSiCollegaPrimaDiEssereVisibileELaLetturaDelTelefonoTrovaSameFile() = runBlocking<Unit> {
    val mediaId = recordNasMedia()
    val versionId = catalog.media(mediaId)!!.currentVersionId
    val writer = MediaStorePhoneMediaWriter(context, versionOf = { "v1" })
    var duringPending: Int? = null
    var linkedLocation: Location.Phone? = null

    val saved = writer.saveToPictures(
      request = SaveRequest("vecchia.jpg", "image/jpeg", bytes.size.toLong()),
      source = source,
      expectedSha256 = fingerprint.fullHash,
    ) { copy ->
      // E' il momento del collegamento: la riga e' ancora in attesa, per il MediaStore e per la lettura del telefono.
      catalog.attachCopy(mediaId, versionId, copy.location, FileStamp(copy.size, T0 + MINUTE), verifiedAtMillis = null)
      linkedLocation = copy.location
      store.putPending(rowOf(copy), bytes)
      duringPending = newSync().syncNow(SyncReason.APP_START).added
    } as SaveResult.Saved

    // La lettura fatta mentre la riga era in attesa non ha visto niente: nessun media nuovo.
    assertThat(duringPending).isEqualTo(0)
    assertThat(mediaCount()).isEqualTo(1)
    assertThat(provider.visibleRows().map { it.second }).containsExactly("vecchia.jpg")

    // Il file diventa visibile; la lettura del telefono lo trova.
    val row = rowOf(saved.copy)
    store.publish(PRIMARY, row.id)
    val report = newSync().syncNow(SyncReason.APP_START)

    assertThat(report.added).isEqualTo(0)
    assertThat(mediaCount()).isEqualTo(1)
    assertThat(versionCount()).isEqualTo(1)
    assertThat(mediaAt(row)).isEqualTo(mediaId)
    assertThat(stateAt(row)).isEqualTo(LocationState.PRESENT)
    // La posizione collegata dal writer e' lo stesso posto che la lettura vede (stessa chiave, stesso volume e nome).
    assertThat(linkedLocation!!.key).isEqualTo(placeOf(row).key)
    // Due posizioni: il NAS e il telefono, dello stesso media.
    val locations = catalog.media(mediaId)!!.locations
    assertThat(locations.map { it.location.type.code }).containsExactly("nas", "phone")
    assertThat(locations.all { it.isPresent }).isTrue()
  }

  @Test
  fun conUnNomeInConflittoLaPosizioneCollegataEQuellaConIlNumero() = runBlocking<Unit> {
    val mediaId = recordNasMedia()
    val versionId = catalog.media(mediaId)!!.currentVersionId
    provider.seed("vecchia.jpg")
    val writer = MediaStorePhoneMediaWriter(context, versionOf = { "v1" })

    val saved = writer.saveToPictures(SaveRequest("vecchia.jpg", "image/jpeg", bytes.size.toLong()), source) { copy ->
      catalog.attachCopy(mediaId, versionId, copy.location, FileStamp(copy.size, T0 + MINUTE), verifiedAtMillis = null)
    } as SaveResult.Saved

    assertThat(saved.copy.location.displayName).isEqualTo("vecchia (2).jpg")
    val row = rowOf(saved.copy)
    store.put(row, bytes)
    val report = newSync().syncNow(SyncReason.APP_START)
    assertThat(report.added).isEqualTo(0)
    assertThat(mediaAt(row)).isEqualTo(mediaId)
    assertThat(mediaCount()).isEqualTo(1)
  }

  @Test
  fun seIlCollegamentoNonRiesceNienteDiVisibileESulCatalogoNienteDiNuovo() = runBlocking<Unit> {
    val mediaId = recordNasMedia()
    val writer = MediaStorePhoneMediaWriter(context, versionOf = { "v1" })

    val result = writer.saveToPictures(SaveRequest("vecchia.jpg", "image/jpeg", bytes.size.toLong()), source) { _ ->
      // Una versione che non esiste: il catalogo rifiuta.
      catalog.attachCopy(mediaId, dev.pampa.mycloudgallery.core.model.VersionId.random(), Location.Phone("external_primary", "Pictures/MyCloud Gallery/", "vecchia.jpg"), FileStamp(bytes.size.toLong(), T0), null)
    }

    assertThat(result).isEqualTo(SaveResult.Failed("LINK_FAILED"))
    assertThat(provider.allRows()).isEmpty()
    assertThat(mediaCount()).isEqualTo(1)
  }
}
