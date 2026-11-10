package dev.pampa.mycloudgallery.describe

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.view.accessibility.AccessibilityManager
import dev.pampa.mycloudgallery.core.describe.contract.A11yActivity
import dev.pampa.mycloudgallery.core.describe.contract.DescribePeople
import dev.pampa.mycloudgallery.core.describe.contract.DescribeText
import dev.pampa.mycloudgallery.core.describe.contract.MediaDescriber
import dev.pampa.mycloudgallery.core.describe.rules.DefaultMediaDescriber
import dev.pampa.mycloudgallery.core.describe.rules.ItalianDescribeWords
import dev.pampa.mycloudgallery.core.describe.rules.MediaDescribers
import dev.pampa.mycloudgallery.core.library.contract.HiddenMask
import dev.pampa.mycloudgallery.core.library.contract.VisibleMedia
import dev.pampa.mycloudgallery.core.memories.contract.MemoryPeople
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.StateFlow

// LE DESCRIZIONI DEI MEDIA NELL'APP (Fase 10, P13; docs/fase10-progetto.md §1.4 "Descrizioni", §2.6, D22, C438; "Cosa deve fare P13" di P7 in docs/decisioni.md). Un solo
// descrittore per processo, legato all'avvio prima di qualunque schermata (`MediaDescribers.install`), con le porte vere: persone dalle persone montate (con il filtro dei
// Nascosti), testo letto dall'indice locale, strumento di accessibilita' dal sistema. Ogni porta fallisce **chiusa** (niente nomi, niente testo). Mai nei log, mai in
// Diagnostica: ne' le frasi ne' se uno strumento di accessibilita' e' attivo (D39).

/**
 * I nomi delle persone di un media (D22): **solo** con Persone attiva qui (`available` della ricerca per persona: `On` con `ACTIVE` o `LOCAL_ONLY`), solo persone con un
 * nome, non nascoste ne' dimenticate (l'indice delle persone non le ha), **mai** per un media che `VisibleMedia` o `HiddenMask` escludono adesso. Le persone vengono da
 * [people] (`IndexMemoryPeople`: la mappa da persone a celle si calcola una volta per versione delle persone e dell'istantanea, poi si risponde da memoria). Ordine: per
 * nome. Un errore = nessun nome.
 */
