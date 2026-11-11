package dev.pampa.mycloudgallery.feature.viewer.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.antigravity.fluidengine.ui.fluid.FluidCapsuleShape
import dev.antigravity.fluidengine.ui.fluid.fluidPressable
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.feature.viewer.R
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceChip
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerFaceOutcome
import dev.pampa.mycloudgallery.feature.viewer.people.ViewerPeople

// LA RIGA "PERSONE" DELLE INFORMAZIONI (Fase 8, P9; docs/fase8-progetto.md §1.5 `ViewerFaceChip`, §2.6 "Info del viewer", D24, D25, D28, F99). Un
// chip per volto riconosciuto: il volto rotondo (ritagliato al momento, mai salvato) con **sotto** il nome, o "Chi e'?" se non ne ha uno. Un tocco
// apre la pagina della persona (o chiede "Chi e'?"); la pressione lunga, o le azioni di TalkBack, aprono "Chi e'?", "Non e' …" e "Ignora questo
// volto". "1 volto non riconoscibile" per i volti troppo piccoli o di profilo. Nessun riquadro disegnato sulla foto in questa fase. Una persona
// nascosta non compare (la toglie chi lega la porta, D28). Senza la porta ([dev.pampa.mycloudgallery.feature.viewer.ViewerServices.people] null)
// la riga non c'e': il pannello e' quello di prima. Solo componenti dell'engine e del design system.

/** La sezione "Persone" con i suoi fogli: quello che `ViewerOverlay` monta dentro il pannello e accanto agli altri fogli. */
internal class PeoplePanel(val sections: List<InfoSectionModel>, val sheets: @Composable () -> Unit) {
  companion object {
    /** Nessuna sezione e nessun foglio: il viewer senza le persone (capacita' assente, D13). */
    val NONE: PeoplePanel = PeoplePanel(emptyList()) {}
  }
}

/**
 * La sezione "Persone" per il media [media] mentre il pannello e' aperto ([active]). I chip si aggiornano da soli quando cambia un nome o una
 * correzione (`ViewerPeople.faces` e' un flusso); senza volti la sezione non c'e'.
 */
@Composable
internal fun rememberPeoplePanel(source: ViewerPeople?, media: MediaId, active: Boolean): PeoplePanel {
  if (source == null || !active) return PeoplePanel.NONE
  val scope = rememberCoroutineScope()
  val controller = remember(source, scope) { PeopleInfoController(source, scope) }
  val chips by remember(source, media) { source.faces(media) }.collectAsState(initial = emptyList())
  val notice by controller.notice.collectAsState()
  val sheet by controller.sheet.collectAsState()
  val header = stringResource(R.string.viewer_people_header)
  val sections = remember(chips, media, source, controller, notice, header) {
    if (chips.isEmpty()) {
      emptyList()
    } else {
      listOf(InfoSectionModel(header = header, rows = emptyList(), content = { PeopleSection(source, media, chips, notice, controller) }, icon = Icons.Rounded.Face))
    }
  }
  return PeoplePanel(sections) { PeopleSheets(source, media, chips, sheet, controller) }
}

