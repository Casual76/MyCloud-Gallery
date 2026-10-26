package dev.pampa.mycloudgallery.feature.albums

import dev.pampa.mycloudgallery.core.albums.contract.HiddenChoice
import dev.pampa.mycloudgallery.core.albums.contract.ShareConfirmation
import dev.pampa.mycloudgallery.core.albums.contract.ShareExclusion
import dev.pampa.mycloudgallery.core.albums.contract.ShareItemAction
import dev.pampa.mycloudgallery.core.albums.contract.SharePlan
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanItem
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanKind
import dev.pampa.mycloudgallery.core.albums.contract.SharePlanOutcome
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpId
import dev.pampa.mycloudgallery.core.albums.contract.ShareOpState
import dev.pampa.mycloudgallery.core.albums.contract.ShareRequest
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunResult
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunStop
import dev.pampa.mycloudgallery.core.albums.contract.ShareRunner
import dev.pampa.mycloudgallery.core.albums.contract.ShareStartOutcome
import dev.pampa.mycloudgallery.core.model.AlbumId
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.model.ShareLevel
import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow

// Il banco dei fogli di condivisione (Fase 7, P10): un `ShareRunner` "a copione" che registra ogni richiesta e ogni `start`, e piani scritti a mano. Solo test.

/** Un `ShareRunner` a copione: risponde con [planOutcome] e [startOutcome], e registra cosa gli si chiede. */
class ScriptedRunner(
  var planOutcome: (ShareRequest) -> SharePlanOutcome,
  var startOutcome: (SharePlan, ShareConfirmation) -> ShareStartOutcome = { _, _ -> ShareStartOutcome.Started(ShareOpId("op-1")) },
) : ShareRunner {
  val plans: MutableList<ShareRequest> = mutableListOf()
  val starts: MutableList<Pair<SharePlan, ShareConfirmation>> = mutableListOf()

  override val operations: MutableStateFlow<List<ShareOpState>> = MutableStateFlow(emptyList())

  override suspend fun plan(request: ShareRequest): SharePlanOutcome {
    plans += request
    return planOutcome(request)
  }

  override suspend fun start(plan: SharePlan, confirmation: ShareConfirmation): ShareStartOutcome {
    starts += plan to confirmation
    return startOutcome(plan, confirmation)
  }

  override suspend fun resumeAll(budget: Duration): ShareRunResult = ShareRunResult(0, 0, 0, 0, ShareRunStop.FINISHED)

  override suspend fun cancel(op: ShareOpId) = Unit
}

object ShareFixtures {
  val ALBUM = AlbumId("00000000-0000-4000-8000-0000000000a1")

  fun media(n: Int): MediaId = MediaId("00000000-0000-4000-8000-%012d".format(n))

  fun item(n: Int, action: ShareItemAction = ShareItemAction.MOVE_IN, video: Boolean = false, hidden: Boolean = false, size: Long = 2_000_000L): SharePlanItem =
    SharePlanItem(media(n), action, size, video, hidden)

  /** Un piano di un album nuovo "Oslo 2026" con 20 foto dall'area privata, 3 dal telefono, 1 gia' nell'area comune e 2 video. */
  fun newAlbumPlan(
    request: ShareRequest = ShareRequest.NewAlbum("Oslo 2026", ShareLevel.ADD, (1..26).map(::media)),
    hidden: HiddenChoice = HiddenChoice.LEAVE_OUT,
    hiddenLeftOut: Int = 0,
    hiddenIn: Int = 0,
    createdAt: Long = 10_000_000L,
  ): SharePlan {
    val items = buildList {
      (1..20).forEach { add(item(it)) }
      (21..23).forEach { add(item(it, ShareItemAction.UPLOAD)) }
      add(item(24, ShareItemAction.REFERENCE))
      add(item(25, video = true, size = 50_000_000L))
      add(item(26, video = true, size = 90_000_000L))
      (27 until 27 + hiddenIn).forEach { add(item(it, hidden = true)) }
    }
    val excluded = if (hiddenLeftOut > 0) mapOf(ShareExclusion.HIDDEN_LEFT_OUT to hiddenLeftOut) else emptyMap()
    return SharePlan(
      request, SharePlanKind.SHARE, ALBUM, "Oslo 2026", "Album condivisi/Oslo 2026", ShareLevel.ADD, "00112233445566778899aabbccddeeff", items, excluded, hidden, createdAt,
    )
  }

  /** Un piano su un album condiviso che c'e' gia' ("Aggiungi ad album"): niente livello, niente nonce. */
  fun addPlan(count: Int = 4): SharePlan = SharePlan(
    ShareRequest.AddToShared(ALBUM, (1..count).map(::media)), SharePlanKind.SHARE, ALBUM, "Natale in famiglia", "Album condivisi/Natale in famiglia", null, null,
    (1..count).map { item(it) }, emptyMap(), HiddenChoice.LEAVE_OUT, 10_000_000L,
  )

  fun bringBackPlan(count: Int = 3): SharePlan = SharePlan(
    ShareRequest.BringBack(ALBUM, (1..count).map(::media)), SharePlanKind.BRING_BACK, ALBUM, "Natale in famiglia", "Natale in famiglia", null, null,
    (1..count).map { item(it, ShareItemAction.MOVE_OUT) }, emptyMap(), HiddenChoice.SHARE_TOO, 10_000_000L,
  )

  fun adoptPlan(count: Int = 5): SharePlan = SharePlan(
    ShareRequest.FolderAdoption(ALBUM, (1..count).map(::media)), SharePlanKind.ADOPT, ALBUM, "Natale in famiglia", "Album condivisi/Natale in famiglia", null, null,
    (1..count).map { item(it, ShareItemAction.ADOPT) }, emptyMap(), HiddenChoice.SHARE_TOO, 10_000_000L,
  )
}
