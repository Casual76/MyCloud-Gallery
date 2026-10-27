package dev.pampa.mycloudgallery.debugtools

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupPerson
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId
import dev.pampa.mycloudgallery.debugtools.eval.faces.EvalFaceInfo
import dev.pampa.mycloudgallery.debugtools.eval.faces.EvalGroupInfo
import dev.pampa.mycloudgallery.debugtools.eval.faces.EvalTotals
import dev.pampa.mycloudgallery.debugtools.eval.faces.FacesEvalEnvironment
import dev.pampa.mycloudgallery.debugtools.eval.faces.ThresholdValues
import dev.pampa.mycloudgallery.faces.FacesGraph
import dev.pampa.mycloudgallery.faces.FacesPeopleFacade
import kotlinx.coroutines.flow.first

/**
 * SOLO debug (Fase 8, P13 lega la porta di P11): lo strumento "Valutazione delle persone" sopra le persone montate, in **sola lettura**. I gruppi sono quelli
 * che Persone mostra davvero (con un nome, o in "Altri volti"); i volti sono solo di celle **mostrabili** (un nascosto perfetto non esce mai, nemmeno nei
 * conteggi, D28: il cancello e' quello dei ritagli, `ArchivePeople.mayShow`); i punteggi sono quelli del raggruppamento (`s(f, P)`: la media dei 3 coseni piu'
 * alti con gli esemplari della persona, escluso il volto stesso) e la seconda persona e' la migliore fra le altre; i volti senza vettore non ci sono. I vettori
 * restano in questa classe: escono solo coseni. Niente nei log.
 *
 * Sta fuori da `debugtools/eval/faces/` di proposito: quella cartella e' lo strumento di P11 e non nomina mai `core.faces` (`FacesEvalSourceParityTest`); questo
 * file e' il legame di P13 e vede le persone montate.
 */
class AppFacesEvalEnvironment(private val faces: FacesGraph, private val people: FacesPeopleFacade) : FacesEvalEnvironment {

  private var byLocator: Map<String, GroupFace> = emptyMap()
  private var byRef: Map<FaceRef, GroupFace> = emptyMap()
  private var grouping: GroupingState? = null

  override suspend fun prepare() {
    val live = faces.ensureLive() ?: error("Persone non e' montata.")
    val state = live.switch.currentState()
    check(state.activeHere) { "Persone non e' attiva qui." }
    val archive = live.archives.open(state.generationOrNull ?: error("Nessuna generazione.")) ?: error("L'archivio dei volti non si apre.")
    val all = archive.groupFaces()
    byLocator = all.associateBy { it.locator.encode() }
    byRef = all.associateBy { it.ref }
    grouping = archive.grouping()
  }

  override suspend fun groups(): List<EvalGroupInfo> {
    val list = people.list.value
    val others = people.others().first().people
    return (list.named + list.unnamed + others).distinctBy { it.id }.map { card ->
      EvalGroupInfo(card.id, faces = people.reviewFaces(card.id).size, photos = card.photos, named = card.name != null, cover = card.cover)
    }
  }

  override suspend fun faces(group: PersonId): List<EvalFaceInfo> {
    val state = grouping ?: return emptyList()
    val person = state.persons[state.resolve(group) ?: return emptyList()] ?: return emptyList()
    val rivals = state.persons.values.filter { it.state == PersonState.ACTIVE && it.id != person.id }
    return people.reviewFaces(group).mapNotNull { shown ->
      val face = faceOf(shown.face) ?: return@mapNotNull null
      val vector = face.vector()
      val own = similarity(vector, person, face.ref)
      val second = rivals.maxOfOrNull { rival -> similarity(vector, rival, null) }
      EvalFaceInfo(face.locator, core = face.tier == FaceTier.CORE, score = own, second = second)
    }
  }

  override suspend fun cosines(faces: List<FaceLocator>): FloatArray? {
    val resolved = faces.map { faceOf(it) ?: return null }
    val n = resolved.size
    val out = FloatArray(n * n)
    for (i in 0 until n) {
      val vi = resolved[i].vector()
      for (j in 0 until n) out[i * n + j] = resolved[j].dot(vi)
    }
    return out
  }

  override suspend fun totals(): EvalTotals {
    val state = grouping ?: return EvalTotals.EMPTY
    var core = 0
    var coreAssigned = 0
    var weak = 0
    var weakAssigned = 0
    for (face in byRef.values) {
      if (faces.live.value?.people?.mayShow(face.locator) != true) continue
      val assigned = state.assignments[face.ref]?.let { state.resolve(it.person) != null } == true
      if (face.tier == FaceTier.CORE) {
        core++
        if (assigned) coreAssigned++
      } else {
        weak++
        if (assigned) weakAssigned++
      }
    }
    val list = people.list.value
    return EvalTotals(core, coreAssigned, weak, weakAssigned, list.named.size + list.unnamed.size + list.othersCount)
  }

  override val thresholds: ThresholdValues = ThresholdValues(
    tJoin = GroupingParams.T_JOIN,
    tJoinWeak = GroupingParams.T_JOIN_WEAK,
    margin = GroupingParams.MARGIN,
    tEdge = GroupingParams.T_EDGE,
    tBirth = GroupingParams.T_BIRTH,
  )

  override suspend fun crop(face: FaceLocator): ImageBitmap? = faces.live.value?.crops?.crop(face, CROP_PX)?.asImageBitmap()

  private fun faceOf(locator: FaceLocator): GroupFace? = byLocator[locator.encode()] ?: byRef.values.firstOrNull { it.locator.matches(locator) }

  /** `s(f, P)`: la media dei 3 coseni piu' alti con gli esemplari di [person] (escluso [self]); -1 se la persona non ha esemplari. */
  private fun similarity(vector: FloatArray, person: GroupPerson, self: FaceRef?): Float {
    val best = person.exemplars.filter { it != self }.mapNotNull { byRef[it]?.dot(vector) }.sortedDescending().take(GroupingParams.TOP_EXEMPLARS)
    return if (best.isEmpty()) -1f else best.average().toFloat()
  }

  private companion object {
    const val CROP_PX = 160
  }
}
