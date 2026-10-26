package dev.pampa.mycloudgallery.core.albums.eval

import dev.pampa.mycloudgallery.core.albums.contract.AlbumCandidate
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.albums.smart.Cities
import dev.pampa.mycloudgallery.core.albums.smart.MemoryWorld
import dev.pampa.mycloudgallery.core.albums.smart.SmartCandidate
import dev.pampa.mycloudgallery.core.albums.smart.SyntheticStory
import dev.pampa.mycloudgallery.core.albums.testing.FakeAlbumLibrary
import dev.pampa.mycloudgallery.core.model.GeoPoint
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

// LA SPECIFICA DEI VIAGGI SINTETICI PER IL GENERATORE (riga P12 del §4.4): le 20 storie di P6 (`SyntheticStory`) scritte come testo, una riga per foto.
// E' il ponte fra la JVM e il telefono: il generatore (`tools/fixtures/trips/generate_trips.py`, Python) legge questo file e scrive i JPEG con l'EXIF e
// il GPS; i numeri attesi (`attesi.tsv`) li calcola la JVM **da questo stesso testo** (riletto), non dai dati in memoria: cosi' quello che il generatore
// disegna e quello che la JVM misura sono la stessa cosa, coordinate arrotondate comprese.
//
// Colonne (separate da tabulazione, la prima riga comincia con `#`): storia, n (il numero del media nella storia), giorno locale, minuto del giorno
// locale, fuso in minuti dall'UTC, latitudine e longitudine (`-` se non c'e' il GPS; sei decimali), luogo (l'id del luogo della storia, solo per la
// JVM), dispositivo (`telefono`, `tablet`, `-`), sorgente (`my_phone`, `my_private`, `family`), schermo (0/1), video (0/1), album (gli indici degli
// album della storia che hanno questa foto fra quelle del viaggio, separati da virgola, o `-`).

internal object TripFixtureSpec {
  const val MEDIA_HEADER: String =
    "#storia\tn\tgiorno\tminuto\tfuso\tlat\tlon\tluogo\tdispositivo\tsorgente\tschermo\tvideo\talbum"
  const val ALBUM_HEADER: String = "#storia\tindice\ttitolo\tgenere\tviaggi\tfoto"

  const val PHONE = "telefono"
  const val TABLET = "tablet"

  /** Il dispositivo di origine come lo scrive la storia (P6): il telefono, il tablet nella cartella di backup, o nessuno (un familiare). */
  private const val TABLET_ORIGIN = "backup:tablet-di-casa"

  class Row(
    val story: Int,
    val n: Int,
    val day: LocalDate,
    val minute: Int,
    val zone: Int,
    val lat: Double?,
    val lon: Double?,
    val place: Int,
    val device: String,
    val source: SmartSource,
    val screen: Boolean,
    val video: Boolean,
    val albums: List<Int>,
  ) {
    val onPhone: Boolean get() = device == PHONE && source == SmartSource.MY_PHONE

    override fun toString(): String = "Row($story, $n)"
  }

  class Album(val story: Int, val index: Int, val title: String, val kind: String, val trips: Int, val photos: Int)

  class Spec(val rows: List<Row>, val albums: List<Album>)

  /** Le righe di una storia, con lo stesso ordine di creazione. */
  fun rowsOf(storyIndex: Int, story: SyntheticStory): List<Row> {
    val albumsOf = HashMap<String, MutableList<Int>>()
    story.cases.forEachIndexed { i, case -> for (m in case.truth) albumsOf.getOrPut(m.value) { ArrayList() } += i }
    return story.world.items().map { item ->
      val c = item.c
      val minute = c.localTime!!.hour * 60 + c.localTime!!.minute
      val dayStartUtc = c.day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
      val zone = ((dayStartUtc + minute * 60_000L - c.takenAtUtcMs) / 60_000L).toInt()
      Row(
        story = storyIndex,
        n = UUID.fromString(c.media.value).leastSignificantBits.toInt(),
        day = c.day,
        minute = minute,
        zone = zone,
        lat = c.gps?.latitude?.let(::round6),
        lon = c.gps?.longitude?.let(::round6),
        place = c.placeId,
        device = when (c.originDevice) {
          "phone" -> PHONE
          TABLET_ORIGIN -> TABLET
          else -> "-"
        },
        source = c.source,
        screen = c.screen,
        video = c.isVideo,
        albums = albumsOf[c.media.value].orEmpty(),
      )
    }
  }

