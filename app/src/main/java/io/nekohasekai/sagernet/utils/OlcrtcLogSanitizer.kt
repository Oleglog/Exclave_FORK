/******************************************************************************
 *                                                                            *
 * Copyright (C) 2026  olcRTC for Android contributors                        *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                       *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program. If not, see <https://www.gnu.org/licenses/>.      *
 *                                                                            *
 ******************************************************************************/

package io.nekohasekai.sagernet.utils

object OlcrtcLogSanitizer {
    // 64-character hex strings (key_hex)
    private val HEX_64 = Regex("[0-9a-fA-F]{64}")
    // Room ID after "room/" — digits
    private val ROOM_ID = Regex("(?<=room/)[0-9]+")

    fun sanitize(line: String): String {
        return line
            .replace(HEX_64, "[REDACTED-KEY]")
            .replace(ROOM_ID, "[REDACTED-ROOM]")
    }
}
