package dev.pampa.mycloudgallery.debugtools.eval.memories

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate

/**
 * SOLO debug (Fase 9, P11): tutto cio' che lo strumento "Valutazione dei ricordi" chiede all'app, in una porta sola (come `AlbumsEvalEnvironment` e `FacesEvalEnvironment`).
 * La lega P13 sopra il motore dei ricordi vero: la porta parla solo con tipi neutri (testo, `LocalDate`, `MediaId`) perche' lo strumento sta in `src/debug` e non vede
 * `core-memories`. **Sola lettura**: lo strumento non scrive mai lo scaffale ne' i dati personali, ne' la Libreria, ne' il NAS. Mai rete.
 *
 * Il contratto di [generate] (la corsa; la definizione sul banco e' `FamilyRig.run` di `core-memories/src/test/.../eval/MemoriesEvalKit.kt`, provata con la Libreria
 * sintetica; docs/decisioni.md, "Fase 9 P11"):
 *  - per ognuno degli ultimi `days` giorni, dal piu' vecchio a oggi, "oggi" e' quel giorno e si fa un giro di notte (budget di 60 secondi) con **un motore suo**: scaffale
 *    in memoria (`InMemoryShelfStore`, che parte vuoto), le scelte "Mostra meno", "Mai" e "Ricordi con le persone" **copiate in sola lettura** in un deposito in memoria
 *    (i **visti** e gli **scartati** NO: la corsa deve dire tutto cio' che il motore avrebbe prodotto, anche cio' che l'utente ha gia' visto o scartato), le stesse
 *    porte vere (viaggi, persone, vettori, pixel, album, ritagli) e la visibilita' vera dei Nascosti;
 *  - ogni ricordo **nuovo** sullo scaffale dopo il giro e' un ricordo da giudicare, nato in quel giorno; i ricordi gia' visti nella corsa non si ripetono;
 *  - **mai un nascosto**, nemmeno nei conti; mai coordinate, mai la casa; il titolo e' composto come lo vedrebbe l'utente (date e luoghi veri) ma **mai con il nome di una
 *    persona** (il file dei giudizi lo salva: D12, i nomi non si salvano);
 *  - niente log (solo numeri e codici), niente scrittura.
 */
interface MemoriesEvalEnvironment {
  /** I ricordi della corsa degli ultimi [days] giorni (vedi sopra); vuoto se la Libreria non e' pronta o non c'e' niente. */
  suspend fun generate(days: Int): List<EvalMemory>

  /** Quali di questi media sono **ancora visibili** (non nascosti, non eliminati): per togliere dallo strumento cio' che e' stato nascosto dopo la corsa. */
  suspend fun visibleAmong(ids: Collection<String>): Set<String>

  /** La miniatura **locale** del media (mai dal NAS), o null se non c'e': la schermata mostra un riquadro. */
  suspend fun thumbnail(id: MediaId): ImageBitmap?

  /** Il giorno di oggi (porta `Today`): serve solo a dire quando e' stata fatta la corsa. */
  val today: LocalDate
}

/** Il punto in cui l'app (P13, `DeveloperTools`) mette la porta quando parte. Fino ad allora e' vuoto e la schermata dice "non collegati". */
object MemoriesEvalEnvironmentHolder {
  @Volatile var current: MemoriesEvalEnvironment? = null
}
