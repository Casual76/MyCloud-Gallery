package dev.pampa.mycloudgallery.feature.integrations.receive

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.backup.contract.IncomingOrigin
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ContentProviderController
import org.robolectric.Robolectric
import androidx.test.core.app.ApplicationProvider
import android.app.Application

/**
 * Fase 5B, P11 (D24, C18, E-X2): l'intento di condivisione letto in modo difensivo. Dell'intento contano azione, tipo e indirizzi dei flussi
 * (`EXTRA_STREAM` e `ClipData`); un intento che non e' per noi, o che porta altro che indirizzi, vale come vuoto.
 */
@RunWith(RobolectricTestRunner::class)
class ReceiveRequestTest {

  private fun uri(n: Int): Uri = Uri.parse("content://com.example.provider/item/$n")

  @Test
  fun unaCondivisioneSingolaPrendeGliIndirizziDaExtraStreamEDalClipDataSenzaDoppioni() {
    val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri(1))
    intent.clipData = ClipData.newRawUri("x", uri(1)).apply { addItem(ClipData.Item(uri(2))) }

    val request = ReceiveRequest.from(intent)!!

    assertThat(request.uris).containsExactly(uri(1), uri(2)).inOrder()
    assertThat(request.overflow).isEqualTo(0)
  }

  @Test
  fun piuFileEUnaLista() {
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
      .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(uri(1), uri(2), uri(3)))

    assertThat(ReceiveRequest.from(intent)!!.uris).hasSize(3)
  }

  @Test
  fun unIntentoCheNonEPerNoiVaCome_Vuoto() {
    // Azione sbagliata, tipo che non e' foto o video, nessun flusso.
    assertThat(ReceiveRequest.from(null)).isNull()
    assertThat(ReceiveRequest.from(Intent(Intent.ACTION_VIEW).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri(1)))).isNull()
    assertThat(ReceiveRequest.from(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri(1)))).isNull()
    assertThat(ReceiveRequest.from(Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, uri(1)))).isNull()
    assertThat(ReceiveRequest.from(Intent(Intent.ACTION_SEND).setType("image/png"))).isNull()
    assertThat(ReceiveRequest.from(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/png").putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Uri>()))).isNull()
  }

  @Test
  fun unExtraStreamCheNonEUnIndirizzoSiIgnoraENonFaDanni() {
    // L'altra app ci mette una stringa, un numero o un Bundle dove ci aspettiamo un Uri: per noi non c'e' niente da leggere.
    for (junk in listOf<Any>("file:///data/data/x", 42, Bundle().apply { putString("a", "b") })) {
      val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg")
      when (junk) {
        is String -> intent.putExtra(Intent.EXTRA_STREAM, junk)
        is Int -> intent.putExtra(Intent.EXTRA_STREAM, junk)
        is Bundle -> intent.putExtra(Intent.EXTRA_STREAM, junk)
      }
      assertThat(ReceiveRequest.from(intent)).isNull()
    }
  }

  @Test
  fun oltreIlTettoGliIndirizziSiContanoENonSiTengono() {
    val many = ArrayList<Uri>((0 until ReceiveRequest.MAX_URIS + 25).map { uri(it) })
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, many)

    val request = ReceiveRequest.from(intent)!!

    assertThat(request.uris).hasSize(ReceiveRequest.MAX_URIS)
    assertThat(request.overflow).isEqualTo(25)
  }

  @Test
  fun iFlussiSiCostruisconoSenzaAprireNienteEIRifiutiNonSiAprono() {
    val context = ApplicationProvider.getApplicationContext<Application>()
    val request = ReceiveRequest(
      listOf(Uri.parse("file:///data/x.jpg"), Uri.parse("content://${context.packageName}.integrations.files/share/a.jpg"), uri(1)),
      0,
    )

    val streams = runBlocking { IncomingStreams.of(context.contentResolver, context.packageName, request) }

    assertThat(streams.map { it.origin }).containsExactly(IncomingOrigin.FILE_SCHEME, IncomingOrigin.OWN_PROVIDER, IncomingOrigin.CONTENT_OF_ANOTHER_APP).inOrder()
    // Un file:// o un provider nostro: nessun nome, nessuna dimensione, e se qualcuno provasse ad aprirlo lancia.
    for (hostile in streams.take(2)) {
      assertThat(hostile.displayName).isNull()
      assertThat(hostile.declaredSize).isNull()
      org.junit.Assert.assertThrows(java.io.IOException::class.java) { hostile.open() }
    }
  }
}
