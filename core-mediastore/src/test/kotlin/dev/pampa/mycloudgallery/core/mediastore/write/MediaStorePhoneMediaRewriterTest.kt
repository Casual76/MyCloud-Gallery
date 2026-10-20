package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.android.AndroidPhoneMediaIntents
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteCodes
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteRecovery
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteResult
import dev.pampa.mycloudgallery.core.mediastore.testing.FakeMediaContent
import dev.pampa.mycloudgallery.core.model.MediaRef
import dev.pampa.mycloudgallery.core.model.Sha256
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * "Riscrivere un file del telefono" sul MediaStore vero, con un provider `media` che scrive (Fase 5B, P0-B, D19, E-E10; riga P0-B del
 * §4.4): la rete di salvataggio (copia dei byte attuali, scrittura sul posto, rilettura, ripristino), il permesso di `createWriteRequest`,
 * i byte originali per le foto (`setRequireOriginal`) e nessuna copia di sicurezza che resti in giro.
 */
@RunWith(AndroidJUnit4::class)
class MediaStorePhoneMediaRewriterTest {

  @get:Rule val temp = TemporaryFolder()

  private lateinit var provider: WritableMediaProvider
  private lateinit var undoDir: File
  private lateinit var rewriter: MediaStorePhoneMediaRewriter
  private val context: Context get() = ApplicationProvider.getApplicationContext()

  /** I byte della foto sul telefono (con la sua posizione, se avesse un EXIF) e quelli corretti. */
  private val before: ByteArray = FakeMediaContent.jpeg(9_000, seed = 11)
  private val corrected: ByteArray = FakeMediaContent.jpeg(7_000, seed = 12)

  @Before
  fun register() {
    provider = WritableMediaProvider.register()
    undoDir = temp.newFolder("rewrite")
    rewriter = MediaStorePhoneMediaRewriter(context, undoDir = undoDir)
  }

  private fun phoneRow(content: ByteArray = before, mime: String = "image/jpeg"): MediaRef.Phone {
    val id = provider.seed("IMG_1.jpg", path = "DCIM/mcg-prova/", mime = mime, content = content)
    return MediaRef.Phone("external_primary", id, mime)
  }

  private fun newFile(bytes: ByteArray = corrected): File = temp.newFile().apply { writeBytes(bytes) }

  private fun replace(
    ref: MediaRef.Phone,
    file: File = newFile(),
    sha: Sha256 = Sha256.of(file.readBytes()),
    current: Sha256 = Sha256.of(provider.contentOf(ref.mediaStoreId) ?: before),
  ): RewriteResult = runBlocking { rewriter.replace(ref, file, sha, current) }

