package dev.pampa.mycloudgallery.core.faces

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.FacesActivationConsent
import dev.pampa.mycloudgallery.core.faces.contract.FacesDiag
import dev.pampa.mycloudgallery.core.faces.contract.FacesEraseConfirmation
import dev.pampa.mycloudgallery.core.faces.contract.FacesItem
import dev.pampa.mycloudgallery.core.faces.contract.FacesArea
import dev.pampa.mycloudgallery.core.faces.contract.FacesResource
import dev.pampa.mycloudgallery.core.faces.contract.FacesState
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.HereState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PeopleState
import dev.pampa.mycloudgallery.core.faces.contract.PersonNames
import dev.pampa.mycloudgallery.core.faces.contract.PersonPair
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.contract.AreaKind
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceGeneration
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.MediaKind
import dev.pampa.mycloudgallery.core.model.MediaSource
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.core.model.StableCode
import dev.pampa.mycloudgallery.core.trust.nas.NasLayout
import java.util.Random
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Fase 8 (P0): i tipi dei contratti di core-faces. Codici stabili e unici, invarianti, alias con catene e cicli (§2.5), l'ultima scrittura
 * delle voci `people` commutativa, associativa e idempotente (come F4-D26), una lettura sola delle voci ([PeopleState]), conferme che
 * scadono, e **nessun nome, id, posto o generazione** in un `toString()` (D37).
 */
class ContractTypesTest {

  private val key = ContentKey("aa".repeat(32))
  private val face = FaceLocator(key, 0, FaceBox(100, 100, 5_000, 6_000))
  private val marco = PersonId.random()
  private val anna = PersonId.random()

  @Test
  fun iCodiciSonoStabiliEUnici() {
    val enums: List<List<StableCode>> = listOf(
      AssignOrigin.entries, PersonState.entries, AreaKind.entries, FacesArea.entries,
      dev.pampa.mycloudgallery.core.faces.contract.FacesStop.entries, dev.pampa.mycloudgallery.core.faces.contract.FacesWait.entries,
      dev.pampa.mycloudgallery.core.faces.contract.FacesWriteBlock.entries, dev.pampa.mycloudgallery.core.faces.contract.FlushReason.entries,
      dev.pampa.mycloudgallery.core.faces.contract.EraseStep.entries, dev.pampa.mycloudgallery.core.faces.contract.StepState.entries,
      dev.pampa.mycloudgallery.core.faces.contract.AckState.entries, dev.pampa.mycloudgallery.core.faces.contract.EraseWait.entries,
      dev.pampa.mycloudgallery.core.faces.contract.PeopleAlbumsChoice.entries, dev.pampa.mycloudgallery.core.faces.contract.NasEraseReason.entries,
    )
    for (values in enums) {
      val codes = values.map { it.code }
      assertThat(codes.toSet()).hasSize(codes.size)
      codes.forEach { assertThat(it).matches("^[a-z0-9_]{1,32}$") }
    }
    // §2.4: i byte dell'istantanea.
    assertThat(AssignOrigin.entries.associate { it.wire to it.code }).containsExactly(1, "auto", 2, "user", 3, "ignored")
    assertThat(PersonState.entries.associate { it.wire to it.code }).containsExactly(1, "active", 2, "merged", 3, "forgotten")
    // Le aree delle unita' sono quelle dei percorsi delle risorse (D16).
    assertThat(AreaKind.PRIVATE.code).isEqualTo(NasLayout.FACES_AREA_PRIVATE)
    assertThat(AreaKind.COMMON.code).isEqualTo(NasLayout.FACES_AREA_COMMON)
    assertThat(FacesDiag.ALL.toSet()).hasSize(FacesDiag.ALL.size)
    FacesDiag.ALL.forEach { assertThat(it).matches("^(FACES|PEOPLE)_[A-Z_]+$") }
  }

