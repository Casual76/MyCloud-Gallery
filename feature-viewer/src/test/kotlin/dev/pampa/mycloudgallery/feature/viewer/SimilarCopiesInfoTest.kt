package dev.pampa.mycloudgallery.feature.viewer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.library.similar.CopyPlace
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopiesGroup
import dev.pampa.mycloudgallery.core.library.similar.SimilarCopy
import dev.pampa.mycloudgallery.core.model.MediaGroupId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.feature.viewer.details.ViewerFormat
import dev.pampa.mycloudgallery.feature.viewer.info.SimilarCopiesActions
import dev.pampa.mycloudgallery.feature.viewer.info.placesText
import dev.pampa.mycloudgallery.feature.viewer.info.similarCopiesInfoSection
import org.junit.Test
import org.junit.runner.RunWith

/**
 * La sezione "N copie" del pannello (Fase 5A, P15; §2.12, D36): una riga per copia con dove sta, risoluzione, dimensione e formato, le due
 * azioni, il nascosto detto. Funzione pura di uno stato: nessuno schermo.
 */
@RunWith(AndroidJUnit4::class)
class SimilarCopiesInfoTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val format = ViewerFormat(context)

  private val phone = MediaId.random()
  private val cloud = MediaId.random()
  private val old = MediaId.random()

  private fun copy(id: MediaId, places: Set<CopyPlace>, w: Int, h: Int, bytes: Long, mime: String?, original: Boolean = false) =
    SimilarCopy(id, MediaKind.PHOTO, places, w, h, bytes, mime, bitrate = null, cameraOriginal = original)

  private val group = SimilarCopiesGroup(
    MediaGroupId.random(), cover = phone,
    copies = listOf(
      copy(phone, setOf(CopyPlace.PHONE, CopyPlace.NAS_PRIVATE), 4000, 3000, 3_400_000, "image/jpeg", original = true),
      copy(cloud, setOf(CopyPlace.NAS_PRIVATE), 2000, 1500, 600_000, "image/jpeg"),
      copy(old, setOf(CopyPlace.NAS_COMMON), 4000, 3000, 2_100_000, "image/heic"),
    ),
    coverChosenByUser = false, hidden = false,
  )

  private class Taps {
    val viewed = ArrayList<MediaId>()
    var main = 0
    var separate = 0
    val actions = SimilarCopiesActions(onView = { viewed += it }, onMakeMain = { main++ }, onSeparate = { separate++ })
  }

  @Test
  fun laSezioneHaIlNumeroDelleCopieEUnaRigaPerCopia() {
    val taps = Taps()
    val section = similarCopiesInfoSection(format, group, viewing = phone, actions = taps.actions)
    assertThat(section.header).isEqualTo("3 copie")
    val rows = section.rows
    assertThat(rows.take(3).map { it.title }).containsExactly(
      "4000 × 3000 · " + format.fileSize(3_400_000),
      "2000 × 1500 · " + format.fileSize(600_000),
      "4000 × 3000 · " + format.fileSize(2_100_000),
    ).inOrder()
    assertThat(rows[0].title).contains("MB")
    assertThat(rows[0].caption).startsWith("Telefono e NAS, area privata · JPEG")
    assertThat(rows[1].caption).isEqualTo("NAS, area privata · JPEG")
    assertThat(rows[2].caption).isEqualTo("NAS, area comune · HEIC")
    // La principale lo dice con una pillola; l'originale della fotocamera si riconosce.
    assertThat(rows[0].pill).isEqualTo("Principale")
    assertThat(rows[1].pill).isNull()
    assertThat(rows[0].caption).contains("La stai guardando")
    assertThat(rows[0].tone).isEqualTo(FluidTone.Primary)
    assertThat(rows.all { it.icon != null }).isTrue()
  }

  @Test
  fun siTocca_UnaCopiaPerGuardarla_LaCopiaInVistaNonSiTocca() {
    val taps = Taps()
    val rows = similarCopiesInfoSection(format, group, viewing = cloud, actions = taps.actions).rows
    assertThat(rows[1].onClick).isNull() // la copia in vista
    rows[0].onClick!!.invoke()
    rows[2].onClick!!.invoke()
    assertThat(taps.viewed).containsExactly(phone, old).inOrder()
    assertThat(rows[1].caption).contains("La stai guardando")
    assertThat(rows[1].tone).isEqualTo(FluidTone.Primary)
  }

  @Test
  fun leAzioniSonoDueEMostraComePrincipaleNonCeSeGiaLaPrincipale() {
    val taps = Taps()
    // Guardando una copia che non e' la principale: tutte e due.
    val onCloud = similarCopiesInfoSection(format, group, viewing = cloud, actions = taps.actions).rows
    val titles = onCloud.map { it.title }
    assertThat(titles).containsAtLeast("Mostra questa come principale", "Separa")
    onCloud.first { it.title == "Mostra questa come principale" }.onClick!!.invoke()
    onCloud.first { it.title == "Separa" }.onClick!!.invoke()
    assertThat(taps.main).isEqualTo(1)
    assertThat(taps.separate).isEqualTo(1)
    // Guardando la principale: solo "Separa".
    val onCover = similarCopiesInfoSection(format, group, viewing = phone, actions = taps.actions).rows.map { it.title }
    assertThat(onCover).doesNotContain("Mostra questa come principale")
    assertThat(onCover).contains("Separa")
    // "Separa" e' un'azione da pensare: il tono lo dice (avviso), non e' un tasto rosso.
    assertThat(onCloud.first { it.title == "Separa" }.tone).isEqualTo(FluidTone.Warning)
  }

  @Test
  fun mentreUnAzioneEInCorsoLeRigheNonRispondono() {
    val taps = Taps()
    val rows = similarCopiesInfoSection(format, group, viewing = cloud, actions = taps.actions, busy = true).rows
    assertThat(rows.all { it.onClick == null }).isTrue()
  }

  @Test
  fun ilGruppoNascostoLoDiceE_ConUnaSolaCopiaFraLeAltre_LaPlurale() {
    val hidden = SimilarCopiesGroup(
      MediaGroupId.random(), cover = phone, copies = group.copies.take(2), coverChosenByUser = true, hidden = true,
    )
    val section = similarCopiesInfoSection(format, hidden, viewing = phone, actions = Taps().actions)
    assertThat(section.header).isEqualTo("2 copie")
    val row = section.rows.first { it.title == "Nascoste insieme" }
    assertThat(row.caption).contains("vale per tutte")
    assertThat(similarCopiesInfoSection(format, group, phone, Taps().actions).rows.map { it.title }).doesNotContain("Nascoste insieme")
  }

  @Test
  fun ogniPostoHaIlSuoTesto() {
    assertThat(placesText(format, setOf(CopyPlace.PHONE))).isEqualTo("Telefono")
    assertThat(placesText(format, setOf(CopyPlace.NAS_PRIVATE))).isEqualTo("NAS, area privata")
    assertThat(placesText(format, setOf(CopyPlace.NAS_COMMON))).isEqualTo("NAS, area comune")
    assertThat(placesText(format, setOf(CopyPlace.NAS_PRIVATE, CopyPlace.NAS_COMMON))).isEqualTo("NAS, area privata e NAS, area comune")
    assertThat(placesText(format, setOf(CopyPlace.PHONE, CopyPlace.NAS_COMMON))).isEqualTo("Telefono e NAS, area comune")
    assertThat(placesText(format, emptySet())).isEqualTo("Non raggiungibile adesso")
  }

  @Test
  fun unaCopiaSenzaMisureNonScriveZeroPerZero() {
    val unknown = copy(old, setOf(CopyPlace.NAS_COMMON), 0, 0, 2_100_000, null)
    val section = similarCopiesInfoSection(
      format,
      SimilarCopiesGroup(MediaGroupId.random(), cover = phone, copies = listOf(group.copies[0], unknown), coverChosenByUser = false, hidden = false),
      viewing = phone, actions = Taps().actions,
    )
    assertThat(section.rows[1].title).isEqualTo(format.fileSize(2_100_000))
    assertThat(section.rows[1].caption).isEqualTo("NAS, area comune")
  }

  @Test
  fun iVideoHannoLaLoroIcona() {
    val video = SimilarCopy(old, MediaKind.VIDEO, emptySet(), 1920, 1080, 50_000_000, "video/mp4", bitrate = 8_000_000, cameraOriginal = false)
    val pair = SimilarCopiesGroup(
      MediaGroupId.random(), cover = old,
      copies = listOf(video, video.copyOf(phone, setOf(CopyPlace.PHONE))), coverChosenByUser = false, hidden = false,
    )
    val rows = similarCopiesInfoSection(format, pair, viewing = old, actions = Taps().actions).rows
    assertThat(rows[0].title).isEqualTo("1920 × 1080 · " + format.fileSize(50_000_000))
    assertThat(rows[0].caption).startsWith("Non raggiungibile adesso · MP4")
  }

  private fun SimilarCopy.copyOf(id: MediaId, places: Set<CopyPlace>) = SimilarCopy(id, kind, places, width, height, bytes, mime, bitrate, cameraOriginal)
}
