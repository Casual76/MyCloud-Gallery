package dev.pampa.mycloudgallery.feature.search.models

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import dev.antigravity.fluidengine.ui.fluid.ContinuousCornerShape
import dev.antigravity.fluidengine.ui.fluid.FluidMotion
import dev.antigravity.fluidengine.ui.fluid.FluidRadius
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconStatus
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconTile
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryStatusMark
import dev.pampa.mycloudgallery.core.designsystem.media.galleryRingColor
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryOnPhoto
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.library.contract.LibraryAnalysisStatus
import dev.pampa.mycloudgallery.feature.search.R
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisFlow
import dev.pampa.mycloudgallery.feature.search.contract.AnalysisThumb
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails
import dev.pampa.mycloudgallery.feature.search.ui.SearchDimens
import dev.pampa.mycloudgallery.feature.search.windowOf
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.conflate

// IL NASTRO DELL'ANALISI (grafica XU, 12/11/2026). L'utente: «un menu' che mostra in maniera animata le foto e video scorrere nel mentre che vengono
// analizzati». Scelta: **una corsia** orizzontale che passa sotto **un mirino fermo** al centro. Le prossime arrivano da destra (piu' piccole, un po'
// spente), quella di adesso sta grande dentro il mirino, le finite escono a sinistra rimpicciolendo e **prendono la spunta** mentre lasciano il mirino.
// Perche' una corsia e non una pila o tre file: e' **un solo movimento**, nella direzione in cui si legge (da destra a sinistra = il lavoro che
// avanza), e il mirino fermo dice senza parole «qui si guarda»; il telefono la vede intera, il tablet ne mostra di piu' ai lati (non una colonna).
// Le anteprime vanno da bordo a bordo: il nastro entra ed esce dallo schermo.
//
// Quando non si analizza (pausa, tutto pronto, modello che manca, un altro dispositivo...) il mirino resta e dentro c'e' **l'icona del perche'**
// (il termometro, la luna, la spunta...), con le ultime analizzate ferme alla sua sinistra: lo stesso spazio, fermo, che dice perche' senza frasi.
//
// IL COSTO, tenuto basso di proposito (oggi un foglio ha scaldato il telefono ridisegnandosi a ogni fotogramma):
//  - superfici piene, nessun vetro e nessuna sfocatura sotto il nastro; il segnaposto sfocato e' una bitmap gia' pronta, non un effetto;
//  - **un passo per volta e mai piu' di uno ogni [LaneStepMillis] (1,2 s)**: il motore puo' finire piu' foto al secondo, la pagina ne mostra il
//    passo piu' recente (gli intermedi si saltano, la corsia scorre di piu' posti in un movimento solo). Un passo dura ~0,75 s (corsia con
//    `standard` 0,36-0,40 s insieme al mirino che si apre con `snappy` 0,35 s, poi mirino che si chiude e luce con `snappy` 0,35 s: misurati
//    simulando le molle di FluidMotion): fra un passo e l'altro restano **almeno ~0,45 s in cui niente si muove**;
//  - il movimento e' una sola molla ([shift]) letta **nel piazzamento** e nel livello di ciascuna anteprima, e il mirino legge la sua ([focus]) nel
//    disegno: a ogni fotogramma si ripiazza e si ridisegna, **non si ricompone** niente;
//  - nessun ciclo infinito; con la pagina non visibile i flussi non si raccolgono (ciclo di vita) e niente cambia; con il movimento ridotto le
//    anteprime cambiano posto **senza scorrere** e il mirino non si stringe ne' passa la luce;
//  - miniature solo da cio' che e' gia' sul dispositivo (lo stesso caricatore di Cerca); un media che la Libreria non mostrerebbe e' la casella neutra.
//
// RISERVATEZZA (XS2-1, giro 2): il passo riguarda il **movimento**, non l'autorizzazione. Il fotogramma trattenuto dal passo ([rememberPaced]) si
// disegna sempre **filtrato dal nastro di adesso** ([visibleNow]): un media che il flusso non da' piu' come anteprima (revocato, nascosto, nel
// cestino) diventa la casella neutra nello stesso fotogramma in cui il flusso lo toglie, anche a meta' di un passo e con il movimento ridotto; e
// riferimento e ThumbHash vengono sempre dal valore di adesso, mai da quello trattenuto.
//
// GENERAZIONE (giro 3, contratto `AnalysisFlow.visibilityGeneration`): `:app` la alza a ogni cambio di visibilita' (permessi, selezione, nascosti,
// cestino) e nello stesso istante emette uno stato tutto neutro. Qui: (1) un fotogramma trattenuto di una generazione piu' vecchia di quella
// dell'ultimo valore **non si usa piu'**: si disegna l'ultimo valore cosi' com'e' ([frameBase]); (2) subito dopo il passo trattenuto e il
// fotogramma della corsia si buttano (nessun riferimento vecchio resta in memoria) e il movimento si ferma; (3) ogni anteprima a schermo ha
// un'identita' **legata alla generazione** in cui e' nata ([laneKey]): al cambio la sua composizione (miniatura, segnaposto ricordato) si smonta e
// ne nasce una nuova, mai riusata; (4) lo stato neutro di passaggio non fa aspettare il passo ([rememberPaced]).

