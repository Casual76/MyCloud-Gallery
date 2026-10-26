package dev.pampa.mycloudgallery.core.albums.smart

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.albums.contract.PlaceRule
import dev.pampa.mycloudgallery.core.albums.contract.PlaceScope
import dev.pampa.mycloudgallery.core.albums.contract.SmartMediaType
import dev.pampa.mycloudgallery.core.albums.contract.SmartPeriod
import dev.pampa.mycloudgallery.core.albums.contract.SmartRules
import dev.pampa.mycloudgallery.core.albums.contract.SmartSource
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import org.junit.Test

/**
 * Le regole del §2.2 una per una (D14): A, B e C sicuri; S1-S5 suggeriti; i "mai". Candidati scritti a mano attorno a Oslo, coordinate a distanze
 * note dal centro. E-AL3, E-AL4, E-AL10, E-AL11, E-AL17 e i bordi di B (6 ore) e di S3 (1 e 2 volte il raggio).
 */
class SmartClassifierTest {
  private val d1 = day(2026, 3, 10)
  private val d2 = d1.plusDays(1)
  private val d3 = d1.plusDays(2)
  private val d4 = d1.plusDays(3)
  private val trip = ranges(d1 to d4)
  private val oslo = around(Spots.OSLO, 25)
  private val inOslo = Spots.north(Spots.OSLO, 3.0)

  private fun classify(
    rules: SmartRules,
    items: List<SmartCandidate>,
    content: ContentMatches = ContentMatches.NONE,
    excluded: Set<MediaId> = emptySet(),
    owned: Set<MediaId> = emptySet(),
    devices: DeviceNormalizer = DeviceNormalizer("Backup/questo-telefono"),
  ): Map<MediaId, SmartVerdict> =
    SmartClassifier(rules, rules.place?.let { PlaceMatcher(it, Spots.LOOKUP) }, devices, content, excluded, owned).classify(DayIndex(items))

  private fun Map<MediaId, SmartVerdict>.of(n: Int): SmartVerdict? = this[id(n)]

  private val sureA = SmartVerdict.Sure(SureRule.A)
  private val sureB = SmartVerdict.Sure(SureRule.B)
  private val sureC = SmartVerdict.Sure(SureRule.C)

  private fun suggested(reason: SmartReason) = SmartVerdict.Suggested(reason)

