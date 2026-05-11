package dev.chuds.stillcal.ical

/**
 * Line unfolder per RFC 5545 §3.1: a CRLF (or LF) followed by whitespace continues the
 * previous logical line — the linebreak and the single leading whitespace are removed.
 *
 * Tolerates both CRLF and bare LF endings. Trailing CR characters are stripped.
 */
internal object IcsLexer {

    fun unfold(source: String): List<String> {
        val raw = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val out = mutableListOf<String>()
        for (line in raw) {
            if (line.isEmpty()) continue
            val first = line[0]
            if ((first == ' ' || first == '\t') && out.isNotEmpty()) {
                val tail = line.substring(1)
                out[out.lastIndex] = out.last() + tail
            } else {
                out += line
            }
        }
        return out
    }
}
