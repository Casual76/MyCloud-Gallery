package dev.pampa.mycloudgallery.core.release.keys

import dev.pampa.mycloudgallery.core.release.codec.EcdsaP256
import dev.pampa.mycloudgallery.core.release.contract.PinnedKey
import dev.pampa.mycloudgallery.core.release.contract.ReleaseKeyIds
import java.util.Base64

// LE CHIAVI DI PUBBLICAZIONE FISSATE NELL'APP (docs/fase10-progetto.md D31, §2.8 "Le chiavi nell'app"; C440, C442). P1.
//
// **K1** attiva e **K2** di riserva: le due chiavi pubbliche P-256 come SubjectPublicKeyInfo in base64, con l'impronta (SHA-256 dello SPKI in
// esadecimale) scritta anche in `docs/sicurezza.md` §5.6 (`ReleaseKeysTest` confronta codice e documento). Le private non sono MAI qui ne' nel
// repository: le genera l'admin sul PC con `ManifestTool keys` (PKCS#12 con frase segreta in `%USERPROFILE%\.mycloudgallery\release\`, piu'
// la copia offline e il foglio di K2).
//
// **Dal 12/11/2026 sono le chiavi VERE** ([PROVISIONAL] = false): le ha generate l'admin con `ManifestTool keys` quel giorno (le private nel
// PKCS#12 con frase segreta sul suo PC, il foglio di K2 offline); le due pubbliche qui sotto vengono dai certificati del suo file
// (`keytool -list -rfc`), le impronte sono anche in `docs/sicurezza.md` §5.6. Fino ad allora erano SEGNAPOSTO: due punti della curva derivati
// da una frase con SHA-256 ("nothing up my sleeve", la derivazione e' in `ReleaseKeysTest`), di cui nessuno conosce la chiave privata; un'app
// con i segnaposto non accetta nessun manifest vero (sicuro, ma senza kill switch). `release.ps1` (P12) rifiuta una release con
// [PROVISIONAL] vero, e `ReleaseKeysTest` (I5) pretende che con [PROVISIONAL] falso K1 e K2 non siano i segnaposto, ne' KT, ne' uguali.
//
// Unico posto, insieme a `testing/`, dove si costruisce una [PinnedKey] (Phase10ArchitectureTest, regola 3). KT, la chiave di prova, NON e'
// qui: sta in `testing/ReleaseTestKeys` e la fida solo la build debug (`ReleaseTrust.forChannel`).

object ReleaseKeys {
  /** Vero finche' K1 e K2 sono i segnaposto senza chiave privata: nessun manifest vero puo' essere accettato. Falso dal 12/11/2026. */
  const val PROVISIONAL: Boolean = false

  private const val K1_SPKI: String =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEJklojAKlq6F9AfGFJ6clxemlMANd/1co8rnSn725/vGZopH4LntbKnt/pI8oZPSpk14lTLBlVpJ4IYPjDBjOFg=="
  private const val K1_FINGERPRINT: String = "7cb8e8d6e2f28d17463bcc78e3a94d8ff70063b5a9f5329a227bf35aae62029b"

  private const val K2_SPKI: String =
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE9M0huGjvAtK7bcYfAbWxwm5pL/EIMI2JiHhSa63arcJkj1CuFbUlf8LIrLqyZb0xjD+pb3Cut7u12jFiGXa76A=="
  private const val K2_FINGERPRINT: String = "710442254f06d82dba7d9bbdcb7aac87ae82005fc24c176677bd0955dfa2a3d0"

  /** La chiave attiva: firma ogni manifest finche' e' buona. */
  val K1: PinnedKey = pinned(ReleaseKeyIds.K1, K1_SPKI, K1_FINGERPRINT)

  /** La chiave di riserva, offline: si usa solo se K1 e' persa o rubata (D31, E-REL8). */
  val K2: PinnedKey = pinned(ReleaseKeyIds.K2, K2_SPKI, K2_FINGERPRINT)

  /** Le chiavi di una build di release (beta e stabile). */
  val ALL: List<PinnedKey> = listOf(K1, K2)

  /**
   * Una chiave fissata da [spkiBase64] (SPKI P-256 non compresso, punto della curva) e dalla sua [fingerprint]: se non tornano, lancia (un
   * errore di chi scrive il codice, che i test trovano prima di ogni build).
   */
  internal fun pinned(id: Int, spkiBase64: String, fingerprint: String): PinnedKey {
    val spki = Base64.getDecoder().decode(spkiBase64)
    require(EcdsaP256.isValidSpki(spki)) { "La chiave $id non e' uno SPKI P-256 valido." }
    require(EcdsaP256.sha256Hex(spki) == fingerprint) { "L'impronta della chiave $id non torna." }
    return PinnedKey(id, spki, fingerprint)
  }
}
