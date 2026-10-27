package dev.pampa.mycloudgallery.feature.library.utility

import dev.pampa.mycloudgallery.core.library.duplicates.NasTrashRef
import dev.pampa.mycloudgallery.core.library.duplicates.PhoneTrashRef
import dev.pampa.mycloudgallery.feature.library.trash.NasTrashOutcome
import dev.pampa.mycloudgallery.feature.library.trash.UserTap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Fase 7, P8: le PORTE di `feature-library` per Doppioni e Utility verso `:app` (P13). `feature-library` non vede `core-nasops` (chi mostra la
// Libreria non raggiunge le operazioni distruttive, Phase4ArchitectureTest) ne' `core-personal`: cio' che tocca il NAS o le impostazioni passa da
// qui, con tipi nostri o di core-library. Tutte opzionali per Dagger (`OptionalUtilityBindingsModule`).

/**
 * L'interruttore "Controlla di notte i video del NAS" (D27, `full_hash_nightly`): spento di serie, impostazione personale sincronizzata fra i
 * dispositivi dell'account. Lo lega `:app` a `FullHashPersonalSettings` (core-personal); cambiandolo avvia anche `FullHashNightly.apply` (mette in
 * coda o toglie dalla coda `FULL_HASH`).
 */
interface FullHashSwitch {
  val enabled: StateFlow<Boolean>

  suspend fun set(on: Boolean)
}

/** Nessun interruttore: la pagina non lo mostra. */
object NoFullHashSwitch : FullHashSwitch {
  override val enabled: StateFlow<Boolean> = MutableStateFlow(false)

  override suspend fun set(on: Boolean) = Unit
}

/**
 * La pulizia dei doppioni verso il NAS: porta nel **cestino del NAS** (30 giorni, mai in modo definitivo) le copie [copies], con un piano di
 * `NasOperations` (`OpRequest.Trash`). Lo lega `:app` (P13): costruisce il piano, lo avvia con la conferma che emette **in quel momento**, dopo il
 * tocco di cui riceve la prova ([UserTap]). Un dato letto dal NAS non arriva mai qui: le copie vengono dal catalogo, scelte dall'utente.
 */
fun interface CleanupNasPort {
  suspend fun trash(copies: List<NasTrashRef>, tap: UserTap): NasTrashOutcome
}

/** Nessuna pulizia sul NAS (nessun NAS): le copie del NAS non si propongono. */
object NoCleanupNas : CleanupNasPort {
  override suspend fun trash(copies: List<NasTrashRef>, tap: UserTap): NasTrashOutcome = NasTrashOutcome.REJECTED
}

/**
 * R18 (revisione di Fase 7): prima del dialogo di sistema, le copie del telefono del piano si ricontrollano **riga per riga** nel MediaStore. Una copia
 * sovrascritta dopo la scansione (stesso `_ID`, byte nuovi) non e' piu' un doppione e non va nel cestino. Lo lega `:app` (sopra la porta del telefono
 * del backup, che rilegge la riga con il suo timbro); senza, nessuna copia del telefono si manda nel cestino da qui ([NoPhoneCopyCheck]).
 */
fun interface PhoneCopyCheck {
  /** Delle [copies], quelle che sono ancora la stessa riga con lo stesso timbro (dimensione, data di modifica) del piano. Una che non si sa dire non c'e'. */
  suspend fun unchanged(copies: List<PhoneTrashRef>): List<PhoneTrashRef>
}

/** Nessun controllo del telefono: nessuna copia del telefono si manda nel cestino (fallire chiusi). */
object NoPhoneCopyCheck : PhoneCopyCheck {
  override suspend fun unchanged(copies: List<PhoneTrashRef>): List<PhoneTrashRef> = emptyList()
}

/**
 * Il nome con cui si chiama il familiare di un'area (E-U1: "1 copia in un'area di Marco"). [share] e' la share in cui sta la copia; null = non si sa:
 * la pagina dice "un altro familiare". Facoltativa.
 */
fun interface DuplicatesNames {
  fun familyName(share: String): String?
}
