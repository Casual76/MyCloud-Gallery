package dev.pampa.mycloudgallery.feature.settings.sharing

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Fase 5B (P12, docs/fase5-progetto.md §1.3 `feature-settings`, §2.7 "Condividere senza posizione", D26, C18, L41): l'impostazione "Condivisioni:
// rimuovi la posizione". Acceso, ogni condivisione verso un'altra app manda una **copia senza posizione**, ricontrollata (la prepara `feature-integrations`,
// P11); spento (di default, come scelto dal piano: «GPS mantenuto per scelta nelle condivisioni, rimovibile da impostazione con indicatore»), il media si
// condivide com'e'. Questa porta e' l'**unica fonte** del valore: la pagina la scrive, `:app` (P14) la legge per dire al viewer "Condividi senza posizione"
// e a `LocationFreeShare.prepare(removeLocation)`. Vive qui, con la sua pagina, e `:app` la prende da Hilt.

/** L'impostazione "Rimuovi la posizione" delle condivisioni. */
interface SharingSettings {

  /** Acceso: ogni condivisione toglie la posizione dal file (copia verificata). Spento di default. */
  val removeLocation: StateFlow<Boolean>

  suspend fun setRemoveLocation(on: Boolean)
}

/** L'impostazione sopra le preferenze dell'app (un file privato, un solo valore): nessun dato personale, nessuna rete. */
class SharedPrefsSharingSettings(context: Context) : SharingSettings {

  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val flow = MutableStateFlow(prefs.getBoolean(KEY_REMOVE_LOCATION, DEFAULT_REMOVE_LOCATION))

  override val removeLocation: StateFlow<Boolean> = flow.asStateFlow()

  override suspend fun setRemoveLocation(on: Boolean) {
    // `apply` scrive in un altro thread: il valore nuovo vale subito per chi legge il flusso.
    prefs.edit().putBoolean(KEY_REMOVE_LOCATION, on).apply()
    flow.value = on
  }

  companion object {
    const val FILE: String = "mcg_sharing"
    const val KEY_REMOVE_LOCATION: String = "remove_location"

    /** Spento: la posizione resta nelle condivisioni finche' l'utente non sceglie altrimenti (C18). */
    const val DEFAULT_REMOVE_LOCATION: Boolean = false
  }
}

/** Il piccolo adattatore che le Impostazioni legano da sole: nessuna riga in `:app`, che puo' iniettare [SharingSettings] dove serve. */
@Module
@InstallIn(SingletonComponent::class)
object SharingSettingsModule {

  @Provides
  @Singleton
  fun sharingSettings(@ApplicationContext context: Context): SharingSettings = SharedPrefsSharingSettings(context)
}
