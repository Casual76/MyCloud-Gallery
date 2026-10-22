package dev.pampa.mycloudgallery.ai

import dev.pampa.mycloudgallery.core.ai.compute.AiLocalBodies
import dev.pampa.mycloudgallery.core.ai.contract.AiResults
import dev.pampa.mycloudgallery.core.index.ai.AiEntry
import dev.pampa.mycloudgallery.core.index.ai.AiKey
import dev.pampa.mycloudgallery.core.index.ai.AiReplicaSink
import dev.pampa.mycloudgallery.core.index.ai.AiWinner
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.search.index.LocalAiIndex
import dev.pampa.mycloudgallery.core.trust.acceptedOrNull
import dev.pampa.mycloudgallery.core.trust.data.AiBody
import dev.pampa.mycloudgallery.core.trust.data.AiClass
import dev.pampa.mycloudgallery.core.trust.data.AiSegments
import dev.pampa.mycloudgallery.core.trust.data.OcrBody
import dev.pampa.mycloudgallery.core.trust.data.VecBody
import dev.pampa.mycloudgallery.search.OcrLayoutStore
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Gli ADATTATORI fra il calcolo (P5), la replica `ai` (P2) e l'indice locale di ricerca (P6), in :app (Fase 6, P11).

/**
 * I corpi calcolati **da questo dispositivo**, come pubblicati (codificati da `AiSegments`, riletti con i controlli di chi legge): servono
 * a ripubblicare senza ricalcolare (stesso contenuto in area privata e comune, morte fra salvataggio e outbox: `AiLocalBodies`, P5).
 * Un file per (chiave, classe) in `noBackupFilesDir/search/local-bodies`; l'azzeramento lo toglie con l'indice.
 */
class LocalBodyStore(private val dir: File) {
  suspend fun put(key: ContentKey, cls: AiClass, body: AiBody) = withContext(Dispatchers.IO) {
    val bytes = AiSegments.bodyOrNull(body) ?: return@withContext
    val target = fileOf(key, cls)
    val tmp = File(dir, target.name + ".tmp")
    try {
      dir.mkdirs()
      tmp.outputStream().use { out ->
        out.write(bytes)
        out.fd.sync()
      }
      if (!tmp.renameTo(target)) {
        target.delete()
        if (!tmp.renameTo(target)) tmp.delete()
      }
    } catch (e: IOException) {
      tmp.delete()
    }
  }

  suspend fun get(key: ContentKey, cls: AiClass): AiBody? = withContext(Dispatchers.IO) {
    val file = fileOf(key, cls)
    if (!file.isFile || file.length() > MAX_BYTES) return@withContext null
    val bytes = try {
      file.readBytes()
    } catch (e: IOException) {
      return@withContext null
    }
    AiSegments.decodeBody(cls, bytes).acceptedOrNull()
  }

  private fun fileOf(key: ContentKey, cls: AiClass) = File(dir, "${key.hex}.${cls.wire}")

  private companion object {
    const val MAX_BYTES = 128L * 1024
  }
}

/**
 * L'`AiResults` del calcolo (P5) sopra l'indice locale (P6): prima il corpo locale ([LocalBodyStore], per ripubblicare) e le righe del
 * testo per il viewer ([OcrLayoutStore]), poi l'indice, che e' il punto di conferma (`saved`). Una morte in mezzo lascia al piu' un
 * corpo senza indice: il media si ricalcola, mai il contrario (un "salvato" senza corpo non si potrebbe ripubblicare).
 */
class IndexAiResults(
  private val index: LocalAiIndex,
  private val layouts: OcrLayoutStore,
  private val local: LocalBodyStore,
) : AiResults, AiLocalBodies {
  override suspend fun saveVectors(key: ContentKey, body: VecBody) {
    local.put(key, AiClass.VEC, body)
    index.saveVectors(key, body)
  }

  override suspend fun saveText(key: ContentKey, body: OcrBody) {
    local.put(key, AiClass.OCR, body)
    layouts.put(key, body)
    index.saveText(key, body)
  }

  override suspend fun saved(key: ContentKey, recipe: Int): Set<AiClass> = index.saved(key, recipe)

  override suspend fun vectors(key: ContentKey): VecBody? =
    if (AiClass.VEC in index.saved(key, 1)) local.get(key, AiClass.VEC) as? VecBody else null

  override suspend fun text(key: ContentKey): OcrBody? =
    if (AiClass.OCR in index.saved(key, 1)) local.get(key, AiClass.OCR) as? OcrBody else null
}

/**
 * La destinazione della replica `ai` (`AiReplicaSink`, P2) sull'indice locale (P6): i vincitori di una share entrano con la loro
 * origine (la replica toglie solo le sue, mai cio' che e' calcolato qui); il testo porta anche le righe per il testo dal vivo (D42: il
 * testo letto da un altro dispositivo vale anche qui, con la lettura spenta e senza ML Kit).
 */
class IndexReplicaSink(
  private val index: LocalAiIndex,
  private val layouts: OcrLayoutStore,
) : AiReplicaSink {
  override suspend fun apply(share: String, winners: List<AiWinner>) {
    val items = winners.map { w ->
      when (val e = w.entry) {
        is AiEntry.Vector -> LocalAiIndex.ReplicaItem.Vectors(e.contentKey, w.device, e.body)
        is AiEntry.Text -> {
          layouts.put(e.contentKey, e.body)
          LocalAiIndex.ReplicaItem.Text(e.contentKey, w.device, e.body)
        }
      }
    }
    if (items.isNotEmpty()) index.applyReplica(share, items)
  }

  override suspend fun withdraw(share: String, keys: List<AiKey>) {
    if (keys.isNotEmpty()) index.withdraw(share, keys.map { it.contentKey to it.cls })
  }

  override suspend fun forgetDevice(device: String) = index.forgetDevice(device)

  override suspend fun forgetShare(share: String) = index.forgetShare(share)
}