/** Un'anteprima della corsia e il suo posto: 0 = nel mirino, negativo = finita (a sinistra), positivo = in arrivo (a destra). */
@Immutable
private class LaneEntry(val slot: Int, val thumb: AnalysisThumb?)

/**
 * La corsia: [flow] e' il nastro gia' passato dal cancello di riservatezza in `:app`. [frozenShift] e' solo per gli screenshot (la corsia ferma a
 * meta' di un passo, per vedere il movimento in una foto).
 */
@Composable
internal fun AnalysisLane(
  status: LibraryAnalysisStatus,
  flow: AnalysisFlow,
  thumbnails: SearchThumbnails?,
  modifier: Modifier = Modifier,
  bleed: Dp = GalleryDimens.Gutter,
  frozenShift: Float? = null,
) {
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val pacedState = rememberPaced(flow)
  val paced = pacedState.value
  var held by remember { mutableStateOf(paced) }
  val shift = remember { Animatable(frozenShift ?: 0f) }
  val focus = remember { Animatable(1f) }
  val light = remember { Animatable(1f) }
  LaunchedEffect(paced, reduced) {
    val old = held
    val step = laneStep(old, paced)
    val arrived = paced.current != null && (step > 0 || paced.current != old.current)
    held = paced
    if (frozenShift != null) return@LaunchedEffect
    if (reduced) {
      shift.snapTo(0f)
      focus.snapTo(1f)
      light.snapTo(1f)
      return@LaunchedEffect
    }
    // XU-3: la corsia scorre mentre il mirino si apre (insieme, senza scatti), poi il mirino si stringe e la luce passa una volta. Un passo che
    // arriva mentre il precedente sta finendo parte da dove la corsia e il mirino sono adesso.
    shift.snapTo(step + shift.value)
    coroutineScope {
      launch { shift.animateTo(0f, FluidMotion.standard()) }
      if (arrived) launch { focus.animateTo(0f, FluidMotion.snappy()) }
    }
    if (arrived) {
      light.snapTo(0f)
      coroutineScope {
        launch { focus.animateTo(1f, FluidMotion.snappy()) }
        launch { light.animateTo(1f, FluidMotion.snappy()) }
      }
    }
  }
  // XS2-1: il fotogramma trattenuto si disegna filtrato dal nastro di adesso (lo stesso fotogramma in cui il flusso revoca); se e' di una
  // generazione piu' vecchia non si usa affatto (giro 3).
  val frame = visibleNow(frameBase(held, flow), flow)
  // Giro 3: alla generazione nuova il passo trattenuto e la corsia si buttano subito dopo questo fotogramma (nessun riferimento vecchio in
  // memoria) e il movimento si ferma dov'e': la corsia riparte dal valore di adesso.
  if (held.visibilityGeneration < flow.visibilityGeneration || pacedState.value.visibilityGeneration < flow.visibilityGeneration) {
    SideEffect {
      if (pacedState.value.visibilityGeneration < flow.visibilityGeneration) pacedState.value = flow
      if (held.visibilityGeneration < flow.visibilityGeneration) held = flow
    }
  }
  LaunchedEffect(flow.visibilityGeneration) {
    shift.snapTo(0f)
    focus.snapTo(1f)
    light.snapTo(1f)
  }
  val running = frame.current != null
  val description = laneDescription(frame)
  BoxWithConstraints(
    modifier = modifier
      .testTag(AnalysisLaneTag)
      .semantics { contentDescription = description },
  ) {
    val current = (minOf(maxHeight * CurrentOfHeight, maxWidth * CurrentOfWidth)).coerceIn(SearchDimens.LaneCurrentMin, SearchDimens.LaneCurrentMax)
    val side = (current * SideOfCurrent).coerceIn(SearchDimens.LaneSideMin, SearchDimens.LaneSideMax)
    val density = LocalDensity.current
    val geometry = remember(current, side, density) {
      with(density) { LaneGeometry(current.toPx(), side.toPx(), SearchDimens.LaneGap.toPx()) }
    }
    // Quante per lato ne entrano fino al bordo dello schermo (il nastro passa anche sotto il margine della pagina), piu' quelle che un passo porta.
    val perSide = with(density) { geometry.slotsTo((maxWidth / 2 + bleed).toPx()) } + MaxStep
    val entries = buildList {
      frame.recent.take(perSide).forEachIndexed { index, thumb -> add(LaneEntry(-(index + 1), thumb)) }
      add(LaneEntry(0, frame.current))
      frame.next.take(perSide).forEachIndexed { index, thumb -> add(LaneEntry(index + 1, thumb)) }
    }
    // Una chiave per anteprima; se lo stesso media comparisse due volte (finito e di nuovo in coda), la seconda prende anche il posto.
    val seen = HashSet<Any>()
    val keys = entries.map { entry -> laneKey(entry, frame.visibilityGeneration).let { if (seen.add(it)) it else "$it@${entry.slot}" } }
    // XU-15: le miniature nella finestra del caricatore solo per le anteprime che la corsia mostra (quelle del NAS "in arrivo", mai l'originale), e
    // solo quando cambiano quelle mostrate: non a ogni emissione del motore. Sono gia' filtrate dal nastro di adesso (XS2-1).
    val requests = entries.mapNotNull { (it.thumb as? AnalysisThumb.Shown)?.request }
    LaunchedEffect(requests, thumbnails) {
      val loader = thumbnails?.loader ?: return@LaunchedEffect
      val (local, replicaOnly) = windowOf(requests)
      loader.window(local, replicaOnly)
    }
    val accent = galleryRingColor(GalleryIconFamily.Search)
    val restInk = MaterialTheme.colorScheme.outlineVariant
    val edge = with(density) { (maxWidth / 2 + bleed).toPx() }
    val gate = with(density) { GateMetrics(SearchDimens.LaneGateInset.toPx(), SearchDimens.LaneGateCorner.toPx(), SearchDimens.LaneGateStroke.toPx(), SearchDimens.LaneGateFocus.toPx()) }
    val markPx = with(density) { SearchDimens.LaneMark.toPx() }
    Layout(
      modifier = Modifier
        .fillMaxSize()
        // Senza margine da attraversare (accanto alla testata, sul tablet) la corsia resta nei suoi limiti.
        .then(if (bleed > SearchDimens.LaneNoBleed) Modifier else Modifier.clipToBounds())
        .drawWithContent {
          drawContent()
          // Letto nel disegno: il mirino si ridisegna, la pagina no.
          // A meta' passo (lo screenshot) il mirino e' aperto: si stringe solo quando l'anteprima e' arrivata.
          val f = if (frozenShift != null) 0f else focus.value
          val l = if (frozenShift != null) 1f else light.value
          drawGate(geometry, gate, if (running) accent else restInk, f, sweepAt = if (running && l < 1f) l else null, accent)
        },
      content = {
        entries.forEachIndexed { index, entry ->
          val thumb = entry.thumb
          // L'identita' segue il media, non il posto: scorrendo, una miniatura gia' caricata resta la stessa.
          key(keys[index]) {
          when {
            entry.slot == 0 && thumb == null -> RestCard(status, current)
            thumb != null -> LaneThumb(thumb, thumbnails, current, frame.visibilityGeneration)
            else -> Box(Modifier)
          }
          // Il segno «fatto» (solo le finite e quella di adesso, che lo prende uscendo) e il segno del video: a misura fissa.
          if (entry.slot <= 0 && thumb != null) {
            GalleryStatusMark(status = GalleryIconStatus.Done, size = SearchDimens.LaneMark, ring = MaterialTheme.colorScheme.background, contentDescription = null)
          } else {
            Box(Modifier)
          }
          if ((thumb as? AnalysisThumb.Shown)?.video == true) VideoBadge() else Box(Modifier)
          }
        }
      },
    ) { measurables, constraints ->
      val width = constraints.maxWidth
      val height = constraints.maxHeight
      val big = geometry.current.roundToInt()
      val placeables = measurables.chunked(PartsPerEntry).map { (thumb, mark, video) ->
        Triple(
          thumb.measure(Constraints.fixed(big, big)),
          mark.measure(Constraints()),
          video.measure(Constraints()),
        )
      }
      layout(width, height) {
        // Letto qui: a ogni fotogramma del passo si ripiazza, non si ricompone.
        val moved = frozenShift ?: shift.value
        val centerX = width / 2f
        val centerY = height / 2f
        entries.forEachIndexed { index, entry ->
          val (thumb, mark, video) = placeables[index]
          val pos = entry.slot + moved
          val size = geometry.sizeAt(pos)
          val x = centerX + geometry.xAt(pos)
          val alpha = laneAlpha(pos, abs(geometry.xAt(pos)), size, edge)
          // Anche le invisibili si piazzano (opacita' zero, il livello non disegna): restano nell'albero e non si rimisurano entrando.
          val scale = size / geometry.current
          thumb.placeWithLayer((x - big / 2f).roundToInt(), (centerY - big / 2f).roundToInt()) {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
          }
          val pop = ((-pos - MarkFrom) / MarkSpan).coerceIn(0f, 1f)
          if (pop > 0f && entry.slot <= 0 && entry.thumb != null) {
            mark.placeWithLayer((x + size / 2f - markPx * MarkOverlap).roundToInt(), (centerY + size / 2f - markPx * MarkOverlap).roundToInt()) {
              scaleX = pop
              scaleY = pop
              // Il segno sfuma prima della sua anteprima: non resta mai una spunta sola sul bordo.
              this.alpha = alpha * alpha
            }
          }
          if (video.width > 0) {
            video.placeWithLayer((x - size / 2f + markPx * VideoInset).roundToInt(), (centerY + size / 2f - video.height - markPx * VideoInset).roundToInt()) {
              this.alpha = alpha
            }
          }
        }
      }
    }
  }
}

