package dev.pampa.mycloudgallery.feature.settings.widget

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

// LE SCELTE DEL WIDGET (Fase 10, P3, docs/fase10-progetto.md D3, D6, D11, §1.4 `WidgetPrefs`; risposte Q1 (a), Q2 (a), Q3 (c) del 31/10): Impostazioni -> Widget. **Due scelte
// sole**: che cosa mostra (Q1: di base il ricordo del giorno quando c'e', altrimenti foto a caso) e ogni quanto cambia (Q2: di base circa ogni 3 ore, mai di notte). **Nessuna
// scelta per il blocco dell'app** (Q3 (c): con il blocco acceso il widget mostra solo foto che sono anche sul telefono e nessun titolo, senza opzioni) **e nessuna per la schermata
// di blocco** (Q4 (a): mai). Le scelte valgono per **questo dispositivo e questo profilo** e non si sincronizzano (D11, C19): ogni profilo del tablet ha le sue.
//
// Come `SharingSettings`, vivono qui con la loro pagina e `:app` le prende da Hilt. I tipi sono **delle Impostazioni** (feature-settings non vede `core-widget`: Phase10ArchitectureTest):
// i **codici** sono gli stessi di `WidgetChoice` e `WidgetPeriod`, e `WidgetSettingsCodesTest` lo prova contro `core-widget`. `:app` (P13, `WidgetGraph`) ne fa il `WidgetPrefs` del
// pianificatore e, quando un valore cambia, dice al `WidgetRefresher` (`shelfChanged()` per il contenuto, `reschedulePeriodic()` per la frequenza).

/** Che cosa mostra il widget (Q1). Il [code] e' quello di `WidgetChoice` (core-widget). */
enum class WidgetContent(val code: String) {
  /** Di serie (Q1 (a)): il ricordo di oggi se c'e', altrimenti foto a caso. */
  MEMORY_THEN_PHOTOS("memory_then_photos"),

  /** Solo foto a caso, anche nei giorni con un ricordo. */
  PHOTOS_ONLY("photos_only"),

  /** Solo il ricordo di oggi; senza ricordo il widget invita ad aprire l'app. */
  MEMORIES_ONLY("memories_only"),
  ;

  companion object {
    val DEFAULT: WidgetContent = MEMORY_THEN_PHOTOS

    /** Il valore del [code], o quello di serie se non si riconosce (un valore rovinato non rompe il widget). */
    fun ofCode(code: String?): WidgetContent = entries.firstOrNull { it.code == code } ?: DEFAULT
  }
}

/** Ogni quanto cambia il widget (Q2). Il [code] e le [hours] sono quelli di `WidgetPeriod` (core-widget). Mai fra le 23 e le 7. */
enum class WidgetRefresh(val code: String, val hours: Int) {
  /** Di serie (Q2 (a)): circa ogni 3 ore. */
  HOURS_3("hours_3", 3),
  HOURS_6("hours_6", 6),
  DAILY("daily", 24),
  ;

  companion object {
    val DEFAULT: WidgetRefresh = HOURS_3

    fun ofCode(code: String?): WidgetRefresh = entries.firstOrNull { it.code == code } ?: DEFAULT
  }
}

/** Le scelte del widget su questo dispositivo e profilo. */
interface WidgetSettings {
  val content: StateFlow<WidgetContent>
  val period: StateFlow<WidgetRefresh>

  suspend fun setContent(content: WidgetContent)

  suspend fun setPeriod(period: WidgetRefresh)
}

/** Le scelte sopra le preferenze dell'app (un file privato, due valori): nessun dato personale, nessuna rete, mai sincronizzate. */
class SharedPrefsWidgetSettings(context: Context) : WidgetSettings {

  private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
  private val contentFlow = MutableStateFlow(WidgetContent.ofCode(prefs.getString(KEY_CONTENT, null)))
  private val periodFlow = MutableStateFlow(WidgetRefresh.ofCode(prefs.getString(KEY_PERIOD, null)))

  override val content: StateFlow<WidgetContent> = contentFlow.asStateFlow()
  override val period: StateFlow<WidgetRefresh> = periodFlow.asStateFlow()

  override suspend fun setContent(content: WidgetContent) {
    // `apply` scrive in un altro thread: il valore nuovo vale subito per chi legge il flusso.
    prefs.edit().putString(KEY_CONTENT, content.code).apply()
    contentFlow.value = content
  }

  override suspend fun setPeriod(period: WidgetRefresh) {
    prefs.edit().putString(KEY_PERIOD, period.code).apply()
    periodFlow.value = period
  }

  companion object {
    const val FILE: String = "mcg_widget"
    const val KEY_CONTENT: String = "content"
    const val KEY_PERIOD: String = "period"
  }
}

/** L'adattatore che le Impostazioni legano da sole: nessuna riga in `:app`, che puo' iniettare [WidgetSettings] dove serve (P13). */
@Module
@InstallIn(SingletonComponent::class)
object WidgetSettingsModule {

  @Provides
  @Singleton
  fun widgetSettings(@ApplicationContext context: Context): WidgetSettings = SharedPrefsWidgetSettings(context)
}
