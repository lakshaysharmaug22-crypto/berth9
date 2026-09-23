package dev.berth9.engine.read;

import dev.berth9.engine.model.SourceRecord;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * Streams records out of XML documents (cXML invoices, custom partner feeds) with StAX, so memory
 * stays flat regardless of file size. Each occurrence of the configured record element becomes one
 * record; values found outside it (invoice header, order reference) are carried in as context.
 *
 * <p>Keys are paths relative to the record element: {@code UnitPrice/Money}, {@code @quantity},
 * {@code UnitPrice/Money/@currency}. Context keys use the parent element name:
 * {@code InvoiceDetailRequestHeader/@invoiceID}.
 *
 * <p>DTDs and external entities are disabled (XXE hardening): partner files are untrusted input.
 */
public final class XmlRecordReader implements RecordReader {

    private final XMLStreamReader xml;
    private final String recordElement;
    private final Map<String, String> context = new LinkedHashMap<>();
    private final Set<String> columns = new LinkedHashSet<>();
    private final Deque<String> path = new ArrayDeque<>();
    private final Deque<StringBuilder> text = new ArrayDeque<>();
    private final Deque<Boolean> hasChild = new ArrayDeque<>();
    private SourceRecord next;
    private boolean done;

    public XmlRecordReader(InputStream in, ReaderOptions options) {
        if (options.recordElement() == null || options.recordElement().isBlank()) {
            throw new IllegalArgumentException("XML sources need 'recordElement' in the mapping spec");
        }
        this.recordElement = options.recordElement();
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.IS_COALESCING, true);
            factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, true);
            this.xml = factory.createXMLStreamReader(in, options.charsetOrDefault().name());
        } catch (XMLStreamException e) {
            throw new IllegalArgumentException("unreadable XML: " + e.getMessage(), e);
        }
    }

    @Override
    public List<String> columns() {
        return List.copyOf(columns);
    }

    @Override
    public boolean hasNext() {
        if (next != null) {
            return true;
        }
        if (done) {
            return false;
        }
        next = advance();
        done = next == null;
        return next != null;
    }

    @Override
    public SourceRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        SourceRecord r = next;
        next = null;
        return r;
    }

    private SourceRecord advance() {
        try {
            while (xml.hasNext()) {
                int event = xml.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String name = xml.getLocalName();
                        if (!hasChild.isEmpty()) {
                            hasChild.pop();
                            hasChild.push(true);
                        }
                        if (name.equals(recordElement)) {
                            return readRecord();
                        }
                        for (int i = 0; i < xml.getAttributeCount(); i++) {
                            context.put(name + "/@" + xml.getAttributeLocalName(i), xml.getAttributeValue(i));
                        }
                        path.push(name);
                        text.push(new StringBuilder());
                        hasChild.push(false);
                    }
                    case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                        if (!text.isEmpty()) {
                            text.peek().append(xml.getText());
                        }
                    }
                    case XMLStreamConstants.END_ELEMENT -> {
                        if (path.isEmpty()) {
                            continue;
                        }
                        String name = path.pop();
                        String value = text.pop().toString().strip();
                        boolean leaf = !hasChild.pop();
                        if (leaf && !value.isEmpty()) {
                            String parent = path.peek();
                            context.put(parent == null ? name : parent + "/" + name, value);
                        }
                    }
                    default -> {
                        // comments, processing instructions, DTD events: ignored
                    }
                }
            }
            return null;
        } catch (XMLStreamException e) {
            throw new IllegalStateException("malformed XML near line " + xml.getLocation().getLineNumber() + ": " + e.getMessage(), e);
        }
    }

    /** Called positioned on the record's START_ELEMENT; consumes through its END_ELEMENT. */
    private SourceRecord readRecord() throws XMLStreamException {
        long line = xml.getLocation().getLineNumber();
        Map<String, String> fields = new LinkedHashMap<>(context);
        Map<String, Integer> occurrences = new HashMap<>();
        for (int i = 0; i < xml.getAttributeCount(); i++) {
            fields.put("@" + xml.getAttributeLocalName(i), xml.getAttributeValue(i));
        }
        Deque<String> rel = new ArrayDeque<>();
        Deque<StringBuilder> buffers = new ArrayDeque<>();
        Deque<Boolean> children = new ArrayDeque<>();
        int depth = 1;
        while (xml.hasNext() && depth > 0) {
            int event = xml.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    if (!children.isEmpty()) {
                        children.pop();
                        children.push(true);
                    }
                    String parent = rel.isEmpty() ? "" : rel.peek() + "/";
                    String key = parent + xml.getLocalName();
                    int seen = occurrences.merge(key, 1, Integer::sum);
                    if (seen > 1) {
                        key = key + "[" + seen + "]";
                    }
                    rel.push(key);
                    buffers.push(new StringBuilder());
                    children.push(false);
                    for (int i = 0; i < xml.getAttributeCount(); i++) {
                        fields.put(key + "/@" + xml.getAttributeLocalName(i), xml.getAttributeValue(i));
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (!buffers.isEmpty()) {
                        buffers.peek().append(xml.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    depth--;
                    if (depth == 0) {
                        break;
                    }
                    String key = rel.pop();
                    String value = buffers.pop().toString().strip();
                    boolean leaf = !children.pop();
                    if (leaf) {
                        fields.put(key, value);
                    }
                }
                default -> {
                    // ignored
                }
            }
        }
        columns.addAll(fields.keySet());
        return new SourceRecord(line, fields);
    }

    @Override
    public void close() {
        try {
            xml.close();
        } catch (XMLStreamException e) {
            // closing a finished reader: nothing actionable
        }
    }
}
