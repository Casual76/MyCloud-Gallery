package dev.pampa.mycloudgallery.feature.integrations.picker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaKind
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Fase 5B (P10, D23, C17): cio' che l'altra app chiede. L'intento viene da fuori: input non fidato, letto in modo difensivo; un'azione che il
 * selettore non serve o nessun tipo di media non apre niente.
 */
@RunWith(AndroidJUnit4::class)
class PickerRequestTest {

  private fun request(action: String? = Intent.ACTION_GET_CONTENT, type: String? = "image/*", configure: Intent.() -> Unit = {}): PickerRequest? {
    val intent = Intent(action)
    if (type != null) intent.type = type
    intent.configure()
    return PickerRequest.from(intent)
  }

  @Test
  fun foto_Video_ETutto() {
    val photos = request(type = "image/*")!!
    assertThat(photos.images).isTrue()
    assertThat(photos.videos).isFalse()
    assertThat(photos.acceptsKind(MediaKind.PHOTO)).isTrue()
    assertThat(photos.acceptsKind(MediaKind.VIDEO)).isFalse()
    assertThat(photos.hasSpecificTypes).isFalse()

    val videos = request(type = "video/*")!!
    assertThat(videos.images).isFalse()
    assertThat(videos.videos).isTrue()

    for (type in listOf("*/*", null)) {
      val both = request(type = type)!!
      assertThat(both.images).isTrue()
      assertThat(both.videos).isTrue()
    }
  }

  @Test
  fun entrambeLeAzioniEPickNonLeggeLUriInData() {
    assertThat(request(Intent.ACTION_GET_CONTENT)!!.action).isEqualTo(PickerAction.GET_CONTENT)
    val pick = Intent(Intent.ACTION_PICK, Uri.parse("content://media/external/images/media")).setType("image/*")
    assertThat(PickerRequest.from(pick)!!.action).isEqualTo(PickerAction.PICK)
  }

  /**
   * IN-13 della verifica funzionale 5B: il `PICK` classico (`Intent(ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)`) arriva con il tipo del
   * MediaStore (`vnd.android.cursor.dir/image`), che non entrava nel filtro del manifest e `PickerRequest` rifiutava.
   */
  @Test
  fun in13_ilPickClassicoConIlTipoDelMediaStoreSiServe() {
    val images = request(Intent.ACTION_PICK, type = "vnd.android.cursor.dir/image")!!
    assertThat(images.action).isEqualTo(PickerAction.PICK)
    assertThat(images.images).isTrue()
    assertThat(images.videos).isFalse()
    assertThat(images.mimeFilters).containsExactly("image/*")
    assertThat(images.acceptsMime("image/jpeg")).isTrue()
    assertThat(images.acceptsMime("video/mp4")).isFalse()

    val videos = request(Intent.ACTION_PICK, type = "vnd.android.cursor.dir/video")!!
    assertThat(videos.images).isFalse()
    assertThat(videos.videos).isTrue()
    assertThat(videos.acceptsMime("video/mp4")).isTrue()

    // Altri tipi del MediaStore (audio, voce singola) non sono un'immagine ne' un video: nessun tipo, richiesta rifiutata.
    assertThat(request(Intent.ACTION_PICK, type = "vnd.android.cursor.dir/audio")).isNull()
    assertThat(request(Intent.ACTION_PICK, type = "vnd.android.cursor.item/image")).isNull()
  }

  @Test
  fun in13_ilPickConUnIndirizzoDelMediaStoreESenzaTipoDiceSoloSeSonoImmaginiOVideo_SenzaMaiAprirlo() {
    fun pick(uri: String) = PickerRequest.from(Intent(Intent.ACTION_PICK, Uri.parse(uri)))!!
    pick("content://media/external/images/media").let {
      assertThat(it.images).isTrue()
      assertThat(it.videos).isFalse()
    }
    pick("content://media/external/video/media").let {
      assertThat(it.images).isFalse()
      assertThat(it.videos).isTrue()
    }
    // Un indirizzo che non e' del MediaStore, o che non dice niente: foto e video, come sempre. Nemmeno qui si apre (non c'e' un resolver in gioco).
    for (uri in listOf("content://altra.app/images/x", "content://media/external/audio/media", "file:///sdcard/x")) {
      val request = pick(uri)
      assertThat(request.images).isTrue()
      assertThat(request.videos).isTrue()
    }
    // Il tipo dell'indirizzo vale solo per PICK: GET_CONTENT con lo stesso indirizzo resta "foto e video".
    val getContent = PickerRequest.from(Intent(Intent.ACTION_GET_CONTENT, Uri.parse("content://media/external/images/media")))!!
    assertThat(getContent.videos).isTrue()
  }