  fun albumsOf(storyIndex: Int, story: SyntheticStory): List<Album> =
    story.cases.mapIndexed { i, case -> Album(storyIndex, i, case.title, case.kind, case.trips, case.truth.size) }

  fun round6(v: Double): Double = String.format(Locale.ROOT, "%.6f", v).toDouble()

  fun write(spec: Spec): Pair<String, String> {
    val media = StringBuilder(MEDIA_HEADER).append('\n')
    for (r in spec.rows) {
      media.append(r.story).append('\t').append(r.n).append('\t').append(r.day).append('\t').append(r.minute).append('\t').append(r.zone).append('\t')
        .append(r.lat?.let { "%.6f".format(Locale.ROOT, it) } ?: "-").append('\t').append(r.lon?.let { "%.6f".format(Locale.ROOT, it) } ?: "-").append('\t')
        .append(r.place).append('\t').append(r.device).append('\t').append(r.source.code).append('\t').append(if (r.screen) 1 else 0).append('\t')
        .append(if (r.video) 1 else 0).append('\t').append(if (r.albums.isEmpty()) "-" else r.albums.joinToString(",")).append('\n')
    }
    val albums = StringBuilder(ALBUM_HEADER).append('\n')
    for (a in spec.albums) albums.append(a.story).append('\t').append(a.index).append('\t').append(a.title).append('\t').append(a.kind).append('\t').append(a.trips).append('\t').append(a.photos).append('\n')
    return media.toString() to albums.toString()
  }

  fun parse(mediaText: String, albumText: String): Spec {
    val rows = mediaText.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
      val c = line.split('\t')
      require(c.size == 13) { "Riga della specifica non valida." }
      Row(
        story = c[0].toInt(), n = c[1].toInt(), day = LocalDate.parse(c[2]), minute = c[3].toInt(), zone = c[4].toInt(),
        lat = c[5].takeIf { it != "-" }?.toDouble(), lon = c[6].takeIf { it != "-" }?.toDouble(), place = c[7].toInt(),
        device = c[8], source = SmartSource.entries.first { it.code == c[9] }, screen = c[10] == "1", video = c[11] == "1",
        albums = if (c[12] == "-") emptyList() else c[12].split(',').map { it.toInt() },
      )
    }.toList()
    val albums = albumText.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
      val c = line.split('\t')
      require(c.size == 6) { "Riga degli album non valida." }
      Album(c[0].toInt(), c[1].toInt(), c[2], c[3], c[4].toInt(), c[5].toInt())
    }.toList()
    return Spec(rows, albums)
  }

  /** Il mondo di una storia riletto dalle sue righe della specifica; [asPhotos] fa foto anche dei video (il generatore li disegna cosi'). */
  fun world(rows: List<Row>, asPhotos: Boolean = false): MemoryWorld {
    val items = rows.map { r ->
      val minute = r.minute
      val utc = r.day.atStartOfDay().toInstant(ZoneOffset.ofTotalSeconds(r.zone * 60)).toEpochMilli() + minute * 60_000L
      SmartCandidate(
        AlbumCandidate(
          media = FakeAlbumLibrary.id(r.n),
          day = r.day,
          takenAtUtcMs = utc,
          localTime = LocalTime.of(minute / 60 % 24, minute % 60),
          gps = if (r.lat != null && r.lon != null) GeoPoint(r.lat, r.lon) else null,
          placeId = if (r.lat == null) 0 else r.place,
          originDevice = when (r.device) {
            PHONE -> "phone"
            TABLET -> TABLET_ORIGIN
            else -> null
          },
          source = r.source,
          kind = if (r.video && !asPhotos) MediaKind.VIDEO else MediaKind.PHOTO,
          screen = r.screen,
          pixels = 12_000_000,
        ),
        ownedByMe = false,
      )
    }
    return MemoryWorld(items, Cities.places).also { it.publish() }
  }
}
