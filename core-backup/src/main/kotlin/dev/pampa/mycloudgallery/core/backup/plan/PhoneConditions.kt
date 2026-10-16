package dev.pampa.mycloudgallery.core.backup.plan

// COME STA IL TELEFONO ADESSO, PER GLI AVVISI DEL RIEPILOGO (Fase 5A, P4; docs/fase5-progetto.md §2.1 "Primo backup", §1.5 `BackupSummary`).
//
// Il riepilogo del primo backup propone "Stanotte" (segue il preset) e "Inizia ora" (lavoro avviato dall'utente): l'avviso "meglio in
// carica e sul Wi-Fi di casa", la conferma sui dati mobili con la stima e gli avvisi sui permessi dipendono da come sta il telefono in
// questo momento. core-backup non vede ne' la batteria, ne' la rete, ne' i permessi dei media (core-mediastore): `:app` li lega.
//
// **Nessuna decisione dipende da qui**: sono soli avvisi. Chi decide se un lavoro parte e' il valutatore delle regole al momento di
// partire, con lo stato vero.

/**
 * Le condizioni del telefono che il riepilogo mostra. Null = non nota (nessun avviso).
 *
 * @property onMobileData la rete di adesso e' a pagamento (dati mobili, roaming, Wi-Fi a consumo): "Inizia ora" chiede la conferma
 *   con la stima.
 * @property pluggedIn l'alimentazione e' collegata (anche con la carica sospesa).
 * @property partialMediaAccess accesso solo ad alcune foto (E-P1): si salvano solo quelle.
 * @property mediaLocationMissing manca `ACCESS_MEDIA_LOCATION`: le foto restano in attesa (E-P21).
 * @property phoneStorageLow poco spazio sul telefono (le miniature della replica ne chiedono, E-P3).
 */
data class PhoneConditions(
  val onMobileData: Boolean? = null,
  val pluggedIn: Boolean? = null,
  val partialMediaAccess: Boolean = false,
  val mediaLocationMissing: Boolean = false,
  val phoneStorageLow: Boolean = false,
) {
  companion object {
    /** Non si sa niente: nessun avviso. */
    val UNKNOWN: PhoneConditions = PhoneConditions()
  }
}

/** Da dove arrivano le [PhoneConditions]. */
fun interface PhoneConditionsSource {
  suspend fun current(): PhoneConditions

  companion object {
    val UNKNOWN: PhoneConditionsSource = PhoneConditionsSource { PhoneConditions.UNKNOWN }
  }
}
