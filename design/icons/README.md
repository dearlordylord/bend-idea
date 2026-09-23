# Selected icon candidates

This directory keeps the approved icon artwork and its comparison preview. The two icons are mechanically rendered from explicit SVG paths; no image-generation output or Bender source cutouts are used.

| Asset | Intended use | Approved design |
|---|---|---|
| `bend.svg` / `bend_dark.svg` | Bend `.bend` file type | C2 stacked cards with side tab |
| `idea-catalog.svg` / `idea-catalog_dark.svg` | Future Idea Catalog entry point | O3 open index with B starting the first entry on the left page |

Both icon families also have transparent PNG renders at 16, 20, 32 and 512 px. [comparison.png](comparison.png) shows the selected art in light and dark themes at native small sizes.

## Distribution handoff

The approved Bend artwork is now the active `.bend` file icon in `src/main/resources/icons/` and the plugin brand icon in `src/main/resources/META-INF/`. Those copies have explicit 16 px and 40 px dimensions, respectively, and are included in the plugin distribution. [BendFileType.scala](../../src/main/scala/com/dearlordylord/bend/idea/syntax/BendFileType.scala) loads `/icons/bend.svg`; IntelliJ selects the matching `_dark.svg` resource in dark themes.

The plugin has no Idea Catalog UI or icon consumer yet. Its approved artwork remains here until that feature is implemented; it can then be installed under `src/main/resources/icons/` and loaded by the UI through `/icons/idea-catalog.svg`.

Rebuild all SVG and PNG previews with Python 3, `rsvg-convert` and ImageMagick:

```sh
python3 design/icons/draw.py
```

The artwork was originally selected in commit `76063d5`. Plugin packaging and editor checks belong to the integration change.
