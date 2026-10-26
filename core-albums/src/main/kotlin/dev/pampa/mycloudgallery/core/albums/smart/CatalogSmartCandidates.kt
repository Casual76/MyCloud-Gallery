package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.CandidateCursor
import dev.pampa.mycloudgallery.core.albums.contract.CandidateFilter
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.database.CandidateKey
import dev.pampa.mycloudgallery.core.database.CandidateQuery
import dev.pampa.mycloudgallery.core.database.CandidateSource
import dev.pampa.mycloudgallery.core.database.CatalogCandidate
import dev.pampa.mycloudgallery.core.database.MediaCatalog
import dev.pampa.mycloudgallery.core.model.MediaId
import java.time.LocalDate

// I CANDIDATI DAL CATALOGO VERO (P1: `MediaCatalog.albums`). Una traduzione senza logica: i tipi del database hanno la forma dei contratti di P0
// (`AlbumCandidate`), campo per campo. Le query sono quelle di P1 (indici su giorno, luogo e dispositivo: una pagina in meno di 50 ms su 100 mila
// media). **Non** hanno il contagio dei gruppi nascosti (D22): chi le usa passa i candidati da `VisibleMedia.visible` e dal filtro `HiddenMask`
// (lo fa `RulesSmartEngine`, mai questa classe da sola).

class CatalogSmartCandidates(private val catalog: MediaCatalog) : SmartCandidates {

  override suspend fun page(filter: CandidateFilter, after: CandidateCursor?, limit: Int): SmartCandidatePage {
    filter.media?.let { ids ->
      // Un insieme di media: le celle visibili fra questi, senza pagine (sono quelli che il chiamante ha gia' in mano).
      val wanted = ids.toSet()
      val all = catalog.albums.candidatesOf(wanted).filter { passes(it, filter) }.map(::smart)
      return SmartCandidatePage(all, null)
    }
    val query = CandidateQuery(
      days = filter.days?.let { dayInt(it.from)..dayInt(it.to) },
      sources = filter.sources.mapTo(HashSet()) { sourceOf(it) },
      withGpsOnly = filter.withGpsOnly,
    )
    return fetch(query, after, limit)
  }

  override suspend fun placePage(places: Set<Int>, after: CandidateCursor?, limit: Int): SmartCandidatePage {
    if (places.isEmpty()) return SmartCandidatePage(emptyList(), null)
    return fetch(CandidateQuery(placeIds = places, withGpsOnly = true), after, limit)
  }

  override suspend fun candidatesOf(media: Collection<MediaId>): List<SmartCandidate> =
    if (media.isEmpty()) emptyList() else catalog.albums.candidatesOf(media).map(::smart)

  override suspend fun daysOf(media: Collection<MediaId>): Map<MediaId, LocalDate> {
    if (media.isEmpty()) return emptyMap()
    val out = HashMap<MediaId, LocalDate>()
    for ((id, day) in catalog.albums.daysOf(media)) dayOf(day)?.let { out[id] = it }
    return out
  }

  override suspend fun visibleTotal(): Int = catalog.albums.visibleCount()

  private suspend fun fetch(query: CandidateQuery, after: CandidateCursor?, limit: Int): SmartCandidatePage {
    val key = after?.let { decode(it) }
    val page = catalog.albums.candidates(query, key, limit.coerceIn(1, MAX_PAGE))
    return SmartCandidatePage(page.items.map(::smart), page.next?.let { encode(it) })
  }

  private fun passes(c: CatalogCandidate, filter: CandidateFilter): Boolean =
    sourceFor(c.source) in filter.sources && (!filter.withGpsOnly || c.gps != null) &&
      (filter.days == null || c.day in filter.days)

  private fun smart(c: CatalogCandidate): SmartCandidate = SmartCandidate(
    AlbumCandidate(
      media = c.media,
      day = c.day,
      takenAtUtcMs = c.takenAtUtcMs,
      localTime = c.localTime,
      gps = c.gps,
      placeId = c.placeId ?: 0,
      originDevice = c.originDevice,
      source = sourceFor(c.source),
      kind = c.kind,
      screen = c.screen,
      pixels = c.pixels,
    ),
    c.ownedByMe,
  )

  private fun sourceFor(source: CandidateSource): SmartSource = SmartSource.entries.first { it.code == source.code }

  private fun sourceOf(source: SmartSource): CandidateSource = CandidateSource.entries.first { it.code == source.code }

  /** Il cursore e' opaco per chi lo riceve: i quattro campi della chiave del catalogo, separati da `|` (l'id di un media non ha mai il `|`). */
  private fun encode(key: CandidateKey): CandidateCursor = CandidateCursor("${key.placeId}|${key.day}|${key.time}|${key.media}")

  private fun decode(cursor: CandidateCursor): CandidateKey? {
    val parts = cursor.value.split('|', limit = 4)
    if (parts.size != 4) return null
    return CandidateKey(parts[0].toIntOrNull() ?: return null, parts[1].toIntOrNull() ?: return null, parts[2].toLongOrNull() ?: return null, parts[3])
  }

  private fun dayInt(day: LocalDate): Int = day.year * 10_000 + day.monthValue * 100 + day.dayOfMonth

  private fun dayOf(localDay: Int): LocalDate? {
    if (localDay <= 0) return null
    return runCatching { LocalDate.of(localDay / 10_000, localDay / 100 % 100, localDay % 100) }.getOrNull()
  }

  private companion object {
    const val MAX_PAGE = 5_000
  }
}
