package dev.pampa.mycloudgallery.core.nas.ops

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.NasStamp
import dev.pampa.mycloudgallery.core.nas.Harness
import dev.pampa.mycloudgallery.core.nas.SHARE
import dev.pampa.mycloudgallery.core.nas.contract.NasAttributes
import dev.pampa.mycloudgallery.core.nas.contract.Purpose
import dev.pampa.mycloudgallery.core.nas.contract.okOrNull
import dev.pampa.mycloudgallery.core.nas.testing.NasContent
import dev.pampa.mycloudgallery.core.nas.virtualClock
import dev.pampa.mycloudgallery.core.trust.codec.Base32
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * R3 della revisione 4B sulle primitive SMB vere (`SmbNasFileOps`) e il simulatore Samba: la cancellazione del proprio cestino controlla
 * anche data e `fileId` del diario sullo stesso handle, non cancella e non toglie mai un punto di rianalisi, e dice il genitore **sul
 * disco** di una cartella (la voce `..`: per un collegamento, quello della destinazione).
 */
class SmbNasFileOpsReview4bTest {

  private val me = Base32.encode(ByteArray(16) { 0x11 })
  private val purpose = Purpose.BACKGROUND

  private fun TestScope.harness(): Harness = Harness(backgroundScope, virtualClock())

  private fun Harness.ops(): NasFileOps = NasFileOpsFactory.create(manager, me)

  private fun id(n: Int) = Base32.encode(ByteArray(16) { n.toByte() })

  @Test
  fun r3_conIlTimbroDelDiarioUnFileDiversoDellaStessaDimensioneNonSiCancella() = runTest {
    val h = harness()
    val ops = h.ops()
    val inTrash = NasLayout.trashItemFile(me, id(3))
    val fileId = h.backend.putContent(SHARE, inTrash.value, NasContent.of(byteArrayOf(1, 2, 3)), lastWriteMs = 1_000)
    // Stessa dimensione, data diversa (modificato dal PC): resta.
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 3, purpose, NasStamp(3, 999, fileId)).okOrNull()).isEqualTo(DeleteOutcome.SizeChanged(3))
    // Stessa dimensione e data, un'altra identita' (un altro file messo al suo posto): resta.
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 3, purpose, NasStamp(3, 1_000, fileId + 1)).okOrNull()).isEqualTo(DeleteOutcome.SizeChanged(3))
    assertThat(h.backend.exists(SHARE, inTrash.value)).isTrue()
    // Il file del diario: si cancella.
    assertThat(ops.deleteIfUnchanged(SHARE, inTrash, 3, purpose, NasStamp(3, 1_000, fileId)).okOrNull()).isEqualTo(DeleteOutcome.Deleted)
    assertThat(h.backend.exists(SHARE, inTrash.value)).isFalse()
  }

  @Test
  fun r3_unPuntoDiRianalisiNelCestinoNonSiCancellaENonSiToglie() = runTest {
    val h = harness()
    val ops = h.ops()
    val root = NasLayout.trashItemFile(me, id(4))
    h.backend.putContent(SHARE, root.child("collegato.jpg").value, NasContent.of(byteArrayOf(1, 2)), attributes = NasAttributes.REPARSE_POINT)
    assertThat(ops.deleteIfUnchanged(SHARE, root.child("collegato.jpg"), 2, purpose).okOrNull()).isEqualTo(DeleteOutcome.NotAFile)
    h.backend.put(SHARE, "Foto/Album/nonna.jpg", byteArrayOf(9))
    h.backend.putLink(SHARE, root.child("Album").value, "Foto/Album")
    assertThat(ops.rmdirIfEmpty(SHARE, root.child("Album"), purpose).okOrNull()).isEqualTo(RmdirOutcome.NotEmpty)
    assertThat(h.backend.exists(SHARE, root.child("Album").value)).isTrue()
    assertThat(h.backend.exists(SHARE, "Foto/Album/nonna.jpg")).isTrue()
  }

  @Test
  fun r3_ilGenitoreSulDiscoDiUnaCartellaEDiUnCollegamento() = runTest {
    val h = harness()
    val ops = h.ops()
    val root = NasLayout.trashItemFile(me, id(5))
    h.backend.put(SHARE, root.child("Mare").child("1.jpg").value, byteArrayOf(1))
    h.backend.put(SHARE, "Foto/Album/nonna.jpg", byteArrayOf(9))
    h.backend.putLink(SHARE, root.child("Album").value, "Foto/Album")
    // Una cartella vera: il genitore e' la cartella cestinata.
    assertThat(ops.parentIdentity(SHARE, root.child("Mare"), purpose).okOrNull()).isEqualTo(h.backend.fileId(SHARE, root.value))
    // Un collegamento: il genitore sul disco e' quello della destinazione (Foto), non la cartella cestinata.
    assertThat(ops.parentIdentity(SHARE, root.child("Album"), purpose).okOrNull()).isEqualTo(h.backend.fileId(SHARE, "Foto"))
  }
}