class FacesDescribePeople(
  private val people: MemoryPeople,
  private val visible: VisibleMedia,
  private val mask: HiddenMask,
) : DescribePeople {
  override suspend fun names(ids: Collection<MediaId>): Map<MediaId, List<String>> = try {
    if (ids.isEmpty() || !people.available.value) {
      emptyMap()
    } else {
      val shown = visible.visible(ids)
      val allowed = shown - mask.hiddenAmong(shown)
      val labels = HashMap<Any, String?>()
      people.peopleIn(allowed).mapNotNull { (media, persons) ->
        val names = persons.mapNotNull { person -> labels.getOrPut(person) { people.label(person) } }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        if (names.isEmpty()) null else media to names
      }.toMap()
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptyMap()
  }
}

/**
 * Le prime parole del testo letto (D22, E-AC3): **solo** con la lettura del testo attiva per l'account e non esclusa qui ([readingHere]); [texts] e' una lettura sola
 * per giro (le chiavi di tutti i media insieme). Al piu' [max] parole: la ripulitura (controlli, direzioni, a capo, 12 parole) e' del descrittore. Un errore = niente.
 */
class IndexDescribeText(
  private val readingHere: StateFlow<Boolean>,
  private val texts: suspend (Collection<MediaId>) -> Map<MediaId, String>,
) : DescribeText {
  override suspend fun firstWords(ids: Collection<MediaId>, max: Int): Map<MediaId, String> = try {
    if (ids.isEmpty() || max <= 0 || !readingHere.value) {
      emptyMap()
    } else {
      texts(ids).mapNotNull { (media, raw) ->
        val words = raw.split(WHITESPACE).filter { it.isNotEmpty() }
        if (words.isEmpty()) null else media to words.take(max).joinToString(" ")
      }.toMap()
    }
  } catch (e: CancellationException) {
    throw e
  } catch (e: Exception) {
    emptyMap()
  }

  private companion object {
    val WHITESPACE = Regex("\\s+")
  }
}

/**
 * C'e' uno **strumento** di accessibilita' attivo (TalkBack, Switch Access, Voice Access: `isAccessibilityTool`, Android 12+); sotto Android 12 l'esplorazione al tocco
 * (TalkBack). I servizi che non sono strumenti non contano. Letto al momento, mai salvato, **mai in Diagnostica** (D39: e' un dato sulla salute di una persona).
 */
class SystemA11yActivity(context: Context) : A11yActivity {
  private val remembered = RememberedA11yTool(AndroidA11ySystem(context.applicationContext.getSystemService(AccessibilityManager::class.java)))

  override fun toolEnabled(): Boolean = remembered.toolEnabled()
}

/**
 * Cio' che [SystemA11yActivity] chiede al sistema. Una porta, cosi' la memoria della risposta ([RememberedA11yTool]) si prova senza Android.
 */
internal interface A11ySystem {

  /** Un servizio di accessibilita' qualunque e' acceso (`AccessibilityManager.isEnabled`: un campo locale, nessuna chiamata fuori dal processo). */
  val anyServiceEnabled: Boolean

  /** Fra i servizi accesi c'e' uno strumento (`isAccessibilityTool`; sotto Android 12 l'esplorazione al tocco). E' **una chiamata a `system_server`**. */
  fun toolAmongEnabled(): Boolean

  /**
   * Chiede al sistema di chiamare [onChange] quando l'elenco dei servizi accesi cambia. Falso = il sistema non avvisa (sotto Android 13): la
   * risposta non si puo' ricordare e si rilegge ogni volta, come prima.
   */
  fun watchServices(onChange: () -> Unit): Boolean
}

/**
 * La risposta di [A11ySystem.toolAmongEnabled] **ricordata in memoria** (P1 e P2, prestazioni sull'S25, 10/11).
 *
 * L'elenco dei servizi accesi e' una chiamata a `system_server` (55 µs), e il descrittore la fa per **ogni cella composta** quando un servizio di
 * accessibilita' qualunque e' acceso: nel pinch 1.500 chiamate in 8 s sul thread principale, 2 ms per cambio di livello. Dove il sistema avvisa dei
 * cambi (Android 13+) la risposta si tiene finche' non avvisa: [generation] cresce a ogni avviso, e una risposta vale solo per la generazione in cui e'
 * stata letta (un avviso arrivato mentre si leggeva la butta via).
 *
 * Che cosa **non** cambia: senza nessun servizio acceso la risposta e' "no" senza chiamate e senza memoria; sotto Android 13 si rilegge ogni volta; un
 * errore del sistema e' "no" (e non si ricorda). Mai su disco, mai nei log, mai in Diagnostica (D39): un campo in memoria, perso con il processo.
 */
internal class RememberedA11yTool(private val system: A11ySystem) : A11yActivity {

  private class Answer(val generation: Int, val tool: Boolean)

  private val generation = AtomicInteger()

  @Volatile
  private var answer: Answer? = null

  /** Null = non ancora chiesto al sistema; poi se avvisa o no. */
  @Volatile
  private var watched: Boolean? = null

  override fun toolEnabled(): Boolean = try {
    if (!system.anyServiceEnabled) {
      // Niente di acceso: niente da ricordare. Quando qualcosa si riaccende si rilegge comunque, anche se l'avviso del sistema tardasse.
      answer = null
      false
    } else if (!watching()) {
      system.toolAmongEnabled()
    } else {
      val now = generation.get()
      val known = answer
      if (known != null && known.generation == now) {
        known.tool
      } else {
        val tool = system.toolAmongEnabled()
        answer = Answer(now, tool)
        tool
      }
    }
  } catch (e: RuntimeException) {
    false
  }

  /** Chiede gli avvisi al sistema una volta sola. Se la richiesta fallisce si resta al comportamento di prima (si rilegge ogni volta). */
  private fun watching(): Boolean {
    watched?.let { return it }
    return synchronized(this) {
      watched ?: run {
        val avvisa = try {
          system.watchServices { generation.incrementAndGet() }
        } catch (e: RuntimeException) {
          false
        }
        watched = avvisa
        avvisa
      }
    }
  }
}

/** Il sistema vero: `AccessibilityManager`. */
private class AndroidA11ySystem(private val manager: AccessibilityManager?) : A11ySystem {

  override val anyServiceEnabled: Boolean get() = manager?.isEnabled == true

  override fun toolAmongEnabled(): Boolean {
    val m = manager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.isAccessibilityTool }
    } else {
      m.isTouchExplorationEnabled
    }
  }

  override fun watchServices(onChange: () -> Unit): Boolean {
    val m = manager ?: return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
    m.addAccessibilityServicesStateChangeListener(AccessibilityManager.AccessibilityServicesStateChangeListener { onChange() })
    return true
  }
}

object DescribeWiring {

  /** Il descrittore del processo, legato in `MediaDescribers` (le schermate lo prendono da li', con la sua cache). */
  fun install(places: PlaceNames, people: DescribePeople, text: DescribeText, a11y: A11yActivity): MediaDescriber {
    val describer = DefaultMediaDescriber(places, people, text, ItalianDescribeWords.VALUE, a11y)
    MediaDescribers.install(describer)
    return describer
  }
}
