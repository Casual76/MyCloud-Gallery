package dev.pampa.mycloudgallery.core.library.contract

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.library.noop.NoMediaDragHandler
import dev.pampa.mycloudgallery.core.model.MediaId
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/** Fase 10 (P0, D20, C436): i tipi del trascinamento, i flag ammessi (sola lettura, mai persistenti) e il null-object che rifiuta. */
class DragContractTypesTest {

  private val id = MediaId(UUID(1L, 2L).toString())

  @Test
  fun laRichiesta() {
    assertThrows(IllegalArgumentException::class.java) { DragRequest(DragOrigin.LIBRARY, emptyList(), false) }
    assertThrows(IllegalArgumentException::class.java) { DragRequest(DragOrigin.LIBRARY, listOf(id, id), false) }
    val request = DragRequest(DragOrigin.VIEWER, listOf(id), removeLocation = true)
    assertThat(request.toString()).doesNotContain(id.value)
    assertThat(DragOrigin.entries.map { it.code }).doesNotContain("hidden")
  }

  @Test
  fun soloLetturaSulSingoloUri() {
    assertThat(DragFlags.ALLOWED).isEqualTo(256 or 1)
    assertThat(DragFlags.isAllowed(DragFlags.ALLOWED)).isTrue()
    for (extra in listOf(2, 64, 128)) assertThat(DragFlags.isAllowed(DragFlags.ALLOWED or extra)).isFalse()
    assertThat(DragFlags.isAllowed(256)).isFalse()
    assertThat(DragLimits.MAX_ITEMS).isEqualTo(100)
    assertThat(DragLimits.MAX_BYTES).isEqualTo(2L shl 30)
  }

  @Test
  fun ilNullObjectRifiuta() = runTest {
    assertThat(NoMediaDragHandler.prepare(DragRequest(DragOrigin.ALBUM, listOf(id), false))).isEqualTo(DragPrepared.Refused(DragRefusal.UNAVAILABLE))
  }
}
