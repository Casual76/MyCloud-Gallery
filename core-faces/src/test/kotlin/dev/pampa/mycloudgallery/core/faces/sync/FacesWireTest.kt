package dev.pampa.mycloudgallery.core.faces.sync

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.FaceBody
import dev.pampa.mycloudgallery.core.faces.contract.GroupAssignment
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PeopleEdit
import dev.pampa.mycloudgallery.core.faces.contract.PeopleRecord
import dev.pampa.mycloudgallery.core.faces.contract.PersonNames
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.FaceBox
import dev.pampa.mycloudgallery.core.model.FaceDetectorCode
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceSource
import dev.pampa.mycloudgallery.core.model.FaceSpaceCode
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.Float16
import dev.pampa.mycloudgallery.core.trust.Verdict
import dev.pampa.mycloudgallery.core.trust.crypto.Hashes
import dev.pampa.mycloudgallery.core.trust.crypto.TrustIds
import dev.pampa.mycloudgallery.core.trust.data.FacesDocs
import dev.pampa.mycloudgallery.core.trust.data.PeopleForms
import dev.pampa.mycloudgallery.core.trust.data.SnapshotMark
import java.nio.ByteBuffer
import org.junit.Test

/**
 * Il ponte fra i contratti e il formato (Fase 8, P3): i corpi si rileggono uguali con l'impronta giusta (le prime 8 byte dello SHA-256 del
 * corpo codificato: il punto aperto di P0 su `FaceRef.bodyDigest`, confermato); ogni `PeopleEdit` va e torna; il nome segue la stessa regola
 * in `PersonNames` (core-faces) e in `PeopleForms` (core-trust); un'istantanea va e torna risolta sui corpi vincenti.
 */
class FacesWireTest {

  private val author = TrustIds.random()
  private val key = FacesSyncWorld.key(1)
  private val entries = listOf(FacesSyncWorld.entry(0), FacesSyncWorld.entry(1, FaceTier.WEAK), FacesSyncWorld.entry(2, FaceTier.NONE))

  private fun build(faces: List<dev.pampa.mycloudgallery.core.faces.contract.FaceEntry> = entries) =
    FacesWire.build(key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2026MAY, 3, FaceSource.HQ_TILES, author, faces)

  @Test
  fun ilCorpoHaLImprontaDeiSuoiByte() {
    val body = build()!!
    val bytes = FacesWire.encodedOrNull(body)!!
    assertThat(body.digest).isEqualTo(ByteBuffer.wrap(Hashes.sha256(bytes)).long)
    assertThat(FacesWire.admits(body)).isTrue()
    val back = FacesWire.body(key, author, (FacesDocs.decodeBody(bytes) as Verdict.Accepted).value, FacesDocs.bodyDigest(bytes))
    assertThat(back.faces.map { it.tier }).isEqualTo(body.faces.map { it.tier })
    assertThat(back.faces.map { it.box }).isEqualTo(body.faces.map { it.box })
    assertThat(back.faces[0].vector!!.toList()).isEqualTo(body.faces[0].vector!!.toList())
    assertThat(back.refOf(1)).isEqualTo(body.refOf(1))
    // Un'impronta inventata (i fake di P0) non si pubblica: i riferimenti non tornerebbero sugli altri dispositivi.
    val fake = FaceBody(key, body.space, body.detector, body.recipe, body.source, author, body.faces, 42)
    assertThat(FacesWire.admits(fake)).isFalse()
  }

  @Test
  fun unCorpoCheIlFormatoRifiuterebbeNonSiCostruisce() {
    val nan = ShortArray(FaceSpaceCode.CURRENT.dimension) { 0x7e00 }
    val bad = FacesSyncWorld.entry(0).let {
      dev.pampa.mycloudgallery.core.faces.contract.FaceEntry(0, 0, it.box, it.landmarks, it.score, FaceTier.CORE, 0, it.sidePx, it.yaw, it.sharpness, nan)
    }
    assertThat(build(listOf(bad))).isNull()
    val notNormalized = Float16.encode(FloatArray(FaceSpaceCode.CURRENT.dimension) { 0.5f })
    val loud = dev.pampa.mycloudgallery.core.faces.contract.FaceEntry(0, 0, bad.box, bad.landmarks, 200, FaceTier.CORE, 0, 80, 0, 100, notNormalized)
    assertThat(build(listOf(loud))).isNull()
    // Un fotogramma fuori da un video.
    val frame = dev.pampa.mycloudgallery.core.faces.contract.FaceEntry(0, 2, bad.box, bad.landmarks, 200, FaceTier.WEAK, 0, 30, 0, 100, FacesSyncWorld.vector(1))
    assertThat(build(listOf(frame))).isNull()
  }

