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
  private val manager = context.applicationContext.getSystemService(AccessibilityManager::class.java)

  override fun toolEnabled(): Boolean = try {
    val m = manager
    when {
      m == null || !m.isEnabled -> false
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
        m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.isAccessibilityTool }
      else -> m.isTouchExplorationEnabled
    }
  } catch (e: RuntimeException) {
    false
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
