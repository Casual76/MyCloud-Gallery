package dev.pampa.mycloudgallery.feature.library.collections

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.zIndex
import dev.antigravity.fluidengine.ui.fluid.FluidSwitch
import dev.antigravity.fluidengine.ui.fluid.LocalFluidMotionPolicy
import dev.antigravity.fluidengine.ui.theme.FluidListDivider
import dev.antigravity.fluidengine.ui.theme.FluidListGroup
import dev.antigravity.fluidengine.ui.theme.FluidListRow
import dev.pampa.mycloudgallery.core.designsystem.theme.GalleryDimens
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers
import dev.pampa.mycloudgallery.feature.library.R

// "Modifica Raccolte" (Fase 7, P11, D33, §2.7): l'elenco delle sezioni da riordinare e da mostrare o nascondere, fatto solo con i
// componenti dell'engine (`FluidListGroup`, `FluidListRow`, `FluidSwitch`). Ogni riga e' UN controllo per chi usa TalkBack: l'interruttore
// "Mostra" con il nome della sezione, la sua posizione e le azioni "Sposta su" e "Sposta giu'". La maniglia e' solo per chi trascina e non
// compare ai servizi di accessibilita'. Bersagli da 48 dp: la riga e la maniglia.

/** Il segno di una maniglia nei test e negli strumenti di prova: `collections-handle-<codice>`. */
internal fun handleTag(code: String): String = "collections-handle-$code"

/** Le voci della schermata "Modifica Raccolte" nella lista della pagina: una frase, l'avviso se un salvataggio e' fallito, le righe, la nota. */
internal fun LazyListScope.collectionsEditItems(
  state: CollectionsLayoutState,
  onReorder: (List<String>) -> Unit,
  onSetShown: (String, Boolean) -> Unit,
) {
  item(key = "edit-intro") { EditNote(stringResource(R.string.collections_edit_intro)) }
  if (state.saveFailed) {
    item(key = "edit-failed") { EditNote(stringResource(R.string.collections_edit_save_failed), warning = true) }
  }
  item(key = "edit-rows") { EditableSections(state.sections, onReorder, onSetShown) }
  item(key = "edit-note") { EditNote(stringResource(R.string.collections_edit_note)) }
}

@Composable
private fun EditNote(text: String, warning: Boolean = false) {
  Text(
    text = text,
    modifier = Modifier.fillMaxWidth().padding(horizontal = CollectionsDimens.NoteInset, vertical = CollectionsDimens.NoteGap),
    style = MaterialTheme.typography.bodyMedium,
    color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
  )
}

/** Le sezioni che ci sono adesso, nell'ordine scelto, tutte (anche le nascoste): trascinabili e con l'interruttore "Mostra". */
@Composable
internal fun EditableSections(
  sections: List<CollectionsSection>,
  onReorder: (List<String>) -> Unit,
  onSetShown: (String, Boolean) -> Unit,
) {
  val byCode = sections.associateBy { it.code }
  val reorder = rememberReorderState(external = sections.map { it.code }, onCommit = onReorder)
  val announce = rememberAnnouncer()
  val movedTemplate = stringResource(R.string.collections_edit_moved)
  FluidListGroup {
    val order = reorder.order.filter { it in byCode }
    order.forEachIndexed { index, code ->
      key(code) {
        if (index > 0) FluidListDivider()
        val section = byCode.getValue(code)
        val title = section.title.resolve()
        EditRow(
          section = section,
          title = title,
          position = index,
          count = order.size,
          reorder = reorder,
          onSetShown = { onSetShown(code, it) },
          onMove = { delta ->
            val next = reorder.moveBy(code, delta)
            if (next != null) {
              announce(String.format(movedTemplate, title, GalleryNumbers.format(next.indexOf(code) + 1), GalleryNumbers.format(next.size)))
            }
            next != null
          },
        )
      }
    }
  }
}

@Composable
private fun EditRow(
  section: CollectionsSection,
  title: String,
  position: Int,
  count: Int,
  reorder: ReorderState,
  onSetShown: (Boolean) -> Unit,
  onMove: (Int) -> Boolean,
) {
  val code = section.code
  val slot = rememberSlotShift()
  val dragging = reorder.dragging == code
  val reduced = LocalFluidMotionPolicy.current.reducedMotion
  val canHide = section.hideable
  val status = stringResource(
    when {
      !canHide -> R.string.collections_edit_always
      section.shown -> R.string.collections_edit_shown
      else -> R.string.collections_edit_hidden
    },
  )
  val hint = section.hint?.resolve()
  // Sotto il nome solo cio' che serve: una sezione mostrata si vede dall'interruttore, e la riga resta bassa. "Sempre visibile" spiega
  // perche' Album non ha l'interruttore; "Nascosta" (e la frase della sezione, se ne ha una) compare quando l'hai appena spenta.
  val subtitle = when {
    !canHide -> status
    section.shown -> ""
    hint == null -> status
    else -> "$status · $hint"
  }
  val where = stringResource(R.string.collections_edit_position, GalleryNumbers.format(position + 1), GalleryNumbers.format(count))
  val up = stringResource(R.string.collections_edit_move_up)
  val down = stringResource(R.string.collections_edit_move_down)

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .onSizeChanged { reorder.reportHeight(code, it.height) }
      .zIndex(if (dragging) 1f else 0f)
      .graphicsLayer { translationY = if (dragging) reorder.dragOffset else 0f }
      .then(
        if (dragging) {
          Modifier.shadow(CollectionsDimens.DragElevation).background(MaterialTheme.colorScheme.surfaceContainerHighest)
        } else {
          Modifier
        },
      )
      .slotShift(slot, enabled = !dragging && !reduced),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FluidListRow(
      title = title,
      subtitle = subtitle,
      modifier = Modifier.weight(1f).semantics(mergeDescendants = true) {
        if (canHide) {
          role = Role.Switch
          toggleableState = ToggleableState(section.shown)
        }
        stateDescription = "$status, $where"
        // Le azioni della riga per chi non puo' trascinare (TalkBack): solo quelle possibili, non "Sposta su" in cima.
        customActions = buildList {
          if (position > 0) add(CustomAccessibilityAction(up) { onMove(-1) })
          if (position < count - 1) add(CustomAccessibilityAction(down) { onMove(1) })
        }
      },
      badge = if (canHide) ({ FluidSwitch(checked = section.shown, onCheckedChange = null) }) else null,
      disclosure = false,
      onClick = if (canHide) ({ onSetShown(!section.shown) }) else null,
    )
    // La maniglia sta fuori dalla riga: un tocco sulla maniglia non e' un interruttore.
    Box(
      modifier = Modifier
        .size(GalleryDimens.MinTouchTarget)
        .dragHandle(code, reorder)
        .clearAndSetSemantics { testTag = handleTag(code) },
      contentAlignment = Alignment.Center,
    ) {
      Icon(imageVector = Icons.Rounded.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

/** Dice a voce (TalkBack) cio' che e' successo dopo un'azione che non cambia il testo che il servizio sta leggendo. */
@Composable
private fun rememberAnnouncer(): (String) -> Unit {
  val view = LocalView.current
  return { text -> view.announceForAccessibility(text) }
}
