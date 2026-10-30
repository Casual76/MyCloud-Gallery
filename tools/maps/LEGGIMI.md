# Strumenti della mappa (Fase 9, P2)

Producono, sul PC, tutto ciò che la mappa disegna senza Internet e lo stile con cui disegna le tessere online. Solo libreria standard di Python,
sempre con `python -I` (modalità isolata); **niente scaricamenti** se non a mano, con `--scarica`, dagli host dell'elenco approvato
(`docs/fase9-progetto.md` §8). Gli ingressi stanno in `build\download\<id>\` (una cartella nuova e vuota per file, mai nel repository);
GeoNames (`build\input\geonames\cities1000.zip`) e il dataset dei luoghi dell'app (`core-places/.../places.mcgp1`, solo per i nomi italiani) ci sono già.

```powershell
python -I tools\maps\base\fetch.py               # controlla gli ingressi della carta di base (con --scarica li scarica, a mano)
python -I tools\maps\style\fetch.py              # idem per stili, sprite e glifi (62 MB per i glifi)
python -I tools\maps\base\build_base.py          # scrive feature-map\src\main\assets\map\base\*.geojson e base\impronte.json
python -I tools\maps\style\build_style.py        # scrive stili, glyphs\, sprites\, LICENSE-*, NOTICE.txt e style\impronte.json
python -I tools\maps\style\check_style.py        # i controlli sugli stili scritti (URL, glifi, icone, contrasto, Ametista)
python -I tools\maps\style\preview.py            # anteprime SVG in docs\screenshots\9-P2\ (senza emulatore)
python -I tools\maps\selftest.py                 # prove degli strumenti, anche la riproducibilità (due corse, stessi byte)
python -I tools\maps\base\build_base.py --verifica    # ricostruisce e confronta con le impronte committate e con i file scritti
python -I tools\maps\style\build_style.py --verifica
```

Su Windows, per stampare caratteri non ASCII nella console aggiungere `-X utf8` dopo `-I`.

| Cartella | Cosa fa |
|---|---|
| `mapscommon.py` | Douglas-Peucker iterativo, numeri a 5 decimali, GeoJSON compatti, contrasto WCAG, scaricamento con impronta |
| `base/` | Natural Earth 5.1.2 (50m terre, coste, laghi, confini, nomi dei paesi; 10m province italiane) e GeoNames (città) in GeoJSON piccoli; `topologia.py` ricava i confini di regione e di provincia dai lati in comune e il punto del nome; `mcgp1.py` legge i nomi italiani dal dataset dei luoghi |
| `style/` | Positron e Dark di OpenFreeMap adattati (livelli ridotti, Ametista, caratteri e nomi nostri, URL stabile delle tessere), sprite ritagliato, glifi Noto Sans, licenze e NOTICE |

Le tolleranze, le versioni e le impronte sono in `base/fingerprints.json`, `style/fingerprints.json` e nelle `impronte.json` accanto;
le decisioni e i pesi in `docs/decisioni.md`, "Fase 9 P2". Le prove sugli asset dell'app (le stesse regole, rifatte in Kotlin) sono in
`feature-map/src/test/.../assets/`.
