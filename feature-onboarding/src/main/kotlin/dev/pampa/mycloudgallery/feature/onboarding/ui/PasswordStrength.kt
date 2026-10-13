package dev.pampa.mycloudgallery.feature.onboarding.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy
import dev.pampa.mycloudgallery.core.security.credentials.NasPasswordPolicy.Weakness
import dev.pampa.mycloudgallery.feature.onboarding.R

/**
 * Che cosa non va nella password scritta, secondo `NasPasswordPolicy` (revisione 2, F1); null se va bene o se il campo
 * e' vuoto. Il coordinatore rifa' lo stesso controllo: questo serve a spiegarlo mentre si scrive e a tenere spento il
 * tasto, cosi' una password debole non arriva nemmeno al tentativo. La copia di lavoro si azzera subito.
 */
internal fun passwordWeakness(user: String?, password: String): Weakness? {
  if (password.isEmpty()) return null
  val chars = password.toCharArray()
  return try {
    NasPasswordPolicy.check(user?.trim()?.takeIf { it.isNotEmpty() }, chars)
  } finally {
    chars.fill('\u0000')
  }
}

/** La spiegazione di [weakness], in una riga sotto il campo. */
@Composable
internal fun weaknessText(weakness: Weakness): String = stringResource(
  when (weakness) {
    Weakness.TOO_SHORT -> R.string.password_weak_short
    Weakness.CONTAINS_USER -> R.string.password_weak_user
    Weakness.PREDICTABLE -> R.string.password_weak_predictable
    Weakness.TOO_SIMPLE -> R.string.password_weak_simple
  },
)
