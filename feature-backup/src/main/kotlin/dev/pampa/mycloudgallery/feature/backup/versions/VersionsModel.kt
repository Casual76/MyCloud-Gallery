package dev.pampa.mycloudgallery.feature.backup.versions

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.backup.contract.NasVersionPlan
import dev.pampa.mycloudgallery.core.backup.contract.RestoreAvailability
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.VersionId

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `VersionsState`, §2.4, D17, D18, D35, E-E1, E-E2, E-E9, E-E10): i tipi della lista delle VERSIONI
// di un media e di "Ripristina originale". La lista nasce da `VersionKeeper.plan` (solo catalogo e diario locale: nessuna lettura del NAS);
// "Ripristina originale" e' un cambio di posto sul NAS (`SWAP`, due rinomine, nessun byte caricato) e **nessun file si toglie**. Le versioni
// intermedie si tolgono solo a mano, dal foglio "Elimina…" di feature-nas (cestino del NAS), che `:app` apre da [VersionActions].

/** Da dove si apre il foglio: la lista, o direttamente la conferma di "Ripristina originale" (la voce del menu ⋮ del viewer). */
enum class VersionsStart { LIST, RESTORE }

/**
 * Cio' che la lista delle versioni chiede a `:app` e che questo modulo non sa fare: guardare una versione nel viewer e togliere le
 * intermedie (il foglio "Elimina…" di feature-nas). Ogni capacita' puo' mancare: la voce corrispondente non compare (D13 della Fase 3).
 */
interface VersionActions {

  /** Si puo' guardare una versione nel viewer ("Vedi"). */
  val canView: Boolean get() = false

  fun view(mediaId: MediaId, version: VersionId) = Unit

  /** Si possono eliminare le intermedie dal foglio di feature-nas ("Elimina le intermedie…"). */
  val canDeleteIntermediates: Boolean get() = false

  fun deleteIntermediates(mediaId: MediaId, versions: List<VersionId>) = Unit
}

/** Nessuna azione: la lista c'e' ma e' di sola lettura. */
object NoVersionActions : VersionActions

/** Una frase che il foglio dice dopo che qualcosa e' cambiato o non e' andato: un codice, il testo e' nelle stringhe. */
enum class VersionsMessage {
  /** Il piano e' cambiato dopo che il foglio lo mostrava (una versione nuova, un file spostato dal PC): ricalcolato, si riconferma. */
  PLAN_CHANGED,

  /** E' passato troppo tempo fra il riepilogo e il tocco: si riconferma. */
  CONFIRMATION_EXPIRED,

  /** L'utente non ha dato il permesso del sistema e il telefono non e' stato toccato: l'originale non e' stato ripristinato. */
  PHONE_PERMISSION_MISSING,

  /** Il dialogo del sistema non c'e' su questo telefono. */
  NO_SYSTEM_DIALOG,
}

/** Cosa mostra il foglio delle versioni (§1.5 `VersionsState`). */
@Immutable
internal sealed interface VersionsUi {

  /** Sto leggendo le versioni. */
  data object Loading : VersionsUi

  /** La funzione non e' ancora legata (nessun custode delle versioni): il foglio lo dice. */
  data object Unavailable : VersionsUi

  /** L'elenco: dall'originale all'attuale, con dove stanno e se sono al sicuro. */
  data class Listing(
    val plan: NasVersionPlan,
    val canView: Boolean,
    val canDeleteIntermediates: Boolean,
    val message: VersionsMessage? = null,
  ) : VersionsUi

  /** "Ripristinare l'originale?": il riepilogo e il tocco che conferma. */
  data class ConfirmRestore(val plan: NasVersionPlan, val message: VersionsMessage? = null) : VersionsUi

  /** Il telefono tiene la versione modificata: il sistema chiede il permesso di rimetterla com'era (E-E10). */
  data class AwaitingSystemDialog(val plan: NasVersionPlan, val rows: Int) : VersionsUi

  /** Lo scambio e' partito. */
  data class Restoring(val plan: NasVersionPlan) : VersionsUi

  /** Fatto. [phoneKeptEdited]: l'utente non ha dato il permesso e il telefono tiene la versione modificata (E-E10). */
  data class Restored(val plan: NasVersionPlan, val phoneKeptEdited: Boolean) : VersionsUi
}

/** Se "Ripristina originale" si puo' offrire: c'e' qualcosa da ripristinare (non una sola versione, non gia' l'originale). */
internal fun RestoreAvailability.isOffered(): Boolean = when (this) {
  RestoreAvailability.ONLY_ONE_VERSION, RestoreAvailability.ALREADY_ORIGINAL, RestoreAvailability.NOT_MINE, RestoreAvailability.NOT_AVAILABLE -> false
  RestoreAvailability.AVAILABLE, RestoreAvailability.ORIGINAL_NOT_SAFE, RestoreAvailability.NAS_UNREACHABLE, RestoreAvailability.FROZEN,
  RestoreAvailability.READ_ONLY_AREA,
  -> true
}
