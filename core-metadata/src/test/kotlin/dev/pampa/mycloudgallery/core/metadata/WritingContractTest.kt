package dev.pampa.mycloudgallery.core.metadata

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.metadata.contract.GeoChange
import dev.pampa.mycloudgallery.core.metadata.contract.MetadataChange
import dev.pampa.mycloudgallery.core.metadata.contract.StripOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.StripUnsupported
import dev.pampa.mycloudgallery.core.metadata.contract.WriteFailure
import dev.pampa.mycloudgallery.core.metadata.contract.WriteOutcome
import dev.pampa.mycloudgallery.core.metadata.contract.WriteUnsupported
import dev.pampa.mycloudgallery.core.metadata.noop.NoLocationStripper
import dev.pampa.mycloudgallery.core.metadata.noop.NoMetadataWriter
import dev.pampa.mycloudgallery.core.metadata.testing.ChangeSamples
import dev.pampa.mycloudgallery.core.metadata.testing.FakeLocationStripper
import dev.pampa.mycloudgallery.core.metadata.testing.FakeMetadataWriter
import dev.pampa.mycloudgallery.core.model.CorrectionField
import dev.pampa.mycloudgallery.core.model.ExifOrientation
import dev.pampa.mycloudgallery.core.model.GeoPoint
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Fase 5B (P0-B, D19, D26, L40, L41): il contratto di `MetadataWriter` e `LocationStripper`, sui loro null-object e fake. Le scritture
 * vere (`ExifInterface`) sono di P8 e P11, che riusano questi stessi controlli: il sorgente non si tocca mai, la destinazione non si
 * sostituisce mai, un fallimento non lascia niente, e senza strumento legato **niente si finge**.
 */
class WritingContractTest {

  @get:Rule val folder = TemporaryFolder()

  private fun file(name: String, content: String = "contenuto"): File = folder.newFile(name).apply { writeText(content) }

  private fun absent(name: String): File = File(folder.root, name)

  @Test
  fun unCambiamentoDichiaraICampiCheTocca() {
    assertThat(MetadataChange().isEmpty).isTrue()
    assertThat(MetadataChange(geo = GeoChange.Keep, orientation = null).isEmpty).isTrue()
    val all = MetadataChange(
      takenAtUtcMillis = 1_000L,
      offsetMinutes = 60,
      geo = GeoChange.Set(GeoPoint(45.0, 9.0)),
      orientation = ExifOrientation.ROTATE_90,
    )
    assertThat(all.fields).containsExactly(CorrectionField.DATE, CorrectionField.LOCATION, CorrectionField.ROTATION)
    assertThat(MetadataChange(geo = GeoChange.Clear).fields).containsExactly(CorrectionField.LOCATION)
    // I valori sono dell'utente: la riga di log dice solo quali campi.
    assertThat(all.toString()).doesNotContain("45")
    assertThat(all.toString()).doesNotContain("1000")
  }

  @Test
  fun ilFusoAccompagnaUnaDataEStaDentroIlMondo() {
    assertThrows(IllegalArgumentException::class.java) { MetadataChange(offsetMinutes = 60) }
    assertThrows(IllegalArgumentException::class.java) { MetadataChange(takenAtUtcMillis = 1L, offsetMinutes = 15 * 60) }
    assertThat(MetadataChange(takenAtUtcMillis = 1L, offsetMinutes = -12 * 60).offsetMinutes).isEqualTo(-720)
  }

  @Test
  fun senzaStrumentiLegatiNienteSiFinge() {
    val src = file("a.jpg")
    val dst = absent("out.jpg")
    assertThat(NoMetadataWriter.canWrite("image/jpeg")).isFalse()
    assertThat(NoMetadataWriter.apply(src, dst, ChangeSamples.newDate)).isEqualTo(WriteOutcome.Unsupported(WriteUnsupported.NOT_AVAILABLE))
    assertThat(NoLocationStripper.strip(src, dst, "image/jpeg")).isEqualTo(StripOutcome.Unsupported(StripUnsupported.NOT_AVAILABLE))
    // Il togli-posizione che non c'e' non consegna mai un file "senza posizione": la condivisione si ferma (D26).
    assertThat(dst.exists()).isFalse()
    assertThat(src.readText()).isEqualTo("contenuto")
  }

