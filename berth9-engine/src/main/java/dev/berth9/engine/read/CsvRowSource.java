package dev.berth9.engine.read;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 tokenizer that survives what partners actually send: quoted fields with embedded
 * delimiters and line breaks, doubled quotes, CRLF or LF endings, a UTF-8 BOM and stray quotes
 * in the middle of unquoted text (kept literally instead of failing the whole file).
 */
public final class CsvRowSource implements RowSource {

    private final PushbackReader in;
    private final char delimiter;
    private long line = 1;
    private boolean first = true;

    public CsvRowSource(Reader reader, char delimiter) {
        this.in = new PushbackReader(reader, 2);
        this.delimiter = delimiter;
    }

    @Override
    public Row next() throws IOException {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean fieldStarted = false;
        boolean any = false;
        long start = line;
        int c;
        while ((c = in.read()) != -1) {
            char ch = (char) c;
            if (first) {
                first = false;
                if (ch == '﻿') {
                    continue;
                }
            }
            any = true;
            if (quoted) {
                if (ch == '"') {
                    int peek = in.read();
                    if (peek == '"') {
                        cell.append('"');
                    } else {
                        quoted = false;
                        if (peek != -1) {
                            in.unread(peek);
                        }
                    }
                } else {
                    if (ch == '\n') {
                        line++;
                    }
                    cell.append(ch);
                }
                continue;
            }
            if (ch == '"' && !fieldStarted) {
                quoted = true;
                fieldStarted = true;
            } else if (ch == delimiter) {
                cells.add(cell.toString());
                cell.setLength(0);
                fieldStarted = false;
            } else if (ch == '\r' || ch == '\n') {
                if (ch == '\r') {
                    int peek = in.read();
                    if (peek != '\n' && peek != -1) {
                        in.unread(peek);
                    }
                }
                line++;
                cells.add(cell.toString());
                return new Row(start, cells);
            } else {
                cell.append(ch);
                if (!Character.isWhitespace(ch)) {
                    fieldStarted = true;
                }
            }
        }
        if (!any) {
            return null;
        }
        cells.add(cell.toString());
        return new Row(start, cells);
    }

    @Override
    public void close() {
        try {
            in.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
