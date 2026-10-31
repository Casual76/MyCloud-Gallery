package dev.pampa.mycloudgallery.core.widget.ledger

import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedger
import dev.pampa.mycloudgallery.core.widget.contract.WidgetLedgerContract
import java.io.File
import java.io.IOException
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Un deposito che puo' "morire" a meta' scrittura: lascia sul disco un temporaneo scritto a meta' (`<file>.tmp`) e non arriva alla rinomina, che e'
 * esattamente cio' che resta dopo la morte del processo fra `fsync` e rinomina (o prima dell'`fsync`).
 */
internal class DyingStorage(private val inner: WidgetLedgerStorage, private val file: File) : WidgetLedgerStorage by inner {
  var dieOnNextWrite: Boolean = false
  var writes: Int = 0
    private set

  override fun write(bytes: ByteArray) {
    if (dieOnNextWrite) {
      dieOnNextWrite = false
      File(file.path + ".tmp").writeBytes(bytes.copyOf(bytes.size / 2))
      throw IOException("morte simulata fra la scrittura del temporaneo e la rinomina")
    }
    inner.write(bytes)
    writes++
  }
}

/** Fase 10 P2: `FileWidgetLedger` passa la suite di contratto del registro, su file veri (cartella temporanea). */
class FileWidgetLedgerContractTest : WidgetLedgerContract() {
  @get:Rule
  val folder = TemporaryFolder()

  override fun rig(): Rig = object : Rig {
    private val dir = folder.newFolder("widget")
    private val file = File(dir, FileLedgerStorage.FILE_NAME)
    private val storage = DyingStorage(FileLedgerStorage(file), file)
    override val ledger: WidgetLedger = FileWidgetLedger(storage)

    override fun reopen(): WidgetLedger = FileWidgetLedger(dir)

    override fun dieDuringNextWrite() {
      storage.dieOnNextWrite = true
    }

    override fun corrupt() {
      val bytes = file.readBytes()
      bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x5A).toByte()
      file.writeBytes(bytes)
    }
  }
}
