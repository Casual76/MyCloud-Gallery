package dev.pampa.mycloudgallery.debugtools.eval.faces

import androidx.compose.ui.graphics.ImageBitmap
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * SOLO debug (Fase 8, P11): tutto cio' che lo strumento "Valutazione delle persone" chiede all'app, in una porta sola (come `EvalEnvironment` della ricerca,
 * Fase 6, e `AlbumsEvalEnvironment`, Fase 7). La **lega P13** quando collega `FacesGraph`, sopra le porte di `core-faces`: `People` (i gruppi mostrati),
 * `FacesArchive` e `GroupingState` (volti, punteggi e vettori), le miniature locali. P11 non tocca `:app` fuori da `src/debug/.../eval/faces/`, e `:app` non
 * dipende ancora da `core-faces` (lo aggiunge P13): per questo la porta parla solo con tipi di `core-model` e con numeri.
 *
 * **Sola lettura**: lo strumento non scrive mai nell'archivio dei volti, nelle correzioni, nel NAS o nei dati personali; le etichette stanno in
 * `noBackupFilesDir/eval/faces/` ([FacesEvalStore]). Mai rete, mai NAS: solo l'archivio locale e le anteprime locali.
 *
 * **Contratto per chi la lega** (P13):
 *  - ogni volto che la porta restituisce e' di un media **mostrabile** (un nascosto perfetto non esce mai, D28): la porta filtra con la fonte dei nascosti
 *    prima di rispondere, e non ne parla nemmeno nei conteggi di [totals];
 *  - i gruppi sono le persone che Persone mostra davvero (con un nome, o almeno 3 foto visibili), con l'id risolto dagli alias; con Q2 (b) comprendono
 *    anche "Altri volti";
 *  - i punteggi sono quelli del raggruppamento (`s(f, P)` e la seconda persona, §2.5), calcolati sugli stessi esemplari;
 *  - i volti NONE (senza vettore) non ci sono;
 *  - niente va nei log: nemmeno un id o un posto.
 */
interface FacesEvalEnvironment {
  /** Prima di leggere (di norma apre l'archivio della generazione corrente). Puo' lanciare se Persone non e' attiva qui. */
  suspend fun prepare()

  /** I gruppi che Persone mostra, in un ordine qualunque (la scelta li ordina). */
  suspend fun groups(): List<EvalGroupInfo>

  /** I volti **mostrabili** di un gruppo, con la somiglianza al gruppo e alla seconda persona. Vuoto se il gruppo non c'e' piu'. */
  suspend fun faces(group: PersonId): List<EvalFaceInfo>

  /**
   * I coseni a due a due fra i volti [faces], come matrice n x n per righe (n = `faces.size`), o null se non si possono calcolare (vettori non
   * disponibili). Serve alla regola delle nascite; i vettori non lasciano mai la porta.
   */
  suspend fun cosines(faces: List<FaceLocator>): FloatArray?

  /** I conteggi di adesso: volti CORE e WEAK mostrabili e quanti stanno in un gruppo, e quanti gruppi si mostrano. */
  suspend fun totals(): EvalTotals

  /** Le soglie con cui sono stati formati i gruppi di adesso (`GroupingParams`): la regola non scende sotto. */
  val thresholds: ThresholdValues

  /** Il ritaglio del volto, **locale** e al momento (mai dal NAS, mai salvato: D25), o null se non c'e': la schermata mostra un riquadro grigio. */
  suspend fun crop(face: FaceLocator): ImageBitmap?
}

/** Un gruppo che Persone mostra: la persona, quanti volti e quante foto visibili ha, se ha un nome (il nome non passa di qui), un volto per la copertina. */
class EvalGroupInfo(val id: PersonId, val faces: Int, val photos: Int, val named: Boolean, val cover: FaceLocator?) {
  override fun toString(): String = "EvalGroupInfo($faces volti, $photos foto, named=$named)"
}

/** Un volto di un gruppo: il posto, se e' CORE (altrimenti WEAK), la somiglianza al gruppo e a quella che viene seconda (null se non ce n'e'). */
class EvalFaceInfo(val locator: FaceLocator, val core: Boolean, val score: Float, val second: Float?) {
  override fun toString(): String = "EvalFaceInfo(core=$core)"
}

/** Il punto in cui P13 mette la porta quando l'app parte (`DeveloperTools.installSearch`, solo debug). Fino ad allora e' vuoto e lo strumento lo dice. */
object FacesEvalEnvironmentHolder {
  @Volatile var current: FacesEvalEnvironment? = null
}
