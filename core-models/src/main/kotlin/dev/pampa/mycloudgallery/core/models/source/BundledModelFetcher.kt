package dev.pampa.mycloudgallery.core.models.source

import dev.pampa.mycloudgallery.core.models.contract.BundledAssets
import dev.pampa.mycloudgallery.core.models.contract.FetchRefusal
import dev.pampa.mycloudgallery.core.models.contract.FetchResult
import dev.pampa.mycloudgallery.core.models.contract.ModelFetcher
import dev.pampa.mycloudgallery.core.models.contract.ModelLimits
import dev.pampa.mycloudgallery.core.models.contract.ModelSource
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.download.PartialFile
import java.io.File
import java.io.IOException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Il modello che viaggia DENTRO L'APK (il ritaglio, risposta Q11 (a), D44): si estrae da `assets/cutout/` nel parziale e poi segue la
// strada di tutti gli altri (impronta verificata dal gestore, rinomina in `models/<sha256>`, verifica a ogni apertura). Sempre da
// zero (6 MB): nessuna ripresa. Mai piu' byte della dimensione fissata.

class BundledModelFetcher(
  private val assets: BundledAssets,
  private val io: CoroutineDispatcher = Dispatchers.IO,
) : ModelFetcher {

  override suspend fun fetch(spec: ModelSpec, source: ModelSource, partial: File, budget: Duration): FetchResult =
    withContext(io) {
      val bundled = source as? ModelSource.Bundled ?: return@withContext FetchResult.Refused(FetchRefusal.SOURCE_KIND)
      // L'asset e' quello che il catalogo dice, non uno scelto da chi chiama.
      if (bundled.asset != spec.bundledAsset) return@withContext FetchResult.Refused(FetchRefusal.SOURCE_KIND)
      val input = try {
        assets.open(bundled.asset)
      } catch (e: IOException) {
        null
      } ?: return@withContext FetchResult.NotFound
      input.use { stream ->
        val out = try {
          PartialFile.open(partial, 0)
        } catch (e: IOException) {
          return@withContext if (PartialFile.isNoSpace(e)) FetchResult.NoSpace else FetchResult.NetworkError
        }
        out.use {
          val buffer = ByteArray(ModelLimits.BLOCK_BYTES)
          var filled = 0
          try {
            while (true) {
              val n = stream.read(buffer, filled, buffer.size - filled)
              if (n < 0) break
              filled += n
              if (filled == buffer.size) {
                // Un asset piu' grande della dimensione fissata non e' il modello: si ferma senza scrivere oltre.
                if (out.length + filled > spec.sizeBytes) return@withContext FetchResult.Mismatch
                out.append(buffer, filled)
                filled = 0
              }
            }
            if (out.length + filled > spec.sizeBytes) return@withContext FetchResult.Mismatch
            if (filled > 0) out.append(buffer, filled)
          } catch (e: IOException) {
            return@withContext if (PartialFile.isNoSpace(e)) FetchResult.NoSpace else FetchResult.NetworkError
          }
          if (out.length == spec.sizeBytes) FetchResult.Done else FetchResult.Mismatch
        }
      }
    }
}
