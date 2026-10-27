package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.antigravity.fluidengine.ui.theme.FluidTone
import kotlin.random.Random
import org.junit.Test

/**
 * L'ordine e la visibilita' delle sezioni delle Raccolte (Fase 7, P11, D33, §2.7, E-L14), logica pura su JVM: l'ordine predefinito, una
 * sezione nuova in coda, un ordine salvato che non si rompe, le sezioni che mancano un momento, "Album non si nasconde".
 */
class CollectionsLayoutTest {

  private fun section(code: String, rows: Int = 1, hideable: Boolean = true): CollectionsSection = CollectionsSection(
    code = code,
    title = SectionText.Raw(code),
    rows = List(rows) { SectionRow.Entry(CollectionEntry("$code/$it", "$code $it", "", Icons.Rounded.Folder, FluidTone.Neutral)) },
    hideable = hideable,
  )

  private fun codes(sections: List<CollectionsSection>) = sections.map { it.code }

  private val all = CollectionSections.defaultOrder

  // --- L'ordine predefinito (§2.7) ------------------------------------------------------------------------------------------------------

  @Test
  fun senzaUnOrdineSalvatoValeQuelloDelPianoQualunqueSiaOrdineDiArrivo() {
    val shuffled = all.shuffled(Random(5))
    val arranged = CollectionsLayout.arrange(shuffled.map { section(it) }, saved = emptyList(), hidden = emptySet())
    assertThat(codes(arranged)).containsExactly(
      "albums", "smart", "trips", "people", "memories", "places", "nas", "phone", "safe", "utility", "hidden",
    ).inOrder()
    assertThat(codes(arranged)).isEqualTo(CollectionSections.defaultOrder)
  }

  @Test
  fun lOrdinePredefinitoDelPianoHaIlPostoRiservatoAPersoneERicordi() {
    assertThat(CollectionSections.defaultOrder.indexOf("people")).isEqualTo(3)
    assertThat(CollectionSections.defaultOrder.indexOf("memories")).isEqualTo(4)
    // Fase 9 (D30): Luoghi subito dopo i Ricordi.
    assertThat(CollectionSections.defaultOrder.indexOf("places")).isEqualTo(5)
    // Una sezione che non e' nel piano va in fondo, come arriva.
    val arranged = CollectionsLayout.arrange(listOf(section("zeta"), section("albums"), section("beta")), emptyList(), emptySet())
    assertThat(codes(arranged)).containsExactly("albums", "zeta", "beta").inOrder()
  }

  @Test
  fun unaSezioneSenzaRigheNonCeEUnDoppioneVaPerIlPrimo() {
    val arranged = CollectionsLayout.arrange(
      listOf(section("nas", rows = 0), section("phone"), section("phone", rows = 3), section("hidden")), emptyList(), emptySet(),
    )
    assertThat(codes(arranged)).containsExactly("phone", "hidden").inOrder()
    assertThat(arranged.first().rows).hasSize(1)
  }

  // --- Un ordine salvato, e una sezione nuova in coda (E-L14) -----------------------------------------------------------------

  @Test
  fun unOrdineSalvatoSiRispettaEUnaSezioneNuovaVaInCodaSenzaRompereLOrdine() {
    val saved = listOf("hidden", "phone", "albums", "nas")
    // Persone arriva con la Fase 8: non e' nell'ordine salvato.
    val arranged = CollectionsLayout.arrange(listOf("albums", "people", "nas", "phone", "hidden").map { section(it) }, saved, emptySet())
    assertThat(codes(arranged)).containsExactly("hidden", "phone", "albums", "nas", "people").inOrder()
  }

  @Test
  fun piuSezioniNuoveVannoInCodaNelLoroOrdinePredefinito() {
    val saved = listOf("phone", "albums")
    val present = listOf("hidden", "memories", "albums", "utility", "people", "phone", "x_altro").map { section(it) }
    val arranged = CollectionsLayout.arrange(present, saved, emptySet())
    assertThat(codes(arranged)).containsExactly("phone", "albums", "people", "memories", "utility", "hidden", "x_altro").inOrder()
  }