@Composable
private fun PeopleSection(source: ViewerPeople, media: MediaId, chips: List<ViewerFaceChip>, notice: ViewerFaceOutcome?, controller: PeopleInfoController) {
  val faces = chips.filter { it !is ViewerFaceChip.Unrecognizable }
  val unrecognizable = chips.filterIsInstance<ViewerFaceChip.Unrecognizable>().sumOf { it.count }
  FluidListGroup {
    if (faces.isNotEmpty()) {
      Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = GalleryDimens.Gutter, vertical = ChipsVertical),
        horizontalArrangement = Arrangement.spacedBy(ChipGap),
        verticalAlignment = Alignment.Top,
      ) {
        faces.forEach { chip -> FaceChipView(source, media, chip, controller) }
      }
    }
    if (unrecognizable > 0 || notice != null) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = GalleryDimens.Gutter).padding(bottom = ChipsVertical),
        verticalArrangement = Arrangement.spacedBy(NoteGap),
      ) {
        if (unrecognizable > 0) {
          Text(
            text = LocalContext.current.resources.getQuantityString(R.plurals.viewer_people_unrecognizable, unrecognizable, unrecognizable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        if (notice != null) {
          Text(
            text = stringResource(if (notice == ViewerFaceOutcome.NOT_FOUND) R.string.viewer_people_not_found else R.string.viewer_people_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
          )
        }
      }
    }
  }
}

/** Un chip: il volto rotondo e sotto il nome (o "Chi e'?"). Tutto il chip e' un bersaglio solo, alto piu' di 48 dp. */
@Composable
private fun FaceChipView(source: ViewerPeople, media: MediaId, chip: ViewerFaceChip, controller: PeopleInfoController) {
  val density = LocalDensity.current
  val px = with(density) { FaceSize.roundToPx() }
  val face = when (chip) {
    is ViewerFaceChip.Named -> chip.face
    is ViewerFaceChip.Unnamed -> chip.face
    is ViewerFaceChip.Unrecognizable -> return
  }
  val image by produceState<ImageBitmap?>(initialValue = null, source, media, face, px) { value = source.crop(media, face, px)?.asImageBitmap() }
  val scheme = MaterialTheme.colorScheme
  val resources = LocalContext.current.resources
  val name = (chip as? ViewerFaceChip.Named)?.name
  // GE-3: un gruppo senza nome si dice "Senza nome" e il tocco ne apre la pagina; solo un volto fuori da ogni gruppo chiede "Chi e'?".
  val group = chip is ViewerFaceChip.Unnamed && chip.person != null
  val label = name ?: resources.getString(if (group) R.string.viewer_people_unnamed_group else R.string.viewer_people_who)
  val spoken = when {
    name != null -> resources.getString(R.string.viewer_people_chip_spoken, name)
    group -> resources.getString(R.string.viewer_people_chip_group_spoken)
    else -> resources.getString(R.string.viewer_people_chip_unnamed_spoken)
  }
  val actions = buildList {
    add(CustomAccessibilityAction(resources.getString(R.string.viewer_people_menu)) { controller.menu(chip); true })
    add(CustomAccessibilityAction(resources.getString(R.string.viewer_people_who)) { controller.who(media, face); true })
    if (name != null && chip is ViewerFaceChip.Named) {
      add(CustomAccessibilityAction(resources.getString(R.string.viewer_people_not, name)) { controller.notThisPerson(media, face, chip.person); true })
    }
    add(CustomAccessibilityAction(resources.getString(R.string.viewer_people_ignore)) { controller.askIgnore(face); true })
  }
  Column(
    modifier = Modifier
      .width(ChipWidth)
      .heightIn(min = GalleryDimens.MinTouchTarget)
      .fluidPressable(onClick = { controller.tap(media, chip) }, onLongClick = { controller.menu(chip) }, role = Role.Button)
      .semantics(mergeDescendants = true) {
        contentDescription = spoken
        customActions = actions
      },
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(NoteGap),
  ) {
    Box(
      modifier = Modifier.size(FaceSize).clip(FluidCapsuleShape).background(scheme.surfaceContainerHigh),
      contentAlignment = Alignment.Center,
    ) {
      val bitmap = image
      if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
      } else {
        Icon(imageVector = Icons.Rounded.Person, contentDescription = null, modifier = Modifier.size(GlyphSize), tint = scheme.onSurfaceVariant)
      }
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = if (name == null) scheme.primary else scheme.onSurface,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
    )
  }
}

private val FaceSize = 64.dp
private val GlyphSize = 28.dp
private val ChipWidth = 84.dp
private val ChipGap = 8.dp
private val ChipsVertical = 12.dp
private val NoteGap = 4.dp