  @Test
  fun gliAliasSiRisolvonoAncheInCatenaENeiCicli() {
    val (p, q, r) = listOf(PersonId.random(), PersonId.random(), PersonId.random()).sorted()
    fun person(id: PersonId, into: PersonId?) = GroupPerson(id, if (into == null) PersonState.ACTIVE else PersonState.MERGED, into, 1, 1, emptyList())
    val chain = GroupingState(1, 0, mapOf(p to person(p, q), q to person(q, r), r to person(r, null)), emptyMap())
    assertThat(chain.resolve(p)).isEqualTo(r)
    // Un ciclo (due unioni contrarie): la radice e' l'id minore del ciclo.
    val cycle = GroupingState(1, 0, mapOf(p to person(p, q), q to person(q, r), r to person(r, p)), emptyMap())
    assertThat(cycle.resolve(r)).isEqualTo(p)
    val forgotten = GroupingState(1, 0, mapOf(p to GroupPerson(p, PersonState.FORGOTTEN, null, 1, 1, emptyList())), emptyMap())
    assertThat(forgotten.resolve(p)).isNull()
    assertThat(chain.resolve(PersonId.random())).isNull()
    assertThrows(IllegalArgumentException::class.java) { GroupPerson(p, PersonState.MERGED, null, 1, 1, emptyList()) }
    assertThrows(IllegalArgumentException::class.java) { GroupPerson(p, PersonState.ACTIVE, q, 1, 1, emptyList()) }
    assertThat(PersonPair.of(q, p)).isEqualTo(PersonPair.of(p, q))
  }

  /** F4-D26 per le voci `people`: qualunque ordine e raggruppamento, stesso stato; una voce da datare vince (scelta appena fatta qui). */
  @Test
  fun lUltimaScritturaVinceInUnOrdineTotale() {
    val devices = listOf("0".repeat(26), "1".repeat(26), "2".repeat(26))
    val random = Random(3)
    val records = (0 until 200).map {
      val edit = when (random.nextInt(3)) {
        0 -> PeopleEdit.Name(if (random.nextBoolean()) marco else anna, if (random.nextBoolean()) null else "Nome${random.nextInt(4)}")
        1 -> PeopleEdit.Hidden(marco, random.nextBoolean())
        else -> PeopleEdit.Ignore(face, random.nextBoolean())
      }
      PeopleRecord(edit, if (random.nextInt(10) == 0) null else 1_800_000_000_000 + random.nextInt(5), devices[random.nextInt(3)])
    }
    val expected = PeopleRecord.merge(emptyMap(), records)
    repeat(20) {
      val shuffled = records.shuffled(random)
      val half = shuffled.size / 2
      val merged = PeopleRecord.merge(PeopleRecord.merge(emptyMap(), shuffled.take(half)), shuffled.drop(half))
      assertThat(merged).isEqualTo(expected)
      assertThat(PeopleRecord.merge(merged, shuffled)).isEqualTo(expected)
    }
    val undated = PeopleRecord(PeopleEdit.Hidden(marco, true), null, devices[2])
    val dated = PeopleRecord(PeopleEdit.Hidden(marco, false), 4_000_000_000_000, devices[0])
    assertThat(PeopleRecord.winner(dated, undated)).isEqualTo(undated)
  }

