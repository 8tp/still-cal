package dev.chuds.stillcal.ical

/**
 * A property as it sat on disk: name, optional params, value. The mapper in [IcsTypes]
 * converts these into a typed [dev.chuds.stillcal.data.Event].
 */
internal data class RawProperty(
    val name: String,
    val params: Map<String, String>,
    val value: String,
)

internal data class RawVAlarm(
    val properties: List<RawProperty>,
)

internal data class RawVEvent(
    val properties: List<RawProperty>,
    val alarms: List<RawVAlarm>,
)

/**
 * BEGIN/END-aware reader. Yields one [RawVEvent] per VEVENT block. Anything outside a
 * VEVENT (CALENDAR-level properties, TIMEZONE blocks, unknown components) is skipped —
 * we tolerate them, we don't honor them.
 */
internal object IcsParser {

    fun parseEvents(source: String): List<RawVEvent> {
        val lines = IcsLexer.unfold(source)
        val out = mutableListOf<RawVEvent>()

        var i = 0
        while (i < lines.size) {
            val prop = parseProperty(lines[i])
            i++
            if (prop.name == "BEGIN" && prop.value == "VEVENT") {
                val (event, consumed) = readVEvent(lines, i)
                out += event
                i = consumed
                continue
            }
            // Anything else at the top level is consumed silently.
        }
        return out
    }

    private fun readVEvent(lines: List<String>, start: Int): Pair<RawVEvent, Int> {
        val props = mutableListOf<RawProperty>()
        val alarms = mutableListOf<RawVAlarm>()
        var i = start
        while (i < lines.size) {
            val prop = parseProperty(lines[i])
            i++
            if (prop.name == "END" && prop.value == "VEVENT") {
                return RawVEvent(props, alarms) to i
            }
            if (prop.name == "BEGIN" && prop.value == "VALARM") {
                val (alarm, consumed) = readVAlarm(lines, i)
                alarms += alarm
                i = consumed
                continue
            }
            if (prop.name == "BEGIN") {
                // Unknown nested block — skip until matching END.
                i = skipBlock(lines, i, prop.value)
                continue
            }
            props += prop
        }
        return RawVEvent(props, alarms) to i
    }

    private fun readVAlarm(lines: List<String>, start: Int): Pair<RawVAlarm, Int> {
        val props = mutableListOf<RawProperty>()
        var i = start
        while (i < lines.size) {
            val prop = parseProperty(lines[i])
            i++
            if (prop.name == "END" && prop.value == "VALARM") {
                return RawVAlarm(props) to i
            }
            if (prop.name == "BEGIN") {
                i = skipBlock(lines, i, prop.value)
                continue
            }
            props += prop
        }
        return RawVAlarm(props) to i
    }

    /**
     * Consume an unrecognized BEGIN…END block. Only [blockName]'s END decrements depth —
     * mismatched ENDs (e.g. END:FOO inside a BEGIN:BAR block) are tolerated as data, not
     * treated as block delimiters.
     */
    private fun skipBlock(lines: List<String>, start: Int, blockName: String): Int {
        var i = start
        var depth = 1
        while (i < lines.size && depth > 0) {
            val prop = parseProperty(lines[i])
            i++
            if (prop.name == "BEGIN") depth++
            else if (prop.name == "END" && prop.value == blockName) depth--
        }
        return i
    }

    private fun parseProperty(line: String): RawProperty {
        // name(;param=value)*:value
        val colon = findUnquotedColon(line)
        if (colon < 0) {
            return RawProperty(line.uppercase(), emptyMap(), "")
        }
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = splitUnquoted(head, ';')
        val name = parts.first().uppercase()
        val params = LinkedHashMap<String, String>()
        for (k in 1 until parts.size) {
            val eq = parts[k].indexOf('=')
            if (eq < 0) continue
            val pk = parts[k].substring(0, eq).uppercase()
            val rawValue = parts[k].substring(eq + 1)
            // Only strip a matched pair of surrounding double quotes — trim('"') would have
            // stripped quotes from anywhere, including the interior of a value like "a"b".
            val pv = if (rawValue.length >= 2 && rawValue.startsWith('"') && rawValue.endsWith('"')) {
                rawValue.substring(1, rawValue.length - 1)
            } else {
                rawValue
            }
            params[pk] = pv
        }
        return RawProperty(name = name, params = params, value = value)
    }

    private fun findUnquotedColon(s: String): Int {
        var inQuotes = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ':' && !inQuotes) return i
        }
        return -1
    }

    private fun splitUnquoted(s: String, sep: Char): List<String> {
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuotes = false
        for (c in s) {
            when {
                c == '"' -> { inQuotes = !inQuotes; buf.append(c) }
                c == sep && !inQuotes -> { out += buf.toString(); buf.setLength(0) }
                else -> buf.append(c)
            }
        }
        out += buf.toString()
        return out
    }
}
