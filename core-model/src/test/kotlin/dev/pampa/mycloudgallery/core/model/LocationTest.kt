package dev.pampa.mycloudgallery.core.model

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.Fixtures.ME
import dev.pampa.mycloudgallery.core.model.Fixtures.nas
import dev.pampa.mycloudgallery.core.model.Fixtures.phone
import org.junit.Assert.assertThrows
import org.junit.Test
import java.text.Normalizer

class LocationTest {

  @Test
  fun percorsiNasOstiliSonoRifiutati() {
    val hostile = listOf(
      "", "/assoluto/x.jpg", "//server/share/x.jpg", "a/../b.jpg", "../x.jpg", "./x.jpg", "a//b.jpg",
      "a/b/", "a\\b.jpg", "C:/x.jpg", "x.jpg:stream", "a/\u0000.jpg", "a/b\n.jpg",
      "a/" + "x".repeat(256), "a/".repeat(600) + "x",
    )
    hostile.forEach { assertThat(NasPath.parseOrNull(it)).isNull() }
    assertThrows(IllegalArgumentException::class.java) { NasPath.of("../x") }
  }

  @Test
  fun percorsiNasNormaliSonoAccettatiEConservatiComeSono() {
    val path = NasPath.of("Foto 2024/Vacanze à Oslo/IMG_0001 😀.JPG")
    assertThat(path.value).isEqualTo("Foto 2024/Vacanze à Oslo/IMG_0001 😀.JPG")
    assertThat(path.fileName).isEqualTo("IMG_0001 😀.JPG")
    assertThat(path.parent).isEqualTo("Foto 2024/Vacanze à Oslo")
    assertThat(NasPath.of("radice.jpg").parent).isEmpty()
  }

  /** E-N7: nomi che differiscono solo per maiuscole sono lo stesso posto (nessun doppione fantasma). */
  @Test
  fun nomiCheDifferisconoSoloPerMaiuscoleSonoLoStessoPosto() {
    assertThat(nas("Foto/IMG_0001.JPG").key).isEqualTo(nas("foto/img_0001.jpg").key)
    assertThat(nas("Foto/IMG_0001.JPG", share = "Alessio").key).isEqualTo(nas("foto/img_0001.jpg", share = "alessio").key)
    assertThat(phone("IMG_0001.JPG", path = "DCIM/Camera/").key).isEqualTo(phone("img_0001.jpg", path = "dcim/camera/").key)

    assertThat(nas("Foto/IMG_0001.JPG").key).isNotEqualTo(nas("Foto/IMG_0002.JPG").key)
    assertThat(nas("Foto/IMG_0001.JPG").key).isNotEqualTo(nas("Foto/IMG_0001.JPG", share = "NAS").key)
    assertThat(nas("Foto/IMG_0001.JPG").key).isNotEqualTo(phone("IMG_0001.JPG", path = "Foto/").key)
  }

  /** E-N6: lo stesso nome accentato composto (NFC) o scomposto (NFD, macOS) e' lo stesso posto. */
  @Test
  fun formeUnicodeDiverseDelloStessoNomeSonoLoStessoPosto() {
    val composed = "Città/perché.jpg"
    val decomposed = Normalizer.normalize(composed, Normalizer.Form.NFD)
    assertThat(decomposed).isNotEqualTo(composed)
    assertThat(nas(decomposed).key).isEqualTo(nas(composed).key)
  }

  @Test
  fun laCartellaEDerivataDalPercorso() {
    assertThat(nas("Foto/2024/a.jpg").folderKey).isEqualTo(nas("foto/2024/B.JPG").folderKey)
    assertThat(nas("Foto/2024/a.jpg").folderKey).isNotEqualTo(nas("Foto/2025/a.jpg").folderKey)
    assertThat(phone("a.jpg").folderKey).isEqualTo(phone("B.jpg").folderKey)
  }

  @Test
  fun posizioniDelTelefonoValidate() {
    assertThrows(IllegalArgumentException::class.java) { Location.Phone("external_primary", "/DCIM/", "a.jpg") }
    assertThrows(IllegalArgumentException::class.java) { Location.Phone("external_primary", "DCIM", "a.jpg") }
    assertThrows(IllegalArgumentException::class.java) { Location.Phone("external_primary", "DCIM/../", "a.jpg") }
    assertThrows(IllegalArgumentException::class.java) { Location.Phone("external_primary", "DCIM/", "a/b.jpg") }
    assertThrows(IllegalArgumentException::class.java) { Location.Phone("External Primary", "DCIM/", "a.jpg") }
    assertThrows(IllegalArgumentException::class.java) { Location.Phone("external_primary", "DCIM/", "a.jpg", mediaStoreId = 0) }
    assertThat(Location.Phone("1a2b-3c4d", "", "radice.jpg").key.value).isEqualTo("phone:1a2b-3c4d:radice.jpg")
  }

  /** La cache dell'_ID vale solo con la stessa versione del MediaStore (docs/android.md, scoperta 7). */
  @Test
  fun lIdMediaStoreValeSoloConLaStessaVersioneDelMediaStore() {
    val before = phone("a.jpg", mediaStoreId = 42, storeVersion = "v1")
    assertThat(before.hasSameMediaStoreRowAs(phone("b.jpg", mediaStoreId = 42, storeVersion = "v1"))).isTrue()
    assertThat(before.hasSameMediaStoreRowAs(phone("a.jpg", mediaStoreId = 42, storeVersion = "v2"))).isFalse()
    assertThat(before.hasSameMediaStoreRowAs(phone("a.jpg", mediaStoreId = 43, storeVersion = "v1"))).isFalse()
    assertThat(phone("a.jpg").hasSameMediaStoreRowAs(phone("a.jpg"))).isFalse()
  }

  @Test
  fun ilTimboDiceSeRileggereIlFile() {
    val stamp = FileStamp(100, 5_000, generation = 7)
    assertThat(FileStamp(100, 5_000, 7).looksUnchangedFrom(stamp)).isTrue()
    assertThat(FileStamp(100, 5_000, null).looksUnchangedFrom(stamp)).isTrue()
    assertThat(FileStamp(100, 5_001, 7).looksUnchangedFrom(stamp)).isFalse()
    assertThat(FileStamp(101, 5_000, 7).looksUnchangedFrom(stamp)).isFalse()
    assertThat(FileStamp(100, 5_000, 8).looksUnchangedFrom(stamp)).isFalse()
  }

  @Test
  fun unaPosizioneAssenteDichiaraDaQuando() {
    val v = Fixtures.original(Fixtures.full(1))
    assertThrows(IllegalArgumentException::class.java) {
      Fixtures.located(1, v, nas("a.jpg")).copy(state = LocationState.ABSENT)
    }
    assertThat(LocationState.ABSENT.exists).isFalse()
    assertThat(LocationState.UNAVAILABLE.exists).isTrue()
  }

  @Test
  fun lAreaDiUnaShareSiTrovaSenzaBadareAlleMaiuscole() {
    assertThat(Fixtures.AREAS.areaOf(ME, nas("x.jpg", share = "ALESSIO").shareKey)).isEqualTo(NasArea.PRIVATE)
    assertThat(Fixtures.AREAS.areaOf(ME, nas("x.jpg", share = "nas").shareKey)).isEqualTo(NasArea.COMMON)
    assertThat(Fixtures.AREAS.areaOf(ME, nas("x.jpg", share = "musica").shareKey)).isNull()
  }
}
