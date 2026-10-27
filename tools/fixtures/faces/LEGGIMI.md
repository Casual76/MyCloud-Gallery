# Dati di prova dei volti: ritratti di pubblico dominio e composizioni (Fase 8, P11)

Per le prove dei motori e del raggruppamento (`docs/fase8-progetto.md` §5.2) servono immagini con volti **senza persone vere**. Qui sta l'elenco dei ritratti di
pubblico dominio e il generatore dei casi che servono ai test; le immagini stanno **fuori dal repository**, in `mcg-models\faces\` (con gli ONNX e l'ambiente
Python di P1, `tools/models/faces/`). Nessun volto, ritratto o nome di una persona vera della famiglia entra mai in questa cartella: lo controlla
`NoPrivateFacesEvalInGitTest` (nessuna immagine sotto `tools/fixtures/faces/`, nessun file di etichette, nessun nome di chi e' ritratto nei nomi dei file o nei
sorgenti).

| File | Cosa |
|---|---|
| `ritratti.tsv` | i 35 ritratti: etichetta (`ritratto-NN`, mai il nome di chi e' ritratto), soggetto (`sNN` = una persona, `mNN` = una scena con piu' persone; lo stesso `sNN` e' la stessa persona ritratta in anni diversi, E-F2), fonte (pagina di Wikimedia Commons), licenza (pubblico dominio, PD-Art), dimensione e SHA-256 dell'immagine scaricata, e i nomi che non devono comparire altrove |
| `controlla_ritratti.py` | controlla l'elenco (e, con `--root`, le immagini scaricate: dimensione e SHA-256) |
| `compose_casi.py` | genera i casi: E-F3 (un ritratto dentro una "televisione"), E-F15 (la stessa persona due volte: nello specchio e in una foto incorniciata; un collage), gruppi da 3 a 8 volti piccoli |
| `misure_pubbliche.py` | le somiglianze vere di SFace sul set pubblico, come osservazioni per la regola delle soglie (`core-faces/src/test/resources/.../eval/osservazioni-pubbliche.tsv`) |

`compose.py` di P1 (`tools/models/faces/`) copre gia' le serie A (3-8 volti, lato 24-160 px), B (8 volti piccoli, 24-47 px) e C (senza volti): qui non si
duplica, `compose_casi.py` lo riusa (volti nitidi, sfondi senza volti, incollatura con bordo sfumato) e aggiunge solo cio' che manca.

## Come si lancia (sul PC, con l'ambiente di P1)

Tutto con `python -I` e il Python dell'ambiente (`<root>\venv\conv\Scripts\python.exe`); `<root>` e' `C:\VibeCoded Projects\mcg-models\faces` o dove si e' messo
l'ambiente. Nessuno scaricamento: i ritratti li ha scaricati `tools/models/faces/fetch_portraits.py` (P1, con il consenso dell'utente).

```
python -I tools\fixtures\faces\controlla_ritratti.py --root <root>
python -I tools\fixtures\faces\compose_casi.py --root <root> --verifica
python -I tools\fixtures\faces\misure_pubbliche.py --root <root> --scrivi core-faces\src\test\resources\dev\pampa\mycloudgallery\core\faces\eval\osservazioni-pubbliche.tsv
```

`compose_casi.py` scrive in `<root>\out\p11\` (`tv\`, `doppia\`, `collage\`, `piccoli\`, `verita.json`, `verifica.json`) con il seme fisso `20261029`: due
esecuzioni danno le stesse immagini. `verita.json` ha, per ogni immagine, i volti con il loro ruolo (`persona`, `tv`, `specchio`, `foto-nella-foto`, `collage`),
il ritratto di origine (`ritratto-NN`), il riquadro e i cinque punti, nelle coordinate dell'immagine da 1024 px.

| Serie | Immagini | Cosa prova |
|---|---|---|
| `tv` | 8 | E-F3: il volto dello schermo non e' una persona della casa; l'utente lo toglie con "Ignora questo volto". Dispari: una persona vera accanto alla TV |
| `doppia` | 8 | E-F15: la stessa persona due volte nella stessa foto; una sola delle due entra nella persona (a: riflesso in uno specchio, b: foto incorniciata appesa al muro) |
| `collage` | 6 | E-F15 ed E-F2: 4, 6 o 9 riquadri, fra cui due ritratti della stessa persona |
| `piccoli` | 12 | E-F13 ed E-F14: da 3 a 8 volti con lato corto 24-60 px su sfondi diversi |

Con `--verifica` le immagini passano da YuNet (ONNX di P1 a 1024 px) e lo script dice quanti volti di verita' trova e quanti riquadri in piu' (il volto sulla TV e
quello nella cornice li trova, ed e' il caso da provare). I numeri stanno in `docs/decisioni.md`, "Fase 8 P11".

## Sul telefono

Le immagini si portano sull'S25 o sull'emulatore con `adb push` dentro `DCIM/mcg-prova/` (la cartella recintata delle prove, D33), mai in altre cartelle. La
qualita' vera sui volti veri non si misura con queste immagini: si misura con lo strumento solo debug "Valutazione delle persone" (`docs/decisioni.md`,
"Fase 8 P11", i passi per l'utente).
