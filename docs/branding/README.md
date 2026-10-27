# Aurora brand assets

Artwork used at the top of the project README and anywhere else a wide banner is
needed. Everything here is generated from the sources in [`build/`](build), so it
can be re-rendered after a copy or layout change instead of being hand-edited.

## Files

| File | Size | Use |
|---|---|---|
| `aurora-hero-1760x990.png` / `.webp` | 1760 × 990 | Primary README banner (referenced from the root `README.md`) |
| `aurora-hero-3200x1800.png` | 3200 × 1800 | Master render, 2× of a 1600 × 900 canvas |
| `aurora-hero-slim-1280x320.png` / `.webp` | 1280 × 320 | Slim banner for headers, package listings, slides |
| `aurora-hero-slim-2560x640.png` | 2560 × 640 | Master render of the slim banner |
| `aurora-mark.png` | 267 × 264 | App mark cut from the splash art, transparent background |
| `aurora-wordmark-light.png` / `-dark.png` | 480 × 73 | "AURORA" wordmark, tinted for dark / light backgrounds |
| `aurora-submark-light.png` / `-dark.png` | 346 × 36 | "MESSENGER" submark, tinted for dark / light backgrounds |

## What is in the banner

* Left: the real logo mark and wordmark lifted from
  `app/src/main/res/drawable-nodpi/aurora_splash.png`, the headline, and five
  capability chips.
* Right: three phone mockups. The pairing screen and the incoming-call screen are
  untouched screenshots from `docs/test-evidence/`. The conversation in the middle
  is drawn in HTML using the app's own colours (`primary` / `surfaceVariant` in the
  dark scheme) because every chat screenshot in the test evidence contains
  test-harness strings such as `Hello-Alice-encrypted-over-TCP`.
* Bottom left: the pre-release and audit status, so the artwork never reads as a
  shipping-product claim.

## Palette

| Token | Hex | Where |
|---|---|---|
| Midnight | `#0F172A` | Canvas, dark surface |
| Lime | `#00C875` | Aurora ribbon, accent chip, online dot |
| Cyan | `#5CE1E6` | Outgoing bubbles, headline gradient |
| Cobalt | `#1D4ED8` | Aurora wash, gradient end |
| Magenta | `#A21CAF` | Corner glow |

## Rebuilding

Requires Python 3 with Pillow and NumPy/SciPy, plus Chrome or Edge.

```powershell
cd docs/branding/build
python extract_logo.py     # re-cut the mark and wordmark from the splash art
python fetch_fonts.py      # download Inter (SIL OFL 1.1) for the HTML render
python export.py           # render hero.html and write the final PNG/WebP files
```

`export.py` drives headless Chrome twice: once at 1600 × 900 for the wide banner
and once at 1280 × 320 with the `#slim` hash for the slim one. `render.ps1` is a
one-off helper for previewing a single render while editing.

The layout lives in `hero.html`; the slim variant is a set of `body.slim`
overrides at the bottom of the stylesheet.

## Font

Inter, SIL Open Font License 1.1 — https://github.com/rsms/inter
