package dev.pampa.mycloudgallery.feature.people.nav

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.contract.PeopleActions
import dev.pampa.mycloudgallery.feature.people.contract.PeopleCrops
import dev.pampa.mycloudgallery.feature.people.contract.PeopleThumbnails
import dev.pampa.mycloudgallery.feature.people.hidden.HiddenPeopleRoute
import dev.pampa.mycloudgallery.feature.people.list.OthersRoute
import dev.pampa.mycloudgallery.feature.people.list.PeopleListRoute
import dev.pampa.mycloudgallery.feature.people.page.PersonPageExits
import dev.pampa.mycloudgallery.feature.people.page.PersonPageRoute
import dev.pampa.mycloudgallery.feature.people.review.FaceReviewRoute
import kotlinx.serialization.Serializable

// LE ROTTE DELLE PERSONE (Fase 8, P9): un grafo da agganciare, come `albumsGraph` e `utilityGraph`. **Non** tocca `MainScaffold` ne' `AppNavHost`
// (sono di P13): P13 chiama [peopleGraph] dentro il `NavHost` e apre le rotte con le funzioni `open...` di sotto, a partire dalle voci della sezione
// Persone delle Raccolte ([PeopleEntryTarget.parse]). La pagina di attivazione, l'avviso e Impostazioni -> Persone sono di P7 (`activation/`) e
// "Disattiva e cancella" di P8 (`erase/`): da qui si arriva con [PeopleActions.openActivation] e [PeopleActions.openPeopleSettings]. Il viewer non
// e' una rotta: [PeopleActions.openViewer] manda la richiesta a `:app`, che lo ospita sopra la pagina corrente.

/** "Tutte le persone" (la sezione Persone delle Raccolte oltre le prime 8). */
@Serializable
data object PeopleListDestination

/** "Altri volti" (Q2 (b)): i gruppi senza nome. */
@Serializable
data object OthersDestination

/** La pagina di una persona. [id] e' il `PersonId` come testo (stabile; mai un nome). */
@Serializable
data class PersonDestination(val id: String)

/** "Controlla le foto" ([split] falso) o "Dividi" ([split] vero) di una persona. */
@Serializable
data class ReviewDestination(val id: String, val split: Boolean = false)

/** Le persone nascoste (Impostazioni -> Persone -> "Persone nascoste", E-F28). */
@Serializable
data object HiddenPeopleDestination

fun NavController.openPeople() = navigate(PeopleListDestination)

fun NavController.openOthers() = navigate(OthersDestination)

fun NavController.openPerson(id: PersonId) = navigate(PersonDestination(id.value))

fun NavController.openReview(id: PersonId, split: Boolean) = navigate(ReviewDestination(id.value, split))

fun NavController.openHiddenPeople() = navigate(HiddenPeopleDestination)

/**
 * Cosa apre una voce della sezione Persone delle Raccolte: `:app` lo riconosce dal codice (come fa per `nas/...` e per gli album) e porta alla
 * pagina giusta. I codici sono stabili e non portano mai un nome: solo l'id della persona.
 */
sealed interface PeopleEntryTarget {
  /** "Attiva riconoscimento dei volti" o l'avviso sugli altri dispositivi: la pagina di P7. */
  data object Activation : PeopleEntryTarget

  /** "Non attiva su questo dispositivo", il primo calcolo, la nota della recinzione, "Aggiorna l'app", la cancellazione: Impostazioni -> Persone. */
  data object Settings : PeopleEntryTarget

  data class Person(val id: PersonId) : PeopleEntryTarget

  data object All : PeopleEntryTarget

  data object Others : PeopleEntryTarget

  companion object {
    const val ENABLE = "people/enable"
    const val NOTICE = "people/notice"
    const val EXCLUDED = "people/excluded"
    const val PROGRESS = "people/progress"
    const val LOCAL = "people/local"
    const val UPDATE = "people/update"
    const val ERASING = "people/erasing"
    const val MODELS = "people/models"
    const val ALL = "people/all"
    const val OTHERS = "people/others"
    const val PERSON_PREFIX = "people/person/"

    /** Il bersaglio di una voce, o null se il codice non e' di questa sezione (o e' di una versione piu' nuova). */
    fun parse(code: String): PeopleEntryTarget? = when {
      code == ENABLE || code == NOTICE -> Activation
      code == EXCLUDED || code == PROGRESS || code == LOCAL || code == UPDATE || code == ERASING || code == MODELS -> Settings
      code == ALL -> All
      code == OTHERS -> Others
      code.startsWith(PERSON_PREFIX) -> PersonId.parse(code.removePrefix(PERSON_PREFIX))?.let(::Person)
      else -> null
    }
  }
}

/** Apre la destinazione di una voce della sezione ([PeopleEntryTarget.parse]); le pagine di P7 passano da [actions]. */
fun NavController.openPeopleEntry(target: PeopleEntryTarget, actions: PeopleActions) {
  when (target) {
    PeopleEntryTarget.Activation -> actions.openActivation()
    PeopleEntryTarget.Settings -> actions.openPeopleSettings()
    PeopleEntryTarget.All -> openPeople()
    PeopleEntryTarget.Others -> openOthers()
    is PeopleEntryTarget.Person -> openPerson(target.id)
  }
}

/**
 * Le pagine delle persone nel grafo di navigazione: "Tutte le persone", "Altri volti", la pagina di una persona, "Controlla le foto" e "Dividi", le persone
 * nascoste. [actions] sono le uscite verso `:app` (viewer, "Crea album smart"); [thumbnails] le miniature locali della Libreria per le foto di una
 * persona; [crops] i volti ritagliati al momento (`GuardedPeopleCrops`: solo da media mostrabili, mai su disco).
 */
fun NavGraphBuilder.peopleGraph(nav: NavController, actions: PeopleActions, thumbnails: PeopleThumbnails, crops: PeopleCrops) {
  val back: () -> Unit = { nav.popBackStack() }

  composable<PeopleListDestination> {
    PeopleListRoute(
      crops = crops,
      onOpenPerson = nav::openPerson,
      onOpenOthers = nav::openOthers,
      onOpenHidden = nav::openHiddenPeople,
      onBack = back,
    )
  }
  composable<OthersDestination> { OthersRoute(crops = crops, onOpenPerson = nav::openPerson, onBack = back) }
  composable<PersonDestination> {
    PersonPageRoute(
      actions = actions,
      thumbnails = thumbnails,
      crops = crops,
      exits = PersonPageExits(onBack = back, onReview = nav::openReview),
    )
  }
  composable<ReviewDestination> {
    FaceReviewRoute(
      crops = crops,
      onBack = back,
      // Dopo "Dividi" la persona nuova prende il posto della griglia: indietro non riporta alla scelta dei volti.
      onOpenPerson = { id ->
        nav.popBackStack()
        nav.openPerson(id)
      },
    )
  }
  composable<HiddenPeopleDestination> { HiddenPeopleRoute(crops = crops, onBack = back) }
}