  /** Una lettura sola delle voci (D23, D24): nomi, dimenticate, alias, posti che si sostituiscono per IoU. */
  @Test
  fun leVociPeopleSiLeggonoInUnModoSolo() {
    val a = "0".repeat(26)
    val moved = FaceLocator(key, 0, FaceBox(200, 200, 5_100, 6_100))
    val state = PeopleState.of(
      listOf(
        PeopleRecord(PeopleEdit.Name(marco, "Marco"), 1, a),
        PeopleRecord(PeopleEdit.Name(anna, "Anna"), 1, a),
        PeopleRecord(PeopleEdit.Forget(anna), 2, a),
        PeopleRecord(PeopleEdit.Is(face, anna), 3, a),
        // Lo stesso volto, ricalcolato con un riquadro un po' diverso: vale la voce piu' recente.
        PeopleRecord(PeopleEdit.Is(moved, marco), 4, a),
        PeopleRecord(PeopleEdit.Apart(marco, anna, true), 5, a),
      ),
    )
    assertThat(state.names).containsExactly(marco, "Marco")
    assertThat(state.forgotten).containsExactly(anna)
    assertThat(state.fixed).containsExactly(moved, marco)
    assertThat(state.fixedFor(face)).isEqualTo(marco)
    assertThat(state.corrections().apart).containsExactly(PersonPair.of(marco, anna))
    assertThat(state.corrections().named).containsExactly(marco)
    assertThat(state.toString()).doesNotContain("Marco")
  }

  @Test
  fun nomiConfermeERisorse() {
    assertThat(PersonNames.normalize("  Marco  ")).isEqualTo("Marco")
    assertThat(PersonNames.normalize("")).isNull()
    assertThat(PersonNames.normalize("x".repeat(65))).isNull()
    assertThat(PersonNames.normalize("Ma‮rco")).isNull()
    assertThat(PersonNames.normalize("Ma\u0000rco")).isNull()
    assertThrows(IllegalArgumentException::class.java) { PeopleEdit.Name(marco, " Marco") }
    assertThat(PeopleEdit.Name(marco, "Marco").toString()).doesNotContain("Marco")
    assertThat(PeopleEdit.Is(face, marco).field).isEqualTo("is")
    assertThat(PeopleEdit.Not(face, marco, true).field).isEqualTo("not:${marco.base32()}")
    assertThat(PeopleEdit.Apart(marco, anna, false).value).isNull()
    val confirmation = FacesEraseConfirmation(previewDigest = 9, shownAtMonotonicNanos = 1_000)
    assertThat(confirmation.isValidAt(1_000 + FacesEraseConfirmation.VALIDITY_NANOS)).isTrue()
    assertThat(confirmation.isValidAt(1_001 + FacesEraseConfirmation.VALIDITY_NANOS)).isFalse()
    assertThat(confirmation.isValidAt(999)).isFalse()
    assertThrows(IllegalArgumentException::class.java) { FacesActivationConsent(0, 1) }
    assertThrows(IllegalArgumentException::class.java) { FacesResource.WorkUnit(AreaKind.PRIVATE, "2025-13", 0) }
    val screenshot = FacesItem(key, MediaId.random(), MediaKind.PHOTO, MediaSource.SCREENSHOT, false, false, "2025-07", FacesArea.PRIVATE)
    assertThat(screenshot.excluded).isTrue()
    assertThat(screenshot.copy(source = MediaSource.CAMERA).excluded).isFalse()
    assertThat(screenshot.copy(source = MediaSource.CAMERA, document = true).excluded).isTrue()
  }

  /** D37: nessun valore nei `toString()` degli stati e dei tipi dei volti. */
  @Test
  fun nessunValoreNeiLog() {
    val g = FaceGeneration.random()
    val values = listOf(
      FacesState.On(g, HereState.ACTIVE), FacesState.AwaitingNotice(g, 1), FacesState.Erasing(g),
      dev.pampa.mycloudgallery.core.faces.contract.PersonCard(marco, "Marco", 3, face),
      dev.pampa.mycloudgallery.core.faces.contract.FaceChip(face, marco, "Marco", dev.pampa.mycloudgallery.core.model.FaceTier.CORE),
      PeopleRecord(PeopleEdit.Name(marco, "Marco"), 1, "0".repeat(26)),
    )
    for (value in values) {
      val text = value.toString()
      assertWithMessage(text).that(text).doesNotContain(g.base32)
      assertWithMessage(text).that(text).doesNotContain("Marco")
      assertWithMessage(text).that(text).doesNotContain(marco.value)
      assertWithMessage(text).that(text).doesNotContain(key.hex.take(8))
    }
  }
}