  @Test
  fun ogniVocePeopleVaETorna() {
    val a = FacesSyncWorld.person(1)
    val b = FacesSyncWorld.person(2)
    val face = FaceLocator(key, 0, FaceBox(10, 20, 3_000, 4_000))
    val edits = listOf(
      PeopleEdit.Name(a, "Persona Uno"), PeopleEdit.Name(a, null), PeopleEdit.Hidden(a, true), PeopleEdit.Hidden(a, false),
      PeopleEdit.Forget(a), PeopleEdit.Alias(a, b), PeopleEdit.Apart(a, b, true), PeopleEdit.Apart(a, b, false), PeopleEdit.Born(b, a),
      PeopleEdit.Cover(a, face), PeopleEdit.Cover(a, null), PeopleEdit.Is(face, a), PeopleEdit.Is(face, null),
      PeopleEdit.Not(face, a, true), PeopleEdit.Not(face, a, false), PeopleEdit.Ignore(face, true), PeopleEdit.Ignore(face, false),
    )
    for (edit in edits) {
      val record = PeopleRecord(edit, 1_800_000_000_000L, author)
      val doc = FacesWire.doc(record, record.atNas!!)
      assertWithMessage(edit.toString()).that(doc).isNotNull()
      val bytes = FacesDocs.encodePeople(doc!!)
      assertThat(FacesWire.record(doc)).isEqualTo(record)
      assertThat(bytes.size).isGreaterThan(0)
    }
    // Una persona nata da se stessa non e' una voce del formato: non si pubblica.
    assertThat(FacesWire.doc(PeopleRecord(PeopleEdit.Born(a, a), 1_800_000_000_000L, author), 1_800_000_000_000L)).isNull()
  }

  /**
   * D24 e L362 (correzioni C): la regola del nome e' la stessa in core-faces (chi scrive: `forNewName`, con almeno un carattere che si veda) e in core-trust (chi
   * legge: `PeopleForms.isName`). Un nome fatto solo di giunti e selettori di variante non si scrive e non si legge.
   */
  @Test
  fun laRegolaDelNomeELaStessa() {
    val samples = listOf(
      "Anna", "Anna Maria", " Anna", "Anna ", "", " ", "x".repeat(64), "x".repeat(65), "An‮na", "An​na", "An\u0007na",
      "Zoë", "李小龙", "👩‍👩‍👧", "\uD83D", "Anna ", "Anna﻿", "O'Brien", "Ana-Lucía",
      "\u200D\uFE0F\u200D", "\u200D", "\uFE0F", "\u2764", "\u2764\uFE0F", "?",
    )
    for (sample in samples) {
      val writer = PersonNames.forNewName(sample) == sample
      assertWithMessage("'${sample.map { it.code }}'").that(PeopleForms.isName(sample)).isEqualTo(writer)
    }
    // `normalize` resta piu' larga (rilegge anche i dati locali scritti prima): l'unica differenza sono i nomi che non si vedono.
    val zwj = "\u200D\uFE0F\u200D"
    assertThat(PersonNames.normalize(zwj)).isEqualTo(zwj)
    assertThat(PeopleForms.isName(zwj)).isFalse()
  }

  @Test
  fun lIstantaneaVaETornaRisoltaSuiCorpiVincenti() {
    val body = build()!!
    val p = FacesSyncWorld.person(1)
    val q = FacesSyncWorld.person(2)
    val r0 = body.refOf(0)
    val r1 = body.refOf(1)
    val state = GroupingState(
      seq = 5,
      base = 3,
      persons = mapOf(
        p to GroupPerson(p, PersonState.ACTIVE, null, 1, 5, listOf(r0)),
        q to GroupPerson(q, PersonState.MERGED, p, 2, 4, emptyList()),
      ),
      assignments = mapOf(
        r0 to GroupAssignment(r0, FaceLocator(key, 0, body.faces[0].box), p, 0.81f, AssignOrigin.AUTO),
        r1 to GroupAssignment(r1, FaceLocator(key, 0, body.faces[1].box), p, -0.2f, AssignOrigin.USER),
      ),
    )
    val faces = listOf(0, 1).map { GroupFace(body.refOf(it), FaceLocator(key, 0, body.faces[it].box), body.faces[it].tier, Float16.decode(body.faces[it].vector!!)) }
    val wire = FacesWire.snapshotEntries(state, faces)!!
    val doc = (FacesDocs.checkSnapshot(SnapshotMark(5, 3), wire) as Verdict.Accepted).value
    val back = FacesWire.resolve("dispositivo", doc, mapOf(key to body))
    assertThat(back.unresolved).isEqualTo(0)
    assertThat(back.state.persons.keys).containsExactly(p, q)
    assertThat(back.state.resolve(q)).isEqualTo(p)
    assertThat(back.state.assignments.getValue(r1).score).isWithin(1e-4f).of(-0.2f)
    assertThat(back.state.assignments.getValue(r0).locator).isEqualTo(FaceLocator(key, 0, body.faces[0].box))
    assertThat(back.centroids.keys).containsExactly(p)
    // Un corpo vincente diverso (ricetta piu' alta altrove): i volti sono "nuovi", le assegnazioni non tornano.
    val newer = FacesWire.build(key, FaceSpaceCode.CURRENT, FaceDetectorCode.YUNET_2026MAY, 4, FaceSource.HQ_TILES, author, entries)!!
    assertThat(FacesWire.resolve("dispositivo", doc, mapOf(key to newer)).unresolved).isEqualTo(2)
    // Un'istantanea vuota o con una persona cambiata dopo la sua seq non si pubblica.
    assertThat(FacesWire.snapshotEntries(GroupingState.EMPTY, faces)).isNull()
    val late = GroupingState(2, 1, mapOf(p to GroupPerson(p, PersonState.ACTIVE, null, 1, 5, emptyList())), emptyMap())
    assertThat(FacesWire.snapshotEntries(late, faces)).isNull()
  }
}