/** Le misure della corsia in pixel: l'anteprima di adesso, quelle ai lati, lo spazio fra loro. */
@Immutable
private class LaneGeometry(val current: Float, val side: Float, val gap: Float) {
  /** Dal centro del mirino al centro del primo posto accanto. */
  private val first = current / 2f + gap + side / 2f
  private val pitch = side + gap

  /** Il lato di un'anteprima al posto (anche frazionario) [pos]: da grande nel mirino a piccola al primo posto, poi resta piccola. */
  fun sizeAt(pos: Float): Float {
    val d = abs(pos).coerceAtMost(1f)
    return current + (side - current) * d
  }

  /** La distanza dal centro del mirino del centro di un'anteprima al posto [pos]. */
  fun xAt(pos: Float): Float {
    val d = abs(pos)
    val distance = if (d <= 1f) d * first else first + (d - 1f) * pitch
    return sign(pos) * distance
  }

  /** Quanti posti per lato servono per arrivare a [edge] pixel dal centro. */
  fun slotsTo(edge: Float): Int = if (edge <= first) 1 else 1 + ((edge - first) / pitch).toInt() + 1
}

/**
 * L'opacita' di un'anteprima: quanta ne resta dentro il bordo (piena se ci sta tutta, zero quando e' uscita), cosi' quella che esce sfuma invece di
 * restare tagliata a piena luce; le prossime un po' spente finche' non arrivano al mirino.
 */
