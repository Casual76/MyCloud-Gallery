package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.Context
import android.system.ErrnoException
import android.system.OsConstants
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.PhoneSaveSource
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveRequest
import dev.pampa.mycloudgallery.core.mediastore.contract.SaveResult
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Scarica sul telefono" sul MediaStore vero, con un provider `media` che scrive (P8, D20, E-L2; `docs/fase4-progetto.md`
 * §2.6 e riga P8 del §4.4): la riga nasce `IS_PENDING = 1`, i byte arrivano con lo SHA-256, la posizione si collega
 * prima che il file diventi visibile, e a meta' non resta mai niente di visibile. Il collegamento `SameFile` con il
 * catalogo vero sta in [PhoneSaveLinkingTest].
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePhoneMediaWriterTest {

  private lateinit var provider: WritableMediaProvider
  private lateinit var writer: MediaStorePhoneMediaWriter
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private val content: ByteArray = FakeMediaContent.jpeg(5_000, seed = 7)
  private val sha: Sha256 = Sha256.of(content)

  @Before
  fun register() {
    provider = WritableMediaProvider.register()
    writer = newWriter()
  }

  private fun newWriter() = MediaStorePhoneMediaWriter(context, versionOf = { "v1" })

  private fun request(name: String = "foto.jpg", mime: String = "image/jpeg", size: Long = content.size.toLong()) = SaveRequest(name, mime, size)

  /** Una sorgente che consegna [bytes] a blocchi, come `readAll` del NAS. */
  private fun sourceOf(bytes: ByteArray = content, chunk: Int = 1_024, between: suspend (Int) -> Unit = {}) = PhoneSaveSource { sink ->
    var offset = 0
    var index = 0
    while (offset < bytes.size) {
      val n = minOf(chunk, bytes.size - offset)
      sink(bytes.copyOfRange(offset, offset + n), n)
      offset += n
      between(index++)
    }
  }

  // --- Il percorso felice --------------------------------------------------------------------------------------------

  @Test
  fun laRigaNasceInAttesaIByteArrivanoEIlFileSiVedeSoloAllaFine() = runBlocking<Unit> {
    val seenWhileCopying = mutableListOf<Pair<List<Long>, List<Long>>>()
    var atLink: Pair<List<Long>, List<Long>>? = null

    val result = writer.saveToPictures(
      request = request(),
      source = sourceOf { seenWhileCopying += provider.visibleRows().map { it.first } to provider.pendingIds() },
      expectedSha256 = sha,
      beforePublish = { atLink = provider.visibleRows().map { it.first } to provider.pendingIds() },
    )

    result as SaveResult.Saved
    // Durante la copia e al momento del collegamento: una riga in attesa, nessuna visibile.
    assertThat(seenWhileCopying).isNotEmpty()
    assertThat(seenWhileCopying.all { (visible, pending) -> visible.isEmpty() && pending.size == 1 }).isTrue()
    assertThat(atLink!!.first).isEmpty()
    assertThat(atLink!!.second).hasSize(1)
    // Alla fine: visibile, completo, nessuna riga in attesa.
    val id = result.copy.location.mediaStoreId!!
    assertThat(provider.visibleRows()).containsExactly(id to "foto.jpg")
    assertThat(provider.pendingIds()).isEmpty()
    assertThat(provider.contentOf(id)).isEqualTo(content)
    assertThat(result.copy.sha256).isEqualTo(sha)
    assertThat(result.copy.size).isEqualTo(content.size.toLong())
    // La posizione e' quella che il MediaStore ha scritto davvero.
    val location = result.copy.location
    assertThat(location.volume).isEqualTo("external_primary")
    assertThat(location.relativePath).isEqualTo("Pictures/MyCloud Gallery/")
    assertThat(location.displayName).isEqualTo("foto.jpg")
    assertThat(location.mediaStoreVersion).isEqualTo("v1")
    assertThat(provider.rowOf(id)!!["relative_path"]).isEqualTo("Pictures/MyCloud Gallery/")
    assertThat(provider.rowOf(id)!!["media_type"]).isEqualTo("1")
  }

  @Test
  fun unVideoVaNellaCollezioneDeiVideo() = runBlocking<Unit> {
    val mp4 = FakeMediaContent.mp4(3_000, seed = 2)
    val result = writer.saveToPictures(SaveRequest("clip.mp4", "video/mp4", mp4.size.toLong()), sourceOf(mp4)) as SaveResult.Saved

    assertThat(provider.rowOf(result.copy.location.mediaStoreId!!)!!["media_type"]).isEqualTo("3")
    assertThat(provider.visibleRows().map { it.second }).containsExactly("clip.mp4")
  }

  @Test
  fun ilProgressoCresceFinoAllaDimensioneTotale() = runBlocking<Unit> {
    val seen = mutableListOf<Long>()
    writer.saveToPictures(request(), sourceOf(chunk = 700), progress = { seen += it })

    assertThat(seen).isInOrder()
    assertThat(seen.last()).isEqualTo(content.size.toLong())
    assertThat(seen.size).isAtLeast(7)
  }

  @Test
  fun soloImmaginiEVideoNientePdfNeFileSconosciuti() = runBlocking<Unit> {
    assertThat(writer.saveToPictures(request("a.pdf", "application/pdf"), sourceOf())).isEqualTo(SaveResult.Failed("UNSUPPORTED_TYPE"))
    assertThat(provider.allRows()).isEmpty()
  }

  // --- Nomi in conflitto ---------------------------------------------------------------------------------------------

  @Test
  fun unNomeInConflittoDiventaConDueETreSenzaDistinguereLeMaiuscole() = runBlocking<Unit> {
    provider.seed("foto.jpg")

    val first = writer.saveToPictures(request(), sourceOf()) as SaveResult.Saved
    val second = writer.saveToPictures(request(), sourceOf()) as SaveResult.Saved
    val upper = writer.saveToPictures(request("FOTO.JPG"), sourceOf()) as SaveResult.Saved

    assertThat(first.copy.location.displayName).isEqualTo("foto (2).jpg")
    assertThat(second.copy.location.displayName).isEqualTo("foto (3).jpg")
    assertThat(upper.copy.location.displayName).isEqualTo("FOTO (4).JPG")
    assertThat(provider.visibleRows().map { it.second }).containsExactly("foto.jpg", "foto (2).jpg", "foto (3).jpg", "FOTO (4).JPG")
  }

  @Test
  fun ancheUnaRigaCestinataOccupaIlNomeEUnaInAttesaDiUnProcessoMortoSiRipulisce() = runBlocking<Unit> {
    provider.seed("b.jpg", trashed = true)
    provider.seed("a.jpg", pending = true)

    val b = writer.saveToPictures(request("b.jpg"), sourceOf()) as SaveResult.Saved
    val a = writer.saveToPictures(request("a.jpg"), sourceOf()) as SaveResult.Saved

    // Il cestino tiene il suo nome; la riga in attesa orfana e' stata tolta all'inizio e il nome e' tornato libero.
    assertThat(b.copy.location.displayName).isEqualTo("b (2).jpg")
    assertThat(a.copy.location.displayName).isEqualTo("a.jpg")
    assertThat(provider.allRows().map { it.name }).containsExactly("b.jpg", "b (2).jpg", "a.jpg")
  }

  @Test
  fun seIlProviderRinominaUnConflittoNatoNelFrattempoLaPosizioneEQuellaScritta() = runBlocking<Unit> {
    // Un altro scrittore prende "foto.jpg" fra il controllo del nome e l'inserimento: il provider rinomina in "foto (1).jpg".
    provider.beforeInsert = { provider.seed("foto.jpg") }

    val saved = writer.saveToPictures(request("foto.jpg"), sourceOf()) as SaveResult.Saved

    val id = saved.copy.location.mediaStoreId!!
    assertThat(saved.copy.location.displayName).isEqualTo("foto (1).jpg")
    assertThat(provider.rowOf(id)!!["_display_name"]).isEqualTo("foto (1).jpg")
    assertThat(provider.visibleRows().map { it.second }).containsExactly("foto.jpg", "foto (1).jpg")
  }

  // --- Cose che vanno storte: mai un file a meta' --------------------------------------------------------------------

  @Test
  fun unaSorgenteInterrottaNonLasciaNienteNeVisibileNeInAttesa() = runBlocking<Unit> {
    val broken = PhoneSaveSource { sink ->
      sink(content, 2_000)
      throw IOException("NAS sparito")
    }

    assertThat(writer.saveToPictures(request(), broken)).isEqualTo(SaveResult.Failed("SOURCE_FAILED"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun unaSorgenteCheFinisceInAnticipoDaDimensioneDiversa() = runBlocking<Unit> {
    val short = sourceOf(content.copyOf(3_000))

    assertThat(writer.saveToPictures(request(), short)).isEqualTo(SaveResult.Failed("SIZE_MISMATCH"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun unHashDiversoDaQuelloAtteso() = runBlocking<Unit> {
    val result = writer.saveToPictures(request(), sourceOf(), expectedSha256 = Sha256.of(ByteArray(3)))

    assertThat(result).isEqualTo(SaveResult.Failed("HASH_MISMATCH"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun unCollegamentoCheFallisceNonPubblicaIlFile() = runBlocking<Unit> {
    val result = writer.saveToPictures(request(), sourceOf(), beforePublish = { throw IllegalStateException("catalogo non scrive") })

    assertThat(result).isEqualTo(SaveResult.Failed("LINK_FAILED"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun unaPubblicazioneCheNonRiesceTogliLaRiga() = runBlocking<Unit> {
    provider.failUpdates = true

    assertThat(writer.saveToPictures(request(), sourceOf())).isEqualTo(SaveResult.Failed("MEDIASTORE_ERROR"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun ilMediaStoreCheNonRispondeNonCreaNiente() = runBlocking<Unit> {
    provider.nullQueries = true

    assertThat(writer.saveToPictures(request(), sourceOf())).isEqualTo(SaveResult.Failed("MEDIASTORE_ERROR"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun ilDiscoPienoAllAperturaEUnTelefonoPieno() = runBlocking<Unit> {
    provider.failOpens = FileNotFoundException("write").apply { initCause(IOException(ErrnoException("open", OsConstants.ENOSPC))) }

    assertThat(writer.saveToPictures(request(), sourceOf())).isEqualTo(SaveResult.Failed("PHONE_FULL"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun unaFileNonApribileInScritturaEUnErroreDelMediaStore() = runBlocking<Unit> {
    provider.failOpens = FileNotFoundException("niente")

    assertThat(writer.saveToPictures(request(), sourceOf())).isEqualTo(SaveResult.Failed("MEDIASTORE_ERROR"))
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun spazioEsauritoSiRiconoscePerErrnoEPerTesto() {
    assertThat(MediaStorePhoneMediaWriter.isNoSpace(IOException(ErrnoException("write", OsConstants.ENOSPC)))).isTrue()
    assertThat(MediaStorePhoneMediaWriter.isNoSpace(IOException("write failed: ENOSPC (No space left on device)"))).isTrue()
    assertThat(MediaStorePhoneMediaWriter.isNoSpace(IOException("Connection reset"))).isFalse()
    assertThat(MediaStorePhoneMediaWriter.isNoSpace(null)).isFalse()
  }

  // --- Annullamento e morte del processo -----------------------------------------------------------------------------

  @Test
  fun unAnnullamentoATMetaTogliLaRigaESaleInAlto() = runBlocking<Unit> {
    val halfway = CompletableDeferred<Unit>()
    var linked = false
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      writer.saveToPictures(
        request = request(),
        source = PhoneSaveSource { sink ->
          sink(content, 1_500)
          halfway.complete(Unit)
          awaitCancellation()
        },
        beforePublish = { linked = true },
      )
    }
    halfway.await()
    assertThat(provider.pendingIds()).hasSize(1)

    job.cancelAndJoin()

    assertThat(job.isCancelled).isTrue()
    assertThat(linked).isFalse()
    assertThat(provider.allRows()).isEmpty()
  }

  @Test
  fun laMorteDelProcessoLasciaUnaRigaInvisibileCheIlGiroDopoRipulisce() = runBlocking<Unit> {
    val halfway = CompletableDeferred<Unit>()
    // Il processo muore: la ripulitura che l'annullamento farebbe non arriva (il provider non toglie niente).
    provider.failDeletes = true
    val job = launch(start = CoroutineStart.UNDISPATCHED) {
      writer.saveToPictures(
        request = request(),
        source = PhoneSaveSource { sink ->
          sink(content, 1_500)
          halfway.complete(Unit)
          awaitCancellation()
        },
      )
    }
    halfway.await()
    job.cancelAndJoin()

    // Resta la riga in attesa: nessun file visibile a meta', nemmeno con il nome giusto.
    assertThat(provider.visibleRows()).isEmpty()
    assertThat(provider.pendingIds()).hasSize(1)

    // Il giro dopo, in un processo nuovo: il salvataggio comincia ripulendo.
    provider.failDeletes = false
    val restarted = newWriter()
    val saved = restarted.saveToPictures(request(), sourceOf()) as SaveResult.Saved

    assertThat(provider.pendingIds()).isEmpty()
    assertThat(provider.visibleRows().map { it.second }).containsExactly("foto.jpg")
    assertThat(saved.copy.location.displayName).isEqualTo("foto.jpg")
  }

  @Test
  fun laRipulituraDiUnAltroGiroNonToccaUnSalvataggioInCorso() = runBlocking<Unit> {
    val halfway = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val running = launch {
      writer.saveToPictures(
        request = request(),
        source = PhoneSaveSource { sink ->
          sink(content, 1_500)
          halfway.complete(Unit)
          release.await()
          sink(content.copyOfRange(1_500, content.size), content.size - 1_500)
        },
      )
    }
    halfway.await()
    // Un orfano vero accanto a quello in corso.
    val orphan = provider.seed("orfano.jpg", pending = true)

    val removed = writer.purgeOrphans()

    assertThat(removed).isEqualTo(1)
    assertThat(provider.pendingIds()).doesNotContain(orphan)
    assertThat(provider.pendingIds()).hasSize(1)
    release.complete(Unit)
    running.join()
    assertThat(provider.pendingIds()).isEmpty()
    assertThat(provider.visibleRows().map { it.second }).containsExactly("foto.jpg")
    assertThat(provider.contentOf(provider.visibleRows().single().first)).isEqualTo(content)
  }

  @Test
  fun unaRipulituraCheFallisceNonImpediscePiuIlSalvataggio() = runBlocking<Unit> {
    provider.seed("orfano.jpg", pending = true)
    provider.failDeletes = true

    val saved = writer.saveToPictures(request(), sourceOf())

    // L'orfano non si e' potuto togliere (provider che rifiuta): il salvataggio parte lo stesso.
    assertThat(saved).isInstanceOf(SaveResult.Saved::class.java)
  }

  @Test
  fun dueSalvataggiInParalleloHannoNomiEdIdDiversiENessunoSiPerde() = runBlocking<Unit> {
    val a = CompletableDeferred<SaveResult>()
    val b = CompletableDeferred<SaveResult>()
    val gate = CompletableDeferred<Unit>()
    launch { a.complete(writer.saveToPictures(request(), sourceOf(between = { gate.await() }))) }
    launch { b.complete(writer.saveToPictures(request(), sourceOf(between = { gate.await() }))) }
    gate.complete(Unit)

    val ra = a.await() as SaveResult.Saved
    val rb = b.await() as SaveResult.Saved

    assertThat(setOf(ra.copy.location.displayName, rb.copy.location.displayName)).hasSize(2)
    assertThat(provider.visibleRows()).hasSize(2)
    assertThat(provider.pendingIds()).isEmpty()
  }

  @Test
  fun checkDiCoerenzaSullaCancellazioneNonInghiottitaDalLink() = runBlocking<Unit> {
    // Un annullamento che arriva mentre si collega risale (non diventa LINK_FAILED) e il file non si pubblica.
    val result = runCatching {
      writer.saveToPictures(request(), sourceOf(), beforePublish = { throw CancellationException("annullato") })
    }

    assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
    assertThat(provider.allRows()).isEmpty()
  }
}
