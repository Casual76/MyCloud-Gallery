package dev.pampa.mycloudgallery.core.faces.people

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.faces.contract.EditOutcome
import dev.pampa.mycloudgallery.core.faces.contract.MatchLevel
import dev.pampa.mycloudgallery.core.faces.contract.PeopleWorld
import dev.pampa.mycloudgallery.core.faces.contract.PersonNames
import dev.pampa.mycloudgallery.core.faces.contract.PersonTarget
import dev.pampa.mycloudgallery.core.faces.testing.FakeSearchHistoryPurge
import dev.pampa.mycloudgallery.core.model.PeopleMode
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.VisibleText
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Correzioni dopo le revisioni della Fase 8, pacchetto D, nelle persone di `core-faces` (archivio vero):
 *  - **R2**: "Rinomina", "Dimentica" e "Nascondi persona" tolgono **subito** dalla cronologia le ricerche con quella persona, per id e per il nome di prima
 *    (anche le persone unite in lei); un'azione che non cambia niente non tocca la cronologia; una cronologia che non risponde non fa fallire l'azione;
 *  - **R9**: un nome fatto solo di giunti ZWJ e selettori non si scrive; un emoji o un nome in un altro alfabeto si';
 *  - **R10**: `contentWith` **lancia** se i volti non si leggono adesso ("non so" non vale "nessuna foto");
 *  - **NV-1**: `knows` dice che una persona con le foto tutte nascoste c'e' ancora, e che una dimenticata no.
 * **Solo nomi inventati.**
 */
@RunWith(AndroidJUnit4::class)
class PeopleCorrDTest {

  @get:Rule
  val folder = TemporaryFolder()

  private var count = 0

  private fun rig(history: FakeSearchHistoryPurge = FakeSearchHistoryPurge()) =
    PeopleRig(File(folder.root, "banco-${count++}"), PeopleWorld(), namedOnly = true, history = history)

  // --- R2 -----------------------------------------------------------------------------------------------------------------------