private fun laneAlpha(pos: Float, center: Float, size: Float, edge: Float): Float {
  val atEdge = ((edge - (center - size / 2f)) / size).coerceIn(0f, 1f)
  val waiting = if (pos > 0f) 1f - (1f - NextAlpha) * pos.coerceAtMost(1f) else 1f
  return atEdge * waiting
}

@Immutable
private class GateMetrics(val inset: Float, val corner: Float, val stroke: Float, val focus: Float)

/**
 * Il mirino: quattro angoli intorno al posto di adesso. All'arrivo di un'anteprima si stringe ([focus] da 0 a 1) e una luce la attraversa una volta
 * dall'alto in basso ([sweep]); poi resta fermo. A riposo e' del colore dei bordi, mentre lavora della tinta della ricerca.
 */
private fun DrawScope.drawGate(geometry: LaneGeometry, gate: GateMetrics, color: Color, focus: Float, sweepAt: Float?, light: Color) {
  val center = Offset(size.width / 2f, size.height / 2f)
  val half = geometry.current / 2f + gate.inset + gate.focus * (1f - focus)
  val box = Rect(center.x - half, center.y - half, center.x + half, center.y + half)
  val arm = gate.corner
  val stroke = Stroke(width = gate.stroke, cap = StrokeCap.Round)
  val path = Path().apply {
    moveTo(box.left, box.top + arm); lineTo(box.left, box.top); lineTo(box.left + arm, box.top)
    moveTo(box.right - arm, box.top); lineTo(box.right, box.top); lineTo(box.right, box.top + arm)
    moveTo(box.right, box.bottom - arm); lineTo(box.right, box.bottom); lineTo(box.right - arm, box.bottom)
    moveTo(box.left + arm, box.bottom); lineTo(box.left, box.bottom); lineTo(box.left, box.bottom - arm)
  }
  drawPath(path, color = color, style = stroke)
  val sweep = sweepAt ?: return
  // La luce che passa una volta: una fascia sfumata della tinta della ricerca, dentro l'anteprima (ritagliata alla sua forma).
  val photoHalf = geometry.current / 2f
  val photo = Rect(center.x - photoHalf, center.y - photoHalf, center.x + photoHalf, center.y + photoHalf)
  val band = geometry.current * SweepBand
  val y = photo.top - band + (photo.height + band * 2f) * sweep
  val shape = ContinuousCornerShape(FluidRadius.Card).createOutline(Size(photo.width, photo.height), layoutDirection, this)
  val clip = Path().apply { addOutline(shape); translate(photo.topLeft) }
  clipPath(clip) {
    drawRect(
      brush = Brush.verticalGradient(
        0f to light.copy(alpha = 0f),
        0.5f to light.copy(alpha = SweepAlpha),
        1f to light.copy(alpha = 0f),
        startY = y - band,
        endY = y + band,
      ),
      topLeft = Offset(photo.left, y - band),
      size = Size(photo.width, band * 2f),
    )
  }
}

