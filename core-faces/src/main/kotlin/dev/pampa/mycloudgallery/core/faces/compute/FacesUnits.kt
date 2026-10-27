package dev.pampa.mycloudgallery.core.faces.compute

import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesCatalog
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.util.Collections

// LE UNITA' DI LAVORO DEI VOLTI (docs/fase8-progetto.md §2.3 "Unita'", D16): `faces/<G>/work/<priv|comm>/<mese>/<parte>`, 200 media per
// chiave di contenuto, dal mese piu' recente, gli stessi confini su ogni dispositivo dell'account. "Fatto" non e' un segno sull'unita': e' una
// voce `faces` della ricetta corrente di **un** dispositivo dell'account, oppure "non supportato" (`FacesArchive.analyzed`). La prenotazione
// serve solo a non fare lo stesso lavoro in due (danno solo d'efficienza); il resto lo dicono gli archivi.

/** Cosa si analizza (D18): non gli screenshot, le registrazioni dello schermo, i documenti, i ritagli; e le immagini animate non sono nell'elenco. */
internal object FaceScopeRules {
  fun analyzable(item: FacesItem): Boolean = !item.excluded && item.kind != MediaKind.ANIMATED_IMAGE
}

/** Un'unita' per il `seen` di una tappa: l'area (anche "solo telefono"), il mese e la parte. */
internal data class UnitId(val area: FacesArea, val month: String, val part: Int) {
  override fun toString(): String = "UnitId(${area.code})"
}

/**
 * Un'unita' scelta: i media da fare ([items], uno per chiave di contenuto, in ordine di chiave), quanti saltati perche' esclusi
 * ([excluded]) e, per un'unita' senza niente da fare ma non ancora segnata finita, le chiavi gia' fatte ([analyzedKeys]): servono a rimettere
 * in coda cio' che questo dispositivo ha salvato e non ha forse accodato (morte fra il salvataggio e la messa in coda).
 */
internal class PlannedUnit(
  val id: UnitId,
  val resource: FacesResource.WorkUnit?,
  val items: List<FacesItem>,
  val excluded: Int,
  val analyzedKeys: List<ContentKey>,
) {
  override fun toString(): String = "PlannedUnit(${id.area.code}, ${items.size} da fare)"
}

/** Cio' che il processo ricorda fra una tappa e l'altra (mai su disco): errori passeggeri per chiave, unita' gia' rimesse in coda. */
internal class FacesMemory {
  /** IX-7: gli errori passeggeri del motore per chiave, per tutto il processo (al piu' 4.096 chiavi). */
  val strikes: MutableMap<ContentKey, Int> = Collections.synchronizedMap(
    object : LinkedHashMap<ContentKey, Int>() {
      override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContentKey, Int>?): Boolean = size > MAX_STRIKES
    },
  )

  /** Le unita' di cui questo processo ha gia' rimesso in coda i corpi propri, e quelle in cui una messa in coda e' fallita (da rifare). */
  val restaged: MutableSet<UnitId> = Collections.synchronizedSet(HashSet())
  val dirty: MutableSet<UnitId> = Collections.synchronizedSet(HashSet())

  /** Le chiavi che questo processo ha gia' messo in coda per la pubblicazione (al piu' 50.000): non si rimettono in coda due volte. */
  val staged: MutableSet<ContentKey> = Collections.newSetFromMap(
    Collections.synchronizedMap(
      object : LinkedHashMap<ContentKey, Boolean>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ContentKey, Boolean>?): Boolean = size > MAX_STAGED
      },
    ),
  )

  private companion object {
    const val MAX_STRIKES = 4_096
    const val MAX_STAGED = 50_000
  }
}

/** Sceglie la prossima unita' con qualcosa da fare in un'area (dal mese piu' recente), senza riguardare quelle gia' viste in questa tappa. */
internal class FacesUnits(private val catalog: FacesCatalog, private val unitSize: Int, private val recipe: Int) {
  // Una tappa guarda il catalogo una volta: i mesi e i loro media si leggono la prima volta e poi restano (altrimenti ogni unita' rileggerebbe tutti
  // i mesi che la precedono). Cio' che cambia in mezzo (un media nuovo) lo vede la tappa dopo.
  private val monthsOf = HashMap<FacesArea, List<String>>()
  private val itemsOf = HashMap<Pair<FacesArea, String>, Map<ContentKey, List<FacesItem>>>()

  private suspend fun months(area: FacesArea): List<String> =
    monthsOf.getOrPut(area) { catalog.months(area).distinct().sortedDescending() }

  private suspend fun itemsByKey(area: FacesArea, month: String): Map<ContentKey, List<FacesItem>> = itemsOf[area to month]
    ?: catalog.items(area, month).groupBy { it.contentKey }.toSortedMap(compareBy<ContentKey> { it.hex }).also { itemsOf[area to month] = it }

  suspend fun next(area: FacesArea, store: ComputeStore, seen: Set<UnitId>, memory: FacesMemory): PlannedUnit? {
    for (month in months(area)) {
      val byKey = itemsByKey(area, month)
      if (byKey.isEmpty()) continue
      for ((part, keys) in byKey.keys.toList().chunked(unitSize).withIndex()) {
        val id = UnitId(area, month, part)
        if (id in seen) continue
        val resource = resourceOf(area, month, part)
        val wanted = keys.filter { key -> byKey.getValue(key).any(FaceScopeRules::analyzable) }
        if (wanted.isEmpty()) continue
        val done = store.archive.analyzed(wanted, recipe)
        val pending = wanted.filter { it !in done }
        if (pending.isNotEmpty()) {
          val items = pending.map { key -> byKey.getValue(key).first(FaceScopeRules::analyzable) }
          return PlannedUnit(id, resource, items, excluded = keys.size - wanted.size, analyzedKeys = done.toList())
        }
        // Niente da fare. Se non e' segnata finita (una morte puo' aver lasciato corpi salvati e non accodati), un giro di sola rimessa in coda.
        if (resource != null && needsRestage(id, resource, store, memory)) {
          return PlannedUnit(id, resource, emptyList(), excluded = 0, analyzedKeys = done.toList())
        }
      }
    }
    return null
  }

  private suspend fun needsRestage(id: UnitId, resource: FacesResource.WorkUnit, store: ComputeStore, memory: FacesMemory): Boolean {
    if (id in memory.restaged && id !in memory.dirty) return false
    return !store.units.isDone(resource, recipe)
  }

  /** La risorsa prenotabile dell'unita'; un mese che non ha la forma del catalogo (`aaaa-mm`) non si prenota (si lavora senza, danno solo d'efficienza). */
  private fun resourceOf(area: FacesArea, month: String, part: Int): FacesResource.WorkUnit? = try {
    when (area) {
      FacesArea.PRIVATE -> FacesResource.WorkUnit(AreaKind.PRIVATE, month, part)
      FacesArea.COMMON -> FacesResource.WorkUnit(AreaKind.COMMON, month, part)
      FacesArea.PHONE_ONLY -> null
    }
  } catch (e: IllegalArgumentException) {
    null
  }
}