  // --- A -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun aDataEGpsEntroIlRaggioSonoSicuri() {
    val v = classify(SmartRules(trip, oslo, null), listOf(cand(1, d1, gps = inOslo), cand(2, d4, gps = Spots.north(Spots.OSLO, 24.9))))
    assertThat(v.of(1)).isEqualTo(sureA)
    assertThat(v.of(2)).isEqualTo(sureA)
  }

  @Test
  fun fraUnoEDueVolteIlRaggioEsuggeritoS3EOltreDueVolteEFuori() {
    val v = classify(
      SmartRules(trip, oslo, null),
      listOf(cand(1, d1, gps = Spots.north(Spots.OSLO, 25.5)), cand(2, d1, gps = Spots.north(Spots.OSLO, 49.5)), cand(3, d1, gps = Spots.north(Spots.OSLO, 51.0))),
    )
    assertThat(v.of(1)).isEqualTo(suggested(SmartReason.S3))
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S3))
    assertThat(v.of(3)).isNull()
  }

  @Test
  fun fuoriDalPeriodoNonEMaiNienteNeppureConIlGpsGiusto() {
    val v = classify(SmartRules(trip, oslo, null), listOf(cand(1, d1.minusDays(1), gps = inOslo), cand(2, d4.plusDays(1), gps = inOslo)))
    assertThat(v).isEmpty()
  }

  @Test
  fun unGpsCheContraddiceIlLuogoEFuoriAnchePerLaDescrizione() {
    val rules = SmartRules(trip, oslo, "gatto")
    val far = cand(1, d1, gps = Spots.BERGEN)
    val v = classify(rules, listOf(far), content = ContentMatches(setOf(far.media), emptySet(), used = true))
    assertThat(v).isEmpty()
  }

  @Test
  fun unFamiliareConIlGpsGiustoENonSicuroE_AL17() {
    val v = classify(
      SmartRules(trip, oslo, null),
      listOf(
        cand(1, d2, gps = inOslo, source = SmartSource.FAMILY, device = null),
        cand(2, d2, gps = inOslo, source = SmartSource.COMMON, device = null),
        // Un mio media passato nell'area comune resta mio (P1: ownedByMe) e puo' essere sicuro.
        cand(3, d2, gps = inOslo, source = SmartSource.COMMON, device = null, owned = true),
      ),
    )
    assertThat(v.of(1)).isEqualTo(suggested(SmartReason.S1))
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S1))
    assertThat(v.of(3)).isEqualTo(sureA)
  }

  @Test
  fun leSorgentiELiTipiNonAmmessiSonoFuori() {
    val rules = SmartRules(trip, oslo, null, sources = setOf(SmartSource.MY_PHONE), types = setOf(SmartMediaType.PHOTO))
    val v = classify(
      rules,
      listOf(
        cand(1, d2, gps = inOslo),
        cand(2, d2, gps = inOslo, source = SmartSource.MY_PRIVATE),
        cand(3, d2, gps = inOslo, kind = MediaKind.VIDEO),
        cand(4, d2, gps = inOslo, kind = MediaKind.MOTION_PHOTO),
      ),
    )
    assertThat(v.keys).containsExactly(id(1), id(4))
  }

  // --- B e S5 ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun bUnaFotoSenzaGpsFraDueSicuriDelloStessoDispositivoESicura() {
    val v = classify(
      SmartRules(trip, oslo, null),
      listOf(cand(1, d2, 10 * 60, gps = inOslo), cand(2, d2, 12 * 60), cand(3, d2, 14 * 60, gps = inOslo)),
    )
    assertThat(v.of(2)).isEqualTo(sureB)
  }

  @Test
  fun bIlBordoDelleSeiOreEStretto() {
    // Ancore alle 10:00 e alle 17:59 (7h59m): la foto delle 12:00 e' a 2h dalla prima e a 5h59m dalla seconda: sicura.
    val inside = classify(SmartRules(trip, oslo, null), listOf(cand(1, d2, 10 * 60, gps = inOslo), cand(2, d2, 12 * 60), cand(3, d2, 17 * 60 + 59, gps = inOslo)))
    assertThat(inside.of(2)).isEqualTo(sureB)
    // A esattamente 6 ore dall'ancora dopo: non e' "a meno di 6 ore".
    val edge = classify(SmartRules(trip, oslo, null), listOf(cand(1, d2, 10 * 60, gps = inOslo), cand(2, d2, 12 * 60), cand(3, d2, 18 * 60, gps = inOslo)))
    assertThat(edge.of(2)).isEqualTo(suggested(SmartReason.S5))
    // Esattamente 6 ore dopo la prima ancora, 2 ore dalla seconda.
    val edgeBefore = classify(SmartRules(trip, oslo, null), listOf(cand(1, d2, 6 * 60, gps = inOslo), cand(2, d2, 12 * 60), cand(3, d2, 14 * 60, gps = inOslo)))
    assertThat(edgeBefore.of(2)).isEqualTo(suggested(SmartReason.S5))
  }

  @Test
  fun bServonoDueAncoreUnaSolaNonBasta() {
    val v = classify(SmartRules(trip, oslo, null), listOf(cand(1, d2, 10 * 60, gps = inOslo), cand(2, d2, 12 * 60), cand(3, d2, 13 * 60)))
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S5))
    assertThat(v.of(3)).isEqualTo(suggested(SmartReason.S5))
  }

  @Test
  fun bAncheAttraversandoLaMezzanotte() {
    // Ancore alle 23:00 di d2 e alle 03:00 di d3; la foto di 01:00 del giorno dopo e' a 2h da entrambe.
    val v = classify(SmartRules(trip, oslo, null), listOf(cand(1, d2, 23 * 60, gps = inOslo), cand(2, d3, 60), cand(3, d3, 3 * 60, gps = inOslo)))
    assertThat(v.of(2)).isEqualTo(sureB)
  }

  @Test
  fun bVuoleLoStessoDispositivoELeDueEtichetteDelTelefonoValgonoLoStesso() {
    val items = listOf(
      cand(1, d2, 10 * 60, gps = inOslo, device = "phone"),
      // Lo stesso telefono dopo "Libera spazio": la copia sul telefono non c'e' piu', resta il backup nella propria cartella.
      cand(2, d2, 12 * 60, device = "backup:questo-telefono"),
      cand(3, d2, 12 * 60 + 5, device = "backup:tablet-di-casa"),
      cand(4, d2, 12 * 60 + 10, device = null),
      cand(5, d2, 14 * 60, gps = inOslo, device = "phone"),
    )
    val v = classify(SmartRules(trip, oslo, null), items)
    assertThat(v.of(2)).isEqualTo(sureB)
    // Un altro dispositivo (E-AL3) e un'origine sconosciuta non sono mai B: sono dubbi.
    assertThat(v.of(3)).isEqualTo(suggested(SmartReason.S1))
    assertThat(v.of(4)).isEqualTo(suggested(SmartReason.S1))
  }

  @Test
  fun bIlTabletConIlSuoGpsFaLaSuaB() {
    val items = listOf(
      cand(1, d2, 10 * 60, gps = inOslo, device = "backup:tablet-di-casa", source = SmartSource.MY_PRIVATE),
      cand(2, d2, 12 * 60, device = "backup:tablet-di-casa", source = SmartSource.MY_PRIVATE),
      cand(3, d2, 14 * 60, gps = inOslo, device = "backup:tablet-di-casa", source = SmartSource.MY_PRIVATE),
      cand(4, d2, 12 * 60, device = "phone"),
    )
    val v = classify(SmartRules(trip, oslo, null), items)
    assertThat(v.of(2)).isEqualTo(sureB)
    // Il telefono non ha punti fermi quel giorno: la sua foto senza GPS e' un dubbio.
    assertThat(v.of(4)).isEqualTo(suggested(SmartReason.S1))
  }

  @Test
  fun e_AL11UnGiornoSenzaGpsDelViaggioVaInSuggerite() {
    // d1 e d3 hanno il GPS; d2 e' un giorno intero senza posizione: tutte le sue foto sono S5 (stesso dispositivo, fuori dalla finestra di B).
    val items = listOf(
      cand(1, d1, 20 * 60, gps = inOslo),
      cand(2, d2, 9 * 60), cand(3, d2, 13 * 60), cand(4, d2, 19 * 60),
      cand(5, d3, 9 * 60, gps = inOslo),
    )
    val v = classify(SmartRules(trip, oslo, null), items)
    assertThat(listOf(2, 3, 4).map { v.of(it) }).containsExactly(suggested(SmartReason.S5), suggested(SmartReason.S5), suggested(SmartReason.S5))
  }

  @Test
  fun bAdAncoraEscluseNonValgono() {
    val items = listOf(cand(1, d2, 10 * 60, gps = inOslo), cand(2, d2, 12 * 60), cand(3, d2, 14 * 60, gps = inOslo))
    val v = classify(SmartRules(trip, oslo, null), items, excluded = setOf(id(1)))
    assertThat(v.of(1)).isNull()
    // Una foto esclusa per sbaglio di posizione non regge piu' la B dei vicini (prudenza).
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S5))
  }

  @Test
  fun unFamiliareSenzaGpsESempreS1EMaiBAncheFraAncoreSue() {
    val items = listOf(
      cand(1, d2, 10 * 60, gps = inOslo, device = "phone-di-marco", source = SmartSource.FAMILY),
      cand(2, d2, 12 * 60, device = "phone-di-marco", source = SmartSource.FAMILY),
      cand(3, d2, 14 * 60, gps = inOslo, device = "phone-di-marco", source = SmartSource.FAMILY),
    )
    val v = classify(SmartRules(trip, oslo, null), items)
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S1))
  }

  // --- S2 -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun e_AL4GliScreenshotEDunqueLeRegistrazioniDelloSchermoSonoSempreESoloSuggeriti() {
    val items = listOf(
      cand(1, d2, 10 * 60, gps = inOslo),
      cand(2, d2, 12 * 60, screen = true),
      cand(3, d2, 12 * 60 + 1, gps = inOslo, screen = true),
      cand(4, d2, 12 * 60 + 2, kind = MediaKind.VIDEO, screen = true),
      cand(5, d2, 14 * 60, gps = inOslo),
    )
    val v = classify(SmartRules(trip, oslo, null), items)
    assertThat(listOf(2, 3, 4).map { v.of(it) }).containsExactly(suggested(SmartReason.S2), suggested(SmartReason.S2), suggested(SmartReason.S2))
  }

  @Test
  fun unoScreenshotNonEMaUnPuntoFermo() {
    val items = listOf(cand(1, d2, 10 * 60, gps = inOslo, screen = true), cand(2, d2, 12 * 60), cand(3, d2, 14 * 60, gps = inOslo))
    val v = classify(SmartRules(trip, oslo, null), items)
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S5))
  }

  // --- Fusi (E-AL10) ----------------------------------------------------------------------------------------------------------

  @Test
  fun e_AL10IlViaggioInUnAltroFusoRestaNeiSuoiGiorniLocali() {
    // New York, UTC-5: la foto delle 23:30 locali del 10 marzo e' l'una e mezza del giorno dopo in UTC, ma e' del 10 (giorno locale dello scatto).
    val ny = around(Spots.NEW_YORK, 25, listOf(Spots.NEW_YORK_ID))
    val last = cand(1, d1, 23 * 60 + 30, gps = Spots.north(Spots.NEW_YORK, 2.0), zone = -300)
    val first = cand(2, d1.plusDays(1), 8 * 60, gps = Spots.north(Spots.NEW_YORK, 2.0), zone = -300)
    val onlyFirstDay = ranges(d1 to d1)
    val v = classify(SmartRules(onlyFirstDay, ny, null), listOf(last, first))
    assertThat(v.of(1)).isEqualTo(sureA)
    assertThat(v.of(2)).isNull()
  }

  // --- Senza periodo -------------------------------------------------------------------------------------------------------------

  @Test
  fun unLuogoSenzaPeriodoPrendeTuttiIGpsGiustiEIlResto() {
    val items = listOf(
      cand(1, d1, gps = inOslo), cand(2, day(2020, 7, 1), gps = inOslo), cand(3, d1, gps = Spots.ROMA),
      // Senza periodo una foto senza GPS non si lega al luogo (S1, S2 e S5 sono "nel periodo")...
      cand(4, d1, 5 * 60), cand(5, d1, 7 * 60, screen = true),
    )
    val v = classify(SmartRules(null, oslo, null), items)
    assertThat(v.keys).containsExactly(id(1), id(2))
  }

  @Test
  fun unLuogoSenzaPeriodoPrendeComunqueLaBFraDueAncore() {
    val items = listOf(cand(1, d1, 10 * 60, gps = inOslo), cand(2, d1, 12 * 60), cand(3, d1, 14 * 60, gps = inOslo))
    val v = classify(SmartRules(null, oslo, null), items)
    assertThat(v.of(2)).isEqualTo(sureB)
  }

  // --- Regioni e paesi ---------------------------------------------------------------------------------------------------------

  @Test
  fun unaRegioneValeComeLuogoEUnPaesePiuInternoDellaStessaRegioneNonResta() {
    val puglia = PlaceRule(listOf(Spots.LECCE_ID, Spots.BARI_ID), Spots.LECCE, SmartLimitsMax, "Puglia", PlaceScope.REGION)
    val items = listOf(
      cand(1, d1, gps = Spots.LECCE, placeId = Spots.LECCE_ID),
      // Un luogo di Puglia che la regola non conosceva: il catalogo lo conosce e dice che e' in Puglia.
      cand(2, d1, gps = GeoPointNear, placeId = 3173435),
      cand(3, d1, gps = Spots.ROMA, placeId = Spots.ROMA_ID),
      // Senza un luogo (place_id 0) non si sa in che regione sta.
      cand(4, d1, gps = GeoPointNear, placeId = 0),
    )
    val v = classify(SmartRules(ranges(d1 to d1), puglia, null), items)
    assertThat(v.of(1)).isEqualTo(sureA)
    assertThat(v.of(2)).isEqualTo(sureA)
    assertThat(v.of(3)).isNull()
    assertThat(v.of(4)).isNull()
  }

  // --- C e S4 ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun cSenzaLuogoLaDescrizioneSicuraNelPeriodoESicuraELaMenoProbabileESuggerita() {
    val items = listOf(cand(1, d2), cand(2, d2), cand(3, d2), cand(4, d2), cand(5, d1.minusDays(9)))
    val content = ContentMatches(setOf(id(1), id(5)), setOf(id(2)), used = true)
    val v = classify(SmartRules(trip, null, "gatto"), items, content)
    assertThat(v.of(1)).isEqualTo(sureC)
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S4))
    assertThat(v.of(3)).isNull()
    assertThat(v.of(4)).isNull()
    // Fuori dal periodo non entra, sia pure sicura.
    assertThat(v.of(5)).isNull()
  }

  @Test
  fun cSenzaPeriodoNeLuogoSoloLaDescrizioneSicura() {
    val items = listOf(cand(1, d1), cand(2, day(2021, 1, 1)), cand(3, d1))
    val content = ContentMatches(setOf(id(1), id(2)), setOf(id(3)), used = true)
    val v = classify(SmartRules(null, null, "gatto"), items, content)
    assertThat(v.of(1)).isEqualTo(sureC)
    assertThat(v.of(2)).isEqualTo(sureC)
    assertThat(v.of(3)).isEqualTo(suggested(SmartReason.S4))
  }

  @Test
  fun cUnFamiliareEUnoScreenshotNonSonoMaiSicuri() {
    val items = listOf(cand(1, d2, source = SmartSource.FAMILY, device = null), cand(2, d2, screen = true))
    val content = ContentMatches(setOf(id(1), id(2)), emptySet(), used = true)
    val v = classify(SmartRules(trip, null, "gatto"), items, content)
    assertThat(v.of(1)).isEqualTo(suggested(SmartReason.S1))
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S2))
  }

  @Test
  fun senzaIlMotoreCeS4TacionoLAlbumDiSolaDescrizioneEVuoto() {
    val items = listOf(cand(1, d2), cand(2, d2))
    val none = classify(SmartRules(trip, null, "gatto"), items, ContentMatches.NONE)
    assertThat(none).isEmpty()
    // Con un luogo le regole A e B ci sono lo stesso (D15): solo la descrizione tace.
    val withPlace = classify(SmartRules(trip, oslo, "gatto"), listOf(cand(3, d2, gps = inOslo), cand(4, d2)), ContentMatches.NONE)
    assertThat(withPlace.of(3)).isEqualTo(sureA)
    // Un punto fermo solo, anche nello stesso istante, non e' "fra due sicuri": la foto senza GPS e' S5 (stesso dispositivo, fuori da B).
    assertThat(withPlace.of(4)).isEqualTo(suggested(SmartReason.S5))
  }

  @Test
  fun conUnLuogoLaDescrizioneAggiungeSoloSuggeritiAChiNonHaAltro() {
    // Senza periodo una foto senza GPS non e' di questo luogo: solo la descrizione la porta, e solo come dubbio (S4).
    val items = listOf(cand(1, d1), cand(2, d1), cand(3, d1, gps = inOslo))
    val content = ContentMatches(setOf(id(1)), setOf(id(2)), used = true)
    val v = classify(SmartRules(null, oslo, "gatto"), items, content)
    assertThat(v.of(1)).isEqualTo(suggested(SmartReason.S4))
    assertThat(v.of(2)).isEqualTo(suggested(SmartReason.S4))
    assertThat(v.of(3)).isEqualTo(sureA)
  }

  // --- Solo il periodo ----------------------------------------------------------------------------------------------------------

  @Test
  fun soloIlPeriodoLaRegolaASenzaLuogoMieiSicuriFamiliariEScreenshotDubbi() {
    val items = listOf(
      cand(1, d2), cand(2, d2, source = SmartSource.MY_PRIVATE, device = "backup:tablet-di-casa"),
      cand(3, d2, source = SmartSource.FAMILY, device = null), cand(4, d2, screen = true), cand(5, d1.minusDays(1)),
      cand(6, d2, kind = MediaKind.VIDEO),
    )
    val v = classify(SmartRules(trip, null, null), items)
    assertThat(v.of(1)).isEqualTo(sureA)
    assertThat(v.of(2)).isEqualTo(sureA)
    assertThat(v.of(3)).isEqualTo(suggested(SmartReason.S1))
    assertThat(v.of(4)).isEqualTo(suggested(SmartReason.S2))
    assertThat(v.of(5)).isNull()
    assertThat(v.of(6)).isEqualTo(sureA)
  }

  @Test
  fun everyYearEDaysFunzionanoComePeriodo() {
    val every = SmartPeriod.EveryYear(java.time.MonthDay.of(12, 24), java.time.MonthDay.of(1, 6))
    val v = classify(SmartRules(every, null, null), listOf(cand(1, day(2024, 12, 31)), cand(2, day(2025, 1, 5)), cand(3, day(2025, 1, 7)), cand(4, day(2025, 6, 1))))
    assertThat(v.keys).containsExactly(id(1), id(2))
    val days = SmartPeriod.Days(setOf(day(2024, 3, 31), day(2025, 4, 20)))
    val w = classify(SmartRules(days, null, null), listOf(cand(1, day(2024, 3, 31)), cand(2, day(2024, 3, 30)), cand(3, day(2025, 4, 20))))
    assertThat(w.keys).containsExactly(id(1), id(3))
  }

  // --- I mai ---------------------------------------------------------------------------------------------------------------------

  @Test
  fun gliEsclusiELeScelteDellUtenteNonHannoVerdetto() {
    val items = listOf(cand(1, d2, gps = inOslo), cand(2, d2, gps = inOslo), cand(3, d2, gps = inOslo))
    val v = classify(SmartRules(trip, oslo, null), items, excluded = setOf(id(1)), owned = setOf(id(2)))
    assertThat(v.keys).containsExactly(id(3))
  }

  @Test
  fun senzaPeriodoLuogoNeDescrizioneLAlbumEVuoto() {
    assertThat(classify(SmartRules(null, null, null), listOf(cand(1, d1)))).isEmpty()
    assertThat(classify(SmartRules(null, null, "  "), listOf(cand(1, d1)))).isEmpty()
  }

  private companion object {
    const val SmartLimitsMax = 300
    val GeoPointNear = dev.pampa.mycloudgallery.core.model.GeoPoint(40.05, 18.0)
  }
}