  @Test
  fun loScrittoreFintoCopiaSenzaToccareIlSorgente() {
    val writer = FakeMetadataWriter()
    val src = file("foto.jpg", "JPEGBYTES")
    val dst = absent("corretta.jpg")
    val outcome = writer.apply(src, dst, ChangeSamples.newDate) as WriteOutcome.Written
    assertThat(outcome.bytes).isEqualTo(dst.length())
    assertThat(writer.fieldsWritten(dst)).containsExactly("date")
    assertThat(src.readText()).isEqualTo("JPEGBYTES")

    // Non si sostituisce: la destinazione che c'e' resta com'e'.
    val before = dst.readBytes().toList()
    assertThat(writer.apply(src, dst, ChangeSamples.clearLocation)).isEqualTo(WriteOutcome.Failed(WriteFailure.DESTINATION_EXISTS))
    assertThat(dst.readBytes().toList()).isEqualTo(before)
  }

  @Test
  fun iFormatiFuoriDallaQ6EUnCambiamentoVuotoNonSiScrivono() {
    val writer = FakeMetadataWriter()
    for (name in listOf("foto.heic", "raw.dng", "video.mp4")) {
      val dst = absent("out-$name")
      assertThat(writer.apply(file(name), dst, ChangeSamples.newDate)).isEqualTo(WriteOutcome.Unsupported(WriteUnsupported.FORMAT))
      assertThat(dst.exists()).isFalse()
    }
    assertThat(writer.canWrite("image/webp")).isTrue()
    assertThat(writer.canWrite("image/heic")).isFalse()
    assertThat(writer.apply(file("v.png"), absent("v-out.png"), MetadataChange())).isEqualTo(WriteOutcome.Unsupported(WriteUnsupported.NOTHING_TO_DO))
  }

  @Test
  fun unGuastoNonLasciaLaDestinazione() {
    val writer = FakeMetadataWriter()
    val dst = absent("out.jpg")
    writer.failNext = WriteFailure.IO
    assertThat(writer.apply(file("a.jpg"), dst, ChangeSamples.newDate)).isEqualTo(WriteOutcome.Failed(WriteFailure.IO))
    assertThat(dst.exists()).isFalse()
    // Una volta sola: la scrittura dopo riesce.
    assertThat(writer.apply(file("b.jpg"), dst, ChangeSamples.newDate)).isInstanceOf(WriteOutcome.Written::class.java)
    // Un sorgente che non c'e' non si legge: nessuna destinazione.
    assertThat(writer.apply(absent("assente.jpg"), absent("o.jpg"), ChangeSamples.newDate)).isEqualTo(WriteOutcome.Failed(WriteFailure.SOURCE_UNREADABLE))
  }

  @Test
  fun ilTogliPosizioneFintoSiComportaComeIlContratto() {
    val stripper = FakeLocationStripper()
    val src = file("img.jpg", "CONGPS")
    val dst = absent("senza.jpg")
    assertThat(stripper.strip(src, dst, "image/jpeg")).isEqualTo(StripOutcome.Stripped("image/jpeg"))
    assertThat(stripper.isStripped(dst)).isTrue()
    assertThat(src.readText()).isEqualTo("CONGPS")

    // L'HEIC diventa JPEG: la condivisione usa il tipo della destinazione.
    assertThat(stripper.strip(file("h.heic"), absent("h.jpg"), "image/heic")).isEqualTo(StripOutcome.Stripped("image/jpeg"))
    // Un formato che non si sa trattare ferma la condivisione (L41), senza creare niente.
    val gif = absent("g.gif")
    assertThat(stripper.strip(file("g.src"), gif, "image/gif")).isEqualTo(StripOutcome.Unsupported(StripUnsupported.FORMAT))
    assertThat(gif.exists()).isFalse()
    // Il ricontrollo trova una posizione rimasta: la destinazione non resta e la condivisione si ferma (D26).
    stripper.stillHasLocation = true
    val leftover = absent("resta.jpg")
    assertThat(stripper.strip(src, leftover, "image/jpeg")).isEqualTo(StripOutcome.StillHasLocation)
    assertThat(leftover.exists()).isFalse()
    assertThat(stripper.requests).containsExactly("image/jpeg", "image/heic", "image/gif", "image/jpeg").inOrder()
  }
}
