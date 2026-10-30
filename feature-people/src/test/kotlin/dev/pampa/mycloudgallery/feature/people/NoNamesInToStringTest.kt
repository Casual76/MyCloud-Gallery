package dev.pampa.mycloudgallery.feature.people

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import com.google.common.truth.Truth.assertThat
import dev.antigravity.fluidengine.ui.theme.FluidTone
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.people.nav.PersonDestination
import dev.pampa.mycloudgallery.feature.people.nav.ReviewDestination
import dev.pampa.mycloudgallery.feature.people.section.SectionData
import dev.pampa.mycloudgallery.feature.people.section.SectionEntry
import org.junit.Test

/**
 * I4 (correzioni dopo le revisioni della Fase 8, pacchetto D): nessun `toString()` di Persone mostra un nome o l'id di una persona (D37, C120). Prima `SectionEntry`,
 * `SectionData`, `PersonDestination` e `ReviewDestination` stampavano tutto (titolo e sottotitolo con un nome, l'id della persona): oggi nessun log li stampa, ma un
 * log scritto domani o un messaggio di un'eccezione non deve poterlo fare. **Solo nomi inventati.**
 */
class NoNamesInToStringTest {

  private val person = PersonId.random()

  @Test
  fun leVociDiSezioneEIDestinazioniNonStampanoNomiNeId() {
    val entry = SectionEntry("person:1", "Marco Quarzo", "12 foto con Marco Quarzo", Icons.Rounded.Face, FluidTone.Primary, badge = 3, trailing = "Marco Quarzo")
    val section = SectionData("people", "Persone di Marco Quarzo", listOf(entry), hint = "Marco Quarzo non e' qui")
    val texts = listOf(entry.toString(), section.toString(), PersonDestination(person.value).toString(), ReviewDestination(person.value, split = true).toString())
    for (text in texts) {
      assertThat(text.lowercase()).doesNotContain("marco")
      assertThat(text.lowercase()).doesNotContain("quarzo")
      assertThat(text).doesNotContain(person.value)
    }
    // Il codice resta: e' fatto per non portare mai un nome ([PeopleEntryTarget]).
    assertThat(entry.toString()).contains("person:1")
  }
}
