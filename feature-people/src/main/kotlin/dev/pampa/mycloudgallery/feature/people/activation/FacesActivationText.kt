package dev.pampa.mycloudgallery.feature.people.activation

import android.content.res.Resources
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.pampa.mycloudgallery.core.faces.contract.FacesSwitch
import dev.pampa.mycloudgallery.feature.people.R

// IL TESTO DELLA PAGINA DI ATTIVAZIONE (docs/fase8-progetto.md §2.1 "La pagina di attivazione", D3, D32, D34). Dato, non composable: lo prova un test
// (le parole che devono esserci, quelle che non devono) senza comporre niente. Ha una VERSIONE: il consenso la porta con se', e se il testo cambia
// tutti i dispositivi tornano all'avviso.

object FacesActivationText {

  /** La versione di questo testo: quella che il consenso porta e che gli altri dispositivi confrontano (`FacesSwitch.CURRENT_TEXT_VERSION`). */
  const val VERSION: Int = FacesSwitch.CURRENT_TEXT_VERSION

  /** I sei paragrafi del §2.1, in frasi brevi, con in grassetto cio' che conta; il settimo (modelli e licenze) e' [models]. */
  fun paragraphs(resources: Resources): List<AnnotatedString> = listOf(
    AnnotatedString(resources.getString(R.string.faces_activation_what)),
    marked(resources, R.string.faces_activation_work_pre, R.string.faces_activation_work_bold, null),
    marked(resources, null, R.string.faces_activation_yours_bold, R.string.faces_activation_yours_post),
    marked(resources, R.string.faces_activation_others_pre, R.string.faces_activation_others_bold, R.string.faces_activation_others_post),
    marked(resources, null, R.string.faces_activation_erase_bold, R.string.faces_activation_erase_post),
    marked(resources, R.string.faces_activation_device_pre, R.string.faces_activation_device_bold, R.string.faces_activation_device_post),
  )

  /** L'ultima riga: i modelli e le loro licenze (la nota sui dati di addestramento sta in Informazioni e licenze, L98). */
  fun models(resources: Resources): String = resources.getString(R.string.faces_activation_models)

  /** Le parole in grassetto di tutta la pagina, per i test. */
  fun boldPhrases(resources: Resources): List<String> =
    paragraphs(resources).flatMap { p -> p.spanStyles.map { p.text.substring(it.start, it.end) } }

  private fun marked(resources: Resources, pre: Int?, bold: Int, post: Int?): AnnotatedString = buildAnnotatedString {
    pre?.let { append(resources.getString(it)) }
    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(resources.getString(bold)) }
    post?.let { append(resources.getString(it)) }
  }
}
