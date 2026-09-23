package dev.berth9.engine.read;

import java.io.IOException;
import java.io.InputStream;

/** Opens a {@link RecordReader} for one format. */
@FunctionalInterface
public interface ReaderFactory {

    RecordReader open(InputStream in, ReaderOptions options) throws IOException;
}
