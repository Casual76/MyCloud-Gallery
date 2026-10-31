package dev.pampa.mycloudgallery.core.describe.testing

import dev.pampa.mycloudgallery.core.describe.contract.A11yActivity
import dev.pampa.mycloudgallery.core.describe.contract.DescribePeople
import dev.pampa.mycloudgallery.core.describe.contract.DescribeStrings
import dev.pampa.mycloudgallery.core.describe.contract.DescribeText
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.places.contract.PlaceNames
import dev.pampa.mycloudgallery.core.model.PlaceRef

// Fase 10 (P0, §5.2): i fake delle porte delle descrizioni. Nomi e testo letto sono **di prova** (nessun dato personale). La suite
// `DescribePortsContract` gira anche sugli adattatori veri di P7 e P13 (`PeopleVisibility`, replica del testo letto).

/** Le parole di oggi (`GridStrings` di feature-library) e le nuove di D22, in italiano. */
object ItalianDescribeStrings {
  val VALUE: DescribeStrings = DescribeStrings(
    kindPhoto = "Foto", kindVideo = "Video", kindMotion = "Foto in movimento", kindRaw = "Foto RAW", kindAnimated = "Immagine animata",
    favoriteFeminine = "preferita", favoriteMasculine = "preferito", editedFeminine = "modificata", editedMasculine = "modificato",
    unreadable = "non leggibile", estimated = "data stimata", duration = "durata %1\$s", group = "%1\$s scatti",
    similarCopies = "%1\$s copie simili", phoneOnly = "solo sul telefono", nasOnly = "solo sul NAS", both = "sul telefono e sul NAS",
    safe = "al sicuro sul NAS", toVerify = "copia sul NAS da verificare", backupProblem = "problema con il backup",
    withPeople = "con %1\$s", and = "e", others = "e altri %1\$s", withText = "con la scritta «%1\$s»",
  )
}

/**
 * Le persone finte: [names] per media, gia' nella forma che la porta vera da' (solo persone con un nome, non nascoste, non dimenticate);
 * [active] = Persone attiva qui (spenta: nessun nome); [failing] = errore di lettura (nessun nome).
 */
class FakeDescribePeople(
  names: Map<MediaId, List<String>> = emptyMap(),
  var active: Boolean = true,
  var failing: Boolean = false,
) : DescribePeople {
  val names: MutableMap<MediaId, List<String>> = names.toMutableMap()
  var calls: Int = 0
    private set

  override suspend fun names(ids: Collection<MediaId>): Map<MediaId, List<String>> {
    calls++
    if (!active || failing) return emptyMap()
    return ids.mapNotNull { id -> names[id]?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
  }
}

/** Il testo letto finto: [texts] per media; [active] = lettura attiva e non esclusa qui. Taglia a [max] parole come la porta vera. */
class FakeDescribeText(texts: Map<MediaId, String> = emptyMap(), var active: Boolean = true) : DescribeText {
  val texts: MutableMap<MediaId, String> = texts.toMutableMap()

  override suspend fun firstWords(ids: Collection<MediaId>, max: Int): Map<MediaId, String> {
    if (!active) return emptyMap()
    return ids.mapNotNull { id ->
      val words = texts[id]?.let { ReferenceDescriber.clean(it) }?.split(' ')?.take(max)?.joinToString(" ")
      words?.takeIf { it.isNotEmpty() }?.let { id to it }
    }.toMap()
  }
}

/** Lo strumento di accessibilita' finto. */
class FakeA11yActivity(var enabled: Boolean = true) : A11yActivity {
  override fun toolEnabled(): Boolean = enabled
}

/** I luoghi finti: un nome per id. */
class FakeDescribePlaces(private val byId: Map<Int, String>) : PlaceNames {
  override fun label(ref: PlaceRef): String? = null

  override fun labelOfId(geonameId: Int): String? = byId[geonameId]

  override fun summary(top: IntArray, distinct: Int): String = top.toList().mapNotNull { byId[it] }.joinToString(" e ")
}
