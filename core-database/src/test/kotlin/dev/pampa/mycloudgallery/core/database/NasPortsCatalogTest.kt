package dev.pampa.mycloudgallery.core.database

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.database.Db.ME
import dev.pampa.mycloudgallery.core.database.Db.T0
import dev.pampa.mycloudgallery.core.database.Db.full
import dev.pampa.mycloudgallery.core.model.CaptureTime
import dev.pampa.mycloudgallery.core.model.CaptureTimeConfidence
import dev.pampa.mycloudgallery.core.model.CaptureTimeSource
import dev.pampa.mycloudgallery.core.model.FileStamp
import dev.pampa.mycloudgallery.core.model.Fingerprint
import dev.pampa.mycloudgallery.core.model.Location
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaMetadata
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.NasPath
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.model.WorkErrorCode
import dev.pampa.mycloudgallery.core.model.WorkStage
import dev.pampa.mycloudgallery.core.model.WorkStatus
import dev.pampa.mycloudgallery.core.model.domain.Observation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Le operazioni del catalogo aggiunte da P11 per le porte della scansione (`NasScanStore`, aggiunte di P5) e della replica
 * (`ReplicaCatalog`, P4): spostamenti riconosciuti dal `FileId`, posizioni non viste dal giro, esclusioni, metadati in una
 * transazione, posizioni nate solo dall'indice.
 */
@RunWith(AndroidJUnit4::class)
class NasPortsCatalogTest : CatalogTestBase() {

  private val epoch = T0 + 1_000

  private fun nas(path: String, fileId: Long? = null): Location.Nas = Location.Nas(ME, "alessio", NasPath.of(path), fileId)

  private fun nasObs(path: String, fingerprint: Fingerprint, fileId: Long? = null) =
    Observation(nas(path, fileId), FileStamp(fingerprint.size, T0), fingerprint, MediaKind.PHOTO, MediaSource.OTHER)

  @Test
  fun unoSpostamentoDalPcConservaIlMediaETornaPresente() = runBlocking<Unit> {
    val first = catalog.recordAll(listOf(nasObs("Vecchia/A.jpg", full(1), fileId = 7), nasObs("Altro/B.jpg", full(2), fileId = 8)), seenAt = T0)
    val media = first[0].mediaId!!
    val known = catalog.nasByFileIds(ME, "ALESSIO", listOf(7, 8, 9))
    assertThat(known.keys).containsExactly(7L, 8L)
    assertThat(known.getValue(7).location.path).isEqualTo(NasPath.of("Vecchia/A.jpg"))

    // Il posto d'arrivo di B e' gia' occupato da A: niente spostamento per B.
    val moved = catalog.relocateNas(
      listOf(
        NasRelocation(known.getValue(7).locationId, nas("Nuova/A.jpg"), NasStamp(full(1).size, T0, 7)),
        NasRelocation(known.getValue(8).locationId, nas("Nuova/A.jpg"), NasStamp(full(2).size, T0, 8)),
      ),
      seenAt = epoch,
      cursor = NasScanState(ME, "alessio", epoch, NasPath.of("Nuova")),
    )
    assertThat(moved).containsExactly(true, false).inOrder()
    val after = catalog.nasLocationAt(nas("Nuova/A.jpg"))!!
    assertThat(after.locationId).isEqualTo(known.getValue(7).locationId)
    assertThat(after.lastSeenAt).isEqualTo(epoch)
    assertThat(after.stamp.fileId).isEqualTo(7)
    assertThat(catalog.nasLocationAt(nas("Vecchia/A.jpg"))).isNull()
    assertThat(catalog.media(media)!!.locations.single().location).isEqualTo(nas("Nuova/A.jpg", 7))
    assertThat(catalog.nasScanState(ME, "alessio")!!.resumeAfter).isEqualTo(NasPath.of("Nuova"))
  }

