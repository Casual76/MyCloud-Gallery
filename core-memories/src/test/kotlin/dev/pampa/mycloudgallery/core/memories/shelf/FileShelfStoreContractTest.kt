package dev.pampa.mycloudgallery.core.memories.shelf

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.memories.contract.MemoryShelfStore
import dev.pampa.mycloudgallery.core.memories.contract.ShelfStoreContract
import java.io.File
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * La suite di contratto del deposito dello scaffale (P0: D9, D10, §2.3, §4.3 "Stabilita'") su **file veri** di una cartella temporanea:
 * scrittura atomica con il disco vero, la morte del processo a meta' scrittura (i resti di un temporaneo a meta'), il file rovinato.
 */
class FileShelfStoreContractTest : ShelfStoreContract() {

  @get:Rule
  val temp = TemporaryFolder()

  override fun rig(): Rig = object : Rig {
    private val directory = File(temp.root, "memories")
    private val io = FaultyIo(StateFilesIo)
    override val store: MemoryShelfStore = FileShelfStore(directory, io, Dispatchers.Unconfined)

    override fun dieDuringNextWrite() {
      io.dieAt = DeathPoint.PARTIAL_TEMP
    }

    override fun corrupt() {
      val file = File(directory, FileShelfStore.FILE_NAME)
      val bytes = file.readBytes()
      bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5a).toByte()
      file.writeBytes(bytes)
    }
  }

  @Test
  fun ilControlloDellaCartellaDiProva() {
    // Controllo positivo: il rig lavora davvero su un file (se non lo facesse, la suite sopra passerebbe a vuoto).
    val store = rig().store
    kotlinx.coroutines.runBlocking { store.write(richShelf()) }
    assertThat(File(temp.root, "memories/${FileShelfStore.FILE_NAME}").isFile).isTrue()
  }
}
