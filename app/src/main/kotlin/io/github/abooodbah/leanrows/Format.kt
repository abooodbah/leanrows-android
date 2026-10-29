package io.github.abooodbah.leanrows

/** How records and fields are found, as on the desktop (DocumentFormat). */
enum class Format(val delimiter: Byte?, val label: String) {
    CSV(','.code.toByte(), "CSV"),
    TSV('\t'.code.toByte(), "TSV"),
    /** JSON Lines and NDJSON: one record per line, shown as one cell. */
    JSONL(null, "JSON Lines"),
    /** Logs and plain text: one record per line, shown as one cell. */
    LOG(null, "Text"),
    ;

    val delimited: Boolean get() = delimiter != null

    companion object {
        const val QUOTE: Byte = '"'.code.toByte()

        /**
         * The desktop app chooses by extension. A shared file on Android may
         * arrive without one, so the content type is tried next, and last the
         * first lines are counted for tabs, commas and semicolons.
         */
        fun infer(name: String?, mimeType: String?, sample: ByteArray): Format {
            when (name?.substringAfterLast('.', "")?.lowercase()) {
                "csv" -> return CSV
                "tsv", "tab" -> return TSV
                "jsonl", "ndjson" -> return JSONL
                "log", "txt" -> return LOG
            }
            when (mimeType?.lowercase()?.substringBefore(';')?.trim()) {
                "text/csv", "text/comma-separated-values", "text/x-csv", "application/csv" -> return CSV
                "text/tab-separated-values", "text/tsv" -> return TSV
                "application/x-ndjson", "application/jsonl", "application/x-jsonlines" -> return JSONL
            }
            return sniff(sample)
        }

        /** Tabs win over commas when both appear on most lines; otherwise text. */
        internal fun sniff(sample: ByteArray): Format {
            var lines = 0
            var tabLines = 0
            var commaLines = 0
            var tabs = 0
            var commas = 0
            for (byte in sample) {
                when (byte) {
                    '\n'.code.toByte() -> {
                        lines++
                        if (tabs > 0) tabLines++
                        if (commas > 0) commaLines++
                        tabs = 0
                        commas = 0
                    }
                    '\t'.code.toByte() -> tabs++
                    ','.code.toByte() -> commas++
                }
            }
            if (lines == 0) {
                lines = 1
                if (tabs > 0) tabLines++
                if (commas > 0) commaLines++
            }
            return when {
                tabLines * 2 > lines -> TSV
                commaLines * 2 > lines -> CSV
                else -> LOG
            }
        }
    }
}
