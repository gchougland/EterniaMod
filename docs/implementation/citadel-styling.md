# Citadel typography and surfaces

Eternia uses deep teal, warm ivory, brass and sage. Keep decorations geometric: a fine double rim, clipped corners, tiny diamond accents and restrained gradients. Do not add noise, distressed textures or painted frames.

## Fonts

The website self-hosts **Cinzel** for display headings and **Source Sans 3** for body text and controls. Both were downloaded from the official Google Fonts repository, with their SIL Open Font Licenses beside the files:

- https://github.com/google/fonts/tree/main/ofl/cinzel
- https://github.com/google/fonts/tree/main/ofl/sourcesans3

Files live in `website/web/fonts/`; `website/web/styles.css` declares variable-weight `@font-face` rules with `font-display: swap`. No Google CDN request or internet access is needed at runtime. Keep each OFL file when packaging the site.

The pinned Hytale assets demonstrate `FontName: "Default"` and `"Secondary"`. No supported arbitrary TTF registration hook was found in the available 0.6.5 server API/assets. Do **not** put `FontName: "Cinzel"` into native markup: an unknown font can break a page. Dynamic game text uses Default, with Secondary for titles. The service-shell Eternia wordmark is rendered from Cinzel into a transparent image; it is not a custom client font. Live text stays selectable/localizable through ordinary native labels.

## Reusable native surfaces

`Common/UI/Custom/EterniaMod/Theme/Citadel.ui` exposes `PanelSurface`, `InsetSurface`, `HeaderSurface`, primary/secondary button states, and disabled states. Import it as `$T` and use `Background: $T.@PanelSurface;` or the existing `$T.@TextButton` templates. Use semantic flat colors only for progress, validity indicators and small separators.

The surfaces are 64px PNGs with a native 12px nine-slice border. This keeps the rim/corner shapes crisp when a button or panel changes size. Gradients are simple code-drawn fills; no texture art or ImageGen is involved. Regenerate with:

```text
python scripts/generate-citadel-surfaces.py
```

Requires Pillow and the downloaded Cinzel font. The script also renders the 360x80 wordmark, displayed at 180x40. Preserve a minimum 12px content inset inside frames. Keep action text at readable size; grow/wrap the control instead of shrinking text or hiding it with ellipses. Use ASCII `-`, `<` and `>` where an arrow or minus glyph might be missing.

## Local review

```text
node website/scripts/theme-preview.js
```

This starts a temporary loopback-only preview, checks that both local fonts load and desktop/mobile widths do not overflow, writes `website/test-output/citadel-theme.png` and `citadel-theme-mobile.png`, then stops its browser/server. Set `CHROMIUM_PATH` if Chrome is elsewhere. The preview uses the actual CSS and game patch images; it does **not** render Hytale's native layout engine.

Client checks: open Housing, the season journal, mailbox, Crown Store and road settings at your normal UI scale. Confirm text fits the framed controls. Rebind both mouse actions, Use, abilities 1/2 and Pick Block; equip the Road Designer and verify all six item-HUD key labels update on that client. The native server cannot validate client glyph rendering or key remapping headlessly.

## Plot previews

Claim lines are opaque, raised slightly above the ground and thicker in bird's-eye view, proportional to camera distance. A changed plot/validity/view replaces the overlay; the timer no longer accumulates coincident translucent copies. Closing the claim page clears it and restores the player camera. Check all free/paid personal and guild dimensions overhead, including the long rectangular guild plot. Default nearby property boundaries use the [aurora curtain](housing-navigation-and-boundaries.md#aurora-border), separately from this temporary grid.
