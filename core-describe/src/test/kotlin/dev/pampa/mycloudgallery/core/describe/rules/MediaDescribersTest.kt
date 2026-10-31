package dev.pampa.mycloudgallery.core.describe.rules

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.testing.FakeA11yActivity
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePeople
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribePlaces
import dev.pampa.mycloudgallery.core.describe.testing.FakeDescribeText
import dev.pampa.mycloudgallery.core.describe.testing.ItalianDescribeStrings
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test

/** Fase 10 (P7, D22): il descrittore per gli schermi, il ripiego senza porte e il legame di :app (P13). */
class MediaDescribersTest {

  private val zone: ZoneId = ZoneId.of("Europe/Rome")

  @After
  fun reset() = MediaDescribers.reset()

  private fun snapshot(localDay: Int, place: Int = 0) = InMemoryTimelineSnapshot(
    seq = 1, idMsb = longArrayOf(1L), idLsb = longArrayOf(2L),
    sortTime = longArrayOf(LocalDateTime.of(2024, 3, 12, 10, 15).atZone(zone).toInstant().toEpochMilli()),
    localDay = intArrayOf(localDay), kinds = byteArrayOf(MediaKind.PHOTO.ordinal.toByte()), sources = byteArrayOf(MediaSource.CAMERA.ordinal.toByte()),
    flags = intArrayOf(CellFlags.FAVORITE), aspectMilli = intArrayOf(1000), durationMs = longArrayOf(0), place = intArrayOf(place), groupSize = intArrayOf(1),
  )

  @Test
  fun leParoleDiProduzioneSonoQuelleDelleProve() {
    assertThat(ItalianDescribeWords.VALUE).isEqualTo(ItalianDescribeStrings.VALUE)
  }

  @Test
  fun ilRipiegoDiceSoloIFatti() {
    val places = FakeDescribePlaces(mapOf(3_143_244 to "Oslo"))
    val text = MediaDescribers.describeCell(MediaDescribers.shared(places), snapshot(20240312, place = 3_143_244), 0, zone)
    assertThat(text).isEqualTo("Foto, 12 marzo 2024, 10:15, Oslo, preferita")
    assertThat(MediaDescribers.describeCell(MediaDescribers.shared(), snapshot(20240312), 0, zone)).isEqualTo("Foto, 12 marzo 2024, 10:15, preferita")
    assertThat(MediaDescribers.describeCell(MediaDescribers.shared(), snapshot(20240312), 0, zone, DescribeSurface.WIDGET)).isEqualTo("Foto, 12 marzo 2024")
  }

  @Test
  fun unGiornoRovinatoDaNullENonCadeNulla() {
    assertThat(MediaDescribers.describeCell(MediaDescribers.shared(), snapshot(20241399), 0, zone)).isNull()
    assertThat(MediaDescribers.describeCell(MediaDescribers.shared(), snapshot(0), 0, zone)).isNull()
  }

  @Test
  fun ilDescrittoreLegatoDaAppVinceESiPortaLePorte() = runTest {
    val id = MediaId(java.util.UUID(1L, 2L).toString())
    val bound = DefaultMediaDescriber(
      FakeDescribePlaces(emptyMap()), FakeDescribePeople(mapOf(id to listOf("Anna"))), FakeDescribeText(mapOf(id to "Scontrino")),
      ItalianDescribeWords.VALUE, FakeA11yActivity(),
    )
    bound.warm(listOf(id))
    MediaDescribers.install(bound)
    val text = MediaDescribers.describeCell(MediaDescribers.shared(), snapshot(20240312), 0, zone)
    assertThat(text).isEqualTo("Foto, 12 marzo 2024, 10:15, con Anna, con la scritta «Scontrino», preferita")
    MediaDescribers.reset()
    assertThat(MediaDescribers.describeCell(MediaDescribers.shared(), snapshot(20240312), 0, zone)).doesNotContain("Anna")
  }
}
