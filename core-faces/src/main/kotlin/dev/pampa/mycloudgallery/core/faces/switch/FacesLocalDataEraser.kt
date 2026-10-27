package dev.pampa.mycloudgallery.core.faces.switch

import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.StableCode

// LA CANCELLAZIONE LOCALE CHE L'INTERRUTTORE CHIEDE (docs/fase8-progetto.md §2.1 "Esclusione", D4, D14, D30; E-F8). L'interruttore decide
// **quando** i dati locali di una generazione vanno tolti (esclusione di questo dispositivo, generazione non piu' corrente, voce spenta); come
// si tolgono lo sa chi li ha: la chiave del Keystore per prima (D14), poi il database `faces-<G>.db`, le cache in memoria, i modelli estratti.
// Qui c'e' solo la porta: la lega :app (P13) ai passi locali di "Disattiva e cancella" (P8) e all'archivio (P6). L'interruttore non vede
// ne' il database ne' la chiave (Phase8ArchitectureTest, regola 3).

/** Perche' si tolgono i dati locali: decide se vanno via anche i modelli estratti. */
enum class LocalEraseWhy(override val code: String) : StableCode {
  /** "Non su questo dispositivo" (E-F8): via anche i modelli estratti da questo dispositivo. */
  EXCLUDED("excluded"),

  /** L'account ha una generazione nuova (un'altra attivazione, E-F9, E-F33): i modelli possono restare, servono ancora se si accetta l'avviso. */
  GENERATION_CHANGED("generation_changed"),

  /** L'account e' spento ("Disattiva e cancella" fatto da un altro dispositivo): via anche i modelli estratti. */
  ACCOUNT_OFF("account_off"),
}

/**
 * Toglie i dati locali di una generazione. Idempotente e **ripetibile dopo una morte del processo a meta'**: la chiave del Keystore si distrugge
 * per prima, cosi' ogni resto (pagine libere di SQLite, WAL, blocchi della flash) e' gia' illeggibile anche se il resto fallisce. Vero solo
 * quando non resta niente (nessun file `faces-<G>.db*`, nessuna chiave `mcg-faces-<G>`); falso se qualcosa e' rimasto: l'interruttore ricorda
 * la generazione ([dev.pampa.mycloudgallery.core.faces.contract.FacesLocal.dataGeneration]) e ci riprova al prossimo giro. Mai un'eccezione
 * per un caso previsto.
 */
fun interface FacesLocalDataEraser {
  suspend fun erase(generation: FaceGeneration, why: LocalEraseWhy): Boolean
}

/**
 * Nessuna cancellazione legata (finche' :app non lega i passi veri): **non toglie niente e lo dice** (falso). Cosi' se un'istanza dell'interruttore
 * nasce senza il collegamento, la generazione dei dati resta ricordata e la cancellazione si fa quando il collegamento c'e'; il contrario (dire
 * "fatto" senza aver tolto niente) lascerebbe dati biometrici che nessuno ricorda piu'.
 */
object NoFacesLocalDataEraser : FacesLocalDataEraser {
  override suspend fun erase(generation: FaceGeneration, why: LocalEraseWhy): Boolean = false
}
