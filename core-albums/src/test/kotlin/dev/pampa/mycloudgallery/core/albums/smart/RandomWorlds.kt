package dev.pampa.mycloudgallery.core.albums.smart

import dev.pampa.mycloudgallery.core.albums.contract.LocalDayRange
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.MediaKind
import java.time.LocalDate
import java.time.MonthDay
import kotlin.random.Random

/** I mondi e le regole a caso delle prove di proprieta' (incrementale, nascosti, prestazioni): riproducibili da un seme. */
internal object RandomWorlds {
  val BASE: LocalDate = LocalDate.of(2026, 3, 1)
  private val zones = intArrayOf(60, 120, -300, 540, 0)
  private val devices = listOf("phone", "phone", "phone", "backup:questo-telefono", "backup:tablet", "backup:tablet", null)
  private val oslo = Spots.OSLO

  fun candidate(n: Int, rnd: Random, day: LocalDate = BASE.plusDays(rnd.nextLong(0, 30))): SmartCandidate {
    val gpsKind = rnd.nextInt(100)
    val gps = when {
      gpsKind < 50 -> Spots.north(oslo, rnd.nextDouble() * 6.0)
      gpsKind < 60 -> Spots.north(oslo, 28.0 + rnd.nextDouble() * 18.0)
      gpsKind < 68 -> Spots.BERGEN
      else -> null
    }
    val place = when {
      gps == null -> 0
      gpsKind < 50 -> Spots.OSLO_ID
      gpsKind < 60 -> if (rnd.nextBoolean()) Spots.OSLO_ID else 0
      else -> Spots.BERGEN_ID
    }
    val sourcePick = rnd.nextInt(100)
    val source = when {
      sourcePick < 55 -> SmartSource.MY_PHONE
      sourcePick < 75 -> SmartSource.MY_PRIVATE
      sourcePick < 90 -> SmartSource.FAMILY
      else -> SmartSource.COMMON
    }
    return cand(
      n, day, minutes = rnd.nextInt(24 * 60), gps = gps, placeId = place, device = devices[rnd.nextInt(devices.size)], source = source,
      kind = if (rnd.nextInt(100) < 10) MediaKind.VIDEO else MediaKind.PHOTO, screen = rnd.nextInt(100) < 8, zone = zones[rnd.nextInt(zones.size)],
      owned = source == SmartSource.COMMON && rnd.nextBoolean(),
    )
  }

  fun rules(rnd: Random): SmartRules {
    val period: SmartPeriod? = when (rnd.nextInt(5)) {
      0 -> SmartPeriod.Ranges(listOf(LocalDayRange(day(2026, 3, 3), day(2026, 3, 15))))
      1 -> SmartPeriod.Ranges(
        listOf(
          LocalDayRange(day(2026, 3, 2), day(2026, 3, 8)),
          LocalDayRange(day(2026, 3, 15), day(2026, 3, 22)),
        ),
      )
      2 -> SmartPeriod.EveryYear(MonthDay.of(3, 5), MonthDay.of(3, 25))
      3 -> SmartPeriod.Days(setOf(day(2026, 3, 4), day(2026, 3, 5), day(2026, 3, 11), day(2026, 3, 12), day(2026, 3, 13), day(2026, 3, 29)))
      else -> null
    }
    val place: PlaceRule? = when (rnd.nextInt(4)) {
      0 -> PlaceRule(listOf(Spots.OSLO_ID), oslo, 25, "Oslo", PlaceScope.AROUND)
      1 -> PlaceRule(listOf(Spots.OSLO_ID), oslo, 10 + rnd.nextInt(30), "Oslo", PlaceScope.AROUND)
      2 -> PlaceRule(listOf(Spots.OSLO_ID, Spots.BERGEN_ID), oslo, 300, "Norvegia", PlaceScope.COUNTRY)
      else -> null
    }
    val sources = if (rnd.nextInt(4) == 0) setOf(SmartSource.MY_PHONE, SmartSource.MY_PRIVATE) else SmartSource.ALL
    val types = if (rnd.nextInt(5) == 0) setOf(SmartMediaType.PHOTO) else SmartMediaType.ALL
    // Un album senza regole e' vuoto: va bene, e' un caso (ma non troppo spesso).
    return SmartRules(period, place, null, sources = sources, types = types)
  }
}
