package dev.pampa.mycloudgallery.core.search.parse

import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.search.contract.DateMediaCount
import dev.pampa.mycloudgallery.core.search.contract.PeopleDictionary
import dev.pampa.mycloudgallery.core.search.contract.PersonEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceDictionary
import dev.pampa.mycloudgallery.core.search.contract.PlaceEntry
import dev.pampa.mycloudgallery.core.search.contract.PlaceLevel
import dev.pampa.mycloudgallery.core.search.contract.PlaceOption

/**
 * Il dizionario finto delle PERSONE dei test (Fase 8, P10): **solo nomi inventati**, nessun nome di una persona vera della famiglia (D37). Ogni
 * persona ha un id fisso (le prove sono riproducibili) e il numero di media visibili che decide gli omonimi e le ambiguita' (E-F36).
 *
 * Le collisioni volute: "Natale" e "Aprile" e "Maggio" (una festa e due mesi), "Marina" e "Lucca" (due luoghi, uno piu' fotografato del nome e
 * l'altro meno), "Roma" (un nome meno fotografato della citta'); nomi di piu' parole che ne contengono altri ("Anna Maria" e "Anna", "Gian Luca"
 * e "Luca", "Nonna Rosa" e "Rosa"); un nome con l'apostrofo.
 */
internal object TestPeople {
  fun id(n: Int): PersonId = PersonId.fromBytes(ByteArray(PersonId.BYTES) { if (it == PersonId.BYTES - 1) n.toByte() else 0 })

  val MARCO = id(1)
  val ANNA = id(2)
  val GIULIA = id(3)
  val LUCA = id(4)
  val SARA = id(5)
  val ELENA = id(6)
  val PAOLO = id(7)
  val CHIARA = id(8)
  val MARIA_TERESA = id(9)
  val ANNA_MARIA = id(10)
  val GIAN_LUCA = id(11)
  val NICOLO = id(12)
  val ZIO_PIERO = id(13)
  val NONNA_ROSA = id(14)
  val ROSA = id(15)
  val NATALE = id(16)
  val MARINA = id(17)
  val ROMA = id(18)
  val LUCCA = id(19)
  val APRILE = id(20)
  val MAGGIO = id(21)
  val MARIO = id(22)
  val STEFANO = id(23)
  val BIANCHI = id(24)

  private fun entry(id: PersonId, label: String, count: Int) = PersonEntry(PeopleDictionary.normalize(label), id, label, count)

  val ENTRIES: List<PersonEntry> = listOf(
    entry(MARCO, "Marco", 120),
    entry(ANNA, "Anna", 80),
    entry(GIULIA, "Giulia", 60),
    entry(LUCA, "Luca", 45),
    entry(SARA, "Sara", 30),
    entry(ELENA, "Elena", 25),
    entry(PAOLO, "Paolo", 20),
    entry(CHIARA, "Chiara", 15),
    entry(MARIA_TERESA, "Maria Teresa", 12),
    entry(ANNA_MARIA, "Anna Maria", 7),
    entry(GIAN_LUCA, "Gian Luca", 9),
    entry(NICOLO, "Nicolò D'Amico", 5),
    entry(ZIO_PIERO, "Zio Piero", 8),
    entry(NONNA_ROSA, "Nonna Rosa", 11),
    entry(ROSA, "Rosa", 3),
    entry(NATALE, "Natale", 3),
    entry(MARINA, "Marina", 40),
    entry(ROMA, "Roma", 5),
    entry(LUCCA, "Lucca", 40),
    entry(APRILE, "Aprile", 6),
    entry(MAGGIO, "Maggio", 90),
    entry(MARIO, "Mario", 22),
    entry(STEFANO, "Stefano", 18),
    entry(BIANCHI, "Bianchi", 14),
  )

  /** Quanti media coprirebbe una data nel banco: Natale 40, Aprile 25, Maggio 60, il resto 10 (in produzione lo conta l'istantanea). */
  val DATES = DateMediaCount { chip ->
    when {
      chip.label.startsWith("Natale") -> 40
      chip.label.startsWith("Aprile") -> 25
      chip.label.startsWith("Maggio") -> 60
      else -> 10
    }
  }

  val DICTIONARY = PeopleDictionary(ENTRIES, DATES)

  /** I luoghi di `TestPlaces` e "Marina" (12 media): un luogo con lo stesso nome di una persona piu' fotografata. */
  val PLACES = PlaceDictionary(TestPlaces.DICTIONARY.entries + PlaceEntry("marina", PlaceOption(setOf(20), "Marina", PlaceLevel.PLACE), 12))
}
