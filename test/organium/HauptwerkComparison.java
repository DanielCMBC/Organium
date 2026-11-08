package organium;

import organium.audio.Sample;
import organium.audio.VoiceEngine;
import organium.io.OrganParser;
import organium.organ.Console;
import organium.organ.Organ;
import organium.organ.spatial.MeasuredPanTable;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a Hauptwerk spatial test organ through Organium (parser, Console with the measured pan
 * table, VoiceEngine) and compares each note with Hauptwerk's recording of the same organ.
 *
 * Each of the 33 natural keys 36..91 is held for 2.5 s with 2.0 s gaps, like the Hauptwerk takes,
 * and measured over onset + 0.5 s .. + 2.0 s. Compared per note:
 *   per-channel gain relative to the pipe's own centre, 20log10(L / ((L + R) / 2)) and likewise R,
 *   which for Hauptwerk's law equals 20log10(1 -/+ p); and the amplitude sum, which must be the same
 *   for every pipe.
 *
 * Usage: HauptwerkComparison <organ.xml> <hauptwerk analysis.csv> <out.csv>
 */
public final class HauptwerkComparison {
    private static final int RATE = 48000;
    private static final int BLOCK = 256;
    private static final int[] NATURALS = java.util.stream.IntStream.range(36, 92)
            .filter(n -> java.util.Set.of(0, 2, 4, 5, 7, 9, 11).contains(n % 12)).toArray();

    public static void main(String[] args) throws Exception {
        Path def = Path.of(args[0]);
        Organ organ = OrganParser.parse(def);
        Map<Integer, Sample> samples = OrganParser.loadSamples(organ, OrganParser.sampleSetsRootOf(def)).byId();
        VoiceEngine engine = new VoiceEngine(256, organ.pipes().size(), RATE, 0.05f);
        Console console = new Console(organ, samples, engine, MeasuredPanTable.hauptwerk());

        Map<Integer, double[]> organium = new HashMap<>();
        float[] block = new float[BLOCK * 2];
        int hold = RATE * 25 / 10 / BLOCK;
        int gap = RATE * 2 / BLOCK;
        int w0 = RATE / 2 / BLOCK;
        int w1 = RATE * 2 / BLOCK;
        for (int note : NATURALS) {
            console.keyDown(0, note);
            double sl = 0;
            double sr = 0;
            long n = 0;
            for (int b = 0; b < hold; b++) {
                engine.render(block, BLOCK);
                if (b >= w0 && b < w1) {
                    for (int f = 0; f < BLOCK; f++) {
                        sl += block[2 * f] * block[2 * f];
                        sr += block[2 * f + 1] * block[2 * f + 1];
                    }
                    n += BLOCK;
                }
            }
            console.keyUp(0, note);
            for (int b = 0; b < gap; b++) {
                engine.render(block, BLOCK);
            }
            organium.put(note, new double[]{Math.sqrt(sl / n), Math.sqrt(sr / n)});
        }

        Map<Integer, double[]> hauptwerk = new HashMap<>();
        try (BufferedReader in = Files.newBufferedReader(Path.of(args[1]))) {
            String[] head = in.readLine().split(",");
            List<String> cols = List.of(head);
            for (String line; (line = in.readLine()) != null; ) {
                String[] v = line.split(",", -1);
                if (!v[cols.indexOf("matched")].equals("True")) {
                    continue;
                }
                double l = Math.pow(10, Double.parseDouble(v[cols.indexOf("rms_l_db")]) / 20);
                double r = Math.pow(10, Double.parseDouble(v[cols.indexOf("rms_r_db")]) / 20);
                double lr = Double.parseDouble(v[cols.indexOf("block_l_minus_r_median_db")]);
                hauptwerk.put(Integer.parseInt(v[cols.indexOf("note")]), new double[]{l, r, lr});
            }
        }

        List<String> rows = new ArrayList<>();
        rows.add("note,x_m,y_m,organium_l_rel_db,organium_r_rel_db,hauptwerk_l_rel_db,hauptwerk_r_rel_db,err_l_db,err_r_db,organium_sum_db,hauptwerk_sum_db");
        double worst = 0;
        double sq = 0;
        int count = 0;
        double sumMin = Double.MAX_VALUE;
        double sumMax = -Double.MAX_VALUE;
        Map<Integer, Organ.Pipe> pipeOfNote = new HashMap<>();
        for (Organ.Pipe p : organ.pipes()) {
            if (p.rankId() == 2) {
                pipeOfNote.put(p.note(), p);
            }
        }
        for (int note : NATURALS) {
            double[] o = organium.get(note);
            double[] h = hauptwerk.get(note);
            if (h == null) {
                continue;
            }
            double oMid = (o[0] + o[1]) / 2;
            // Hauptwerk: use the block-median ratio (robust), converted to the same per-channel form
            double ratio = Math.pow(10, h[2] / 20);
            double hl = 20 * Math.log10(2 * ratio / (1 + ratio));
            double hr = 20 * Math.log10(2 / (1 + ratio));
            double ol = 20 * Math.log10(o[0] / oMid);
            double or = 20 * Math.log10(o[1] / oMid);
            double el = ol - hl;
            double er = or - hr;
            double oSum = 20 * Math.log10(o[0] + o[1]);
            sumMin = Math.min(sumMin, oSum);
            sumMax = Math.max(sumMax, oSum);
            worst = Math.max(worst, Math.max(Math.abs(el), Math.abs(er)));
            sq += el * el + er * er;
            count += 2;
            Organ.Pipe p = pipeOfNote.get(note);
            rows.add(String.format(java.util.Locale.ROOT, "%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                    note, p.x(), p.y(), ol, or, hl, hr, el, er, oSum, 20 * Math.log10(h[0] + h[1])));
        }
        Files.write(Path.of(args[2]), rows);
        System.out.printf("%s: %d notes  per-channel gain error vs Hauptwerk: rms %.4f dB, max %.4f dB;  "
                        + "Organium amplitude sum spread %.4f dB%n",
                def.getFileName(), count / 2, Math.sqrt(sq / count), worst, sumMax - sumMin);
    }
}
