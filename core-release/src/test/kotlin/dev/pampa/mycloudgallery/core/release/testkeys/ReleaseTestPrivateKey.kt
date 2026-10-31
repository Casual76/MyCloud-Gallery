package dev.pampa.mycloudgallery.core.release.testkeys

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

/**
 * La chiave **privata** di KT, la chiave di PROVA del manifest (docs/fase10-progetto.md D31, §2.8; P1). Sta **solo** qui, sotto `src/test/`
 * (il passo 3b di `tools/check.ps1` rifiuta una chiave privata in qualunque altro file del repository): la usano i test e
 * `ManifestTool sign --test-key`, che si compila con i test di core-release. KT non apre niente su una release (`ReleaseTrust.forChannel`) e
 * la sua pubblica non e' nel dex di release (passo 7i). Non e' un segreto: e' una chiave di prova, e chiunque legga il repository la conosce.
 */
internal object ReleaseTestPrivateKey {
  private const val KT_PKCS8: String = "MEECAQAwEwYHKoZIzj0CAQYIKoZIzj0DAQcEJzAlAgEBBCB9p5H+IAsY9OugStymCBywFk9b327zaza1R1FP7RD5Aw=="

  fun kt(): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(KT_PKCS8)))
}
