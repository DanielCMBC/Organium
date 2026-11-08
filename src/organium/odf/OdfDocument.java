package organium.odf;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic reader for Hauptwerk's XML database files (organ definitions, custom organs,
 * installation package definitions). Structure:
 * <pre>
 * &lt;Hauptwerk FileFormat="Organ" FileFormatVersion="..."&gt;
 *   &lt;ObjectList ObjectType="Rank"&gt;
 *     &lt;Rank&gt;&lt;a&gt;1&lt;/a&gt;&lt;b&gt;Open Diapason 8&lt;/b&gt;...&lt;/Rank&gt;
 * </pre>
 * Organ definitions are normally "compacted": field tags are shortened to a, b, ... a1, b1,
 * and fields holding their default value are omitted. Every table is kept as a list of field
 * maps; meaning is applied by higher layers (see OrganParser).
 */
public final class OdfDocument {
    private final String fileFormat;
    private final String fileFormatVersion;
    private final Map<String, List<OdfObject>> tables;

    private OdfDocument(String fileFormat, String fileFormatVersion, Map<String, List<OdfObject>> tables) {
        this.fileFormat = fileFormat;
        this.fileFormatVersion = fileFormatVersion;
        this.tables = Collections.unmodifiableMap(tables);
    }

    public static OdfDocument read(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return read(in);
        } catch (XMLStreamException e) {
            throw new IOException(file + ": " + e.getMessage(), e);
        }
    }

    private static OdfDocument read(InputStream in) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XMLStreamReader xml = factory.createXMLStreamReader(in);

        String format = null;
        String version = null;
        Map<String, List<OdfObject>> tables = new LinkedHashMap<>();
        Map<String, String> internedNames = new HashMap<>(); // field tags repeat in every row
        StringBuilder text = new StringBuilder();

        // depth 1 = <Hauptwerk>, 2 = <ObjectList>, 3 = object, 4 = field
        int depth = 0;
        String table = null;
        List<OdfObject> rows = null;
        Map<String, String> fields = null;
        String field = null;

        while (xml.hasNext()) {
            switch (xml.next()) {
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    if (depth == 1) {
                        if (!xml.getLocalName().equals("Hauptwerk")) {
                            throw new XMLStreamException("Not a Hauptwerk file: root is <" + xml.getLocalName() + ">");
                        }
                        format = xml.getAttributeValue(null, "FileFormat");
                        version = xml.getAttributeValue(null, "FileFormatVersion");
                    } else if (depth == 2) {
                        table = xml.getAttributeValue(null, "ObjectType");
                        if (table == null) {
                            table = xml.getLocalName();
                        }
                        rows = tables.computeIfAbsent(table, t -> new ArrayList<>());
                    } else if (depth == 3) {
                        fields = new LinkedHashMap<>();
                    } else if (depth == 4) {
                        field = internedNames.computeIfAbsent(xml.getLocalName(), k -> k);
                        text.setLength(0);
                    }
                }
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA -> {
                    if (depth == 4) {
                        text.append(xml.getText());
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (depth == 4) {
                        fields.put(field, text.toString().trim());
                    } else if (depth == 3) {
                        rows.add(new OdfObject(table, fields));
                    }
                    depth--;
                }
                default -> {
                }
            }
        }
        xml.close();
        if (format == null) {
            throw new XMLStreamException("Missing <Hauptwerk> root element");
        }
        for (Map.Entry<String, List<OdfObject>> e : tables.entrySet()) {
            e.setValue(Collections.unmodifiableList(e.getValue()));
        }
        return new OdfDocument(format, version, tables);
    }

    /** "Organ", "CustomOrgan", "InstallationPackageDefinition", ... */
    public String fileFormat() {
        return fileFormat;
    }

    public String fileFormatVersion() {
        return fileFormatVersion;
    }

    public Map<String, List<OdfObject>> tables() {
        return tables;
    }

    /** Rows of a table; empty if the file has no such table. */
    public List<OdfObject> table(String type) {
        return tables.getOrDefault(type, List.of());
    }

    /** The single _General row (spelled _general in custom organ files). */
    public OdfObject general() {
        List<OdfObject> rows = table("_General");
        if (rows.isEmpty()) {
            rows = table("_general");
        }
        return rows.isEmpty() ? null : rows.get(0);
    }
}