/** Un'anteprima: la miniatura locale (o il segnaposto sfocato, gia' pronto) in una tessera piena; un media non mostrabile e' la casella neutra. */
@Composable
private fun LaneThumb(thumb: AnalysisThumb, thumbnails: SearchThumbnails?, side: Dp, generation: Long) {
  val shape = ContinuousCornerShape(FluidRadius.Card)
  // La generazione in cui questa composizione e' nata: con la chiave legata alla generazione ([laneKey]) non sopravvive a un cambio di visibilita'.
  val born = remember { generation }
  Box(
    modifier = Modifier
      .size(side)
      .clip(shape)
      .background(MaterialTheme.colorScheme.surfaceContainerHigh)
      .testTag(if (thumb is AnalysisThumb.Quiet) AnalysisQuietTag else AnalysisShownTag)
      .semantics {
        hideFromAccessibility()
        // Solo per le prove (XS2-1: quale media e' a schermo); TalkBack non lo legge.
        (thumb as? AnalysisThumb.Shown)?.let { this[AnalysisMediaKey] = it.id.value; this[AnalysisBornKey] = born }
      },
  ) {
    when (thumb) {
      is AnalysisThumb.Shown -> {
        val image = thumb.request?.let { thumbnails?.loader?.image(it.key, it.ref) }
        val placeholder = remember(thumb.thumbHash, thumbnails) { thumb.thumbHash?.let { thumbnails?.placeholders?.image(it) } }
        val bitmap = image?.value ?: placeholder
        if (bitmap != null) Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
      }
      // Niente foto, niente sfocato: una casella del colore dei gruppi con l'occhio barrato.
      AnalysisThumb.Quiet -> Icon(
        imageVector = Icons.Rounded.VisibilityOff,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(side * QuietGlyph).align(androidx.compose.ui.Alignment.Center),
      )
    }
  }
}

