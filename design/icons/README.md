# Selected icon candidates

This directory keeps the approved icon artwork and its comparison preview. The two icons are mechanically rendered from explicit SVG paths; no image-generation output or Bender source cutouts are used.

| Asset | Intended use | Approved design |
|---|---|---|
| `bend.svg` / `bend_dark.svg` | Bend `.bend` file type | C2 stacked cards with side tab |
| `idea-catalog.svg` / `idea-catalog_dark.svg` | Future Idea Catalog entry point | O3 open index with B starting the first entry on the left page |

Both icon families also have transparent PNG renders at 16, 20, 32 and 512 px. [comparison.png](comparison.png) shows the selected art in light and dark themes at native small sizes.

## Distribution handoff

The plugin packages `src/main/resources` into its distribution by default. The existing Bend file type loads `/icons/bend.svg` from [BendFileType.scala](../../src/main/scala/com/dearlordylord/bend/idea/syntax/BendFileType.scala), so a future integration can place `bend.svg` and `bend_dark.svg` in `src/main/resources/icons/` to replace the current file icon. The plugin has no Idea Catalog UI or icon consumer yet; the catalog pair can go in the same resource directory when that feature is implemented and should then be loaded by its UI through `/icons/idea-catalog.svg`.

These candidates are not copied into `src/main/resources`, registered in `plugin.xml`, or included in a plugin distribution yet. The separate marketplace/plugin brand marks under `src/main/resources/META-INF/` are not part of this handoff.

Rebuild all SVG and PNG previews with Python 3, `rsvg-convert` and ImageMagick:

```sh
python3 design/icons/draw.py
```

No plugin tests were run; this commit contains design assets and packaging notes only.
