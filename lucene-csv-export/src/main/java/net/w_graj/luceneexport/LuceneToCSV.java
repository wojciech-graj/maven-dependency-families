package net.w_graj.luceneexport;

import org.apache.lucene.index.*;
import org.apache.lucene.document.Document;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Bits;

import java.io.*;
import java.nio.file.Paths;
import java.util.*;

public class LuceneToCSV {
    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            System.exit(1);

        final String indexPath = args[0];
        final String csvPath = args[1];

        exportLuceneToCSV(indexPath, csvPath);

        System.out.println("Export complete: " + csvPath);
    }

    public static void exportLuceneToCSV(String indexDir, String csvFile) throws Exception {
        final FSDirectory dir = FSDirectory.open(Paths.get(indexDir));
        try (IndexReader reader = DirectoryReader.open(dir)) {
            final Set<String> fieldSet = new LinkedHashSet<>();
            for (LeafReaderContext ctx : reader.leaves()) {
                final LeafReader leafReader = ctx.reader();
                for (FieldInfo fi : leafReader.getFieldInfos()) {
                    fieldSet.add(fi.name);
                }
            }
            final List<String> fields = new ArrayList<>(fieldSet);
            System.out.println("Fields: " + fields.toString());

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(csvFile))) {
                for (LeafReaderContext ctx : reader.leaves()) {
                    final LeafReader leafReader = ctx.reader();
                    final Bits liveDocs = leafReader.getLiveDocs();
                    final int maxDoc = leafReader.maxDoc();
                    for (int i = 0; i < maxDoc; i++) {
                        if (liveDocs != null && !liveDocs.get(i)) {
                            continue;
                        }
                        final Document doc = leafReader.document(i);
                        final List<String> row = new ArrayList<>();
                        for (String field : fields) {
                            final String value = doc.get(field);
                            row.add(value == null ? "" : value);
                        }
                        writeCSVRow(writer, row);
                    }
                }
            }
        }
    }

    private static void writeCSVRow(Writer writer, List<String> values) throws IOException {
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(csvEscape(values.get(i)));
        }
        writer.write(sb.append('\n').toString());
    }

    private static String csvEscape(String value) {
        if (value == null) return "";
        final boolean needEscape = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r");
        if (!needEscape) return value;
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
