# Libreria di prova dei ricordi (Fase 9, P11)

Serve a provare **Raccolte -> Ricordi** sul telefono di prova (account `test`) e a misurare la macchina dei ricordi sulla JVM. Non e' la misura vera: quella la fa
l'utente sui suoi ricordi (`docs/decisioni.md`, "Fase 9 P11", F125).

## Che cosa c'e'

- **La Libreria sintetica "di una famiglia"** sta nel codice Kotlin (`core-memories/src/test/.../eval/MemoriesEvalFamilyKit.kt`): cinque anni di foto con anniversari,
  viaggi, un evento, tre persone (nomi neutri), riprese ripetute (i doppioni), screenshot e documenti nei giorni giusti, foto mosse e buie come misure finte, "giorni
  tristi" come concetti nei vettori finti, nascosti perfetti. Tutto da un seme; nessun dato personale, nessun luogo vero (id finti), nessun GPS.
- **Le date sono relative al giorno della prova**: ogni foto dice `anni_fa` e `giorni_fa`, il giorno e' `oggi - giorni_fa giorni - anni_fa anni`. Gli anniversari cadono
  sempre nei giorni giusti, e nel repository non c'e' nessuna data.
- `generate_memories.py` (solo Python 3, nessuna libreria) legge la specifica e scrive i JPEG con l'EXIF, riusando il disegno e lo scrittore EXIF di
  `tools/fixtures/trips/generate_trips.py`. `generate-memories.ps1` lancia prima Gradle (se serve) e poi il generatore.
- `campione/spec-campione.tsv`: una parte della specifica (una festa, un giorno triste, un giorno di soli screenshot) per le prove del generatore senza Gradle. Un test
  della JVM (`MemoriesFixtureExportTest`) controlla che resti una parte della Libreria di oggi.

## Come si usa

```powershell
# le prove del generatore (EXIF riletto, nessun GPS, uscita deterministica, date relative a due giorni di prova)
powershell -ExecutionPolicy Bypass -File tools\fixtures\memories\generate-memories.ps1 -Verifica

# la Libreria per il giorno di oggi, in tools\fixtures\memories\out\ (non si committa)
powershell -ExecutionPolicy Bypass -File tools\fixtures\memories\generate-memories.ps1
```

La specifica si scrive con `gradlew :core-memories:testDebugUnitTest --tests "*MemoriesFixtureExportTest"` (su un PC condiviso, dentro un posto Gradle) in
`core-memories/build/memories-fixtures/` insieme a `attesi.tsv`, i numeri che la JVM attende dal riferimento sulla stessa Libreria. In `out/MCG-ricordi/` ci sono anche
`concetti.tsv` (i concetti dei vettori finti), `nascoste.txt` (le foto da nascondere a mano per provare i Nascosti) e `LEGGIMI.txt` con i comandi `adb push`.

## Limiti

- L'EXIF scritto ha la data e l'ora al minuto: le riprese ripetute a meno di un minuto hanno lo stesso orario sul telefono (la JVM le distingue al secondo).
- Le immagini non hanno vettori: i concetti di `concetti.tsv` dicono come costruirli (`SyntheticVectors`), ma sul telefono i vettori veri li calcola il modello della
  ricerca, quindi su queste immagini i doppioni si cercano senza vettori (firma visiva o finestra dei doppioni).
- Niente immagini nel repository (`.gitignore`, `NoPrivateMemoriesEvalInGitTest`).
