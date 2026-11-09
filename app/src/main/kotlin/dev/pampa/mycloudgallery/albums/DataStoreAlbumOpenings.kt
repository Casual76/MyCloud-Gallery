package dev.pampa.mycloudgallery.albums

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.pampa.mycloudgallery.feature.albums.contract.AlbumOpenings
import dev.pampa.mycloudgallery.feature.albums.contract.InMemoryAlbumOpenings
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * L'ultima apertura delle voci della scheda Album (fase grafica, G2b) in un DataStore di `noBackupFilesDir/datastore` (`album_openings`): un dato
 * **locale del profilo**, come le preferenze della Libreria. Non si sincronizza: l'ordine "ultima apertura" e' di chi tiene il telefono in mano, e
 * mettere un campo nuovo nei dati personali sincronizzati costerebbe un formato in piu' da tenere compatibile (decisioni.md, "G2b").
 *
 * Una sola stringa, una riga per voce (`chiave<TAB>numero`): le chiavi sono quelle di `AlbumsTabKeys` (un id d'album o il codice di una raccolta),
 * mai un titolo. Al piu' [InMemoryAlbumOpenings.DEFAULT_LIMIT] voci. Un file illeggibile vale "nessuna apertura".
 */
class DataStoreAlbumOpenings(private val store: DataStore<Preferences>, scope: CoroutineScope) : AlbumOpenings {

  override val order: StateFlow<Map<String, Long>?> = store.data
    .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
    .map { prefs -> decode(prefs[KEY]) }
    .stateIn(scope, SharingStarted.Eagerly, null)

  override suspend fun opened(key: String) {
    if (key.isEmpty() || key.any { it == SEPARATOR || it == '\n' }) return
    try {
      store.edit { prefs -> prefs[KEY] = encode(InMemoryAlbumOpenings.next(decode(prefs[KEY]), key)) }
    } catch (e: CancellationException) {
      throw e
    } catch (_: IOException) {
      // Non ricordare un'apertura costa solo l'ordine: la scheda resta usabile.
    }
  }

  companion object {
    /** Il nome del file del DataStore. */
    const val FILE_NAME: String = "album_openings"

    private val KEY = stringPreferencesKey("openings")
    private const val SEPARATOR = '\t'

    fun decode(text: String?): Map<String, Long> {
      if (text.isNullOrEmpty()) return emptyMap()
      val out = HashMap<String, Long>()
      for (line in text.lineSequence()) {
        val key = line.substringBefore(SEPARATOR, "")
        val seq = line.substringAfter(SEPARATOR, "").toLongOrNull()
        if (key.isNotEmpty() && seq != null) out[key] = seq
      }
      return out
    }

    fun encode(order: Map<String, Long>): String = order.entries.joinToString("\n") { (key, seq) -> "$key$SEPARATOR$seq" }
  }
}
