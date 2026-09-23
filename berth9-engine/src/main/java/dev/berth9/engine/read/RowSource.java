package dev.berth9.engine.read;

import java.io.IOException;

/**
 * Produces raw rows from a tabular file. CSV lives in the engine; spreadsheet sources are
 * plugged in by the server (Apache POI) so the engine keeps zero third-party dependencies.
 */
public interface RowSource extends AutoCloseable {

    /** Next row, or null at end of input. */
    Row next() throws IOException;

    @Override
    void close();
}
