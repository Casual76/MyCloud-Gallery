package dev.pampa.mycloudgallery.feature.backup.free

import androidx.compose.runtime.Immutable
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceBlock
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpaceKind
import dev.pampa.mycloudgallery.core.backup.contract.FreeSpacePlan

// Fase 5B (P12, docs/fase5-progetto.md §1.5 `FreeSpaceState`, §2.5, D21): gli STATI di "Libera spazio" come li vede la UI, uno per passo del
// §2.5: `Idle`, `Summary`, `Guarding`, `Verifying`, `Ready`, `Confirming`, `Done`, `Blocked`. Li produce `FreeSpaceFlow` dal giro del contratto
// (`FreeSpaceRun`); le schermate li disegnano e basta. Solo numeri e codici: nessun nome di file.

/** Perche' "Libera spazio" non va avanti: i motivi del contratto (`FreeSpaceBlock`) e quelli che il giro aggiunge. */
enum class FreeSpaceBlockedBy {
  /** Area privata congelata o scritture ferme (E-B22). */
  AREA_FROZEN,

  /** Il NAS non risponde (E-B9). */
  NAS_UNREACHABLE,

  /** La conferma non vale per il piano di adesso: qualcosa e' cambiato dopo il foglio. */
  CONFIRMATION_REJECTED,

  /** "Libera spazio" non c'e': backup non pronto, in prova (D12) o senza area privata. */
  NOT_AVAILABLE,

  /** Il sistema non ha il dialogo per togliere i file (Android 10). */
  NO_SYSTEM_DIALOG,

  /** La verifica e' scaduta (6 ore) o il telefono si e' riavviato: si rifa' dal piano (D21, D31). */
  EXPIRED,
  ;

  internal companion object {
    fun of(block: FreeSpaceBlock?): FreeSpaceBlockedBy = when (block) {
      FreeSpaceBlock.AREA_FROZEN -> AREA_FROZEN
      FreeSpaceBlock.NAS_UNREACHABLE -> NAS_UNREACHABLE
      FreeSpaceBlock.CONFIRMATION_REJECTED -> CONFIRMATION_REJECTED
      FreeSpaceBlock.NO_SYSTEM_DIALOG -> NO_SYSTEM_DIALOG
      FreeSpaceBlock.NOT_AVAILABLE, null -> NOT_AVAILABLE
    }
  }
}

/** Cosa mostra la pagina "Libera spazio" (§1.5 `FreeSpaceState`). */
@Immutable
internal sealed interface FreeSpaceUi {

  /** Sto contando cosa si puo' liberare. */
  data object Idle : FreeSpaceUi

  /** Il riepilogo: quanti e quanti GB, gli esclusi per motivo, il filtro scelto. */
  data class Summary(val plan: FreeSpacePlan, val kind: FreeSpaceKind) : FreeSpaceUi

  /** Il guardiano fa le copie private dei media con la sola copia comune (D9). */
  data class Guarding(val done: Int, val total: Int) : FreeSpaceUi

  /** La verifica sul momento: rilettura completa di ogni copia privata (D21). */
  data class Verifying(val done: Int, val total: Int, val bytes: Long) : FreeSpaceUi

  /** Verificato: i lotti aspettano il dialogo del sistema. [validUntilMonotonicMs] e' l'istante (monotono) in cui la verifica scade. */
  data class Ready(
    val batchesDone: Int,
    val batchesTotal: Int,
    val items: Int,
    val bytes: Long,
    val permanent: Boolean,
    val validUntilMonotonicMs: Long?,
  ) : FreeSpaceUi

  /** Il dialogo del sistema e' aperto: aspetto la risposta dell'utente per il lotto [batch] di [of]. */
  data class Confirming(val batch: Int, val of: Int, val permanent: Boolean) : FreeSpaceUi

  /**
   * Finito. [items] e [freedBytes] sono cio' che Android ha confermato; [toAndroidTrash] dice se sono nel cestino (30 giorni) o tolti per sempre;
   * [kept] sono quelli rimasti sul telefono perche' l'utente non ha confermato (o Android non l'ha detto); [dropped] quelli usciti perche' la
   * copia sul NAS non tornava (E-B8, L39); [cancelled] = l'utente ha rifiutato il dialogo.
   */
  data class Done(
    val items: Int,
    val freedBytes: Long,
    val toAndroidTrash: Boolean,
    val kept: Int,
    val dropped: Int,
    val cancelled: Boolean,
  ) : FreeSpaceUi

  data class Blocked(val reason: FreeSpaceBlockedBy) : FreeSpaceUi
}

/** Il foglio di conferma aperto: nessuno, quello di "Libera spazio" (un tocco) o quello di "Libera subito" (due gesti distinti). */
@Immutable
internal sealed interface FreeSheet {
  data object None : FreeSheet

  data class Confirm(val plan: FreeSpacePlan) : FreeSheet

  data class ConfirmNow(val plan: FreeSpacePlan) : FreeSheet
}
