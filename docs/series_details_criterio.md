# Criterio temporadas / episodios (Synology)

PinerTV no usa metadatos Emby/TMDB para listar episodios: lee el árbol de carpetas del NAS.

## Estructura esperada

```
/piner/SERIES/<Nombre Serie>/
  Temporada 1/          ← o Season 1, S01, o "1"
    *.mkv|mp4|…
  Temporada 2/
    …
```

También válido (sin carpetas de temporada):

```
/piner/SERIES/<Nombre Serie>/
  Show.S01E01.mkv
  Show.S01E02.mkv
  Cap.203.mkv           ← temporada 2, episodio 03
```

## Inferencia

1. **Carpetas de temporada**: nombre `Temporada N`, `Season N`, `S01` o número solo `1`–`40`.
2. **Sin carpetas**: se listan todos los vídeos bajo la serie (profundidad ≤ 3) y se agrupan por temporada parseada del nombre (`SxxExx`, `NxNN`, `Cap.NNN`, etc. vía `NameParser.parseEpisodeFile`).
3. **Sin número de temporada en el archivo**: cae en temporada 1.
4. **Episodio sin número**: se muestra como `Ep` y se ordena al final de la temporada.

La carpeta raíz de la serie se guarda en `MediaItem.folderPath` al indexar.
