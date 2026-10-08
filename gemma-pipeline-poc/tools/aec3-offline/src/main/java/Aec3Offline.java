import cn.enaium.webrtc.aec3.*;
import java.io.*;
import java.nio.*;
import java.nio.file.*;

/**
 * E2 — AEC3 offline sobre un dataset de eco (mic.wav + ref.wav @16 kHz).
 * Uso: gradle run --args="<datasetDir> <preDelayMs> [bufferDelayMs]"
 *   preDelayMs: cuánto se retrasa la referencia antes de analyzeRender
 *   (pre-alineación gruesa; AEC3 estima el residual).
 * Escribe <datasetDir>/out_pre<N>.wav e imprime reducción de energía.
 */
public class Aec3Offline {
    static final int SR = 16000, F = 160;

    static short[] readWav(Path p) throws IOException {
        byte[] b = Files.readAllBytes(p);
        ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int n = (b.length - 44) / 2;
        short[] s = new short[n];
        for (int i = 0; i < n; i++) s[i] = bb.getShort(44 + 2 * i);
        return s;
    }

    static void writeWav(Path p, short[] s) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(44 + s.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + s.length * 2).put("WAVE".getBytes()).put("fmt ".getBytes())
         .putInt(16).putShort((short) 1).putShort((short) 1).putInt(SR).putInt(SR * 2)
         .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(s.length * 2);
        for (short v : s) h.putShort(v);
        Files.write(p, h.array());
    }

    static double energy(short[] x, int from, int to) {
        double e = 0;
        for (int i = Math.max(0, from); i < Math.min(x.length, to); i++) e += (double) x[i] * x[i];
        return e;
    }

    public static void main(String[] a) throws Exception {
        Path dir = Paths.get(a[0]);
        int preMs = Integer.parseInt(a[1]);
        int bufMs = a.length > 2 ? Integer.parseInt(a[2]) : -1;
        String micName = System.getProperty("mic", "mic.wav");
        short[] mic = readWav(dir.resolve(micName)), ref = readWav(dir.resolve("ref.wav"));
        int pre = preMs * SR / 1000, n = Math.min(mic.length, ref.length) / F;

        String cfgPath = System.getProperty("cfg");
        Aec3Config cfg = cfgPath != null
            ? Aec3_jvmKt.createAec3ConfigFromJson(new String(Files.readAllBytes(Paths.get(cfgPath))))
            : Aec3_jvmKt.createAec3Config();
        if (cfg == null) throw new IllegalArgumentException("config JSON inválido: " + cfgPath);
        if (cfgPath == null) {
            cfg.setFilterInitialStateSeconds(0.5f);
            cfg.setFilterConservativeInitialPhase(false);
        }
        if (System.getProperty("dumpcfg") != null) { System.out.println(cfg.toJson()); return; }
        Aec3Environment env = Aec3_jvmKt.createAec3Environment();
        Aec3Factory fac = Aec3_jvmKt.createAec3FactoryWithConfig(cfg);
        Aec3EchoControl ec = Aec3_jvmKt.createAec3EchoControl(fac, env, SR, 1, 1);
        if (bufMs >= 0) ec.setAudioBufferDelay(bufMs);
        Aec3AudioBuffer rb = Aec3_jvmKt.createAec3AudioBuffer(SR, 1), cb = Aec3_jvmKt.createAec3AudioBuffer(SR, 1);

        short[] out = new short[n * F];
        float[] rf = new float[F], cf = new float[F];
        for (int i = 0; i < n; i++) {
            for (int k = 0; k < F; k++) {
                int ri = i * F + k - pre;
                rf[k] = ri >= 0 && ri < ref.length ? ref[ri] : 0f;
                cf[k] = mic[i * F + k];
            }
            rb.writeChannel(0, rf); ec.analyzeRender(rb);
            cb.writeChannel(0, cf); ec.analyzeCapture(cb); ec.processCapture(cb, false);
            float[] o = cb.readChannel(0);
            for (int k = 0; k < F; k++) out[i * F + k] = (short) Math.max(-32768, Math.min(32767, Math.round(o[k])));
            if (i > 0 && i % 2000 == 0) {
                Aec3Metrics m = ec.getMetrics();
                System.out.printf("  t=%5.1fs ERL=%.1f ERLE=%.1f delay=%d ms%n", i * F / (double) SR,
                    m.getEchoReturnLoss(), m.getEchoReturnLossEnhancement(), m.getDelayMs());
            }
        }
        writeWav(dir.resolve(System.getProperty("out", "out_pre" + preMs + ".wav")), out);

        // Regiones: "eco" = ref activa ~540 ms antes (ventanas de 100 ms); "limpio" = sin ref en 0.3–1.6 s previos.
        int W = SR / 10, echoLag = 540 * SR / 1000;
        double eMicEcho = 0, eOutEcho = 0, eMicClean = 0, eOutClean = 0;
        int nEcho = 0, nClean = 0;
        for (int s = 2 * SR; s + W < out.length; s += W) {
            double refAt = energy(ref, s - echoLag, s - echoLag + W) / W;
            double refAny = energy(ref, s - (int) (1.6 * SR), s - (int) (0.3 * SR) + W) / ((int) (1.3 * SR) + W);
            double micE = energy(mic, s, s + W) / W;
            if (micE < 1e4) continue;                 // ignora silencio (~ -50 dBFS)
            if (refAt > 1e5) { eMicEcho += energy(mic, s, s + W); eOutEcho += energy(out, s, s + W); nEcho++; }
            else if (refAny < 1e2) { eMicClean += energy(mic, s, s + W); eOutClean += energy(out, s, s + W); nClean++; }
        }
        System.out.printf("pre=%d ms: reducción en eco = %.1f dB (n=%d)  |  cambio en voz sin eco = %.1f dB (n=%d)%n",
            preMs, 10 * Math.log10(eMicEcho / Math.max(eOutEcho, 1)), nEcho,
            10 * Math.log10(Math.max(eOutClean, 1) / Math.max(eMicClean, 1)), nClean);
        Aec3Metrics m = ec.getMetrics();
        System.out.printf("  final: ERL=%.1f ERLE=%.1f delay=%d ms%n", m.getEchoReturnLoss(), m.getEchoReturnLossEnhancement(), m.getDelayMs());
        rb.close(); cb.close(); ec.close(); fac.close(); env.close(); cfg.close();
    }
}
