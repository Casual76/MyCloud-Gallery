package dev.pampa.mycloudgallery.core.family.onboarding

import java.security.MessageDigest

/**
 * Il riassunto di "di chi sono i dati di questo telefono" (R6 della revisione 5B): l'account del NAS e il NAS (il suo GUID).
 * "Configura di nuovo" lo ricorda e, alla fine del nuovo onboarding, lo confronta con quello di chi e' rientrato: se sono
 * diversi, i dati locali di prima (catalogo, dati personali, miniature, diari) sono di un'altra persona e si tolgono.
 *
 * Un riassunto (SHA-256, i primi 16 byte in esadecimale) e non l'utente e il GUID in chiaro: serve solo al confronto, e
 * come ogni altro dato dell'onboarding non va mai in un log (§4.2). Maiuscole e minuscole non contano: l'account di un NAS
 * e il suo GUID si confrontano sempre cosi'.
 */
internal object OwnerStamp {

  private const val DOMAIN = "mcg-owner-v1"

  /** Il riassunto di [nas] e [user], o null se uno dei due non si conosce (allora non si sa di chi siano i dati). */
  fun of(nas: NasFacts?, user: String?): String? {
    val guid = nas?.guid?.takeIf { it.isNotBlank() } ?: return null
    val account = user?.takeIf { it.isNotBlank() } ?: return null
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(DOMAIN.toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(guid.trim().lowercase().toByteArray(Charsets.UTF_8))
    digest.update(0)
    digest.update(account.trim().lowercase().toByteArray(Charsets.UTF_8))
    return digest.digest().copyOf(16).joinToString("") { "%02x".format(it) }
  }
}
