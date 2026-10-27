package dev.pampa.mycloudgallery.feature.viewer.people

import com.google.common.truth.Truth.assertThat
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.feature.viewer.ViewerServices
import dev.pampa.mycloudgallery.feature.viewer.testing.FakeViewerPeople
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P0, D25, D37): la porta delle persone del viewer. Di serie non c'e' (nessuna riga "Persone"); i chip e le azioni non mostrano
 * mai nomi o id nei log; il fake applica le azioni come `People` (P9 lo usa per la riga e per "Chi e'?").
 */
class ViewerPeopleSignaturesTest {

  private val media = MediaId.random()
  private val marco = PersonId.random()
  private val face = ViewerFaceRef("volto-1")

  @Test
  fun diSerieNessunaRigaPersone() {
    assertThat(ViewerServices().people).isNull()
  }

  @Test
  fun chipEAzioniNonMostranoNomiNeId() {
    assertThat(ViewerFaceChip.Named(face, marco, "Marco").toString()).doesNotContain("Marco")
    assertThat(ViewerFaceAction.IsNew("Marco").toString()).doesNotContain("Marco")
    assertThat(ViewerFaceAction.Is(marco).toString()).doesNotContain(marco.value)
    assertThat(ViewerPersonChoice(marco, "Marco").toString()).doesNotContain("Marco")
    assertThat(face.toString()).doesNotContain("volto-1")
    assertThrows(IllegalArgumentException::class.java) { ViewerFaceRef("") }
    assertThrows(IllegalArgumentException::class.java) { ViewerFaceChip.Unrecognizable(0) }
  }

  @Test
  fun ilFakeApplicaLeAzioniComePeople() = runTest {
    val fake = FakeViewerPeople().person(marco, "Marco")
    val other = ViewerFaceRef("volto-2")
    fake.show(media, listOf(ViewerFaceChip.Unnamed(face, null), ViewerFaceChip.Unnamed(other, null), ViewerFaceChip.Unrecognizable(1)))
    assertThat(fake.act(media, face, ViewerFaceAction.Is(marco))).isEqualTo(ViewerFaceOutcome.DONE)
    assertThat(fake.act(media, other, ViewerFaceAction.Ignore)).isEqualTo(ViewerFaceOutcome.DONE)
    assertThat(fake.act(media, ViewerFaceRef("sparito"), ViewerFaceAction.Ignore)).isEqualTo(ViewerFaceOutcome.NOT_FOUND)
    assertThat(fake.act(media, face, ViewerFaceAction.IsNew(" "))).isEqualTo(ViewerFaceOutcome.INVALID_NAME)
    val chips = fake.faces(media).first()
    assertThat(chips).containsExactly(ViewerFaceChip.Named(face, marco, "Marco"), ViewerFaceChip.Unrecognizable(1)).inOrder()
    assertThat(fake.choices(media, face).map { it.person }).containsExactly(marco)
    fake.openPerson(marco)
    assertThat(fake.opened).containsExactly(marco)
  }
}