  @Test
  fun leAzioniChePerNoNonSonoSonoRifiutate() {
    for (action in listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE, Intent.ACTION_MAIN, "azione.sconosciuta", "", null)) {
      assertThat(request(action)).isNull()
    }
    assertThat(PickerRequest.from(null)).isNull()
  }

  @Test
  fun nessunTipoDiMediaEUnRifiuto() {
    assertThat(request(type = "application/pdf")).isNull()
    assertThat(request(type = "text/plain")).isNull()
    assertThat(request(type = "audio/*")).isNull()
    assertThat(request(type = "*/*") { putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "audio/mpeg")) }).isNull()
  }

  @Test
  fun iTipiPreciOSovrascrivonoIlTipoDellIntento() {
    val precise = request(type = "*/*") { putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/png", "VIDEO/MP4; codecs=avc1")) }!!
    assertThat(precise.mimeFilters).containsExactly("image/png", "video/mp4")
    assertThat(precise.hasSpecificTypes).isTrue()
    assertThat(precise.acceptsMime("image/png")).isTrue()
    assertThat(precise.acceptsMime("IMAGE/PNG; q=1")).isTrue()
    assertThat(precise.acceptsMime("video/mp4")).isTrue()
    assertThat(precise.acceptsMime("image/jpeg")).isFalse()
    assertThat(precise.acceptsMime("video/webm")).isFalse()
    assertThat(precise.acceptsMime("application/pdf")).isFalse()
  }

  @Test
  fun iTipiJollyAmmettonoSoloMedia() {
    val images = request(type = "image/*")!!
    assertThat(images.acceptsMime("image/heic")).isTrue()
    assertThat(images.acceptsMime("video/mp4")).isFalse()
    val all = request(type = "*/*")!!
    assertThat(all.acceptsMime("image/gif")).isTrue()
    assertThat(all.acceptsMime("video/mp4")).isTrue()
    // Anche con "tutto" non si consegna ne' si accetta cio' che non e' una foto o un video.
    assertThat(all.acceptsMime("application/pdf")).isFalse()
    assertThat(all.acceptsMime("")).isFalse()
    assertThat(all.acceptsMime("immagine")).isFalse()
  }

  @Test
  fun scelteMultipleESoloLocale() {
    val plain = request()!!
    assertThat(plain.allowMultiple).isFalse()
    assertThat(plain.localOnly).isFalse()
    val flags = request { putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true).putExtra(Intent.EXTRA_LOCAL_ONLY, true) }!!
    assertThat(flags.allowMultiple).isTrue()
    assertThat(flags.localOnly).isTrue()
  }

  @Test
  fun gliExtraOstiliValgonoComeAssentiENonFannoCadere() {
    // Tipi sbagliati: una stringa al posto dell'array, un numero al posto del booleano.
    val wrongTypes = request {
      putExtra(Intent.EXTRA_MIME_TYPES, "image/png")
      putExtra(Intent.EXTRA_ALLOW_MULTIPLE, "si")
      putExtra(Intent.EXTRA_LOCAL_ONLY, 12)
    }!!
    assertThat(wrongTypes.mimeFilters).containsExactly("image/*")
    assertThat(wrongTypes.allowMultiple).isFalse()
    assertThat(wrongTypes.localOnly).isFalse()
    // Voci assurde nell'array: ignorate.
    val junk = request(type = "image/*") {
      putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("", "///", "image", "../../etc/passwd", "image/jpeg\u0000", "a".repeat(5_000), "image/jpeg"))
    }!!
    assertThat(junk.mimeFilters).containsExactly("image/jpeg")
  }

  @Test
  fun unElencoEnormeDiTipiNonRallenta() {
    val many = Array(50_000) { "image/x-tipo-$it" }
    val started = System.nanoTime()
    val parsed = request(type = "*/*") { putExtra(Intent.EXTRA_MIME_TYPES, many) }!!
    assertThat((System.nanoTime() - started) / 1_000_000).isLessThan(5_000L)
    assertThat(parsed.images).isTrue()
    assertThat(parsed.videos).isFalse()
  }

  @Test
  fun unPacchettoRovinatoNonLanciaEccezioni() {
    val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
    intent.putExtra("sconosciuto", Bundle().apply { putString("x", "y") })
    assertThat(PickerRequest.from(intent)).isNotNull()
  }

  @Test
  fun laRichiestaNonScriveNienteNelToString() {
    val text = request(type = "image/*")!!.toString()
    assertThat(text).doesNotContain("content://")
  }
}
