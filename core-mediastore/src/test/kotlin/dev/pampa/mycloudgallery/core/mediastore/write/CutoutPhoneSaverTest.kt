package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.PendingCopy
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneMediaWriter
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.mediastore.contract.SyncReason
import dev.pampa.mycloudgallery.core.mediastore.sync.PhoneSyncTestBase
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.mediastore.testing.FakePhoneMediaWriter
import dev.pampa.mycloudgallery.core.model.LocationState
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.Sha256
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 6 (P13, D47, E-V11; riga P13 del §4.4): "Salva come nuova foto" del ritaglio. Il writer vero davanti a un catalogo vero e a una lettura
 * del telefono vera, come `PhoneSaveLinkingTest`: il PNG nasce con `IS_PENDING` in `Pictures/MyCloud Gallery/Ritagli/`, il collegamento al
 * catalogo (media nuovo, e nascosto se viene dai Nascosti) avviene PRIMA che la riga sia visibile, e quando la lettura del telefono la
 * vede trova un posto gia' noto con gli stessi byte (`SameFile`).
 */
@RunWith(AndroidJUnit4::class)
class CutoutPhoneSaverTest : PhoneSyncTestBase() {

  private lateinit var provider: WritableMediaProvider
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  /** Un "PNG" qualunque: il writer e il catalogo non guardano dentro (la firma e' la sola cosa che sembra un PNG). */
  private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + FakeMediaContent.jpeg(5_000, seed = 21)

  @Before
  fun registerProvider() {
    provider = WritableMediaProvider.register()
  }

  private fun saver(folder: String = PhoneMediaFolders.CUTOUTS) = CutoutPhoneSaver(MediaStorePhoneMediaWriter(context, relativePath = folder, versionOf = { "v1" }), catalog)

  private fun relativePathOf(id: Long): String? = provider.rowOf(id)?.get("relative_path") as String?

  /** La riga che il MediaStore mostrera' alla lettura del telefono per la copia appena scritta. */
  private fun rowOf(id: Long, name: String, size: Long, path: String = PhoneMediaFolders.CUTOUTS) =
    photo(id = id, taken = T0 + 5 * MINUTE, name = name, path = path, size = size, mime = "image/png")

  @Test
  fun ilRitaglioNasceInAttesaNellaCartellaRitagliEVieneCollegatoPrimaDiEssereVisibile() = runBlocking<Unit> {
    val writer = MediaStorePhoneMediaWriter(context, relativePath = PhoneMediaFolders.CUTOUTS, versionOf = { "v1" })
    // Un writer che spia: cosa si vede nel provider e nel catalogo mentre il collegamento e' in corso.
    var visibleDuring: List<String>? = null
    var pendingDuring: List<Long>? = null
    var mediaDuring: Int? = null
    val spying = object : PhoneMediaWriter {
      override suspend fun saveToPictures(
        request: SaveRequest,
        source: PhoneSaveSource,
        expectedSha256: Sha256?,
        progress: (Long) -> Unit,
        beforePublish: suspend (PendingCopy) -> Unit,
      ): SaveResult = writer.saveToPictures(request, source, expectedSha256, progress) { copy ->
        beforePublish(copy)
        visibleDuring = provider.visibleRows().map { it.second }
        pendingDuring = provider.pendingIds()
        mediaDuring = mediaCount()
      }
    }

    val saved = CutoutPhoneSaver(spying, catalog).save(png, "IMG_0042.jpg", hidden = false, nowMillis = T0 + 5 * MINUTE) as CutoutPhoneSaver.Outcome.Saved

    // Mentre si collegava la riga era in attesa (invisibile) e il catalogo aveva gia' il media.
    assertThat(visibleDuring).isEmpty()
    assertThat(pendingDuring).hasSize(1)
    assertThat(mediaDuring).isEqualTo(1)
    // Dopo: una riga visibile, nella cartella dei ritagli, PNG, con il nome del file di origine.
    assertThat(saved.displayName).isEqualTo("IMG_0042-ritaglio.png")
    val rows = provider.visibleRows()
    assertThat(rows.map { it.second }).containsExactly("IMG_0042-ritaglio.png")
    assertThat(relativePathOf(rows.single().first)).isEqualTo("Pictures/MyCloud Gallery/Ritagli/")
    assertThat(provider.rowOf(rows.single().first)?.get("mime_type")).isEqualTo("image/png")
    assertThat(provider.contentOf(rows.single().first)).isEqualTo(png)
    assertThat(provider.pendingIds()).isEmpty()
  }