  @Test
  fun riscriveRileggeEAccorciaAllaLunghezzaNuova() {
    val ref = phoneRow()
    val result = replace(ref) as RewriteResult.Replaced
    assertThat(result.size).isEqualTo(corrected.size.toLong())
    assertThat(result.sha256).isEqualTo(Sha256.of(corrected))
    // La riga ha i byte nuovi e niente dei vecchi in coda (il file piu' corto non lascia un resto).
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(corrected.toList())
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun unFileNuovoPiuLungoSiScriveInteroEUnoUgualeNonCambiaNiente() {
    val longer = FakeMediaContent.jpeg(20_000, seed = 13)
    val ref = phoneRow()
    assertThat(replace(ref, newFile(longer))).isInstanceOf(RewriteResult.Replaced::class.java)
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(longer.toList())
    // Riscrivere gli stessi byte e' ammesso e idempotente.
    assertThat(replace(ref, newFile(longer))).isInstanceOf(RewriteResult.Replaced::class.java)
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(longer.toList())
  }

  @Test
  fun unFileCheNonHaLoShaAttesoNonSiScriveEIlTelefonoRestaCom() {
    val ref = phoneRow()
    val file = newFile()
    val wrong = Sha256.of(FakeMediaContent.jpeg(10, seed = 99))
    assertThat(replace(ref, file, wrong)).isEqualTo(RewriteResult.SourceMismatch)
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(before.toList())
    // Nemmeno si e' aperto il telefono: la verifica del file viene prima di ogni accesso.
    assertThat(provider.opens).isEmpty()
  }

  @Test
  fun senzaIlPermessoDiScritturaNienteSiTocca() {
    val ref = phoneRow()
    provider.denyWrite = true
    assertThat(replace(ref)).isEqualTo(RewriteResult.NotGranted)
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(before.toList())
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun unaRigaSparitaEGone() {
    val ref = MediaRef.Phone("external_primary", 9_999, "image/jpeg")
    assertThat(replace(ref)).isEqualTo(RewriteResult.Gone)
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun seLaRilletturaNonTornaSiRimettonoIByteDiPrima() {
    val ref = phoneRow()
    // Letture: 1 = copia di sicurezza, 2 = rilettura di verifica (alterata), 3 = rilettura del ripristino (pulita).
    provider.tamperedReads = setOf(2)
    val result = replace(ref)
    assertThat(result).isEqualTo(RewriteResult.Failed(RewriteCodes.HASH_MISMATCH, restored = true))
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(before.toList())
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun seNemmenoIlRipristinoTornaLoDiceETieneLaCopiaPerIlRecupero() {
    val ref = phoneRow()
    provider.tamperedReads = setOf(2, 3)
    val result = replace(ref)
    // Il caso grave: la riga non e' tornata com'era (per quanto la rilettura vede). Il chiamante lo dice all'utente: l'originale e' sul NAS.
    assertThat(result).isEqualTo(RewriteResult.Failed(RewriteCodes.HASH_MISMATCH, restored = false))
    // R9: diario e copia di sicurezza restano per il recupero.
    assertThat(undoDir.listFiles()!!.map { it.extension }.sorted()).containsExactly("journal", "undo").inOrder()
    // Al recupero la riga si rilegge pulita: aveva gia' i byte di prima, si toglie tutto.
    assertThat(runBlocking { rewriter.recover() }).isEqualTo(RewriteRecovery(restored = 0, settled = 1, pending = 0))
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(before.toList())
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
  }

  /** R10 della revisione 5B: anche i video si leggono con i byte originali (con la loro posizione), per la copia di sicurezza e la rilettura. */
  @Test
  fun r10_fotoEVideoSiLeggonoConIByteOriginali() {
    val photo = phoneRow()
    assertThat(replace(photo)).isInstanceOf(RewriteResult.Replaced::class.java)
    val reads = provider.opens.filter { it.second == "r" }
    // Copia di sicurezza e rilettura: entrambe con setRequireOriginal, mai byte oscurati (D9). La scrittura apre `rw`.
    assertThat(reads).hasSize(2)
    assertThat(reads.all { it.first.getBooleanQueryParameter("requireOriginal", false) }).isTrue()
    assertThat(provider.opens.filter { it.second == "rw" }).hasSize(1)

    provider.opens.clear()
    val video = phoneRow(content = FakeMediaContent.mp4(5_000, seed = 3), mime = "video/mp4")
    assertThat(replace(video, newFile(FakeMediaContent.mp4(4_000, seed = 4)))).isInstanceOf(RewriteResult.Replaced::class.java)
    val videoReads = provider.opens.filter { it.second == "r" }
    assertThat(videoReads).hasSize(2)
    assertThat(videoReads.all { it.first.getBooleanQueryParameter("requireOriginal", false) }).isTrue()
  }

  /** R10: senza `ACCESS_MEDIA_LOCATION` l'originale non si apre: niente si scrive e l'eccezione non esce. */
  @Test
  fun r10_senzaIlPermessoDellaPosizioneNienteSiTocca() {
    val video = phoneRow(content = FakeMediaContent.mp4(5_000, seed = 5), mime = "video/mp4")
    provider.locationGranted = false
    val result = replace(video, newFile(FakeMediaContent.mp4(4_000, seed = 6)), current = Sha256.of(FakeMediaContent.mp4(5_000, seed = 5)))
    assertThat(result).isEqualTo(RewriteResult.Failed(RewriteCodes.ORIGINAL_UNREADABLE, restored = true))
    assertThat(provider.opens.none { it.second == "rw" }).isTrue()
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
  }

  /**
   * R3 della revisione 5B: la riga ha una modifica che non e' quella al sicuro sul NAS (Samsung "Salva" prima del giro del backup): il riscrittore
   * non la sovrascrive. Una riga che ha gia' i byte nuovi e' fatta senza riscriverla.
   */
  @Test
  fun r3_unaRigaSenzaLImprontaAttesaNonSiSovrascrive() {
    val unsaved = FakeMediaContent.jpeg(9_500, seed = 31)
    val ref = phoneRow(content = unsaved)
    val result = replace(ref, current = Sha256.of(before))
    assertThat(result).isEqualTo(RewriteResult.RowChanged)
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(unsaved.toList())
    assertThat(provider.opens.none { it.second == "rw" }).isTrue()
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()

    val done = phoneRow(content = corrected)
    provider.opens.clear()
    assertThat(replace(done, current = Sha256.of(before))).isEqualTo(RewriteResult.Replaced(Sha256.of(corrected), corrected.size.toLong()))
    assertThat(provider.opens.none { it.second == "rw" }).isTrue()
  }

  /**
   * R9 della revisione 5B: il processo muore a meta' della scrittura. La riga resta con l'inizio nuovo e la coda vecchia; il diario e la copia
   * di sicurezza sono in `noBackupFilesDir`. Al riavvio (un riscrittore nuovo sulla stessa cartella) la riga torna com'era e tutto si toglie.
   */
  @Test
  fun r9_unaRiscritturaLasciataAMetaSiRimetteAlRiavvio() {
    val ref = phoneRow()
    // Il ripristino non si puo' verificare: diario e copia restano, come dopo una morte del processo durante la scrittura.
    provider.tamperedReads = setOf(2, 3)
    assertThat(replace(ref)).isEqualTo(RewriteResult.Failed(RewriteCodes.HASH_MISMATCH, restored = false))
    // La riga a meta': l'inizio del file nuovo e la coda di quello vecchio.
    val half = corrected.copyOf(corrected.size / 2) + before.copyOfRange(corrected.size / 2, before.size)
    provider.overwrite(ref.mediaStoreId, half)

    val restarted = MediaStorePhoneMediaRewriter(context, undoDir = undoDir)
    assertThat(runBlocking { restarted.recover() }).isEqualTo(RewriteRecovery(restored = 1, settled = 0, pending = 0))
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(before.toList())
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
    // Una seconda volta non c'e' piu' niente.
    assertThat(runBlocking { restarted.recover() }).isEqualTo(RewriteRecovery.NONE)
  }

  /** R9: senza il permesso di scrittura (decaduto al riavvio) il recupero aspetta: diario e copia restano per la volta dopo. */
  @Test
  fun r9_senzaPermessoIlRecuperoAspetta() {
    val ref = phoneRow()
    provider.tamperedReads = setOf(2, 3)
    replace(ref)
    provider.overwrite(ref.mediaStoreId, corrected.copyOf(100) + before.copyOfRange(100, before.size))
    provider.denyWrite = true
    assertThat(runBlocking { rewriter.recover() }).isEqualTo(RewriteRecovery(restored = 0, settled = 0, pending = 1))
    assertThat(undoDir.listFiles()!!.map { it.extension }.sorted()).containsExactly("journal", "undo").inOrder()
    provider.denyWrite = false
    assertThat(runBlocking { rewriter.recover() }.restored).isEqualTo(1)
    assertThat(provider.contentOf(ref.mediaStoreId)!!.toList()).isEqualTo(before.toList())
  }

  /**
   * VE-2 della verifica funzionale 5B: il dialogo e la scrittura usano lo stesso URI. Il dialogo dei fogli della 5B chiede il permesso con un
   * riferimento **senza tipo**; il sistema vuole un URI di immagini o video per id e da' il permesso solo per quello. La richiesta va sulla
   * collezione del tipo della riga, e la riscrittura apre proprio quell'URI: con i permessi per URI del telefono vero la scrittura riesce.
   */
  @Test
  fun ve2_dialogoEScritturaUsanoLoStessoUri() {
    provider.perUriGrants = true
    val photo = phoneRow()
    val videoBytes = FakeMediaContent.mp4(5_000, seed = 7)
    val video = phoneRow(content = videoBytes, mime = "video/mp4")
    val intents = AndroidPhoneMediaIntents(context)
    val untyped = listOf(photo, video).map { MediaRef.Phone(it.volume, it.mediaStoreId, null) }
    assertThat(intents.writeRequest(untyped)).isNotNull()
    assertThat(provider.writeRequests.single().map { it.toString() }).containsExactly(
      "content://media/external_primary/images/media/${photo.mediaStoreId}",
      "content://media/external_primary/video/media/${video.mediaStoreId}",
    )
    // La riscrittura (con o senza il MIME del catalogo) apre gli stessi URI, quindi ha il permesso.
    assertThat(replace(untyped[0])).isInstanceOf(RewriteResult.Replaced::class.java)
    assertThat(replace(video, newFile(FakeMediaContent.mp4(4_000, seed = 8)), current = Sha256.of(videoBytes)))
      .isInstanceOf(RewriteResult.Replaced::class.java)
    val written = provider.opens.filter { it.second == "rw" }.map { it.first.toString() }
    assertThat(written).containsExactly(
      "content://media/external_primary/images/media/${photo.mediaStoreId}",
      "content://media/external_primary/video/media/${video.mediaStoreId}",
    ).inOrder()
  }
}