  @Test
  fun unaSezioneSalvataCheOggiNonCeSiSaltaERiprendeIlSuoPostoQuandoTorna() {
    val saved = listOf("nas", "phone", "hidden")
    assertThat(codes(CollectionsLayout.arrange(listOf("phone", "hidden").map { section(it) }, saved, emptySet())))
      .containsExactly("phone", "hidden").inOrder()
    assertThat(codes(CollectionsLayout.arrange(listOf("phone", "nas", "hidden").map { section(it) }, saved, emptySet())))
      .containsExactly("nas", "phone", "hidden").inOrder()
  }

  @Test
  fun unOrdineSalvatoConDoppioniOCodiciStraniNonRompeNiente() {
    val saved = listOf("phone", "phone", "zzz", "hidden", "phone")
    val arranged = CollectionsLayout.arrange(listOf("hidden", "phone", "albums").map { section(it) }, saved, emptySet())
    assertThat(codes(arranged)).containsExactly("phone", "hidden", "albums").inOrder()
  }

  // --- Visibilita' --------------------------------------------------------------------------------------------------------------------

  @Test
  fun unaSezioneNascostaHaShownFalsoMaRestaNellaLista() {
    val arranged = CollectionsLayout.arrange(listOf("albums", "trips", "hidden").map { section(it) }, emptyList(), setOf("trips", "hidden"))
    assertThat(codes(arranged)).containsExactly("albums", "trips", "hidden").inOrder()
    assertThat(arranged.associate { it.code to it.shown }).containsExactly("albums", true, "trips", false, "hidden", false)
  }

  @Test
  fun albumNonSiNascondeMaiQualunqueCosaDicaLaVoceSincronizzata() {
    val arranged = CollectionsLayout.arrange(listOf(section("albums"), section("trips")), emptyList(), setOf("albums", "trips"))
    val albums = arranged.first { it.code == "albums" }
    assertThat(albums.shown).isTrue()
    assertThat(albums.hideable).isFalse()
    assertThat(arranged.first { it.code == "trips" }.shown).isFalse()
  }

  @Test
  fun unaSezioneCheDichiaraDiNonPoterSiNascondereNonSiNasconde() {
    val arranged = CollectionsLayout.arrange(listOf(section("nas", hideable = false)), emptyList(), setOf("nas"))
    assertThat(arranged.single().shown).isTrue()
    assertThat(arranged.single().hideable).isFalse()
  }

  @Test
  fun lo_StatoDiceLeRigheCheSiVedonoEUnaSezioneNascostaNonLeDa() {
    val state = CollectionsLayout.state(
      present = listOf(section("albums", rows = 2), section("trips", rows = 3)),
      legacy = emptyList(),
      saved = emptyList(),
      hidden = setOf("trips"),
      editing = false,
      saveFailed = false,
    )
    assertThat(state.sections).hasSize(2)
    assertThat(codes(state.visible)).containsExactly("albums")
    assertThat(state.visibleRows).hasSize(2)
    assertThat(state.findEntry("trips/0")).isNull()
    assertThat(state.findEntry("albums/1")).isNotNull()
  }

  // --- I gruppi di :app che non hanno ancora un fornitore ----------------------------------------------------------------------------

  private fun entry(code: String) = CollectionEntry(code, code, "", Icons.Rounded.Folder, FluidTone.Neutral)

