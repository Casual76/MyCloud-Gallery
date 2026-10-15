package dev.pampa.mycloudgallery.feature.nas.contract

import kotlinx.coroutines.flow.StateFlow

// Le aree del NAS che le schermate della 4B mostrano (docs/fase4-progetto.md §1.4, §2.7). Le compone `:app` (P16) da
// `LocalDeviceProfile.privateShare` e dal registro accettato (l'area comune): qui sono solo due nomi di share e il loro genere.
// Niente di tutto questo viene dal NAS in questo momento: sono scelte fatte all'onboarding e firmate dal registro.

/** Di chi e' un'area: solo di chi ha l'account, o di tutta la famiglia. */
enum class NasAreaKind {
  /** L'area privata dell'account: la vede solo lui. */
  PRIVATE,

  /** L'area comune: la vedono (e, con il permesso, la cambiano) tutti i familiari. */
  COMMON,
}

/**
 * Un'area del NAS: la share e il genere. [share] e' un dato dell'utente (mai nei log).
 *
 * @property label il nome da mostrare se `:app` ne vuole uno diverso da "La mia area" / "Area comune" (per esempio "Famiglia").
 */
data class NasArea(val share: String, val kind: NasAreaKind, val label: String? = null) {
  override fun toString(): String = "NasArea(${kind.name})"
}

/** Le aree dell'account su questo dispositivo, nell'ordine in cui si mostrano (prima la privata). */
interface NasAreas {
  val areas: StateFlow<List<NasArea>>
}

/** Quale area e' [share], se e' fra [areas] (il confronto non distingue le maiuscole, come il NAS). */
fun List<NasArea>.areaOf(share: String): NasArea? = firstOrNull { it.share.equals(share, ignoreCase = true) }