  @Test
  fun rinominareTogliSubitoLeRicercheDelNomeDiPrimaEIdDellaPersona() = runBlocking<Unit> {
    val history = FakeSearchHistoryPurge()
    rig(history).use { r ->
      val a = r.world.a
      // Dare il primo nome: niente di prima da togliere.
      assertThat(r.people.rename(a, "Giulia")).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).isEmpty()
      // Correggere il nome: "Giulia al mare" non deve restare nelle ricerche recenti.
      assertThat(r.people.rename(a, "Giulia R.")).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).containsExactly("Giulia")
      assertThat(history.personsAsked).containsExactly(a)
      // Lo stesso nome un'altra volta non cambia niente: la cronologia non si tocca.
      assertThat(r.people.rename(a, "Giulia R.")).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).hasSize(1)
      // "Togli il nome" toglie il nome di prima.
      assertThat(r.people.rename(a, null)).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).containsExactly("Giulia", "Giulia R.").inOrder()
    }
  }

  @Test
  fun nascondereEDimenticareTolgonoLeRicercheDellaPersonaPerIdEPerNome() = runBlocking<Unit> {
    val history = FakeSearchHistoryPurge()
    rig(history).use { r ->
      val a = r.world.a
      val b = r.world.b
      r.people.rename(a, "Marco")
      r.people.rename(b, "Anna")
      // Nascondere: il nome non si propone piu' (E-F28), nemmeno nelle recenti. Mostrare di nuovo non toglie niente.
      assertThat(r.people.setHidden(a, true)).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).containsExactly("Marco")
      assertThat(history.personsAsked).containsExactly(a)
      assertThat(r.people.setHidden(a, true)).isEqualTo(EditOutcome.Done)
      assertThat(r.people.setHidden(a, false)).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).hasSize(1)
      // Dimenticare: gruppo e nome spariscono, e con loro le ricerche.
      assertThat(r.people.forget(b)).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).containsExactly("Marco", "Anna").inOrder()
      assertThat(history.personsAsked).containsExactly(a, b).inOrder()
    }
  }

  @Test
  fun leRicercheDiUnGruppoSenzaNomeDimenticatoSiTolgonoPerId() = runBlocking<Unit> {
    val history = FakeSearchHistoryPurge()
    rig(history).use { r ->
      // Un gruppo senza nome non ha nomi da togliere, ma l'id si': una ricerca nata dal suo chip (aveva un nome prima) esce lo stesso.
      assertThat(r.people.forget(r.world.b)).isEqualTo(EditOutcome.Done)
      assertThat(history.namesAsked).isEmpty()
      assertThat(history.personsAsked).containsExactly(r.world.b)
    }
  }

  @Test
  fun lePersoneUniteInUnaVengonoTolteInsiemeEUnaCronologiaCheNonRispondeNonFaFallireLAzione() = runBlocking<Unit> {
    val history = FakeSearchHistoryPurge()
    rig(history).use { r ->
      val a = r.world.a
      val b = r.world.b
      r.people.rename(a, "Marco")
      // b si unisce ad a: una ricerca nata dal chip di b (id di prima dell'unione) deve uscire quando si rinomina a.
      assertThat(r.people.merge(listOf(a, b), keepNameOf = a)).isEqualTo(EditOutcome.Done)
      assertThat(r.people.rename(a, "Marco Q.")).isEqualTo(EditOutcome.Done)
      assertThat(history.personsAsked).containsExactly(a, b)
      // La cronologia non risponde: l'azione e' fatta lo stesso (si ritolgono con "Disattiva e cancella").
      history.failing = true
      assertThat(r.people.rename(a, "Marco Quarzo")).isEqualTo(EditOutcome.Done)
      assertThat(r.people.list.value.named.map { it.name }).containsExactly("Marco Quarzo")
    }
  }

  // --- R9 -----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unNomeInvisibileNonSiScriveUnEmojiEUnAltroAlfabetoSi() = runBlocking<Unit> {
    rig().use { r ->
      val a = r.world.a
      val zwjOnly = "\u200D\uFE0F\u200D"
      // Solo giunti e selettori di variante: appare vuoto. (`TextRules` li ammette: `PeopleForms.isName` e' di core-trust.)
      assertThat(r.people.rename(a, zwjOnly)).isEqualTo(EditOutcome.InvalidName)
      assertThat(r.people.assign(r.world.facesOf(r.world.b).first().locator, PersonTarget.New(zwjOnly))).isEqualTo(EditOutcome.InvalidName)
      assertThat(PersonNames.forNewName(zwjOnly)).isNull()
      // Visibili: un emoji (anche con il suo selettore), un segno, un nome in cirillico e in cinese.
      for (name in listOf("❤", "❤\uFE0F", "?", "Анна", "李明")) {
        assertThat(PersonNames.forNewName(name)).isEqualTo(name)
        assertThat(r.people.rename(a, name)).isEqualTo(EditOutcome.Done)
      }
      assertThat(VisibleText.hasVisible("  \u200D ")).isFalse()
      assertThat(VisibleText.hasVisible("a")).isTrue()
      // Resta la regola del formato: il nome di una lettura non cambia (`normalize` e `PeopleForms.isName` sono la stessa regola).
      assertThat(PersonNames.normalize(zwjOnly)).isEqualTo(zwjOnly)
    }
  }

  // --- R10 ----------------------------------------------------------------------------------------------------------------------------

  @Test
  fun contentWithLanciaSeIVoltiNonSiLeggonoAdessoEUnVuotoVeroRestaUnVuoto() = runBlocking<Unit> {
    rig().use { r ->
      val a = r.world.a
      // Un vuoto vero: una persona che non c'e' (dimenticata) non ha foto.
      assertThat(r.people.contentWith(setOf(PersonId.random()), PeopleMode.ANY, MatchLevel.SURE)).isEmpty()
      assertThat(r.people.contentWith(setOf(a), PeopleMode.ANY, MatchLevel.SURE)).isNotEmpty()
      // Persone non attiva qui (esclusione, cancellazione): "non so", non "nessuna foto".
      r.available.value = false
      assertThrows(IllegalStateException::class.java) { runBlocking { r.people.contentWith(setOf(a), PeopleMode.ANY, MatchLevel.SURE) } }
      r.available.value = true
      // L'archivio che adesso non si apre (il Keystore non risponde): lo stesso.
      r.store.close()
      assertThrows(IllegalStateException::class.java) { runBlocking { r.people.contentWith(setOf(a), PeopleMode.ANY, MatchLevel.SURE) } }
    }
  }

  // --- NV-1 ---------------------------------------------------------------------------------------------------------------------------

  @Test
  fun unaPersonaConLeFotoTutteNascosteCeAncoraEUnaDimenticataNo() = runBlocking<Unit> {
    rig().use { r ->
      val c = r.world.c
      r.people.rename(c, "Solo nascosti")
      // La pagina non c'e' (D28: nessuna foto da mostrare)...
      assertThat(r.people.person(c).first()).isNull()
      // ...ma la persona c'e' ancora: sono le foto a essere nascoste, non e' dimenticata.
      assertThat(r.people.knows(c)).isTrue()
      assertThat(r.people.knows(r.world.a)).isTrue()
      assertThat(r.people.forget(c)).isEqualTo(EditOutcome.Done)
      assertThat(r.people.person(c).first()).isNull()
      assertThat(r.people.knows(c)).isFalse()
      assertThat(r.people.knows(PersonId.random())).isFalse()
      r.available.value = false
      assertThat(r.people.knows(r.world.a)).isFalse()
    }
  }

  @Test
  fun unaPersonaUnitaAUnAltraCeAncora() = runBlocking<Unit> {
    rig().use { r ->
      assertThat(r.people.merge(listOf(r.world.a, r.world.b), keepNameOf = null)).isEqualTo(EditOutcome.Done)
      assertThat(r.people.knows(r.world.a)).isTrue()
      assertThat(r.people.knows(r.world.b)).isTrue()
    }
  }
}
