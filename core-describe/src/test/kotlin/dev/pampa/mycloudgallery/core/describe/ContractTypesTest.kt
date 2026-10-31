package dev.pampa.mycloudgallery.core.describe

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.describe.contract.DescribeFact
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurface
import dev.pampa.mycloudgallery.core.describe.contract.DescribeSurfaces
import dev.pampa.mycloudgallery.core.describe.contract.MediaFacts
import dev.pampa.mycloudgallery.core.describe.noop.NoA11yTool
import dev.pampa.mycloudgallery.core.describe.noop.NoDescribePeople
import dev.pampa.mycloudgallery.core.describe.noop.NoDescribeText
import dev.pampa.mycloudgallery.core.library.testing.InMemoryTimelineSnapshot
import dev.pampa.mycloudgallery.core.model.CellFlags
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PresenceBadge
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fase 10 (P0, D22): i fatti dall'istantanea, la tabella delle superfici (nessuna esportazione), i `toString()` e i null-object. */
class ContractTypesTest {

  private val zone: ZoneId = ZoneId.of("Europe/Rome")

  private fun snapshot(sortTime: LocalDateTime, localDay: Int, kind: MediaKind, flags: Int, place: Int, duration: Long, group: Int) =
    InMemoryTimelineSnapshot(
      seq = 1, idMsb = longArrayOf(1L), idLsb = longArrayOf(2L), sortTime = longArrayOf(sortTime.atZone(zone).toInstant().toEpochMilli()),
      localDay = intArrayOf(localDay), kinds = byteArrayOf(kind.ordinal.toByte()), sources = byteArrayOf(MediaSource.CAMERA.ordinal.toByte()),
      flags = intArrayOf(flags), aspectMilli = intArrayOf(1000), durationMs = longArrayOf(duration), place = intArrayOf(place),
      groupSize = intArrayOf(group),
    )

  @Test
  fun iFattiDallIstantanea() {
    val coherent = MediaFacts.of(snapshot(LocalDateTime.of(2024, 3, 12, 10, 15), 20240312, MediaKind.VIDEO, CellFlags.ON_NAS, 42, 61_000, 3), 0, zone)
    assertThat(coherent.time).isEqualTo(LocalTime.of(10, 15))
    assertThat(coherent.placeId).isEqualTo(42)
    assertThat(coherent.durationMs).isEqualTo(61_000)
    assertThat(coherent.groupSize).isEqualTo(3)
    assertThat(coherent.presence).isEqualTo(PresenceBadge.NAS_ONLY)
    val travel = MediaFacts.of(snapshot(LocalDateTime.of(2024, 3, 12, 23, 30), 20240313, MediaKind.PHOTO, 0, 0, 0, 1), 0, zone)
    assertThat(travel.time).isNull()
    assertThat(travel.placeId).isNull()
    assertThat(travel.durationMs).isNull()
    val estimated = MediaFacts.of(snapshot(LocalDateTime.of(2024, 3, 12, 10, 15), 20240312, MediaKind.PHOTO, CellFlags.DATE_ESTIMATED, 0, 0, 1), 0, zone)
    assertThat(estimated.time).isNull()
  }

  @Test
  fun nessunaSuperficieDiEsportazione() {
    assertThat(DescribeSurface.entries.map { it.code }).containsExactly("in_app", "hidden_area", "widget", "notification")
    assertThat(DescribeSurfaces.facts(DescribeSurface.WIDGET)).containsExactly(DescribeFact.KIND, DescribeFact.DATE)
    assertThat(DescribeSurfaces.facts(DescribeSurface.NOTIFICATION)).isEmpty()
    assertThat(DescribeSurfaces.facts(DescribeSurface.IN_APP)).containsExactlyElementsIn(DescribeFact.entries)
    assertThat(DescribeSurfaces.facts(DescribeSurface.HIDDEN_AREA)).isEqualTo(DescribeSurfaces.facts(DescribeSurface.IN_APP))
  }

  @Test
  fun iToStringNonPortanoDateNeLuoghi() {
    val facts = MediaFacts(MediaKind.PHOTO, 20240312, LocalTime.of(10, 15), 3_143_244, null, 0, 1, false, null)
    assertThat(facts.toString()).isEqualTo("MediaFacts(photo)")
  }

  @Test
  fun iNullObjectNonDiconoNiente() = runTest {
    val id = MediaId(UUID(1L, 2L).toString())
    assertThat(NoDescribePeople.names(listOf(id))).isEmpty()
    assertThat(NoDescribeText.firstWords(listOf(id), 12)).isEmpty()
    assertThat(NoA11yTool.toolEnabled()).isFalse()
  }
}
