package dev.pampa.mycloudgallery.core.faces.contract

import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import kotlinx.coroutines.flow.Flow

// LE AGGIUNTE DI P9 AI CONTRATTI DELLE PERSONE (docs/fase8-progetto.md §2.6, §2.9, D24, D25, D28, Q2 del 27/10; "Fase 8 P9" in docs/decisioni.md).
// Contratti nuovi (nessuno di P0 cambia): l'opzione di Q2, le persone nascoste, il cancello delle copertine, i volti del media aperto per
// il viewer e le due porte che :app lega per l'implementazione vera (`people/ArchivePeople`). feature-people vede di core-faces solo
// `contract`, `noop` e `testing`: i tipi che le schermate usano stanno qui. Mai nomi o id nei log (D37).

/**
 * La risposta a Q2 come **opzione** (D32): quale variante di Persone vede l'utente. La predefinita e' la (b), scelta il 27/10; la (a) sta
 * dietro questa opzione, senza altro codice: cambia solo come si dividono le persone senza nome fra "Persone" e "Altri volti".
 */
enum class PeopleVariant(override val code: String) : StableCode {
  /** (b), predefinita: in primo piano solo le persone con un nome; gli altri gruppi (almeno 3 foto) in "Altri volti", a un tocco. */
  NAMED_FIRST("named_first"),

  /** (a): tutti i gruppi trovati stanno in "Persone", prima quelli con un nome, poi gli altri; "Altri volti" non c'e'. */
  ALL_GROUPS("all_groups"),
  ;

  val namedOnly: Boolean get() = this == NAMED_FIRST

  companion object {
    /** Q2 (b). */
    val DEFAULT: PeopleVariant = NAMED_FIRST
  }
}

/**
 * Le persone nascoste (E-F28, §2.6 "Nascondi persona"): si ritrovano da Impostazioni -> Persone -> "Persone nascoste". Solo persone che
 * avrebbero un posto fra quelle mostrate (con foto visibili, D28): una persona con soli media nascosti non si elenca nemmeno qui.
 */
interface HiddenPeople {
  fun hidden(): Flow<List<PersonCard>>
}

/**
 * Il cancello delle copertine e dei ritagli (D25, D28): una copertina o la miniatura di un volto si ritaglia solo se il volto sta in un
 * media **mostrabile** adesso (visibile e non nascosto, filtro finale compreso). Chi lega `PeopleCrops` di feature-people passa da qui.
 */
fun interface FaceCropGate {
  suspend fun mayShow(face: FaceLocator): Boolean
}

/**
 * I volti di un media aperto, come li vuole la riga "Persone" del viewer (§2.6): [faces] si aggiorna da solo quando cambia un nome o una
 * correzione; [choices] sono le persone del foglio "Chi e'?" (con un nome, non nascoste). Il viewer non vede core-faces: :app lo lega
 * a `ViewerPeople` con [FaceTokens].
 */
interface MediaFaces {
  fun faces(media: MediaId): Flow<List<FaceChip>>

  suspend fun choices(): List<PersonCard>

  /** Il volto [face] e' di un contenuto di [media] (la cella aperta, membri compresi)? Un riferimento costruito per un altro media non si ritaglia. */
  suspend fun belongsTo(media: MediaId, face: FaceLocator): Boolean
}

/**
 * Un volto di una persona per la revisione ("Controlla le foto", "Dividi", §2.6): il posto, la **cella mostrabile** da cui ritagliarlo
 * ([media], mai un nascosto) e se l'utente l'ha confermato ("E' …"). Dal meno sicuro al piu' sicuro: chi controlla vede prima i volti che
 * l'assegnazione ha messo con la somiglianza piu' bassa.
 */
data class PersonFace(val face: FaceLocator, val media: MediaId, val confirmed: Boolean) {
  override fun toString(): String = "PersonFace($confirmed)"
}

/** I volti di una persona per la revisione (solo di celle mostrabili, D28); vuoto se la persona non c'e' o Persone non e' attiva qui. */
interface PersonFaces {
  suspend fun reviewFaces(person: PersonId): List<PersonFace>

  /** Per ogni persona chiesta, la cella mostrabile da cui si ritaglia la sua copertina ("Altri volti"); manca chi non ne ha una. In una passata sola. */
  suspend fun coverMedia(persons: Collection<PersonId>): Map<PersonId, MediaId>
}

/** Il riferimento opaco di un volto per il viewer (`ViewerFaceRef`): il localizzatore in forma di testo, al piu' 128 caratteri. */
object FaceTokens {
  fun of(face: FaceLocator): String = face.encode()

  /** Il volto di un riferimento, o null se non e' un riferimento valido (un dato ostile non lancia). */
  fun parse(token: String): FaceLocator? = FaceLocator.parse(token)
}

/** Dove sta l'archivio della generazione corrente di questo dispositivo (la lega :app sopra `FacesStores` e `FacesSwitch`). Null = nessuno adesso. */
fun interface PeopleArchiveSource {
  suspend fun current(): FacesArchive?
}

/**
 * Il ponte fra i volti e la Libreria (D26, §2.6): [cellsWith] da' le **celle** che contengono ogni contenuto (una persona compare nella cella
 * che li contiene: per un membro di una raffica o di una coppia RAW+JPEG e' la copertina del gruppo); [keysOf] le chiavi di contenuto di
 * una cella aperta (membri compresi). Mai query nuove sul catalogo (§1.5): `FacesCatalog` le da' gia' (`mediaWith`, `cellKeys`).
 */
interface PeopleCells {
  suspend fun cellsWith(keys: Collection<ContentKey>): Map<ContentKey, Set<MediaId>>

  suspend fun keysOf(media: MediaId): Set<ContentKey>
}
