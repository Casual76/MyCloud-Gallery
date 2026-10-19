package dev.pampa.mycloudgallery.feature.integrations.share

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Fase 5B, P11 (D26, C17; Android 17 e 18): come si chiamano i file condivisi, che intento si costruisce (sempre `ClipData` e sola lettura) e che il
 * testo di ogni fermata esista. I nomi sono dell'utente ma ripuliti: niente separatori, niente controlli, estensione dal tipo vero.
 */
@RunWith(RobolectricTestRunner::class)
class ShareNamesAndIntentsTest {

  private val context: Context get() = ApplicationProvider.getApplicationContext<Application>()

  @Test
  fun ilNomeSiRipulisceEL_EstensioneVieneDalTipoVero() {
    assertThat(ShareNames.of("Mare 2024.jpg", "image/jpeg")).isEqualTo("Mare 2024.jpg")
    // Un HEIC convertito in JPEG si chiama .jpg.
    assertThat(ShareNames.of("IMG_20240312.HEIC", "image/jpeg")).isEqualTo("IMG_20240312.jpg")
    assertThat(ShareNames.of("clip.MOV", "video/quicktime")).isEqualTo("clip.mov")
    // Percorsi, controlli e simboli non entrano nel nome.
    val hostile = ShareNames.of("../../etc/passwd\u0000.jpg", "image/jpeg")
    assertThat(hostile).doesNotContain("/")
    assertThat(hostile).doesNotContain("\u0000")
    assertThat(hostile).endsWith(".jpg")
    assertThat(ShareNames.of("a\\b:c*d?.png", "image/png")).doesNotContainMatch("[\\\\:*?]")
    // Senza nome, o con un nome fatto di niente: un nome neutro.
    assertThat(ShareNames.of(null, "image/webp")).isEqualTo("media.webp")
    assertThat(ShareNames.of("...", "image/png")).isEqualTo("media.png")
    assertThat(ShareNames.of("", "video/mp4")).isEqualTo("media.mp4")
    // Nomi lunghissimi si accorciano.
    assertThat(ShareNames.of("x".repeat(500) + ".jpg", "image/jpeg").length).isAtMost(84)
    // Un tipo che non conosciamo prende l'estensione sensata del nome, o .bin.
    assertThat(ShareNames.of("foto.dng", "image/x-adobe-dng")).isEqualTo("foto.dng")
    assertThat(ShareNames.of("foto", "application/x-strano")).isEqualTo("foto.bin")
    assertThat(ShareNames.of("foto.a/b", "application/x-strano")).endsWith(".bin")
  }

  @Test
  fun unFileSoloInvia_ActionSend_ConClipDataESolaLettura() {
    val uri = Uri.parse("content://prova/s/1/a.jpg")
    val intent = ShareIntents.send(listOf(SharedFile(uri, "image/jpeg", 10, null)))

    assertThat(intent.action).isEqualTo(Intent.ACTION_SEND)
    assertThat(intent.type).isEqualTo("image/*")
    assertThat(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)).isEqualTo(uri)
    assertThat(intent.clipData!!.getItemAt(0).uri).isEqualTo(uri)
    assertThat(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION).isNotEqualTo(0)
    assertThat(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION).isEqualTo(0)
    assertThat(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION).isEqualTo(0) // mai permessi persistenti
  }

  @Test
  fun iTipiComuniEIlRifiutoDiUnaListaVuota() {
    assertThat(ShareIntents.commonType(listOf("image/jpeg", "image/png"))).isEqualTo("image/*")
    assertThat(ShareIntents.commonType(listOf("video/mp4", "video/quicktime"))).isEqualTo("video/*")
    assertThat(ShareIntents.commonType(listOf("image/jpeg", "video/mp4"))).isEqualTo("*/*")
    assertThat(ShareIntents.commonType(listOf("application/x-strano"))).isEqualTo("*/*")
    org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { ShareIntents.send(emptyList()) }
  }

  @Test
  fun lAutoritaDelFileProviderEQuellaDelManifestEIlRevocatoreNonLanciaPerUnIndirizzoStraniero() {
    assertThat(FileProviderUris.authorityOf(context)).isEqualTo("${context.packageName}.integrations.files")
    // Revocare un indirizzo che non e' nostro non fa danni.
    ContextUriRevoker(context).revoke(Uri.parse("content://altra.app/x/1"))
  }

  @Test
  fun ognunaDelleFermateHaUnaFraseCheNonContieneDati() {
    for (stop in ShareStop.entries) {
      val text = shareStopText(context, stop)
      assertWithMessage(stop.code).that(text).isNotEmpty()
      assertThat(text).doesNotContain("%")
    }
    assertThat(shareStopText(context, ShareStop.CANNOT_REMOVE_LOCATION)).contains("senza posizione")
    assertThat(shareStopText(context, ShareStop.TOO_MANY)).contains("100")
    // I codici sono stabili e senza dati: minuscole e trattini bassi.
    assertThat(ShareStop.entries.map { it.code }.toSet()).hasSize(ShareStop.entries.size)
    assertThat(ShareStop.entries.all { Regex("[a-z]+(_[a-z]+)*").matches(it.code) }).isTrue()
  }

  @Test
  fun loStatoPerLaUiDicePosizioneTolta_OFermataConIlMotivo() {
    val ready = SharePreparation.Ready(listOf(SharedFile(Uri.parse("content://p/1"), "image/jpeg", 1, File("x")), SharedFile(Uri.parse("content://p/2"), "image/jpeg", 1, null)), ShareLease(emptyList(), emptyList(), { }, null), stripped = 1)
    assertThat(ready.toState()).isEqualTo(ShareOutState.Ready(verified = 1, direct = 1))
    assertThat(SharePreparation.Stopped(ShareStop.LOCATION_REMAINS, 2).toState()).isEqualTo(ShareOutState.Refused(ShareStop.LOCATION_REMAINS, 2))
  }

  @Test
  fun iContatoriSonoCodiciFissi() {
    FlowCounters.reset()
    FlowCounters.share.add(ShareCounter.PREPARED, 3)
    FlowCounters.share.add(ShareCounter.PREPARED, -4) // un numero negativo non toglie niente
    assertThat(FlowCounters.share.get(ShareCounter.PREPARED)).isEqualTo(3L)
    val all = FlowCounters.snapshot()
    assertThat(all.keys.toSet()).hasSize(ReceiveCounter.entries.size + EditCounter.entries.size + ShareCounter.entries.size)
    assertThat(all.keys.all { Regex("[a-z]+(_[a-z]+)*").matches(it) }).isTrue()
    assertThat(all.values.all { it >= 0 }).isTrue()
    FlowCounters.reset()
  }
}
