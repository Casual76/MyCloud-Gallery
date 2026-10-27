package dev.pampa.mycloudgallery.nas

import dev.pampa.mycloudgallery.core.common.files.StateFiles
import dev.pampa.mycloudgallery.core.common.log.GalleryLog
import dev.pampa.mycloudgallery.core.model.AccountId
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeAcceptance
import dev.pampa.mycloudgallery.core.nasmedia.contract.FreezeAcceptances
import dev.pampa.mycloudgallery.core.nasmedia.contract.ScanDiff
import java.io.File
import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dove si ricorda "Ho controllato, continua" (NA-4, `FreezeAcceptances`): un file piccolo in `noBackupFilesDir/replica/` (fuori dal
 * backup, tolto dall'azzeramento con la replica), scritto su un temporaneo e poi rinominato. Una riga per share: account, share e i
 * cinque numeri del gesto (file presenti, spariti, cambiati, non piu' media, ora del NAS del gesto): solo numeri e i due nomi che
 * l'app ha gia' in locale. Mai dati letti dal NAS.
 *
 * Un file illeggibile vale "nessuna franchigia": il giro dopo ricongela con lo stesso numero e l'utente conferma di nuovo. Mai il
 * contrario: una riga che non si capisce non da' sconti.
 */
class FileFreezeAcceptances(private val file: File) : FreezeAcceptances {

  private val gate = Mutex()

  override suspend fun load(account: AccountId, share: String): FreezeAcceptance? = gate.withLock {
    withContext(Dispatchers.IO) { read()[key(account, share)] }
  }

  override suspend fun save(account: AccountId, share: String, acceptance: FreezeAcceptance?) {
    gate.withLock {
      withContext(Dispatchers.IO) {
        val all = read().toMutableMap()
        if (acceptance == null) all.remove(key(account, share)) else all[key(account, share)] = acceptance
        write(all)
      }
    }
  }

  private fun key(account: AccountId, share: String) = "${account.value}\t${share.lowercase(Locale.ROOT)}"

  private fun read(): Map<String, FreezeAcceptance> {
    return try {
      StateFiles.readLines(file)?.mapNotNull(::parse)?.toMap() ?: emptyMap()
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "franchigia salvata non letta" }
      emptyMap()
    }
  }

  private fun parse(line: String): Pair<String, FreezeAcceptance>? {
    val parts = line.split('\t')
    if (parts.size != FIELDS) return null
    val numbers = parts.drop(2).map { it.toLongOrNull() ?: return null }
    if (numbers.any { it < 0 }) return null
    return try {
      val diff = ScanDiff(numbers[0].toInt(), numbers[1].toInt(), numbers[2].toInt(), numbers[3].toInt())
      "${parts[0]}\t${parts[1]}" to FreezeAcceptance(diff, numbers[4])
    } catch (e: IllegalArgumentException) {
      null
    }
  }

  private fun write(all: Map<String, FreezeAcceptance>) {
    try {
      if (all.isEmpty()) {
        // Una franchigia che resta nel file varrebbe ancora: se non si toglie e' un errore, non un "fatto".
        if (!StateFiles.delete(file)) throw IOException("franchigia non tolta")
        return
      }
      StateFiles.writeText(
        file,
        all.entries.joinToString("\n") { (key, a) ->
          "$key\t${a.diff.knownPresent}\t${a.diff.vanished}\t${a.diff.changed}\t${a.diff.notMedia}\t${a.atNasMillis}"
        },
      )
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "franchigia non salvata: ${e.javaClass.simpleName}" }
    }
  }

  private companion object {
    const val TAG = "FreezeAcceptances"
    const val FIELDS = 7
  }
}