  @Test
  fun iGruppiDiAppSenzaCodiceLoPrendonoDalleLoroVoci() {
    assertThat(CollectionSections.codeOf(CollectionEntryGroup("Sul NAS", listOf(entry("nas/folders"), entry("nas/space"))))).isEqualTo("nas")
    assertThat(CollectionSections.codeOf(CollectionEntryGroup("Utilità", listOf(entry("backup/free-space"))))).isEqualTo("utility")
    assertThat(CollectionSections.codeOf(CollectionEntryGroup("Altro", listOf(entry("Strano!/x"))))).isEqualTo("x_strano")
    assertThat(CollectionSections.codeOf(CollectionEntryGroup("Vuoto", emptyList()))).isNull()
    assertThat(CollectionSections.codeOf(CollectionEntryGroup("Dichiarato", listOf(entry("a")), code = "trips"))).isEqualTo("trips")
    // Il codice ricavato e' sempre scrivibile nelle impostazioni.
    for (head in listOf("nas", "backup", "Ünicode", "1due", "a".repeat(80), "")) {
      val code = CollectionSections.codeOf(CollectionEntryGroup("g", listOf(entry("$head/x"))))
      assertWithMessage(head).that(code == null || CollectionSections.isValidCode(code)).isTrue()
    }
  }

  @Test
  fun unGruppoDiAppSiAggiungeSoloSeNessunoHaGiaIlSuoCodice() {
    val nas = CollectionEntryGroup("Sul NAS", listOf(entry("nas/folders")))
    val util = CollectionEntryGroup("Utilità", listOf(entry("backup/free-space")))
    val state = CollectionsLayout.state(
      present = listOf(section("utility", rows = 2), section("phone")),
      legacy = listOf(nas, util, CollectionEntryGroup("Vuoto", emptyList())),
      saved = emptyList(), hidden = emptySet(), editing = false, saveFailed = false,
    )
    // Utility ce l'ha gia' un fornitore: il gruppo di :app non lo sostituisce e non lo raddoppia. Il NAS si mette al suo posto del piano.
    assertThat(codes(state.sections)).containsExactly("nas", "phone", "utility").inOrder()
    assertThat(state.sections.first { it.code == "utility" }.rows).hasSize(2)
  }

  // --- Salvare un ordine -------------------------------------------------------------------------------------------------------------

  @Test
  fun leSezioniCheOggiNonCiSonoTenganoIlLoroPostoQuandoSiSalvaUnOrdine() {
    // Il NAS non e' ancora configurato: l'utente porta Nascosti sopra Telefono. Il NAS non si perde, e resta dov'era.
    val saved = listOf("nas", "phone", "hidden")
    assertThat(CollectionsLayout.toSave(listOf("hidden", "phone"), saved)).containsExactly("nas", "hidden", "phone").inOrder()
    // Una sezione assente in mezzo segue quella che la precedeva.
    assertThat(CollectionsLayout.toSave(listOf("phone", "albums"), listOf("albums", "nas", "phone")))
      .containsExactly("phone", "albums", "nas").inOrder()
  }

  @Test
  fun alPrimoSalvataggioLeSezioniDelPianoCheMancanoTenganoIlPostoPredefinitoMaPersoneERicordiNo() {
    val saved = CollectionsLayout.toSave(listOf("hidden", "phone", "albums"), previousSaved = emptyList())
    assertThat(saved).containsExactly("hidden", "phone", "safe", "utility", "albums", "smart", "trips", "nas").inOrder()
    assertThat(saved).containsNoneOf("people", "memories", "places")
    // Quando Persone arriva (Fase 8) e' nuova: in coda, e l'ordine scelto non si rompe.
    val later = CollectionsLayout.arrange(saved.map { section(it) } + section("people"), saved, emptySet())
    assertThat(codes(later)).containsExactlyElementsIn(saved + "people").inOrder()
  }

  @Test
  fun seLePersoneCiSonoGiaAlSalvataggioSiTengonoDovel_UtenteLeHaMesse() {
    val chosen = listOf("albums", "smart", "trips", "people", "nas", "phone", "hidden")
    val saved = CollectionsLayout.toSave(chosen, previousSaved = emptyList())
    assertThat(saved.indexOf("people")).isEqualTo(chosen.indexOf("people"))
    assertThat(saved.filter { it in chosen }).containsExactlyElementsIn(chosen).inOrder()
  }

