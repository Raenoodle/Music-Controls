/**
 * Sharp Corners support (Android).
 *
 * The Sharp Corners plugin (bleelblep, https://bleelblep.github.io/bleel-bazinga-repo/index.json)
 * squares everything Discord draws. Its native hook removes the corner from every React Native view,
 * so the player's plain views follow it without any help. It cannot reach what this plugin draws
 * itself: the rim in `transparent.ts` is an SVG path whose corner arcs are worked out here, and a
 * few cards and pictures set an explicit radius. Those ask `sharpCorners()` instead.
 *
 * While Sharp Corners runs it sets `globalThis.__bleelblepSharp = true` and removes it when it stops.
 * It is read on every render and never cached: plugin start order is not fixed, and the flag can
 * appear after this plugin has started.
 *
 * Circles (round buttons, the progress knob, the small cover) are left alone on purpose. Sharp Corners
 * squares or keeps them natively, following its own "Circles too" switch, which this flag does not
 * carry.
 */

/** Whether the Sharp Corners plugin is running. */
export function sharpCorners(): boolean {
	try {
		return (
			(globalThis as { __bleelblepSharp?: unknown }).__bleelblepSharp === true
		)
	} catch {
		return false
	}
}

/** `px` as the corner radius, or 0 while Sharp Corners is running. */
export const corner = (px: number): number => (sharpCorners() ? 0 : px)

/** Style that squares a rounded rectangle; add it only while `sharpCorners()` is true. */
export const SQUARE = { borderRadius: 0 } as const