/** Il segno del video: il triangolo bianco su un tondo di scrim, a misura fissa, nell'angolo in basso a sinistra. */
@Composable
private fun VideoBadge() {
  Box(
    modifier = Modifier
      .size(SearchDimens.LaneMark)
      .clip(ContinuousCornerShape(SearchDimens.LaneMark / 2))
      .background(GalleryOnPhoto.scrim()),
    contentAlignment = androidx.compose.ui.Alignment.Center,
  ) {
    Icon(imageVector = Icons.Rounded.PlayArrow, contentDescription = null, tint = GalleryOnPhoto.Content, modifier = Modifier.size(SearchDimens.LaneVideoGlyph))
  }
}

/** Nel mirino quando non si analizza: una tessera piena con l'icona del perche' (e il suo segno), grande. Nessuna parola: la testata le ha gia' dette. */
@Composable
private fun RestCard(status: LibraryAnalysisStatus, side: Dp) {
  val sign = analysisSign(status)
  val ground = MaterialTheme.colorScheme.surfaceContainerHigh
  Box(
    modifier = Modifier
      .size(side)
      .clip(ContinuousCornerShape(FluidRadius.Card))
      .background(ground)
      .testTag(AnalysisRestTag),
    contentAlignment = androidx.compose.ui.Alignment.Center,
  ) {
    // XU-5: il mirino dice solo il perche'; lo stato (il segno) lo dice l'anello della testata, una volta.
    GalleryIconTile(icon = sign.glyph, family = sign.family, size = side * RestTile, status = null, ring = ground, statusDescription = null)
  }
}

/** Che cosa dice la corsia a TalkBack: che cosa guarda adesso, quante appena fatte, quante in arrivo (o le ultime, ferme). */
@Composable
private fun laneDescription(frame: AnalysisFlow): String {
  val current = frame.current
  val done = frame.recent.size
  return if (current != null) {
    listOf(
      stringResource(
        when {
          current is AnalysisThumb.Quiet -> R.string.analysis_lane_hidden
          (current as AnalysisThumb.Shown).video -> R.string.analysis_lane_video
          else -> R.string.analysis_lane_photo
        },
      ),
      if (done > 0) pluralStringResource(R.plurals.analysis_lane_done, done, done) else "",
      if (frame.next.isNotEmpty()) pluralStringResource(R.plurals.analysis_lane_next, frame.next.size, frame.next.size) else "",
    ).filter { it.isNotEmpty() }.joinToString(". ")
  } else if (done > 0) {
    pluralStringResource(R.plurals.analysis_lane_rest, done, done)
  } else {
    ""
  }
}

