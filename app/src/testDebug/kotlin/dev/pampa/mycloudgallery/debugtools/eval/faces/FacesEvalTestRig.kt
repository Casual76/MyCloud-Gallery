package dev.pampa.mycloudgallery.debugtools.eval.faces

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import dev.pampa.mycloudgallery.core.faces.contract.AssignOrigin
import dev.pampa.mycloudgallery.core.faces.contract.GroupFace
import dev.pampa.mycloudgallery.core.faces.contract.GroupingInput
import dev.pampa.mycloudgallery.core.faces.contract.GroupingParams
import dev.pampa.mycloudgallery.core.faces.contract.GroupingState
import dev.pampa.mycloudgallery.core.faces.contract.PersonState
import dev.pampa.mycloudgallery.core.faces.grouping.FastGrouping
import dev.pampa.mycloudgallery.core.faces.testing.FamilyStories
import dev.pampa.mycloudgallery.core.faces.testing.GroupingMath
import dev.pampa.mycloudgallery.core.faces.testing.GroupingStories
import dev.pampa.mycloudgallery.core.model.ContentKey
import dev.pampa.mycloudgallery.core.model.FaceLocator
import dev.pampa.mycloudgallery.core.model.FaceRef
import dev.pampa.mycloudgallery.core.model.FaceTier
import dev.pampa.mycloudgallery.core.model.PersonId

/**
 * Il banco di prova dello strumento "Valutazione delle persone": una famiglia **sintetica** ([FamilyStories], vettori di identita' inventate: nessuna foto, nessun
 * volto, nessun nome) raggruppata dal nucleo vero ([FastGrouping]) e offerta come [FacesEvalEnvironment], con le stesse regole che P13 deve rispettare: solo volti di
 * media mostrabili (i contenuti in [hidden] non escono mai), i punteggi del raggruppamento, i volti NONE assenti. I "ritagli" sono sagome astratte colorate
 * per identita': servono agli screenshot e non somigliano a nessuno. Qui la verita' ([truth]) si conosce, e l'utente ideale dei test la usa per segnare.
 */
