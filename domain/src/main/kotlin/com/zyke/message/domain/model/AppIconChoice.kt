package com.zyke.message.domain.model

/**
 * The launcher icon colours the person can pick. Android cannot recolour an icon at run time, so each choice is a
 * separate `<activity-alias>` in the manifest (named by [aliasName], a full class name in the app's package), with its
 * own icon; choosing one enables its alias and disables the others. The palette is fixed: no custom colours.
 * [swatch] is 0xAARRGGBB, the same background colour the manifest icon uses, so the Settings preview matches.
 */
enum class AppIconChoice(
    val id: String,
    val label: String,
    val aliasName: String,
    val swatch: Long
) {
    DEFAULT("default", "Default", "com.zyke.message.IconDefault", 0xFF1F63A4),
    BLUE("blue", "Blue", "com.zyke.message.IconBlue", 0xFF2F55D4),
    GREEN("green", "Green", "com.zyke.message.IconGreen", 0xFF1B6D3A),
    ORANGE("orange", "Orange", "com.zyke.message.IconOrange", 0xFFB8590A),
    ROSE("rose", "Rose", "com.zyke.message.IconRose", 0xFFA5254F),
    TEAL("teal", "Teal", "com.zyke.message.IconTeal", 0xFF006A6A),
    PURPLE("purple", "Purple", "com.zyke.message.IconPurple", 0xFF6650A4),
    MONO("mono", "Black and white", "com.zyke.message.IconMono", 0xFF121212);

    companion object {
        /** The choice stored under [id]; anything missing or unknown is [DEFAULT]. */
        fun fromStored(id: String?): AppIconChoice = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
