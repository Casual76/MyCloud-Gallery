package dev.pampa.mycloudgallery.core.designsystem.state

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf

/**
 * **Le scritture sul NAS sono ferme per il manifest firmato** (CA-1 della revisione funzionale della Fase 10): questa versione dell'app e' in sola lettura
 * (`ReleaseStatus.ReadOnly`: sotto la versione minima o in un blocco `WRITES`), non perche' l'utente ha toccato "Ferma le scritture". Nei cancelli le due cause
 * sono la stessa cosa (D29: "come Ferma le scritture"); per chi legge no: l'emergenza la riaccende l'utente, il blocco passa solo aggiornando l'app.
 *
 * La fornisce la radice dell'app (`ReleaseRoot` in `:app`); nei test e nelle anteprime vale falso. La leggono le schermate che hanno una frase propria per questa causa
 * (Backup, album, piede della Libreria); le altre dicono una frase neutra che vale per tutte le cause.
 */
val LocalWritesHeldByRelease = compositionLocalOf { false }

/** La frase giusta per "scritture ferme": [emergency] di sempre, [release] se a fermarle e' il manifest firmato (CA-1). */
@Composable
@ReadOnlyComposable
@StringRes
fun writesHeldText(@StringRes emergency: Int, @StringRes release: Int): Int = if (LocalWritesHeldByRelease.current) release else emergency