  @Test
  fun laLetturaDelTelefonoTrovaSameFileENonNasceUnDoppione() = runBlocking<Unit> {
    val saved = saver().save(png, "IMG_0042.jpg", hidden = false, nowMillis = T0 + 5 * MINUTE) as CutoutPhoneSaver.Outcome.Saved
    assertThat(mediaCount()).isEqualTo(1)
    val id = provider.visibleRows().single().first
    val row = rowOf(id, saved.displayName, png.size.toLong())
    store.put(row, png)
    val report = newSync().syncNow(SyncReason.APP_START)
    // Il posto e' gia' noto con gli stessi byte: niente media nuovo, niente versione nuova.
    assertThat(report.added).isEqualTo(0)
    assertThat(mediaCount()).isEqualTo(1)
    assertThat(versionCount()).isEqualTo(1)
    assertThat(mediaAt(row)).isEqualTo(saved.mediaId)
    assertThat(stateAt(row)).isEqualTo(LocationState.PRESENT)
    // La sorgente e' quella della cartella: "altro" (non fotocamera, non screenshot), il suo posto nel backup e' la cartella "Ritagli".
    assertThat(db.mediaDao().get(saved.mediaId.value)?.source).isEqualTo(MediaSource.OTHER.code)
    assertThat(db.personalDao().get(saved.mediaId.value)?.hidden ?: false).isFalse()
  }

  @Test
  fun dalNascostoLaFotoNuovaNasceNascostaGiaPrimaChePossaEssereVista() = runBlocking<Unit> {
    val writer = MediaStorePhoneMediaWriter(context, relativePath = PhoneMediaFolders.CUTOUTS, versionOf = { "v1" })
    var hiddenWhileStillPending: Boolean? = null
    val spying = object : PhoneMediaWriter {
      override suspend fun saveToPictures(
        request: SaveRequest,
        source: PhoneSaveSource,
        expectedSha256: Sha256?,
        progress: (Long) -> Unit,
        beforePublish: suspend (PendingCopy) -> Unit,
      ): SaveResult = writer.saveToPictures(request, source, expectedSha256, progress) { copy ->
        beforePublish(copy)
        // E-V11: a questo punto il file e' ancora invisibile, e il catalogo lo ha gia' nascosto.
        val id = db.mediaDao().allIds().single()
        hiddenWhileStillPending = db.personalDao().get(id)?.hidden == true && provider.visibleRows().isEmpty()
      }
    }
    val saved = CutoutPhoneSaver(spying, catalog).save(png, "segreta.jpg", hidden = true, nowMillis = T0) as CutoutPhoneSaver.Outcome.Saved
    assertThat(hiddenWhileStillPending).isTrue()
    assertThat(db.personalDao().get(saved.mediaId.value)!!.hidden).isTrue()
    // Dopo che la lettura del telefono l'ha vista resta nascosta (stesso media).
    val id = provider.visibleRows().single().first
    store.put(rowOf(id, saved.displayName, png.size.toLong()), png)
    newSync().syncNow(SyncReason.APP_START)
    assertThat(mediaCount()).isEqualTo(1)
    assertThat(db.personalDao().get(saved.mediaId.value)!!.hidden).isTrue()
  }

  @Test
  fun unRitaglioNonNascostoNonLoDiventa() = runBlocking<Unit> {
    val saved = saver().save(png, null, hidden = false, nowMillis = T0) as CutoutPhoneSaver.Outcome.Saved
    assertThat(saved.displayName).isEqualTo("Ritaglio.png")
    assertThat(db.personalDao().get(saved.mediaId.value)?.hidden ?: false).isFalse()
  }

  @Test
  fun inDebugConUnAccountDiProvaSiScriveSoloInMcgProvaRitagli() = runBlocking<Unit> {
    val saved = saver(PhoneMediaFolders.cutouts(fenced = true)).save(png, "prova.jpg", hidden = false, nowMillis = T0) as CutoutPhoneSaver.Outcome.Saved
    val id = provider.visibleRows().single().first
    assertThat(relativePathOf(id)).isEqualTo("Pictures/mcg-prova/Ritagli/")
    assertThat(saved.displayName).isEqualTo("prova-ritaglio.png")
    assertThat(PhoneMediaFolders.cutouts(fenced = false)).isEqualTo("Pictures/MyCloud Gallery/Ritagli/")
  }

  @Test
  fun lAltroDiscoPienoNonLasciaNienteNeVisibileNeNelCatalogo() = runBlocking<Unit> {
    provider.failOpens = java.io.IOException("write failed: ENOSPC (No space left on device)")
    val result = saver().save(png, "x.jpg", hidden = false, nowMillis = T0)
    assertThat(result).isEqualTo(CutoutPhoneSaver.Outcome.Failed("PHONE_FULL"))
    assertThat(provider.allRows()).isEmpty()
    assertThat(mediaCount()).isEqualTo(0)
  }

