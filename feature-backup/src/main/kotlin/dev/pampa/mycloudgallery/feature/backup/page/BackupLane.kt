package dev.pampa.mycloudgallery.feature.backup.page

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.awaitCancellation
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.backup.contract.BackupLive
import dev.pampa.mycloudgallery.core.backup.contract.BackupStatus
import dev.pampa.mycloudgallery.feature.backup.BackupDimens
import dev.pampa.mycloudgallery.feature.backup.R
import dev.pampa.mycloudgallery.feature.backup.ui.LocalBackupCovers
import kotlin.math.roundToInt

/** Un passo per evento reale. Nessun timer che inventa file o rallenta il caricamento. */
@Composable
internal fun BackupLane(live: BackupLive, status: BackupStatus, still: Boolean = false) {
  val covers = LocalBackupCovers.current ?: return
  val view by covers.view.collectAsStateWithLifecycle()
  val revision by covers.revision.collectAsStateWithLifecycle()
  val working = status is BackupStatus.Running && !status.starting
  val active = if (working) live.active else emptyList()
  val upcoming = if (working) (active.drop(1) + live.next).distinct().take(3) else emptyList()
  val slots = buildList {
    live.recent.take(3).forEachIndexed { index, id -> add(-(index + 1) to id) }
    active.firstOrNull()?.let { add(0 to it) }
    upcoming.forEachIndexed { index, id -> add((index + 1) to id) }
  }
  // La vista autorizzata si consulta ad ogni emissione: mai riferimenti trattenuti per animare.
  val requests = slots.mapNotNull { (_, id) -> covers.request(view, id) }
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  DisposableEffect(covers, lifecycle) {
    var lease: AutoCloseable? = null
    fun updateAccess() {
      if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
        if (lease == null) lease = covers.open()
      } else { covers.release(Owner); lease?.close(); lease = null }
    }
    val observer = LifecycleEventObserver { _, _ -> updateAccess() }
    lifecycle.addObserver(observer)
    updateAccess()
    onDispose { lifecycle.removeObserver(observer); covers.release(Owner); lease?.close() }
  }
  LaunchedEffect(covers, slots, view, revision, lifecycle) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      covers.approve(Owner, slots.map { it.second }.toSet())
      awaitCancellation()
    }
  }
  DisposableEffect(covers, lifecycle, requests) {
    fun updateRequests() = covers.loader.keep(Owner, if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) requests else emptyList())
    val observer = LifecycleEventObserver { _, _ -> updateRequests() }
    lifecycle.addObserver(observer)
    updateRequests()
    onDispose { lifecycle.removeObserver(observer); covers.loader.keep(Owner, emptyList()) }
  }
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val shift = remember { Animatable(0f) }
  val initialFinished = remember { live.finished }
  LaunchedEffect(live.finished, active.firstOrNull(), working, reduced, still) {
    if (!working || reduced || still) { shift.snapTo(0f); return@LaunchedEffect }
    if (live.finished != initialFinished) {
      shift.snapTo(1f)
      // Il backup avanza piu' rapidamente dell'analisi: un solo movimento breve, senza scansione decorativa.
      shift.animateTo(0f, FluidMotion.crossFade(300))
    }
  }
  val description = stringResource(R.string.backup_lane_description, active.size, live.finished)
  val shape = ContinuousCornerShape(FluidRadius.Card)
  Layout(
    modifier = Modifier.fillMaxWidth().height(BackupDimens.LaneHeight).clipToBounds()
      .semantics { contentDescription = description },
    content = {
      slots.forEach { (slot, id) ->
        val request = covers.request(view, id)
        Box(
          modifier = Modifier.size(if (slot == 0) BackupDimens.LaneCurrent else BackupDimens.LaneSide)
            .clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (slot == 0) Modifier.border(BackupDimens.LaneBorder, MaterialTheme.colorScheme.primary, shape) else Modifier),
        ) {
          val bitmap = request?.let { covers.loader.image(it.key, it.ref).value }
          if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize().drawWithContent { if (covers.allows(id)) drawContent() }, contentScale = ContentScale.Crop)
          else Icon(Icons.Rounded.Photo, null, Modifier.align(Alignment.Center).size(BackupDimens.RowGlyph), tint = MaterialTheme.colorScheme.onSurfaceVariant)
          if (slot < 0) Icon(Icons.Rounded.CheckCircle, null, Modifier.align(Alignment.BottomEnd).size(BackupDimens.LaneMark), tint = MaterialTheme.colorScheme.primary)
        }
      }
      if (active.isEmpty()) Box(Modifier.size(BackupDimens.LaneCurrent), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.CloudUpload, null, Modifier.size(BackupDimens.HeroCenterGlyph), tint = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    },
  ) { measurables, constraints ->
    val height = BackupDimens.LaneHeight.roundToPx()
    val width = constraints.maxWidth
    val step = BackupDimens.LaneSide.toPx() + BackupDimens.LaneGap.toPx()
    val placeables = measurables.map { it.measure(Constraints()) }
    layout(width, height) {
      placeables.forEachIndexed { index, placeable ->
        val slot = slots.getOrNull(index)?.first ?: 0
        placeable.placeRelative(
          ((width - placeable.width) / 2f + (slot + shift.value) * step).roundToInt(),
          (height - placeable.height) / 2,
        )
      }
    }
  }
}

private const val Owner = "backup-lane"
