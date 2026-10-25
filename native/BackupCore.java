package com.cabine.momo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Fabrique le fichier Excel (.xlsx) de sauvegarde, sans aucune bibliothèque externe. */
public final class BackupCore {
    private BackupCore() {}

    private static final String H = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private static final String NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
    private static final String RN = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    /**
     * @param tsv une opération par ligne, 11 champs séparés par une tabulation :
     *            date, heure, sim, libellé, montant, frais, bénéfice, par, id, horodatage, code
     * @param day dernier jour inclus (AAAA-MM-JJ)
     * @return le fichier .xlsx, ou null s'il n'y a aucune opération jusqu'à ce jour
     */
    public static byte[] build(String tsv, String day) throws IOException {
        List<String[]> rows = new ArrayList<String[]>();
        for (String line : tsv.split("\n")) {
            if (line.isEmpty()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 11) continue;
            if (f[0].compareTo(day) > 0) continue;
            rows.add(f);
        }
        if (rows.isEmpty()) return null;
        Collections.sort(rows, new java.util.Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return (a[0] + a[9]).compareTo(b[0] + b[9]);
            }
        });

        List<Object[]> ops = new ArrayList<Object[]>();
        ops.add(new Object[] {"Date", "Heure", "SIM", "Opération", "Montant (F)", "Frais (F)", "Bénéfice (F)", "Par", "Identifiant", "Horodatage"});
        // par jour : dépôts, retraits, crédit, forfait internet, forfait appel, bénéfice, nombre
        Map<String, double[]> days = new TreeMap<String, double[]>();
        for (String[] f : rows) {
            double amount = num(f[4]);
            double frais = num(f[5]);
            double profit = num(f[6]);
            ops.add(new Object[] {f[0], f[1], f[2], f[3], Double.valueOf(amount), Double.valueOf(frais), Double.valueOf(profit), f[7], f[8], f[9]});
            double[] d = days.get(f[0]);
            if (d == null) {
                d = new double[7];
                days.put(f[0], d);
            }
            String t = f[10];
            if (t.equals("dep")) d[0] += amount;
            else if (t.equals("ret")) d[1] += amount;
            else if (t.equals("cred")) d[2] += amount;
            else if (t.equals("fnet") || t.equals("forf")) d[3] += amount;
            else if (t.equals("fapp")) d[4] += amount;
            d[5] += profit;
            d[6] += 1;
        }
        List<Object[]> sum = new ArrayList<Object[]>();
        sum.add(new Object[] {"Date", "Dépôts (F)", "Retraits (F)", "Crédit (F)", "Forfait internet (F)", "Forfait appel (F)", "Bénéfice (F)", "Nb opérations"});
        for (Map.Entry<String, double[]> e : days.entrySet()) {
            double[] d = e.getValue();
            sum.add(new Object[] {e.getKey(), Double.valueOf(d[0]), Double.valueOf(d[1]), Double.valueOf(d[2]), Double.valueOf(d[3]), Double.valueOf(d[4]),
                    Double.valueOf(Math.round(d[5] * 100.0) / 100.0), Double.valueOf(d[6])});
        }

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipOutputStream z = new ZipOutputStream(bos);
        put(z, "[Content_Types].xml", H + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/></Types>");
        put(z, "_rels/.rels", H + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + RN + "/officeDocument\" Target=\"xl/workbook.xml\"/></Relationships>");
        put(z, "xl/workbook.xml", H + "<workbook xmlns=\"" + NS + "\" xmlns:r=\"" + RN + "\"><sheets>"
                + "<sheet name=\"Opérations\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Résumé par jour\" sheetId=\"2\" r:id=\"rId2\"/></sheets></workbook>");
        put(z, "xl/_rels/workbook.xml.rels", H + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"" + RN + "/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"" + RN + "/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"" + RN + "/styles\" Target=\"styles.xml\"/></Relationships>");
        put(z, "xl/styles.xml", H + "<styleSheet xmlns=\"" + NS + "\">"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font><font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"2\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/></cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>");
        put(z, "xl/worksheets/sheet1.xml", sheet(ops, new int[] {12, 8, 9, 26, 13, 11, 13, 20, 24, 26}));
        put(z, "xl/worksheets/sheet2.xml", sheet(sum, new int[] {12, 13, 13, 12, 18, 16, 13, 14}));
        z.close();
        return bos.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static double num(String s) {
        try {
            double v = Double.parseDouble(s.trim());
            return (Double.isNaN(v) || Double.isInfinite(v)) ? 0 : v;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String colLetter(int i) {
        StringBuilder sb = new StringBuilder();
        int n = i + 1;
        while (n > 0) {
            int m = (n - 1) % 26;
            sb.insert(0, (char) (65 + m));
            n = (n - 1) / 26;
        }
        return sb.toString();
    }

    private static String esc(String v) {
        StringBuilder sb = new StringBuilder(v.length() + 8);
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '&') sb.append("&amp;");
            else if (c == '<') sb.append("&lt;");
            else if (c == '>') sb.append("&gt;");
            else if (c == '"') sb.append("&quot;");
            else if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            else sb.append(c);
        }
        return sb.toString();
    }

    private static String numStr(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 1e15) return Long.toString((long) d);
        return Double.toString(d);
    }

    private static String sheet(List<Object[]> rows, int[] widths) {
        StringBuilder x = new StringBuilder();
        x.append(H).append("<worksheet xmlns=\"").append(NS).append("\"><sheetViews><sheetView workbookViewId=\"0\">")
                .append("<pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews><cols>");
        for (int i = 0; i < widths.length; i++) {
            x.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1).append("\" width=\"").append(widths[i]).append("\" customWidth=\"1\"/>");
        }
        x.append("</cols><sheetData>");
        for (int r = 0; r < rows.size(); r++) {
            x.append("<row r=\"").append(r + 1).append("\">");
            Object[] row = rows.get(r);
            for (int c = 0; c < row.length; c++) {
                Object v = row[c];
                if (v == null) continue;
                String ref = colLetter(c) + (r + 1);
                if (v instanceof Double) {
                    x.append("<c r=\"").append(ref).append("\"><v>").append(numStr(((Double) v).doubleValue())).append("</v></c>");
                } else {
                    String s = String.valueOf(v);
                    if (s.isEmpty()) continue;
                    x.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"").append(r == 0 ? " s=\"1\"" : "")
                            .append("><is><t xml:space=\"preserve\">").append(esc(s)).append("</t></is></c>");
                }
            }
            x.append("</row>");
        }
        x.append("</sheetData></worksheet>");
        return x.toString();
    }
}
