package dev.pampa.mycloudgallery.feature.integrations.provider

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Random

// IN-2 della verifica funzionale 5B (docs/fase5-progetto.md §2.7, D23, C17): la chiave locale da cui si derivano gli id stabili della radice e delle
// cartelle del provider dei documenti ([DocumentIdTable]). Resta sul telefono, in `noBackupFilesDir` (fuori dal backup di Android), e non esce mai:
// serve solo a far uguali, a ogni avvio del processo, gli id che DocumentsUI tiene nella sua cache. Ruota all'azzeramento dell'app.

/** La chiave degli id stabili. */
interface DocumentIdKey {

  /** I 32 byte della chiave: gli stessi finche' non si chiama [rotate]. */
  fun bytes(): ByteArray

  /** Una chiave nuova: tutti gli id derivati cambiano (azzeramento dell'app, famiglia che cambia). */
  fun rotate()
}

/** Una chiave in memoria: gli id valgono per questo oggetto (i test, le varianti senza archivio). */
class InMemoryDocumentIdKey(private val random: Random = SecureRandom()) : DocumentIdKey {
  private var current: ByteArray = fresh(random)

  @Synchronized
  override fun bytes(): ByteArray = current.copyOf()

  @Synchronized
  override fun rotate() {
    current = fresh(random)
  }

  companion object {
    fun fresh(random: Random): ByteArray = ByteArray(KEY_BYTES).also { random.nextBytes(it) }

    const val KEY_BYTES: Int = 32
  }
}

/**
 * La chiave su file ([file], di norma `noBackupFilesDir/integrations/doc-id.key`): si legge una volta, e se non c'e' (o non e' di 32 byte) se ne
 * crea una nuova e si scrive (prima in un temporaneo, poi si rinomina). Se il file non si puo' leggere ne' scrivere la chiave vale per questo
 * processo e basta: gli id cambiano a ogni avvio, come prima, ma niente si rompe. Un errore non esce mai.
 */
class FileDocumentIdKey(private val file: File, private val random: Random = SecureRandom()) : DocumentIdKey {

  private var current: ByteArray? = null

  @Synchronized
  override fun bytes(): ByteArray {
    current?.let { return it.copyOf() }
    val loaded = read() ?: InMemoryDocumentIdKey.fresh(random).also { write(it) }
    current = loaded
    return loaded.copyOf()
  }

  @Synchronized
  override fun rotate() {
    val fresh = InMemoryDocumentIdKey.fresh(random)
    current = fresh
    write(fresh)
  }

  private fun read(): ByteArray? = try {
    if (file.isFile && file.length() == InMemoryDocumentIdKey.KEY_BYTES.toLong()) StateFiles.readBytes(file) else null
  } catch (_: IOException) {
    null
  } catch (_: SecurityException) {
    null
  }

  private fun write(bytes: ByteArray) {
    try {
      StateFiles.writeBytes(file, bytes)
    } catch (_: IOException) {
      // Resta in memoria: gli id valgono per questo processo.
    } catch (_: SecurityException) {
      // idem
    }
  }
}