  @Test
  fun seIlCollegamentoNonRiesceIlFileNonSiPubblica() = runBlocking<Unit> {
    // Un catalogo che si e' chiuso: `record` lancia, il writer toglie la riga e dice LINK_FAILED.
    val closed = CutoutPhoneSaver(MediaStorePhoneMediaWriter(context, relativePath = PhoneMediaFolders.CUTOUTS, versionOf = { "v1" }), catalog)
    db.close()
    val result = closed.save(png, "x.jpg", hidden = true, nowMillis = T0)
    assertThat(result).isEqualTo(CutoutPhoneSaver.Outcome.Failed("LINK_FAILED"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun lAnnullamentoDelSalvataggioNonLasciaRigheInAttesa() = runBlocking<Unit> {
    val cancelling = object : PhoneMediaWriter {
      override suspend fun saveToPictures(
        request: SaveRequest,
        source: PhoneSaveSource,
        expectedSha256: Sha256?,
        progress: (Long) -> Unit,
        beforePublish: suspend (PendingCopy) -> Unit,
      ): SaveResult = SaveResult.Cancelled
    }
    assertThat(CutoutPhoneSaver(cancelling, catalog).save(png, "x.jpg", hidden = false, nowMillis = T0)).isEqualTo(CutoutPhoneSaver.Outcome.Cancelled)
    assertThat(mediaCount()).isEqualTo(0)
  }

  @Test
  fun lAltraCartellaNonSiMescolaNeiNomiNeNellaRipulitura() = runBlocking<Unit> {
    // Nella cartella dei download c'e' gia' un file con quel nome e una riga in attesa di un processo morto.
    provider.seed("IMG_0042-ritaglio.png", path = PhoneMediaFolders.DOWNLOADS, mime = "image/png")
    val orphan = provider.seed("orfana.png", path = PhoneMediaFolders.DOWNLOADS, mime = "image/png", pending = true)
    val cutoutsOrphan = provider.seed("orfana-ritaglio.png", path = PhoneMediaFolders.CUTOUTS, mime = "image/png", pending = true)
    val writer = MediaStorePhoneMediaWriter(context, relativePath = PhoneMediaFolders.CUTOUTS, versionOf = { "v1" })
    val saved = CutoutPhoneSaver(writer, catalog).save(png, "IMG_0042.jpg", hidden = false, nowMillis = T0) as CutoutPhoneSaver.Outcome.Saved
    // Il nome libero della cartella dei ritagli e' quello pulito (il file omonimo sta nell'altra cartella).
    assertThat(saved.displayName).isEqualTo("IMG_0042-ritaglio.png")
    // La ripulitura del writer dei ritagli tocca le sue righe in attesa, non quelle dei download.
    assertThat(provider.allRows().map { it.id }).contains(orphan)
    assertThat(provider.allRows().map { it.id }).doesNotContain(cutoutsOrphan)
  }

  @Test
  fun loStessoRitaglioSalvatoDueVolteHaIlNomeConIlNumeroEIlCatalogoLoRitrovaComeLoStessoMedia() = runBlocking<Unit> {
    val first = saver().save(png, "IMG_0042.jpg", hidden = false, nowMillis = T0) as CutoutPhoneSaver.Outcome.Saved
    val second = saver().save(png, "IMG_0042.jpg", hidden = false, nowMillis = T0 + MINUTE) as CutoutPhoneSaver.Outcome.Saved
    assertThat(first.displayName).isEqualTo("IMG_0042-ritaglio.png")
    assertThat(second.displayName).isEqualTo("IMG_0042-ritaglio (2).png")
    // Stessi byte: lo stesso media del catalogo (con un secondo posto), non due foto uguali.
    assertThat(second.mediaId).isEqualTo(first.mediaId)
    assertThat(mediaCount()).isEqualTo(1)
  }

  @Test
  fun ilSalvataggioDaUnNascostoNascondeAncheLaCopiaGiaVisibileDelloStessoRitaglio() = runBlocking<Unit> {
    // Si sbaglia dal lato della riservatezza (E-H1): lo stesso ritaglio salvato prima in chiaro e ora dai Nascosti diventa nascosto.
    val visible = saver().save(png, "a.jpg", hidden = false, nowMillis = T0) as CutoutPhoneSaver.Outcome.Saved
    assertThat(db.personalDao().get(visible.mediaId.value)?.hidden ?: false).isFalse()
    val again = saver().save(png, "a.jpg", hidden = true, nowMillis = T0 + MINUTE) as CutoutPhoneSaver.Outcome.Saved
    assertThat(again.mediaId).isEqualTo(visible.mediaId)
    assertThat(db.personalDao().get(visible.mediaId.value)!!.hidden).isTrue()
  }

  @Test
  fun ilFintoScriveNellaCartellaDiSerieEPassaLeStesseRegole() = runBlocking<Unit> {
    val fake = FakePhoneMediaWriter()
    val out = CutoutPhoneSaver(fake, catalog).save(png, "f.jpg", hidden = true, nowMillis = T0) as CutoutPhoneSaver.Outcome.Saved
    assertThat(fake.visible.keys).containsExactly("f-ritaglio.png")
    assertThat(db.personalDao().get(out.mediaId.value)!!.hidden).isTrue()
    assertThat(fake.pendingLeft).isEqualTo(0)
  }
}