/** L'identita' di un'anteprima: il media **e la generazione** in cui e' nata (giro 3): al cambio di visibilita' non se ne riusa nessuna. */
private fun laneKey(entry: LaneEntry, generation: Long): Any = when (val thumb = entry.thumb) {
  is AnalysisThumb.Shown -> "${thumb.id.value}#$generation"
  AnalysisThumb.Quiet -> "nascosta-${entry.slot}"
  null -> "riposo"
}

/**
 * Giro 3: da che cosa parte il fotogramma. Il trattenuto [held] solo se e' della stessa generazione dell'ultimo valore [now]; se e' piu' vecchio si
 * disegna [now] cosi' com'e' (lo stato neutro di passaggio, poi quello risolto), e nessuna sua anteprima arriva a schermo.
 */
internal fun frameBase(held: AnalysisFlow, now: AnalysisFlow): AnalysisFlow =
  if (held.visibilityGeneration < now.visibilityGeneration) now else held

/**
 * Di quanti posti e' scorsa la corsia da [old] a [new]: dalla prima anteprima di [new] che c'era anche in [old].
 *  - XU-4: dalla corsia vuota (la pagina appena aperta) alle prime anteprime, la corsia **entra da destra** di [EntrySteps] posti, una volta;
 *  - XU-9: senza anteprime in comune (una fila di caselle neutre) ma con il mirino occupato prima e dopo, il motore e' andato avanti: un posto.
 *    Non scorre se l'unica differenza e' una revoca (un'anteprima diventata casella neutra al suo posto: [sameShape]).
 * Zero se non si sa.
 */
internal fun laneStep(old: AnalysisFlow, new: AnalysisFlow): Int {
  val before = HashMap<Any, Int>()
  slotsOf(old).forEach { (slot, thumb) -> (thumb as? AnalysisThumb.Shown)?.let { before.putIfAbsent(it.id, slot) } }
  for ((slot, thumb) in slotsOf(new)) {
    val id = (thumb as? AnalysisThumb.Shown)?.id ?: continue
    val was = before[id] ?: continue
    return (was - slot).coerceIn(0, MaxStep)
  }
  // Giro 3: il nastro arriva vuoto, poi neutro, poi risolto. La corsia entra da destra quando compaiono le prime anteprime vere (non con le
  // caselle neutre di passaggio): da un nastro senza anteprime (vuoto o tutto neutro) a uno con anteprime.
  if (!old.hasShown() && new.hasShown() && new.current != null) return EntrySteps
  // Lo stesso nastro con alcune caselle diventate anteprime o viceversa (risolto dopo il neutro, o una revoca) non e' un passo del motore.
  if (sameShape(old, new)) return 0
  if (old.current != null && new.current != null && old != new) return 1
  return 0
}

private fun AnalysisFlow.hasShown(): Boolean =
  current is AnalysisThumb.Shown || recent.any { it is AnalysisThumb.Shown } || next.any { it is AnalysisThumb.Shown }

private fun AnalysisFlow.isEmpty(): Boolean = current == null && recent.isEmpty() && next.isEmpty()

/** Vero se [old] e [new] hanno gli stessi posti e in ogni posto la stessa cosa, o una casella neutra da una parte (revoca o risoluzione). */
private fun sameShape(old: AnalysisFlow, new: AnalysisFlow): Boolean {
  if (old.recent.size != new.recent.size || old.next.size != new.next.size || (old.current == null) != (new.current == null)) return false
  return slotsOf(old).zip(slotsOf(new)).all { (b, a) -> a.second == b.second || a.second == AnalysisThumb.Quiet || b.second == AnalysisThumb.Quiet }
}

/**
 * XS2-1: il fotogramma trattenuto [held], ma solo con cio' che il nastro di adesso [now] mostra: un'anteprima trattenuta resta un'anteprima solo se
 * [now] da' lo stesso media come anteprima, e allora con **il suo** riferimento e il suo ThumbHash; altrimenti e' la casella neutra.
 */
