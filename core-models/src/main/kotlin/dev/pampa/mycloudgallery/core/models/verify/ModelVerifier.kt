package dev.pampa.mycloudgallery.core.models.verify

import dev.pampa.mycloudgallery.core.models.contract.FileStatReader
import dev.pampa.mycloudgallery.core.models.contract.ModelSpec
import dev.pampa.mycloudgallery.core.models.contract.ModelState
import dev.pampa.mycloudgallery.core.models.contract.ModelFailCode
import dev.pampa.mycloudgallery.core.models.contract.VerifiedModel
import dev.pampa.mycloudgallery.core.models.download.FileDigest
import dev.pampa.mycloudgallery.core.models.store.ModelStore
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException

// La VERIFICA (docs/fase6-progetto.md §2.1, D16, E-A2, E-A11): dimensione ESATTA e SHA-256 di un modello, contro il catalogo
// fissato nell'app. Nessuna sorgente, nessuna intestazione, nessuna nota del NAS puo' dire "e' a posto": solo i byte letti qui.
//
// **Sullo stesso descrittore.** Il file si apre UNA volta, in sola lettura; dimensione (`fstat` sul descrittore) e SHA-256 si
// calcolano su quel descrittore, che poi si consegna al chiamante riportato all'inizio: un file sostituito dopo la verifica (una
// rinomina sul percorso) non e' piu' quello che il motore legge. Il descrittore resta aperto finche' il modello non si chiude.
//
// Chiamare solo con la cartella bloccata (`ModelStore.lock`) e fuori dal thread principale: per 370 MiB sono un paio di secondi.

internal class ModelVerifier(private val store: ModelStore, private val statReader: FileStatReader = FileStatReader.None) {

  sealed interface Opened {
    /** Dimensione e impronta tornano: il modello e' pronto, con il suo descrittore. */
    class Verified(val model: VerifiedModel) : Opened

    /** Il file non c'e'. */
    data object Missing : Opened

    /** C'era ma non torna (dimensione o impronta): e' stato tolto, insieme ai programmi compilati (E-A11). */
    data object Rejected : Opened

    /** Un errore di lettura del disco: il file non si e' toccato (potrebbe essere passeggero). */
    data object Unreadable : Opened
  }

  /**
   * Apre e verifica `models/<sha256>`. Aggiorna lo stato (`Verifying`, poi `Ready`, o `Failed(VERIFY_FAILED)` se rifiutato). Il file
   * rifiutato si toglie subito, cosi' non puo' essere caricato per sbaglio da nessun altro percorso.
   */
  suspend fun openVerified(spec: ModelSpec): Opened {
    val file = store.finalFile(spec)
    val before = store.stateOf(spec.id)
    if (!file.isFile) {
      if (before is ModelState.Ready || before == ModelState.Verifying) store.set(spec.id, ModelState.Absent)
      return Opened.Missing
    }
    val stream = try {
      FileInputStream(file)
    } catch (e: IOException) {
      return Opened.Unreadable
    }
    var handedOver = false
    var verdict: Opened? = null
    try {
      store.set(spec.id, ModelState.Verifying)
      val channel = stream.channel
      val hashOk = channel.size() == spec.sizeBytes && FileDigest.sha256(channel, spec.sizeBytes) == spec.sha256
      if (hashOk) {
        channel.position(0)
        // D16, I1: il file verificato e' in sola lettura (anche quelli promossi da una versione precedente). Il cambio di permessi cambia la
        // `ctime`, quindi si fa PRIMA di prendere lo `stat` con cui `PATH_CHECKED` confrontera' il percorso.
        if (file.canWrite()) file.setReadOnly()
        val stat = try {
          statReader.stat(stream.fd)
        } catch (e: RuntimeException) {
          null
        }
        val now = store.nanos()
        store.set(spec.id, ModelState.Ready(spec.sizeBytes, store.readSource(spec), now))
        val model = VerifiedModel(spec, file, now, onClose = { stream.close() }, descriptor = stream.fd, channel = channel, stat = stat)
        handedOver = true
        verdict = Opened.Verified(model)
      }
    } catch (c: CancellationException) {
      store.set(spec.id, before)
      throw c
    } catch (e: IOException) {
      store.set(spec.id, before)
      return Opened.Unreadable
    } finally {
      if (!handedOver) stream.close()
    }
    // Non torna: il flusso e' chiuso (su Windows un file aperto non si toglie) e il file sparisce subito.
    return verdict ?: run {
      reject(spec)
      Opened.Rejected
    }
  }

  /** True se [file] (un parziale completo) ha la dimensione e l'impronta del catalogo. */
  suspend fun matches(spec: ModelSpec, file: File): Boolean {
    if (!file.isFile || file.length() != spec.sizeBytes) return false
    return try {
      FileInputStream(file).use { FileDigest.sha256(it.channel, spec.sizeBytes) == spec.sha256 }
    } catch (e: IOException) {
      false
    }
  }

  /** Il file non torna: si toglie con tutto cio' che ne dipende e si conta. Il resto (rimetterlo in coda) lo fa il prossimo `ensure`. */
  private fun reject(spec: ModelSpec) {
    store.deleteVerified(spec)
    store.counters.verifyFailed()
    store.set(spec.id, ModelState.Failed(ModelFailCode.VERIFY_FAILED))
  }
}
