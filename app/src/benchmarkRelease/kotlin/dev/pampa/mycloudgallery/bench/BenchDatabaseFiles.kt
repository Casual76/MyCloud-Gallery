package dev.pampa.mycloudgallery.bench

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

/**
 * I tre file del database di un profilo, e chi li puo' toccare (correzione del primo avvio, pacchetto C1).
 *
 * **Il difetto.** La preparazione del dataset gira nello stesso processo dell'app, mentre l'app e' accesa: `GalleryGraph` ha gia' costruito il suo
 * `GalleryDatabase` su [BenchProfile.databaseName] e le sue letture partono subito (la cronologia di Cerca, lo stato dell'indicizzazione). Prima il
 * seminatore cancellava **quel file** e lo riscriveva con una seconda istanza di Room. Su Android la cancellazione di un file aperto riesce: per decine
 * di secondi (i modelli delle miniature) al percorso non c'era niente, e la prima connessione nuova dell'app ne creava uno vuoto, senza tabelle
 * (`no such table: search_history`); poi il seminatore teneva una transazione di scrittura per quasi un minuto e la prima scrittura dell'app scadeva
 * (`database is locked`). In tutti e due i casi il processo cadeva con lo stato fermo a `in_corso`.
 *
 * **La regola.** Un file di database ha un solo padrone per processo:
 *
 * - [BenchProfile.databaseName] e' dell'app. Il seminatore non lo apre e non lo cancella mai;
 * - [buildingName] e' del seminatore: lo crea, lo scrive, lo rilegge e lo chiude. Nessun altro lo apre;
 * - [readyName] e' un database completo e chiuso che aspetta: nasce con una rinomina ([publish]), quindi o c'e' tutto o non c'e'.
 *
 * Lo scambio si fa **alla nascita del processo successivo** ([promoteReady], da `BenchDatasetProvider.onCreate`: prima di `Application.onCreate`, cioe'
 * prima che esista il grafo e che qualcuno possa aprire il file). Il collaudo ferma e riavvia sempre l'app dopo lo stato `pronto` (`BenchSession`).
 */
internal object BenchDatabaseFiles {

  /** Il file che il seminatore sta scrivendo. */
  fun buildingName(profile: BenchProfile): String = profile.databaseName + ".building"

  /** Il database finito che aspetta il prossimo avvio. */
  fun readyName(profile: BenchProfile): String = profile.databaseName + ".ready"

  /** Toglie cio' che resta di una preparazione interrotta. Solo il file del seminatore: mai quello dell'app. */
  fun discardBuilding(context: Context, profile: BenchProfile) {
    context.deleteDatabase(buildingName(profile))
  }

  /**
   * Porta tutto il WAL dentro il file principale: dopo, il database e' il solo file `.building`. Da chiamare sull'ultima istanza aperta, prima di
   * chiuderla e fuori da una transazione.
   */
  fun checkpoint(db: SupportSQLiteDatabase) {
    db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { c ->
      // Una riga: (occupato, pagine nel WAL, pagine portate). Con un journal diverso dal WAL risponde (0, -1, -1).
      check(c.moveToFirst() && c.getInt(0) == 0) { "Il database del dataset non si e' consolidato." }
    }
  }

  /**
   * Il database del seminatore, ormai chiuso, diventa quello "pronto". La rinomina e' l'ultimo passo: un processo fermato a meta' lascia un
   * `.building` (che la prossima preparazione toglie), mai un `.ready` a meta'.
   */
  fun publish(context: Context, profile: BenchProfile) {
    val building = context.getDatabasePath(buildingName(profile))
    check(building.isFile) { "Il database del dataset non c'e'." }
    for (suffix in SIDE_FILES) {
      val side = File(building.path + suffix)
      if (!side.exists()) continue
      // Dopo `checkpoint` e la chiusura, WAL e journal sono vuoti o assenti: se non lo sono c'e' ancora una connessione, e il file non e' completo.
      check(suffix == "-shm" || side.length() == 0L) { "Il database del dataset ha ancora un file $suffix non vuoto." }
      check(side.delete()) { "Il file $suffix del dataset non si toglie." }
    }
    val ready = context.getDatabasePath(readyName(profile))
    check(!ready.exists() || ready.delete()) { "Il dataset pronto di prima non si toglie." }
    check(building.renameTo(ready)) { "Il database del dataset non si rinomina." }
  }

  /** Vero se un dataset finito aspetta ancora di essere scambiato (lo scambio dell'ultimo avvio non e' riuscito). */
  fun hasReady(context: Context, profile: BenchProfile): Boolean = context.getDatabasePath(readyName(profile)).exists()

  /**
   * Mette al posto del database dell'app ogni dataset finito che aspetta. **Solo alla nascita del processo**, prima che l'app apra il suo database:
   * e' l'unico momento in cui il file dell'app non ha connessioni. Senza niente che aspetta sono due `stat`, nient'altro (gli avvii a freddo misurati
   * non pagano di piu'). Vero se ha scambiato almeno un file.
   */
  fun promoteReady(context: Context): Boolean {
    var promoted = false
    for (profile in BenchProfile.entries) {
      try {
        val ready = context.getDatabasePath(readyName(profile))
        if (!ready.exists()) continue
        // Il file dell'app con i suoi `-journal`, `-wal`, `-shm`: senza toglierli, un WAL vecchio verrebbe applicato al database nuovo.
        context.deleteDatabase(profile.databaseName)
        val target = context.getDatabasePath(profile.databaseName)
        if (!target.exists() && ready.renameTo(target)) {
          promoted = true
        } else {
          android.util.Log.w(TAG, "dataset pronto non scambiato (${profile.code})")
        }
      } catch (e: Exception) {
        android.util.Log.w(TAG, "dataset pronto non scambiato (${profile.code}): ${e.javaClass.simpleName}")
      }
    }
    return promoted
  }

  private const val TAG = "BenchDataset"
  private val SIDE_FILES = listOf("-wal", "-shm", "-journal")
}