internal class FacesEvalTestRig(
  val story: FamilyStories.Story,
  val state: GroupingState,
  private val namedGroups: Int = 0,
) : FacesEvalEnvironment {

  /** I contenuti nascosti: i loro volti non escono da nessun metodo della porta (e non entrano nei conteggi). */
  val hidden = LinkedHashSet<ContentKey>()
  var prepared = 0
    private set

  /** Le chiamate alla porta, per provare che lo strumento non chiede piu' del dovuto. */
  var facesCalls = 0
    private set

  private val byRef: Map<FaceRef, GroupFace> = story.faces.associateBy { it.ref }
  private val byLocator: Map<FaceLocator, GroupFace> = story.faces.associateBy { it.locator }

  val truth: Map<FaceRef, Int> get() = story.truth

  private fun visible(ref: FaceRef): Boolean = ref.contentKey !in hidden

  /** Le persone attive con almeno 3 volti visibili e i loro volti visibili. */
  fun members(): Map<PersonId, List<GroupFace>> =
    FamilyStories.groups(state).mapValues { (_, refs) -> refs.filter(::visible).mapNotNull { byRef[it] } }
      .filter { (p, faces) -> faces.size >= 3 && state.persons[p]?.state == PersonState.ACTIVE }

  override suspend fun prepare() {
    prepared++
  }

  override suspend fun groups(): List<EvalGroupInfo> = members().entries.sortedBy { it.key.value }.mapIndexed { index, (id, faces) ->
    EvalGroupInfo(id, faces.size, faces.map { it.photo }.distinct().size, named = index < namedGroups, cover = faces.minByOrNull { it.ref }?.locator)
  }

  private fun exemplarsOf(person: PersonId): List<GroupFace> = state.persons[person]?.exemplars.orEmpty().mapNotNull { byRef[it] }.filter { visible(it.ref) }

  override suspend fun faces(group: PersonId): List<EvalFaceInfo> {
    facesCalls++
    val faces = members()[group] ?: return emptyList()
    val others = members().keys.filter { it != group }.associateWith { exemplarsOf(it) }
    val own = exemplarsOf(group)
    return faces.mapNotNull { f ->
      val score = GroupingMath.similarity(f, own) ?: return@mapNotNull null
      EvalFaceInfo(f.locator, core = f.tier == FaceTier.CORE, score = score, second = others.values.mapNotNull { GroupingMath.similarity(f, it) }.maxOrNull())
    }
  }

  override suspend fun cosines(faces: List<FaceLocator>): FloatArray? {
    val v = faces.map { byLocator[it]?.takeIf { f -> visible(f.ref) } ?: return null }
    return FloatArray(v.size * v.size) { v[it / v.size].cosine(v[it % v.size]) }
  }

  override suspend fun totals(): EvalTotals {
    val shown = story.faces.filter { visible(it.ref) }
    val assigned = state.assignments.values.filter { it.origin != AssignOrigin.IGNORED }.map { it.face }.toSet()
    val core = shown.filter { it.tier == FaceTier.CORE }
    val weak = shown.filter { it.tier == FaceTier.WEAK }
    return EvalTotals(core.size, core.count { it.ref in assigned }, weak.size, weak.count { it.ref in assigned }, members().size)
  }

  /** Le soglie con cui il banco ha formato i gruppi (quelle di `GroupingParams` che gli si danno alla nascita, di norma quelle di partenza). */
  override var thresholds: ThresholdValues = ThresholdValues(
    GroupingParams.T_JOIN, GroupingParams.T_JOIN_WEAK, GroupingParams.MARGIN, GroupingParams.T_EDGE, GroupingParams.T_BIRTH,
  )

  /** Una sagoma astratta (sfondo, testa, spalle) con il colore dell'identita' vera: mai un volto. */
  override suspend fun crop(face: FaceLocator): ImageBitmap? {
    val f = byLocator[face]?.takeIf { visible(it.ref) } ?: return null
    val hue = (story.truth.getValue(f.ref) * 47 % 360).toFloat()
    val image = ImageBitmap(SIZE, SIZE)
    val canvas = Canvas(image)
    val paint = Paint()
    paint.color = Color.hsv(hue, 0.45f, 0.80f)
    canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), paint)
    paint.color = Color.hsv(hue, 0.20f, 0.97f)
    canvas.drawCircle(Offset(SIZE / 2f, SIZE * 0.38f), SIZE * 0.22f, paint)
    canvas.drawOval(SIZE * 0.18f, SIZE * 0.66f, SIZE * 0.82f, SIZE * 1.25f, paint)
    return image
  }

  companion object {
    private const val SIZE = 64

    /** Una famiglia di prova: 8 identita' (una coppia di fratelli) in 90 foto, raggruppata come sul telefono. */
    fun family(seed: Long = 7L, named: Int = 3, photos: Int = 90, params: GroupingParams = GroupingParams.DEFAULT, siblings: Int = 1, identities: Int = 8): FacesEvalTestRig {
      val story = FamilyStories.story(
        FamilyStories.Config(seed = seed, identities = identities, photos = photos, maxFacesPerPhoto = 3, groupShare = 0.1f, weakShare = 0.1f, siblings = siblings, strangerShare = 0.05f),
      )
      val ids = GroupingStories.ids(seed)
      val state = FastGrouping().incremental(GroupingInput(GroupingState.EMPTY, story.faces, params = params.copy(birthsEvery = 1)), ids).state
      return FacesEvalTestRig(story, state, named).also {
        it.thresholds = ThresholdValues(params.tJoin, params.tJoinWeak, params.margin, params.tEdge, params.tBirth)
      }
    }

    /** I valori con cui il banco forma gruppi **sbagliati** apposta (soglie molto larghe): i fratelli finiscono insieme e la regola ha qualcosa da correggere. */
    val LOOSE = GroupingParams(tJoin = 0.30f, tJoinWeak = 0.40f, margin = 0f, tEdge = 0.30f, tBirth = 0.33f)
  }
}
