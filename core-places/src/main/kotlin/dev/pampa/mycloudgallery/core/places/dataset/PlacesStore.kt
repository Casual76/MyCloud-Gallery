package dev.pampa.mycloudgallery.core.places.dataset

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** L'impronta fissata nel codice del dataset che l'app deve avere: [PlacesDatasetPin] la rende a [PlacesStore]. */
internal class PlacesPin(val version: Int, val sha256: String, val sizeBytes: Long)

/**
 * Estrae il dataset dall'APK nella cartella dell'app e lo mappa in memoria (D8).
 *
 * - La prima volta copia l'asset in `<dir>/places-<versione>.mcgp1` **calcolando lo SHA-256 mentre copia**: se non e'
 *   quello fissato nel codice il file non viene tenuto (un asset diverso da quello che il codice si aspetta non deve
 *   mai dare luoghi).
 * - Le volte dopo non rilegge l'APK: controlla la dimensione, la struttura e il CRC-32 del file gia' estratto e, se
 *   qualcosa non torna (disco danneggiato, file troncato), lo butta e lo estrae di nuovo.
 * - La scrittura e' atomica (file temporaneo e poi rinomina): non esiste mai un file a meta'.
 * - Le versioni vecchie nella stessa cartella vengono cancellate.
 *
 * Bloccante (legge e scrive file): da chiamare fuori dal thread principale. Nessun log, nessun percorso nei messaggi.
 */
internal object PlacesStore {

  fun open(pin: PlacesPin?, openAsset: () -> InputStream?, dir: File): PlacesOpenResult {
    if (pin == null) return PlacesOpenResult.NotBundled
    return try {
      if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return PlacesOpenResult.Failed(PlacesOpenResult.Reason.IO_ERROR)
      val target = File(dir, fileName(pin.version))
      mapIfValid(target, pin)?.let { return PlacesOpenResult.Opened(PlacesData(it)) }

      val input = openAsset() ?: return PlacesOpenResult.Failed(PlacesOpenResult.Reason.ASSET_MISSING)
      // Un nome unico per estrazione: due processi dell'app (l'interfaccia e un lavoro in background) non si pestano.
      val temp = File.createTempFile("places-", ".tmp", dir)
      val digest = MessageDigest.getInstance("SHA-256")
      var written = 0L
      input.use { source ->
        Files.newOutputStream(temp.toPath()).use { out ->
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val n = source.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            out.write(buffer, 0, n)
            written += n
          }
        }
      }
      if (written != pin.sizeBytes || !Hex.of(digest.digest()).equals(pin.sha256, ignoreCase = true)) {
        temp.delete()
        return PlacesOpenResult.Failed(PlacesOpenResult.Reason.HASH_MISMATCH)
      }
      try {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
      } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
      }
      deleteOtherVersions(dir, target)
      deleteStaleTemporaries(dir)
      val mapped = mapIfValid(target, pin) ?: return PlacesOpenResult.Failed(PlacesOpenResult.Reason.INVALID_FORMAT)
      PlacesOpenResult.Opened(PlacesData(mapped))
    } catch (_: IOException) {
      PlacesOpenResult.Failed(PlacesOpenResult.Reason.IO_ERROR)
    }
  }

  fun fileName(version: Int): String = "places-$version.mcgp1"

  /** Il file mappato se esiste, ha la dimensione fissata ed e' un MCGP1 integro di questa versione; altrimenti lo cancella. */
  private fun mapIfValid(file: File, pin: PlacesPin): Mcgp1? {
    if (!file.isFile) return null
    if (file.length() != pin.sizeBytes) {
      file.delete()
      return null
    }
    val buffer = FileChannel.open(file.toPath(), StandardOpenOption.READ).use { channel ->
      channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
    }
    val opened: Mcgp1? = try {
      Mcgp1.open(buffer, verifyChecksum = true).takeIf { it.version == pin.version }
    } catch (_: Mcgp1FormatException) {
      null
    }
    // Danneggiato: si butta (su Windows un file mappato non si cancella finche' il buffer e' vivo: pazienza, si riscrive).
    if (opened == null) file.delete()
    return opened
  }

  /** I temporanei di estrazioni interrotte (piu' vecchi di un'ora: un'estrazione in corso non si tocca). */
  private fun deleteStaleTemporaries(dir: File) {
    val limit = System.currentTimeMillis() - 60 * 60 * 1000L
    dir.listFiles { f -> f.isFile && f.name.startsWith("places-") && f.name.endsWith(".tmp") && f.lastModified() < limit }
      ?.forEach { it.delete() }
  }

  private fun deleteOtherVersions(dir: File, keep: File) {
    dir.listFiles { f -> f.isFile && f.name.startsWith("places-") && f.name.endsWith(".mcgp1") && f != keep }
      ?.forEach { it.delete() }
  }
}