  @Test
  fun nonVisteDalGiroEdEsclusioni() = runBlocking<Unit> {
    catalog.recordAll(
      listOf(nasObs("Musica/Album/cover.jpg", full(1)), nasObs("Musica/Album/CD1/folder.jpg", full(2)), nasObs("Musica/x.jpg", full(3)), nasObs("Foto/a.jpg", full(4))),
      seenAt = T0,
    )
    catalog.confirmAllPresentNas(listOf(SeenNasFile(nas("Foto/a.jpg"), NasStamp(full(4).size, T0))), seenAt = epoch)
    assertThat(catalog.countUnseenNas(ME, "alessio", epoch)).isEqualTo(3)

    // La cartella esclusa porta via anche le sottocartelle, non la cartella accanto con un nome che inizia uguale.
    catalog.recordAll(listOf(nasObs("Musica/AlbumBis/z.jpg", full(5))), seenAt = epoch)
    val retired = catalog.retireNas(ME, "alessio", listOf(NasPath.of("Musica/Album")), listOf(nas("Musica/x.jpg").key))
    assertThat(retired).isEqualTo(3)
    assertThat(catalog.nasLocationAt(nas("Musica/Album/CD1/folder.jpg"))!!.state).isEqualTo(LocationState.ABSENT)
    assertThat(catalog.nasLocationAt(nas("Musica/AlbumBis/z.jpg"))!!.state).isEqualTo(LocationState.PRESENT)
    assertThat(catalog.countUnseenNas(ME, "alessio", epoch)).isEqualTo(0)
    assertThat(catalog.nasLocationsInShare(ME, "Alessio").map { it.location.path.value })
      .containsExactly("Musica/Album/cover.jpg", "Musica/Album/CD1/folder.jpg", "Musica/x.jpg", "Foto/a.jpg", "Musica/AlbumBis/z.jpg")
  }

  @Test
  fun leNateSoloDallIndiceSiRiconosconoESiTolgono() = runBlocking<Unit> {
    catalog.recordAll(listOf(nasObs("Foto/indice.jpg", full(1))), MediaCatalog.WhenUndecided.KEEP_SEPARATE, seenAt = 0L)
    catalog.recordAll(listOf(nasObs("Foto/scansione.jpg", full(2))), seenAt = epoch)
    val byPath = catalog.nasLocationsInShare(ME, "alessio").associateBy { it.location.path.value }
    assertThat(byPath.getValue("Foto/indice.jpg").indexOnly).isTrue()
    assertThat(byPath.getValue("Foto/scansione.jpg").indexOnly).isFalse()
    assertThat(catalog.markNasVanished(listOf(nas("Foto/indice.jpg").key, nas("Foto/mai.jpg").key))).isEqualTo(1)
    assertThat(catalog.nasLocationAt(nas("Foto/indice.jpg"))!!.state).isEqualTo(LocationState.ABSENT)
  }

  /** P7: una share non raggiungibile (NAS spento, fuori casa) tiene i riferimenti al NAS: le miniature dalla replica (E-L4). */
  @Test
  fun unaShareNonRaggiungibileTieneIRiferimentiAlNas() = runBlocking<Unit> {
    val out = catalog.recordAll(listOf(nasObs("Foto/a.jpg", full(1))), seenAt = T0)
    val id = out.single().mediaId!!
    catalog.markShareUnavailable(ME, "alessio")
    assertThat(catalog.nasLocationAt(nas("Foto/a.jpg"))!!.state).isEqualTo(LocationState.UNAVAILABLE)
    assertThat(catalog.nasRefs(listOf(id)).getValue(id).locationId).isEqualTo(out.single().locationId!!.value)
  }

  @Test
  fun iMetadatiDelRilevamentoEntranoInUnaTransazioneConLaFaseMeta() = runBlocking<Unit> {
    val outcomes = catalog.recordAll(listOf(nasObs("Foto/a.jpg", full(1)), nasObs("Foto/b.jpg", full(2))), seenAt = T0)
    val a = outcomes[0]
    val b = outcomes[1]
    val capture = CaptureTime(T0 - 86_400_000, 120, CaptureTimeConfidence.EXACT, CaptureTimeSource.EXIF_WITH_OFFSET)
    catalog.applyNasMetadata(
      listOf(
        NasMetadataUpdate(a.locationId!!, NasMetadataResult.Read(MediaMetadata(mimeType = "image/jpeg", width = 4000, height = 3000, captureTime = capture), MediaKind.PHOTO)),
        NasMetadataUpdate(b.locationId!!, NasMetadataResult.Failed(WorkErrorCode("DECODE_FAILED"))),
      ),
    )
    val media = catalog.media(a.mediaId!!)!!
    val metadata = db.versionDao().metadata(media.versions.single().id.value)!!
    assertThat(metadata.width).isEqualTo(4000)
    assertThat(catalog.workStates(a.mediaId!!).first { it.stage == WorkStage.META }.status).isEqualTo(WorkStatus.DONE)
    assertThat(catalog.workStates(b.mediaId!!).first { it.stage == WorkStage.META }.status).isEqualTo(WorkStatus.FAILED)
  }
}