internal fun visibleNow(held: AnalysisFlow, now: AnalysisFlow): AnalysisFlow {
  if (held === now) return now
  val allowed = HashMap<Any, AnalysisThumb.Shown>()
  (listOfNotNull(now.current) + now.recent + now.next).forEach { thumb -> (thumb as? AnalysisThumb.Shown)?.let { allowed.putIfAbsent(it.id, it) } }
  fun clean(thumb: AnalysisThumb): AnalysisThumb = when (thumb) {
    is AnalysisThumb.Shown -> allowed[thumb.id] ?: AnalysisThumb.Quiet
    AnalysisThumb.Quiet -> AnalysisThumb.Quiet
  }
  return AnalysisFlow(held.current?.let(::clean), held.recent.map(::clean), held.next.map(::clean), now.visibilityGeneration)
}

private fun slotsOf(flow: AnalysisFlow): List<Pair<Int, AnalysisThumb>> = buildList {
  flow.current?.let { add(0 to it) }
  flow.next.forEachIndexed { index, thumb -> add(index + 1 to thumb) }
  flow.recent.forEachIndexed { index, thumb -> add(-(index + 1) to thumb) }
}

/**
 * Il nastro al passo della pagina: il valore piu' recente, mai piu' di uno ogni [LaneStepMillis] (gli intermedi si saltano). Nessuna attesa dopo
 * un nastro vuoto (la pagina appena aperta, XU-4) ne' dopo lo stato neutro di una generazione nuova (giro 3: subito dopo arriva quello risolto).
 * Lo stato e' restituito perche' la corsia lo possa buttare al cambio di generazione.
 */
@Composable
private fun rememberPaced(flow: AnalysisFlow): MutableState<AnalysisFlow> {
  val latest = rememberUpdatedState(flow)
  val paced = remember { mutableStateOf(flow) }
  LaunchedEffect(Unit) {
    var generation = flow.visibilityGeneration
    snapshotFlow { latest.value }.conflate().collect { value ->
      val bridge = !value.hasShown() && value.visibilityGeneration > generation
      generation = maxOf(generation, value.visibilityGeneration)
      paced.value = value
      if (!value.isEmpty() && !bridge) delay(LaneStepMillis)
    }
  }
  return paced
}

/** Il media di un'anteprima a schermo, per le prove di riservatezza (nessun lettore lo legge). */
internal val AnalysisMediaKey: SemanticsPropertyKey<String> = SemanticsPropertyKey("AnalysisMedia")

/** La generazione in cui e' nata la composizione di un'anteprima a schermo, per le prove (giro 3). */
internal val AnalysisBornKey: SemanticsPropertyKey<Long> = SemanticsPropertyKey("AnalysisBorn")

internal const val AnalysisLaneTag: String = "analisi-nastro"
internal const val AnalysisShownTag: String = "analisi-anteprima"
internal const val AnalysisQuietTag: String = "analisi-nascosta"
internal const val AnalysisRestTag: String = "analisi-a-riposo"

/** Il passo piu' breve della corsia: sotto, il movimento diventerebbe continuo (e la pagina si ridisegnerebbe sempre). */
internal const val LaneStepMillis: Long = 1_200L

/** Di quanti posti al massimo scorre la corsia in un passo (gli intermedi gia' usciti dallo schermo non si animano). */
private const val MaxStep = 4

/** Di quanti posti entra la corsia la prima volta che arrivano anteprime (dalla destra, come le prossime). */
private const val EntrySteps = 2

private const val PartsPerEntry = 3
private const val CurrentOfHeight = 0.66f
private const val CurrentOfWidth = 0.68f
private const val SideOfCurrent = 0.36f
private const val NextAlpha = 0.62f
private const val MarkFrom = 0.2f
private const val MarkSpan = 0.5f
private const val MarkOverlap = 0.7f
private const val VideoInset = 0.3f
private const val SweepBand = 0.22f
private const val SweepAlpha = 0.38f
private const val QuietGlyph = 0.3f
private const val RestTile = 0.42f
