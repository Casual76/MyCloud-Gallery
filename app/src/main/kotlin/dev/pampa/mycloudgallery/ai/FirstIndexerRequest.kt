package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Segno nel profilo locale: l'azzeramento cancella anche la cartella dei modelli. */
internal class FirstIndexerRequest(private val file: File, private val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO) {
  suspend fun done(): Boolean = withContext(io) { file.isFile }
  suspend fun mark() = withContext(io) { StateFiles.writeText(file, "1", Charsets.US_ASCII) }
}
