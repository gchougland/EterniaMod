# Housing placement, road access and boundary curtains

Public plots must be within five horizontal blocks of a public road's **centerline**, measured from the nearest plot edge. Spline roads use their saved Catmull–Rom curve, including existing roads; legacy rectangular roads use their long-axis centerline. The plot does not need to overlap the pavement. Roads and portals keep their full vertical protection if a plot does overlap them.

A public road connected to a Hub portal can anchor claims along its length. The first road must reach within five blocks of the portal area; subsequent road surfaces must touch or overlap. A disconnected road does not create an anchor. Established public houses connected through this network may also anchor neighboring plots. Guild member plots retain their guild-only anchoring rules. Claim previews, confirmation, whole-plot restoration and infrastructure removal checks use this topology. Previously valid claims are retained when changing from edge-based to centerline distances; the server does not move or unclaim them.

The sample road in newly created playgrounds starts at X=3.5 so its existing sample-house locations meet the centerline rule. Existing playground roads and homes are retained, including builder edits.

## House placement

The entire house still needs five clear blocks inside each plot border and five clear blocks from the **road edge**. A free 24×24 plot has a 14×14 interior before road overlaps are subtracted. The starter house is 9×9. A deeply overlapping road can narrow the available placement range.

Opening a house preview selects a valid position nearest the plot center when possible, accounting for the catalog's rotated anchor offset. **Find valid spot** repeats this search for the current orientation; **Snap to me** still follows the player's feet. Neither action changes world blocks. The menu displays the current validator result, and native translations cover setbacks, protected roads, unloaded terrain and house type. Confirmation repeats validation before placing anything.

The reported existing lot at X=-21…2, Z=40…63 still admits a 9×9 house at X=-16…-8 with the required road setback. Its restrictive position is now discoverable without guessing or spending a move credit.

## Returning to the ledger

Choice menus provide a distinct **Back** button that returns to their parent page and pagination. Housing and customization lists return to the Architect's Ledger; their nested catalogs return to the parent list. Mailbox and player-shop service pages opened from a choice menu have a top-level **Back** button. Canceling house, decoration or customization previews returns to the originating list. Close remains available to leave menus entirely.

## Aurora border

The default nearby boundary uses an emissive sage/teal/blue light curtain. Fixed vertical quads follow each edge instead of billboard smoke sprites. One-block tiles join across the boundary, with a 64-frame texture containing two identical animation cycles. Each 3.2-second layer overlaps the next by 1.6 seconds, crossfading without a whole-border blackout. Width and height stay constant; only the light pattern moves.

The curtain extends from roughly 0.03 to 0.89 blocks above sampled ground. It remains distance-limited, deduplicates shared edges, skips protected roads/portals and unsuitable terrain, and never loads terrain for decoration. At most 32 segments are emitted per viewer per refresh. Layers expire after leaving/removing a plot, and client culling limits distant visibility.

Regenerate the geometry-based sprite sheet and particle assets with `python scripts/generate-border-aurora.py` (Pillow required). This creates no noise or painted texture. Client verification should cover both sides of each edge, corners, slopes, daylight/night, walking into range and sustained standing beside a border. Headless tests verify native asset loading, texture seams, crossfade timing and the height envelope, but cannot confirm the client's final appearance.
