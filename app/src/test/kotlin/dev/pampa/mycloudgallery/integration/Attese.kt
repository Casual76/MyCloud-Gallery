package dev.pampa.mycloudgallery.integration

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/**
 * Le attese dei test che girano sul **grafo vero** (`GalleryGraph`, `BackupGraph`, `NasGraph`...), in un posto solo.
 *
 * **Perche' qui si aspetta davvero, e non si porta avanti un orologio.** Il grafo vero gira su `Dispatchers.Default` e `Dispatchers.IO` (la
 * produzione li nomina: `BackupGraph` ricalcola lo stato su `Dispatchers.Default`, le letture dei file e delle query passano da
 * `withContext(Dispatchers.IO)`), parla con Room, DataStore e il disco, e fra un ricalcolo e l'altro ha un'attesa a orologio vero
 * (`BackupGraph.REFRESH_GAP`, un `delay` interno). Nessun `TestDispatcher` e nessun `advanceUntilIdle` puo' pilotare niente di tutto questo
 * senza toccare la produzione: l'attesa su tempo reale e' inevitabile. Quello che si puo' (e si deve) fare e' altro:
 *
 *  1. aspettare **l'evento** che dice che l'effetto c'e' (un flusso di stato, un `CompletableDeferred`, un contatore), mai un tempo fisso;
 *  2. non leggere in ciclo, dal test, i file che la produzione cancella o rinomina. Su Windows `File.delete()` e `renameTo()` falliscono, in
 *     silenzio, se un altro thread ha il file aperto (`java.io` non lo apre con `FILE_SHARE_DELETE`; su Android e su Linux la cancellazione
 *     riesce comunque): un test che legge `memory.lastProblem()` ogni 50 ms blocca da solo la cancellazione che sta aspettando, e piu' il PC e'
 *     carico piu' a lungo il file resta aperto (vedi `VerificaFunzionaleBackupAppTest`, BK-3);
 *  3. non provare un'assenza con una `delay` fissa quando un evento in volo (il catalogo, le copie simili) puo' arrivare tardi e cambiare lo stato
 *     in modo legittimo.
 *
 * **Il tetto non misura la velocita'**: e' la rete che trasforma un blocco in un fallimento invece che in un test appeso. Il lavoro e' sempre lo
 * stesso, ma con piu' build Gradle sul PC (un agente compila mentre un altro prova) diventa anche 10 volte piu' lento: 20 secondi, che da soli
 * bastano con grande margine, non bastano piu'. Per questo e' largo e uguale per tutti i test; un test che si blocca davvero fallisce comunque,
 * dopo [TETTO] invece che dopo 20 secondi.
 */
internal object Attese {

  /** Il tetto di ogni attesa su un evento del grafo vero. */
  val TETTO: Duration = 2.minutes

  /** [TETTO] in millisecondi, per chi lo prende come `Long`. */
  val TETTO_MS: Long = TETTO.inWholeMilliseconds

  /** Ogni quanto si ricontrolla una condizione che non ha un flusso da seguire (un contatore, un elenco registrato). */
  const val SONDA_MS: Long = 20L
}

/**
 * Aspetta che [condition] diventi vera, riguardandola ogni [Attese.SONDA_MS] ms. Per le condizioni che non hanno un flusso da seguire; **mai** su uno
 * stato che la produzione scrive su file (vedi [Attese]). Se il tetto scade il fallimento dice cosa si aspettava.
 */
internal suspend fun until(what: String, condition: () -> Boolean) {
  try {
    withTimeout(Attese.TETTO) { while (!condition()) delay(Attese.SONDA_MS) }
  } catch (e: TimeoutCancellationException) {
    throw AssertionError("Dopo ${Attese.TETTO} non e' successo: $what", e)
  }
}
