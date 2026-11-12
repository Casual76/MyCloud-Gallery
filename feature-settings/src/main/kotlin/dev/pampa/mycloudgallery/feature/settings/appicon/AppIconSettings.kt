package dev.pampa.mycloudgallery.feature.settings.appicon

import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Optional
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// L'ICONA DELL'APP A SCELTA (I5, 12/11/2026). L'utente: «un'opzione di personalizzazione per scegliere un'icona singola al posto della variabilita'
// automatica in base al tema, nel caso qualcuno non lo volesse». Tre scelte: Automatica (di serie, segue il tema del telefono), Chiara e Scura (fisse).
// La pagina non sa come si cambia l'icona ne' quali sono le risorse: parla con questa porta. `:app` la lega ad `AppIconSwitcher` (gli ingressi del
// launcher del suo manifest, le sue risorse); senza il collegamento la voce non compare.

/** L'icona scelta. Il [code] e' quello salvato nelle preferenze dell'app. */
enum class AppIconChoice(val code: String) {
  /** Di serie: lo sfondo segue il tema del telefono (scuro dove il launcher non lo segue). */
  AUTO("auto"),

  /** Sfondo chiaro fisso. */
  LIGHT("light"),

  /** Sfondo scuro fisso. */
  DARK("dark"),
  ;

  companion object {
    val DEFAULT: AppIconChoice = AUTO

    /** La scelta di un [code] salvato, o quella di serie se non si riconosce (un valore rovinato non toglie l'icona). */
    fun ofCode(code: String?): AppIconChoice = entries.firstOrNull { it.code == code } ?: DEFAULT
  }
}

/**
 * Le risorse vere dell'icona, scritte da `:app`: il primo piano (la nuvola, `ic_launcher_foreground`) e i due sfondi fissi. Le anteprime delle
 * Impostazioni le disegnano cosi' come le compone il launcher: lo sfondo pieno, il primo piano ingrandito di 108/72 e ritagliato.
 */
data class AppIconArt(
  @DrawableRes val foreground: Int,
  @ColorRes val lightBackground: Int,
  @ColorRes val darkBackground: Int,
)

/** L'icona dell'app su questo dispositivo: quale e' scelta, e la scelta nuova. */
interface AppIconSettings {

  /** La scelta di adesso (quella salvata): la pagina la segna subito al tocco. */
  val choice: StateFlow<AppIconChoice>

  val art: AppIconArt

  /** Sceglie un'icona: si ricorda subito, l'ingresso del launcher cambia quando l'app passa in secondo piano (`AppIconSwitcher`). */
  fun choose(choice: AppIconChoice)
}

@HiltViewModel
class AppIconViewModel @Inject constructor(settings: Optional<AppIconSettings>) : ViewModel() {
  private val port: AppIconSettings? = settings.orElse(null)

  /** Null se `:app` non ha collegato l'icona: la voce non si disegna. */
  val choice: StateFlow<AppIconChoice?> = port?.choice ?: MutableStateFlow<AppIconChoice?>(null)

  val art: AppIconArt? = port?.art

  fun choose(choice: AppIconChoice) {
    port?.choose(choice)
  }
}
