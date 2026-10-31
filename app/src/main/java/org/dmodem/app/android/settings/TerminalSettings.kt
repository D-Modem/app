// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Neo-Desktop
package org.dmodem.app.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import org.dmodem.app.terminal.BackspaceMode
import org.dmodem.app.terminal.EnterMode
import org.dmodem.app.terminal.TermCharset
import org.dmodem.app.android.terminal.TerminalFont
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class CursorShape(val label: String) { BLOCK("Block"), UNDERLINE("Underline"), BAR("Bar") }

enum class BellMode(val label: String) { NONE("Off"), VIBRATE("Vibrate"), FLASH("Flash"), BOTH("Vibrate and flash") }

enum class VolumeKeys(val label: String) {
    SYSTEM("Volume (system)"),
    FONT_SIZE("Font size"),
    CTRL_ALT("Ctrl (down) and Alt (up)"),
}

/** How the terminal looks and behaves. */
data class TerminalSettings(
    val themeId: String = "vga",
    val font: TerminalFont = TerminalFont.SYSTEM,
    /** programming ligatures (-> != ==) in the fonts that have them */
    val ligatures: Boolean = false,
    /** the text's size (sp) when the columns aren't fixed */
    val fontSize: Float = 12f,
    /** the columns the screen is fitted to (the text sized for them): 0 to size by fontSize */
    val fixedColumns: Int = 80,
    val charset: TermCharset = TermCharset.CP437,
    val backspace: BackspaceMode = BackspaceMode.BS,
    val enter: EnterMode = EnterMode.CR,
    val bell: BellMode = BellMode.VIBRATE,
    val volumeKeys: VolumeKeys = VolumeKeys.FONT_SIZE,
    val keepScreenOn: Boolean = true,
    val scrollback: Int = 5000,
    val cursor: CursorShape = CursorShape.BLOCK,
    val cursorBlink: Boolean = true,
    /** bold as the bright colours (as DOS and BBSes have it) */
    val boldBright: Boolean = true,
    /** blink as a bright background, with no blinking (iCE colours, as ANSI art has it) */
    val iceColors: Boolean = false,
    /** what is typed shown at once (the remote doesn't echo) */
    val localEcho: Boolean = false,
    val extraKeys: Boolean = true,
    /** the answer to ENQ (^E) */
    val answerback: String = "",
    /** box drawing and block characters drawn to fill their cells (no gaps in ANSI art) */
    val drawBoxes: Boolean = true,
)

private val Context.terminalStore: DataStore<Preferences> by preferencesDataStore("terminal")

class TerminalSettingsStore(private val context: Context) {

    private object K {
        val theme = stringPreferencesKey("theme")
        val font = stringPreferencesKey("font")
        val ligatures = booleanPreferencesKey("ligatures")
        val fontSize = floatPreferencesKey("font_size")
        val fixedColumns = intPreferencesKey("fixed_columns")
        val charset = stringPreferencesKey("charset")
        val backspace = stringPreferencesKey("backspace")
        val enter = stringPreferencesKey("enter")
        val bell = stringPreferencesKey("bell")
        val volumeKeys = stringPreferencesKey("volume_keys")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val scrollback = intPreferencesKey("scrollback")
        val cursor = stringPreferencesKey("cursor")
        val cursorBlink = booleanPreferencesKey("cursor_blink")
        val boldBright = booleanPreferencesKey("bold_bright")
        val iceColors = booleanPreferencesKey("ice_colors")
        val localEcho = booleanPreferencesKey("local_echo")
        val extraKeys = booleanPreferencesKey("extra_keys")
        val answerback = stringPreferencesKey("answerback")
        val drawBoxes = booleanPreferencesKey("draw_boxes")
    }

    /** the [E] named [s], or [d] for none or one not known */
    private inline fun <reified E : Enum<E>> enumOf(s: String?, d: E): E =
        s?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: d

    val settings: Flow<TerminalSettings> = context.terminalStore.data.map { from(it) }

    /** the terminal's settings stored in [p], the defaults for what isn't */
    private fun from(p: Preferences): TerminalSettings {
        val d = TerminalSettings()
        return TerminalSettings(
            themeId = p[K.theme] ?: d.themeId,
            font = enumOf(p[K.font], d.font),
            ligatures = p[K.ligatures] ?: d.ligatures,
            fontSize = p[K.fontSize] ?: d.fontSize,
            fixedColumns = p[K.fixedColumns] ?: d.fixedColumns,
            charset = enumOf(p[K.charset], d.charset),
            backspace = enumOf(p[K.backspace], d.backspace),
            enter = enumOf(p[K.enter], d.enter),
            bell = enumOf(p[K.bell], d.bell),
            volumeKeys = enumOf(p[K.volumeKeys], d.volumeKeys),
            keepScreenOn = p[K.keepScreenOn] ?: d.keepScreenOn,
            scrollback = p[K.scrollback] ?: d.scrollback,
            cursor = enumOf(p[K.cursor], d.cursor),
            cursorBlink = p[K.cursorBlink] ?: d.cursorBlink,
            boldBright = p[K.boldBright] ?: d.boldBright,
            iceColors = p[K.iceColors] ?: d.iceColors,
            localEcho = p[K.localEcho] ?: d.localEcho,
            extraKeys = p[K.extraKeys] ?: d.extraKeys,
            answerback = p[K.answerback] ?: d.answerback,
            drawBoxes = p[K.drawBoxes] ?: d.drawBoxes,
        )
    }

    /** what's stored now */
    suspend fun current(): TerminalSettings = settings.first()

    /** one change made to what is saved now (not to a copy that may be behind: changes in quick succession) */
    suspend fun update(change: (TerminalSettings) -> TerminalSettings) {
        context.terminalStore.edit { p -> write(p, change(from(p))) }
    }

    /** [s] stored as it is */
    suspend fun save(s: TerminalSettings) {
        context.terminalStore.edit { p -> write(p, s) }
    }

    /** [s] into [p], enums by name */
    private fun write(p: MutablePreferences, s: TerminalSettings) {
        p[K.theme] = s.themeId
        p[K.font] = s.font.name
        p[K.ligatures] = s.ligatures
        p[K.fontSize] = s.fontSize
        p[K.fixedColumns] = s.fixedColumns
        p[K.charset] = s.charset.name
        p[K.backspace] = s.backspace.name
        p[K.enter] = s.enter.name
        p[K.bell] = s.bell.name
        p[K.volumeKeys] = s.volumeKeys.name
        p[K.keepScreenOn] = s.keepScreenOn
        p[K.scrollback] = s.scrollback
        p[K.cursor] = s.cursor.name
        p[K.cursorBlink] = s.cursorBlink
        p[K.boldBright] = s.boldBright
        p[K.iceColors] = s.iceColors
        p[K.localEcho] = s.localEcho
        p[K.extraKeys] = s.extraKeys
        p[K.answerback] = s.answerback
        p[K.drawBoxes] = s.drawBoxes
    }

    /** the font size [sp] stored alone (the pinch's) */
    suspend fun setFontSize(sp: Float) {
        context.terminalStore.edit { it[K.fontSize] = sp }
    }

    /** the fixed columns [n] stored alone */
    suspend fun setFixedColumns(n: Int) {
        context.terminalStore.edit { it[K.fixedColumns] = n }
    }
}
