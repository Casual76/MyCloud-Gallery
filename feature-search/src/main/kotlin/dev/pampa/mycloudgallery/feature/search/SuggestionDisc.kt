package dev.pampa.mycloudgallery.feature.search

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ImageSearch
import androidx.compose.material.icons.rounded.MotionPhotosOn
import androidx.compose.material.icons.rounded.Panorama
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import dev.pampa.mycloudgallery.core.designsystem.icon.GalleryIconFamily
import dev.pampa.mycloudgallery.core.designsystem.icon.rememberGalleryIconColors
import dev.pampa.mycloudgallery.core.imaging.contract.ThumbRequest
import dev.pampa.mycloudgallery.core.library.contract.TimelineSnapshot
import dev.pampa.mycloudgallery.core.model.MediaId
import dev.pampa.mycloudgallery.core.search.contract.SearchMediaType
import dev.pampa.mycloudgallery.core.search.contract.SearchText
import dev.pampa.mycloudgallery.core.search.contract.Suggestion
import dev.pampa.mycloudgallery.feature.search.contract.SearchThumbnails

// IL TONDO DI UN SUGGERIMENTO (I2). «Anteprima > icona > parola»: la foto del primo risultato (la miniatura della Libreria, la stessa cache e le stesse
// porte della griglia), il volto di una persona (dal cancello dei volti), e solo se non c'e' niente il segno del suo tipo sul colore della sua famiglia
// (le stesse famiglie delle piastrelle di I3). Sotto la foto c'e' sempre il colore (e il segnaposto sfocato): l'anteprima che arriva non salta.
// **Nascosti mai** (D29): una foto si disegna solo se il suo media e' nell'istantanea della Libreria di adesso (che non ha i nascosti), un volto solo se
// una delle sue foto d'origine c'e' e se il cancello l'ha dato con il segno delle revoche di adesso (CG-1: [SuggestionFaces], letto qui a ogni composizione del tondo).

/** Il segno nelle prove del volto disegnato in un tondo (CG-1: dopo una revoca non c'e' piu'). */
internal const val SuggestionFaceTag: String = "suggerimento-volto"

/** Il segno nelle prove della foto disegnata in un tondo (CS-23: una foto nascosta dopo non c'e' piu', nemmeno a cache calda). */
internal const val SuggestionPhotoTag: String = "suggerimento-foto"

/** Il segno nelle prove del segno del tipo, quando il tondo non ha un'anteprima. */
internal const val SuggestionGlyphTag: String = "suggerimento-segno"

/** Chi tiene le anteprime della barra accanto alla finestra del caricatore (`ThumbnailLoader.keep`). */
internal const val SuggestionKeepOwner: String = "ricerca-suggerimenti"

/** Il segno e la famiglia di un suggerimento senza anteprima. */
internal fun glyphOf(suggestion: Suggestion): Pair<ImageVector, GalleryIconFamily> = when (suggestion) {
  is Suggestion.Recent -> Icons.Rounded.History to GalleryIconFamily.Search
  is Suggestion.PlaceSuggestion -> Icons.Rounded.Place to GalleryIconFamily.Places
  is Suggestion.PersonSuggestion -> Icons.Rounded.Face to GalleryIconFamily.People
  is Suggestion.Type -> Pair(
    when (suggestion.type) {
      SearchMediaType.PHOTO -> Icons.Rounded.Photo
      SearchMediaType.VIDEO -> Icons.Rounded.Videocam
      SearchMediaType.SCREENSHOT -> Icons.Rounded.Screenshot
      SearchMediaType.PANORAMA -> Icons.Rounded.Panorama
      SearchMediaType.MOTION_PHOTO -> Icons.Rounded.MotionPhotosOn
      SearchMediaType.DOCUMENT -> Icons.Rounded.Description
    },
    GalleryIconFamily.Photos,
  )
  is Suggestion.Term -> {
    val first = SearchText.normalize(suggestion.label).substringBefore(' ')
    when {
      CalendarCompletions.MONTHS.any { SearchText.normalize(it) == first } -> Icons.Rounded.CalendarMonth to GalleryIconFamily.Memories
      CalendarCompletions.HOLIDAYS.any { SearchText.normalize(it) == SearchText.normalize(suggestion.label) } -> Icons.Rounded.Celebration to GalleryIconFamily.Memories
      // Una cosa cercata per contenuto (CU-25: le scintille sono i Ricordi).
      else -> Icons.Rounded.ImageSearch to GalleryIconFamily.Search
    }
  }
}

