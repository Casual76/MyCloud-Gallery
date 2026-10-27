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
 *
 * **Una franchigia consumata non torna** (R11 della revisione di Fase 7): se il file non si riscrive o non si toglie, la chiave resta in memoria
 * come "consumata" e vale come tolta per tutto il processo (si riprova a ogni scrittura); il file che non si riesce a cancellare si svuota (un file
 * vuoto non da' nessuna franchigia). Prima l'errore finiva nel log e la riga tornava al riavvio, entro la sua settimana.
 *
 * @param removeFile come si toglie il file quando non resta nessuna franchigia (di norma `StateFiles.delete`; una prova ci mette un file che non va via).
 */
class FileFreezeAcceptances(
  private val file: File,
  private val removeFile: (File) -> Boolean = { StateFiles.delete(it) },
) : FreezeAcceptances {

  private val gate = Mutex()

  /** Franchigie consumate che il file non ha ancora dimenticato: contano come tolte, finche' una scrittura non riesce. */
  private val consumed = HashSet<String>()

  override suspend fun load(account: AccountId, share: String): FreezeAcceptance? = gate.withLock {
    withContext(Dispatchers.IO) {
      // Una consumata che il file non ha ancora dimenticato: si riprova a riscriverlo, e intanto non si da'.
      if (consumed.isNotEmpty()) write(read())
      read()[key(account, share)]
    }
  }

  override suspend fun save(account: AccountId, share: String, acceptance: FreezeAcceptance?) {
    gate.withLock {
      withContext(Dispatchers.IO) {
        val key = key(account, share)
        if (acceptance == null) consumed += key else consumed -= key
        val all = read().toMutableMap()
        if (acceptance == null) all.remove(key) else all[key] = acceptance
        write(all)
      }
    }
  }

  private fun key(account: AccountId, share: String) = "${account.value}\t${share.lowercase(Locale.ROOT)}"

  /** Le franchigie del file, senza quelle gia' consumate in questo processo. */
  private fun read(): Map<String, FreezeAcceptance> {
    val saved = try {
      StateFiles.readLines(file)?.mapNotNull(::parse)?.toMap() ?: emptyMap()
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "franchigia salvata non letta" }
      emptyMap()
    }
    return if (consumed.isEmpty()) saved else saved.filterKeys { it !in consumed }
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

  /** Scrive [all]. Vero se il file dice ormai la stessa cosa; allora le consumate non servono piu' in memoria. */
  private fun write(all: Map<String, FreezeAcceptance>): Boolean {
    val saved = try {
      if (all.isEmpty()) {
        // Una franchigia che resta nel file varrebbe ancora: se non si toglie lo si svuota, e se nemmeno quello riesce non e' un "fatto".
        removeFile(file) || emptied()
      } else {
        StateFiles.writeText(
          file,
          all.entries.joinToString(NEWLINE) { (key, a) ->
            "$key$TAB${a.diff.knownPresent}$TAB${a.diff.vanished}$TAB${a.diff.changed}$TAB${a.diff.notMedia}$TAB${a.atNasMillis}"
          },
        )
        true
      }
    } catch (e: IOException) {
      GalleryLog.w(TAG) { "franchigia non salvata: ${e.javaClass.simpleName}" }
      false
    }
    if (saved) consumed.clear()
    return saved
  }

  /** Il file che non si toglie si riscrive vuoto: nessuna riga, nessuna franchigia. */
  private fun emptied(): Boolean = try {
    StateFiles.writeText(file, "")
    true
  } catch (e: IOException) {
    false
  }

  private companion object {
    const val TAG = "FreezeAcceptances"
    const val FIELDS = 7
    const val NEWLINE = "\n"
    const val TAB = "\t"
  }
}
