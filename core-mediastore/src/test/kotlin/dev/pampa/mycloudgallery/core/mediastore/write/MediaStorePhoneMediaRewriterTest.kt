package dev.pampa.mycloudgallery.core.mediastore.write

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.mediastore.contract.RewriteCodes
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

  private fun replace(ref: MediaRef.Phone, file: File = newFile(), sha: Sha256 = Sha256.of(file.readBytes())): RewriteResult =
    runBlocking { rewriter.replace(ref, file, sha) }

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
  fun seNemmenoIlRipristinoTornaLoDice() {
    val ref = phoneRow()
    provider.tamperedReads = setOf(2, 3)
    val result = replace(ref)
    // Il caso grave: la riga non e' tornata com'era (per quanto la rilettura vede). Il chiamante lo dice all'utente: l'originale e' sul NAS.
    assertThat(result).isEqualTo(RewriteResult.Failed(RewriteCodes.HASH_MISMATCH, restored = false))
    assertThat(undoDir.listFiles()!!.toList()).isEmpty()
  }

  @Test
  fun leFotoSiLeggonoConIByteOriginaliEIVideoNo() {
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
    assertThat(provider.opens.filter { it.second == "r" }.none { it.first.getBooleanQueryParameter("requireOriginal", false) }).isTrue()
  }
}