/** La richiesta della miniatura della foto di [media], se e' nell'istantanea [library] e ha una sorgente: altrimenti niente (D29, D28). */
internal fun previewRequest(media: MediaId, library: TimelineSnapshot?, thumbnails: SearchThumbnails?): ThumbRequest? {
  if (library == null || thumbnails == null) return null
  val index = library.indexOf(media)
  return if (index >= 0) thumbRequestOf(thumbnails, library, index) else null
}

/**
 * Il tondo di [item] (riempie il posto che gli da' la barra, gia' ritagliato tondo). [library] e' l'istantanea di adesso, [thumbnails] le miniature della
 * Libreria, [faces] i volti con il segno delle revoche; senza, solo il segno.
 */
@Composable
internal fun SuggestionDisc(item: SuggestionItem, library: TimelineSnapshot?, thumbnails: SearchThumbnails?, faces: SuggestionFaces? = null) {
  val (glyph, family) = remember(item.suggestion) { glyphOf(item.suggestion) }
  val colors = rememberGalleryIconColors(family)
  Box(modifier = Modifier.fillMaxSize().background(Brush.linearGradient(listOf(colors.top, colors.bottom))), contentAlignment = Alignment.Center) {
    val drawn = when (val art = item.art) {
      is SuggestionArt.Photo -> photoArt(art.media, library, thumbnails)
      is SuggestionArt.Face -> {
        // Uno stato letto qui: una revoca o una foto d'origine nascosta ricompongono il tondo, che al fotogramma dopo non ha piu' il volto.
        val image = faces?.image(art.person, library)
        when {
          image != null -> {
            Image(bitmap = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag(SuggestionFaceTag))
            true
          }
          art.fallback != null -> photoArt(art.fallback, library, thumbnails)
          else -> false
        }
      }
      null -> false
    }
    if (!drawn) Icon(imageVector = glyph, contentDescription = null, tint = colors.glyph, modifier = Modifier.glyphSize().testTag(SuggestionGlyphTag))
  }
}

/** Il segno occupa poco piu' di meta' del tondo, come il glifo nelle piastrelle di I3. */
private fun Modifier.glyphSize(): Modifier = fillMaxSize(GlyphFraction)

private const val GlyphFraction = 0.56f

/** La foto: il segnaposto sfocato subito, la miniatura quando c'e'. Falso se il media non c'e' (nascosto, eliminato) o non ha una sorgente. */
@Composable
private fun photoArt(media: MediaId, library: TimelineSnapshot?, thumbnails: SearchThumbnails?): Boolean {
  val index = remember(library, media) { library?.indexOf(media) ?: -1 }
  if (library == null || thumbnails == null || index < 0) return false
  val request = remember(library, index) { thumbRequestOf(thumbnails, library, index) }
  val placeholder = remember(library, index) { library.thumbHashAt(index)?.let { thumbnails.placeholders.image(it) } }
  val image = request?.let { thumbnails.loader.image(it.key, it.ref) }?.value
  val bitmap = image ?: placeholder ?: return false
  Image(bitmap = bitmap, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().testTag(SuggestionPhotoTag))
  return true
}

/**
 * Le anteprime della barra si caricano **accanto** alla finestra della griglia che sta sotto (la Libreria o i risultati), come richieste "in arrivo"
 * (`ThumbnailLoader.keep`): non prendono il posto delle sue celle e la sorgente del NAS non legge mai un originale per loro (CE-5). Chiusa la barra si
 * tolgono.
 */
@Composable
internal fun KeepSuggestionPreviews(items: List<SuggestionItem>, library: TimelineSnapshot?, thumbnails: SearchThumbnails) {
  val requests = remember(items, library, thumbnails) {
    items.mapNotNull { item ->
      when (val art = item.art) {
        is SuggestionArt.Photo -> art.media
        is SuggestionArt.Face -> art.fallback
        null -> null
      }
    }.mapNotNull { previewRequest(it, library, thumbnails) }.distinctBy { it.key }
  }
  LaunchedEffect(requests, thumbnails) { thumbnails.loader.keep(SuggestionKeepOwner, requests) }
  DisposableEffect(thumbnails) { onDispose { thumbnails.loader.keep(SuggestionKeepOwner, emptyList()) } }
}
