package dev.pampa.mycloudgallery.feature.library

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.library.text.GalleryNumbers

/**
 * Come questo modulo scrive un conteggio: sempre da [GalleryNumbers] ("92.019", qualunque lingua abbia il telefono), mai
 * con `%d` nelle risorse. Il numero vero sceglie la forma del plurale; la risorsa riceve il testo gia' scritto (`%1$s`).
 */
@Composable
internal fun countText(@PluralsRes plural: Int, count: Int): String = pluralStringResource(plural, count, GalleryNumbers.format(count))

/** "92.019 foto, 7.981 video": il riepilogo in fondo alla Libreria e alle griglie delle Raccolte. */
@Composable
internal fun photoVideoSummary(photos: Int, videos: Int): String = stringResource(
  R.string.count_photos_videos,
  countText(R.plurals.count_photos, photos),
  countText(R.plurals.count_videos, videos),
)
