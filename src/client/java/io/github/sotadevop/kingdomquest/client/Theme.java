package io.github.sotadevop.kingdomquest.client;

/**
 * Colors of the kingdom menu (same palettes as StoryQuest). Light follows the vanilla menus; dark uses the palette of the
 * user's other dark GUI mod so both look alike. All colors are ARGB with full alpha (text with
 * alpha 0 is invisible in current Minecraft versions).
 *
 * @param panel       panel background
 * @param panelLight  top-left edge of the panel (raised look)
 * @param panelDark   bottom-right edge of the panel
 * @param inset       background of slots and boxes
 * @param insetDark   top-left edge of slots and boxes (sunken look)
 * @param insetLight  bottom-right edge of slots and boxes
 * @param selected    background of the selected row
 */
record Theme(
	int panel, int panelLight, int panelDark,
	int inset, int insetDark, int insetLight,
	int selected,
	int heading, int text, int secondary, int accent,
	int insetText, boolean insetShadow,
	int done, int locked, int warning
) {

	static final Theme LIGHT = new Theme(
		0xFFC6C6C6, 0xFFFFFFFF, 0xFF555555,
		0xFF8B8B8B, 0xFF373737, 0xFFFFFFFF,
		0xFFB0B0B0,
		0xFF404040, 0xFF404040, 0xFF4F4F4F, 0xFF404040,
		0xFFFFFFFF, true,
		0xFF155215, 0xFF505050, 0xFF7A3E00);

	static final Theme DARK = new Theme(
		0xFF4F4F4F, 0xFF666666, 0xFF161616,
		0xFF383838, 0xFF161616, 0xFF666666,
		0xFF383838,
		0xFFFFFFFF, 0xFFE0E0E0, 0xFFC8C8C8, 0xFFFFFF55,
		0xFFE0E0E0, false,
		0xFF7FE04A, 0xFFC8C8C8, 0xFFFFB347);

	static Theme current() {
		return ClientConfig.darkMode() ? DARK : LIGHT;
	}
}