  @Test
  fun unOrdineSalvatoNonSuperaIlTettoDelleVoci() {
    val many = (1..60).map { "s$it" }
    assertThat(CollectionsLayout.toSave(many, emptyList())).hasSize(CollectionSections.MAX_SECTIONS)
  }

  /** La prova che conta: quello che si salva si rilegge nell'ordine scelto, con le sezioni assenti ancora al loro posto. */
  @Test
  fun ProprietaSalvareERileggereDaLOrdineScelto_ELeSezioniAssentiNonSiPerdono() {
    val random = Random(11)
    val universe = CollectionSections.defaultOrder + listOf("x_uno", "x_due")
    repeat(2_000) {
      val previous = if (random.nextInt(4) == 0) emptyList() else universe.shuffled(random).take(random.nextInt(1, universe.size + 1))
      val present = universe.shuffled(random).take(random.nextInt(1, universe.size + 1))
      val chosen = present.shuffled(random)
      val saved = CollectionsLayout.toSave(chosen, previous)
      // L'ordine scelto per le sezioni che ci sono e' quello che si rilegge.
      assertWithMessage("scelto=$chosen prima=$previous salvato=$saved")
        .that(CollectionsLayout.orderOf(chosen, saved)).isEqualTo(chosen)
      // Un codice che era nell'ordine salvato prima non sparisce (fino al tetto).
      assertWithMessage("prima=$previous salvato=$saved").that(saved).containsAtLeastElementsIn(previous.distinct())
      assertThat(saved).containsNoDuplicates()
    }
  }

  // --- Spostare ----------------------------------------------------------------------------------------------------------------------

  @Test
  fun spostareUnaSezioneScambiaConLaVicinaEInCimaOInFondoNonFaNiente() {
    val order = listOf("a", "b", "c")
    assertThat(CollectionsLayout.moved(order, "b", -1)).containsExactly("b", "a", "c").inOrder()
    assertThat(CollectionsLayout.moved(order, "b", +1)).containsExactly("a", "c", "b").inOrder()
    assertThat(CollectionsLayout.moved(order, "a", -1)).isNull()
    assertThat(CollectionsLayout.moved(order, "c", +1)).isNull()
    assertThat(CollectionsLayout.moved(order, "z", +1)).isNull()
    assertThat(CollectionsLayout.moved(order, "a", 0)).isNull()
  }

  // --- Fase 9 (D30, E-L14): Luoghi ---------------------------------------------------------------------------------------------

  /** Luoghi e' una sezione futura come Persone e Ricordi: al primo salvataggio non prende il posto predefinito, quando arriva va in coda. */
  @Test
  fun luoghiArrivaDopoEVaInCodaAUnOrdineGiaSalvato() {
    assertThat(CollectionSections.isValidCode(CollectionSections.PLACES)).isTrue()
    assertThat(CollectionSections.reservedForLater).containsExactly("people", "memories", "places")
    assertThat(CollectionSections.neverHidden).doesNotContain("places")
    val saved = CollectionsLayout.toSave(listOf("hidden", "phone", "albums", "memories"), previousSaved = emptyList())
    assertThat(saved).doesNotContain("places")
    val later = CollectionsLayout.arrange(saved.map { section(it) } + section("places"), saved, emptySet())
    assertThat(codes(later)).containsExactlyElementsIn(saved + "places").inOrder()
    // Senza ordine salvato sta subito dopo i Ricordi, e si puo' nascondere come le altre.
    val fresh = CollectionsLayout.arrange(listOf("places", "albums", "memories", "nas").map { section(it) }, emptyList(), setOf("places"))
    assertThat(codes(fresh)).containsExactly("albums", "memories", "places", "nas").inOrder()
    assertThat(fresh.single { it.code == "places" }.shown).isFalse()
  }
}
