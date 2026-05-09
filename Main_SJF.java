import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.RoundRectangle2D;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * ╔══════════════════════════════════════════════════════════════════════╗
 * ║          CPU Scheduling Simulator  —  Main_SJF.java                 ║
 * ║                                                                      ║
 * ║  Algorithms:                                                         ║
 * ║    • Preemptive SJF  (SRTF — Shortest Remaining Time First)          ║
 * ║    • Non-Preemptive SJF  (Shortest Job First)                        ║
 * ║    • Preemptive Priority Scheduling                                  ║
 * ║                                                                      ║
 * ║  Metrics:                                                            ║
 * ║    CT  = Completion Time                                             ║
 * ║    TAT = CT  − AT   (Turnaround Time)                                ║
 * ║    WT  = TAT − BT   (Waiting Time)                                   ║
 * ║    RT  = First-Start − AT   (Response Time)                          ║
 * ║                                                                      ║
 * ║  Priority Rule: Smaller number = Higher priority                     ║
 * ║                                                                      ║
 * ║  Architecture: MVC-style — all layers in one file per spec.          ║
 * ║    model/       Process, Segment, SimulationResult                   ║
 * ║    scheduler/   Scheduler interface + 3 implementations              ║
 * ║    service/     ValidationService, AnalysisService, ScenarioService  ║
 * ║    controller/  SimulationController                                 ║
 * ║    ui/          Theme, UIFactory, all panels + GanttChartPanel       ║
 * ╚══════════════════════════════════════════════════════════════════════╝
 */
public class Main_SJF extends JFrame {

    // ════════════════════════════════════════════════════════════════════
    //  LAYER 1 — MODEL
    // ════════════════════════════════════════════════════════════════════

    /**
     * Immutable-after-construction data carrier for a single process.
     * Mutable fields (remaining, completion, etc.) are set only by schedulers.
     */
    static class Process {
        // --- Input fields (set at construction, never changed) ---
        final int id;
        final int arrival;
        final int burst;
        final int priority;

        // --- Scheduler-written fields ---
        int remaining;
        int completion;
        int turnaround;
        int waiting;
        int response;
        int firstStart = -1;   // renamed from "start" for clarity

        Process(int id, int arrival, int burst, int priority) {
            this.id       = id;
            this.arrival  = arrival;
            this.burst    = burst;
            this.priority = priority;
            this.remaining = burst;
        }

        /** Copy constructor — resets remaining to full burst for a fresh run. */
        Process(Process source) {
            this.id        = source.id;
            this.arrival   = source.arrival;
            this.burst     = source.burst;
            this.priority  = source.priority;
            this.remaining = source.burst;
        }
    }

    /**
     * One coloured block on the Gantt chart.
     * label = "P{id}" or "IDLE"; start/end are integer time units.
     */
    static class Segment {
        final String label;
        int start;
        int end;

        Segment(String label, int start, int end) {
            this.label = label;
            this.start = start;
            this.end   = end;
        }

        int duration() { return end - start; }
    }

    /** Complete result returned by every scheduler implementation. */
    static class SimulationResult {
        final List<Process> processes;
        final List<Segment> gantt;
        final AlgorithmType algorithm;

        SimulationResult(AlgorithmType algorithm,
                         List<Process> processes,
                         List<Segment> gantt) {
            this.algorithm = algorithm;
            this.processes = Collections.unmodifiableList(processes);
            this.gantt     = Collections.unmodifiableList(gantt);
        }

        double averageWaiting()     { return averageOf(0); }
        double averageTurnaround()  { return averageOf(1); }
        double averageResponse()    { return averageOf(2); }

        private double averageOf(int field) {
            if (processes.isEmpty()) return 0;
            double sum = 0;
            for (Process p : processes)
                sum += (field == 0) ? p.waiting
                     : (field == 1) ? p.turnaround
                     :                p.response;
            return sum / processes.size();
        }
    }

    /** Enum identifying each scheduling algorithm for clean dispatch. */
    enum AlgorithmType {
        SJF_PREEMPTIVE    ("Preemptive SJF (SRTF)"),
        SJF_NON_PREEMPTIVE("Non-Preemptive SJF"),
        PRIORITY_PREEMPTIVE("Priority Scheduling (Preemptive)");

        final String displayName;
        AlgorithmType(String name) { this.displayName = name; }
    }

    // ════════════════════════════════════════════════════════════════════
    //  LAYER 2 — SCHEDULER INTERFACE + IMPLEMENTATIONS
    //  All algorithm logic is isolated here.  The UI never calls these
    //  directly; it goes through SimulationController.
    // ════════════════════════════════════════════════════════════════════

    interface Scheduler {
        SimulationResult run(List<Process> processes);
    }

    // ── Shared helpers (package-private static utility) ──────────────────

    /** Deep-copy a list; each copy resets remaining = burst. */
    static List<Process> deepCopy(List<Process> original) {
        List<Process> copy = new ArrayList<>(original.size());
        for (Process p : original) copy.add(new Process(p));
        return copy;
    }

    /**
     * Append one time-unit slot to the Gantt list, merging consecutive
     * identical labels into one wider segment (reduces segment count).
     */
    static void appendGanttSlot(List<Segment> gantt, String label, int time) {
        if (!gantt.isEmpty()) {
            Segment last = gantt.get(gantt.size() - 1);
            if (last.label.equals(label)) {
                last.end = time + 1;
                return;
            }
        }
        gantt.add(new Segment(label, time, time + 1));
    }

    // ── Algorithm 1: Preemptive SJF (SRTF) ──────────────────────────────
    static class SJFPreemptiveScheduler implements Scheduler {
        @Override
        public SimulationResult run(List<Process> original) {
            List<Process> procs = deepCopy(original);
            List<Segment> gantt = new ArrayList<>();
            int time = 0, done = 0;

            while (done < procs.size()) {
                Process best = selectSRTF(procs, time);

                if (best == null) {
                    appendGanttSlot(gantt, "IDLE", time++);
                    continue;
                }
                if (best.firstStart == -1) {
                    best.firstStart = time;
                    best.response   = time - best.arrival;
                }
                appendGanttSlot(gantt, "P" + best.id, time);
                best.remaining--;
                time++;
                if (best.remaining == 0) {
                    best.completion = time;
                    best.turnaround = time - best.arrival;
                    best.waiting    = best.turnaround - best.burst;
                    done++;
                }
            }
            return new SimulationResult(AlgorithmType.SJF_PREEMPTIVE, procs, gantt);
        }

        /**
         * Select process with shortest REMAINING time.
         * Tie-break: earliest arrival → lowest PID.
         */
        private Process selectSRTF(List<Process> procs, int time) {
            Process best = null;
            for (Process x : procs) {
                if (x.arrival > time || x.remaining <= 0) continue;
                if (best == null
                        || x.remaining  < best.remaining
                        || (x.remaining == best.remaining && x.arrival < best.arrival)
                        || (x.remaining == best.remaining && x.arrival == best.arrival
                            && x.id < best.id))
                    best = x;
            }
            return best;
        }
    }

    // ── Algorithm 2: Non-Preemptive SJF ─────────────────────────────────
    static class SJFNonPreemptiveScheduler implements Scheduler {
        @Override
        public SimulationResult run(List<Process> original) {
            List<Process> procs = deepCopy(original);
            List<Segment> gantt = new ArrayList<>();
            int time = 0, done = 0;

            while (done < procs.size()) {
                Process best = selectShortestBurst(procs, time);

                if (best == null) {
                    appendGanttSlot(gantt, "IDLE", time++);
                    continue;
                }
                if (best.firstStart == -1) {
                    best.firstStart = time;
                    best.response   = time - best.arrival;
                }
                // Non-preemptive: run to completion without interruption
                while (best.remaining > 0) {
                    appendGanttSlot(gantt, "P" + best.id, time);
                    best.remaining--;
                    time++;
                }
                best.completion = time;
                best.turnaround = time - best.arrival;
                best.waiting    = best.turnaround - best.burst;
                done++;
            }
            return new SimulationResult(AlgorithmType.SJF_NON_PREEMPTIVE, procs, gantt);
        }

        /**
         * Select process with shortest BURST time (uses full burst for comparison).
         * Tie-break: earliest arrival → lowest PID.
         */
        private Process selectShortestBurst(List<Process> procs, int time) {
            Process best = null;
            for (Process x : procs) {
                if (x.arrival > time || x.remaining <= 0) continue;
                if (best == null
                        || x.burst  < best.burst
                        || (x.burst == best.burst && x.arrival < best.arrival)
                        || (x.burst == best.burst && x.arrival == best.arrival
                            && x.id < best.id))
                    best = x;
            }
            return best;
        }
    }

    // ── Algorithm 3: Preemptive Priority Scheduling ──────────────────────
    static class PriorityPreemptiveScheduler implements Scheduler {
        @Override
        public SimulationResult run(List<Process> original) {
            List<Process> procs = deepCopy(original);
            List<Segment> gantt = new ArrayList<>();
            int time = 0, done = 0;

            while (done < procs.size()) {
                Process best = selectHighestPriority(procs, time);

                if (best == null) {
                    appendGanttSlot(gantt, "IDLE", time++);
                    continue;
                }
                if (best.firstStart == -1) {
                    best.firstStart = time;
                    best.response   = time - best.arrival;
                }
                appendGanttSlot(gantt, "P" + best.id, time);
                best.remaining--;
                time++;
                if (best.remaining == 0) {
                    best.completion = time;
                    best.turnaround = time - best.arrival;
                    best.waiting    = best.turnaround - best.burst;
                    done++;
                }
            }
            return new SimulationResult(AlgorithmType.PRIORITY_PREEMPTIVE, procs, gantt);
        }

        /**
         * Select process with lowest priority number (= highest priority).
         * Tie-break: earliest arrival → lowest PID.
         */
        private Process selectHighestPriority(List<Process> procs, int time) {
            Process best = null;
            for (Process x : procs) {
                if (x.arrival > time || x.remaining <= 0) continue;
                if (best == null
                        || x.priority  < best.priority
                        || (x.priority == best.priority && x.arrival < best.arrival)
                        || (x.priority == best.priority && x.arrival == best.arrival
                            && x.id < best.id))
                    best = x;
            }
            return best;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  LAYER 3 — SERVICES
    // ════════════════════════════════════════════════════════════════════

    /** Centralised input validation. Returns null on success, error message on failure. */
    static class ValidationService {
        static final int MAX_PID      = 9999;
        static final int MAX_ARRIVAL  = 9999;
        static final int MAX_BURST    = 9999;
        static final int MAX_PRIORITY = 9999;
        static final int MAX_PROCESSES = 20;

        static String validateId(String raw) {
            String err = validatePositiveInt(raw, "Process ID");
            if (err != null) return err;
            int v = Integer.parseInt(raw);
            if (v < 1 || v > MAX_PID)
                return "Process ID must be 1 – " + MAX_PID + ".";
            return null;
        }

        static String validateArrival(String raw) {
            String err = validateNonNegativeInt(raw, "Arrival Time");
            if (err != null) return err;
            int v = Integer.parseInt(raw);
            if (v > MAX_ARRIVAL)
                return "Arrival Time must be 0 – " + MAX_ARRIVAL + ".";
            return null;
        }

        static String validateBurst(String raw) {
            String err = validatePositiveInt(raw, "Burst Time");
            if (err != null) return err;
            int v = Integer.parseInt(raw);
            if (v <= 0)  return "Burst Time cannot be 0 — minimum value is 1.";
            if (v > MAX_BURST) return "Burst Time must be 1 – " + MAX_BURST + ".";
            return null;
        }

        static String validatePriority(String raw) {
            String err = validatePositiveInt(raw, "Priority");
            if (err != null) return err;
            int v = Integer.parseInt(raw);
            if (v < 1 || v > MAX_PRIORITY)
                return "Priority must be 1 – " + MAX_PRIORITY + ".";
            return null;
        }

        private static String validatePositiveInt(String raw, String fieldName) {
            if (raw == null || raw.isEmpty())
                return fieldName + " is required.";
            if (!raw.matches("-?\\d+"))
                return fieldName + ": Only integer values are allowed.";
            try { Integer.parseInt(raw); }
            catch (NumberFormatException ex) { return fieldName + ": Value is out of range."; }
            int v = Integer.parseInt(raw);
            if (v < 0) return fieldName + ": Negative values are not allowed.";
            if (v == 0) return fieldName + " cannot be 0.";
            return null;
        }

        private static String validateNonNegativeInt(String raw, String fieldName) {
            if (raw == null || raw.isEmpty())
                return fieldName + " is required.";
            if (!raw.matches("-?\\d+"))
                return fieldName + ": Only integer values are allowed.";
            try { Integer.parseInt(raw); }
            catch (NumberFormatException ex) { return fieldName + ": Value is out of range."; }
            int v = Integer.parseInt(raw);
            if (v < 0) return fieldName + ": Negative values are not allowed.";
            return null;
        }
    }

    /** Builds the textual analysis report for the Compare tab. */
    static class AnalysisService {

        static String buildReport(SimulationResult pre,
                                  SimulationResult non,
                                  SimulationResult pri) {
            double w1 = pre.averageWaiting(),    t1 = pre.averageTurnaround(), r1 = pre.averageResponse();
            double w2 = non.averageWaiting(),    t2 = non.averageTurnaround(), r2 = non.averageResponse();
            double w3 = pri.averageWaiting(),    t3 = pri.averageTurnaround(), r3 = pri.averageResponse();

            String sep  = "═".repeat(66);
            String sep2 = "─".repeat(66);
            StringBuilder sb = new StringBuilder();

            // Per-process breakdown
            sb.append(sep).append("\n");
            sb.append("  PER-PROCESS BREAKDOWN\n");
            sb.append(sep2).append("\n");
            sb.append(String.format("  %-6s  %-10s  %-12s  %-10s  %-12s  %-8s%n",
                "PID", "SJFpre-WT", "SJFnon-WT", "PRI-WT", "SJFpre-TAT", "PRI-TAT"));
            sb.append(sep2).append("\n");

            Map<Integer, Process> nonMap = new HashMap<>();
            Map<Integer, Process> priMap = new HashMap<>();
            for (Process x : non.processes) nonMap.put(x.id, x);
            for (Process x : pri.processes) priMap.put(x.id, x);
            for (Process s : pre.processes) {
                Process n  = nonMap.get(s.id);
                Process p3 = priMap.get(s.id);
                sb.append(String.format("  P%-5d  %-10d  %-12d  %-10d  %-12d  %-8d%n",
                    s.id, s.waiting,
                    n  != null ? n.waiting     : 0,
                    p3 != null ? p3.waiting    : 0,
                    s.turnaround,
                    p3 != null ? p3.turnaround : 0));
            }
            sb.append(sep).append("\n\n");

            // Best waiting time
            sb.append("  BEST WAITING TIME\n").append(sep2).append("\n");
            sb.append(String.format("  ✔ %s  (Avg WT = %.2f)%n",
                bestName(w1, w2, w3), Math.min(w1, Math.min(w2, w3))));
            sb.append("\n");

            // Algorithm analysis
            sb.append("  ALGORITHM ANALYSIS\n").append(sep2).append("\n");
            sb.append("  Preemptive SJF (SRTF):\n");
            sb.append("    ✔ Minimises average waiting time — optimal for batch workloads.\n");
            sb.append("    ✔ Highest CPU throughput when burst lengths are predictable.\n");
            sb.append("    ✗ Ignores process urgency and importance entirely.\n");
            sb.append("    ✗ Starvation risk: long jobs starve if short jobs keep arriving.\n\n");

            sb.append("  Non-Preemptive SJF:\n");
            sb.append("    ✔ Good throughput without the overhead of context switches.\n");
            sb.append("    ✔ Fairer than SRTF — a running process is never interrupted.\n");
            sb.append("    ✗ Less optimal than SRTF if short jobs arrive mid-execution.\n");
            sb.append("    ✗ Starvation risk remains for long-burst processes.\n\n");

            sb.append("  Preemptive Priority Scheduling:\n");
            sb.append("    ✔ Critical/urgent tasks get the CPU immediately.\n");
            sb.append("    ✔ Essential for real-time and deadline-driven systems.\n");
            sb.append("    ✗ Indefinite starvation for low-priority processes possible.\n");
            sb.append("    ✗ Does not optimise burst efficiency or overall throughput.\n\n");

            // Fairness
            sb.append("  FAIRNESS & STARVATION RISK\n").append(sep2).append("\n");
            sb.append("  • SRTF       — Least fair. Long jobs may never run.\n");
            sb.append("  • SJF Non-P  — Fairer than SRTF; running job always finishes.\n");
            sb.append("  • Priority   — Unfair to low-priority jobs; starvation likely.\n");
            sb.append("  • Fix        — Apply AGING: gradually increase priority of waiting\n");
            sb.append("                 processes to prevent indefinite starvation.\n\n");

            // Trade-offs
            sb.append("  EFFICIENCY vs URGENCY TRADE-OFF\n").append(sep2).append("\n");
            sb.append("  • SJF algorithms optimise for throughput — best global efficiency.\n");
            sb.append("  • Priority serves the most important task first — meets urgency needs.\n");
            sb.append("  • Conflict: a SHORT but LOW-priority job runs next in SJF but may\n");
            sb.append("    be delayed indefinitely under Priority Scheduling.\n\n");

            // Conclusion
            sb.append("  CONCLUSION\n").append(sep2).append("\n");
            sb.append(String.format("  • Best Avg WT  : %s (%.2f)%n", bestName(w1, w2, w3), Math.min(w1, Math.min(w2, w3))));
            sb.append(String.format("  • Best Avg TAT : %s (%.2f)%n", bestName(t1, t2, t3), Math.min(t1, Math.min(t2, t3))));
            sb.append(String.format("  • Best Avg RT  : %s (%.2f)%n", bestName(r1, r2, r3), Math.min(r1, Math.min(r2, r3))));
            sb.append("\n");
            sb.append("  ➜ Use Preemptive SJF     → batch systems, maximise throughput.\n");
            sb.append("  ➜ Use Non-Preemptive SJF → balanced throughput with less overhead.\n");
            sb.append("  ➜ Use Priority Scheduling → real-time systems, task urgency matters.\n");
            sb.append(sep).append("\n");

            return sb.toString();
        }

        static String bestName(double v1, double v2, double v3) {
            double min = Math.min(v1, Math.min(v2, v3));
            if (v1 == min) return AlgorithmType.SJF_PREEMPTIVE.displayName;
            if (v2 == min) return AlgorithmType.SJF_NON_PREEMPTIVE.displayName;
            return AlgorithmType.PRIORITY_PREEMPTIVE.displayName;
        }

        /**
         * Format a one-line averages summary.
         * E.g. "  Avg WT: 3.00  |  Avg TAT: 7.00  |  Avg RT: 1.50"
         */
        static String averageSummaryLine(SimulationResult res) {
            return String.format(
                "  Avg Waiting Time: %.2f   |   Avg Turnaround: %.2f   |   Avg Response: %.2f",
                res.averageWaiting(), res.averageTurnaround(), res.averageResponse());
        }

        /** Build a CSV string for all results (pre, non, pri). */
        static String buildCsv(SimulationResult pre, SimulationResult non, SimulationResult pri) {
            StringBuilder sb = new StringBuilder();
            sb.append("Algorithm,PID,AT,BT,Priority,CT,WT,TAT,RT\n");
            for (SimulationResult r : new SimulationResult[]{pre, non, pri}) {
                for (Process p : r.processes) {
                    sb.append(String.format("%s,P%d,%d,%d,%d,%d,%d,%d,%d%n",
                        r.algorithm.displayName, p.id, p.arrival, p.burst,
                        p.priority, p.completion, p.waiting, p.turnaround, p.response));
                }
            }
            return sb.toString();
        }
    }

    /** Pre-built demo scenarios (data only — no UI dependency). */
    static class ScenarioService {
        enum Scenario {
            A("A — Normal",     "Normal mixed workload loaded."),
            B("B — Conflict",   "Conflict Case: SJF prefers shortest burst while Priority prefers highest priority process."),
            C("C — Starvation", "Scenario C: P1 risks starvation under Priority Scheduling."),
            D("D — Same AT",    "Scenario D: All arrive at time 0 — tests tie-breaking rules.");

            final String label;
            final String statusMessage;
            Scenario(String label, String msg) { this.label = label; this.statusMessage = msg; }
        }

        /** Returns {id, arrival, burst, priority} rows for a scenario. */
        static int[][] getRows(Scenario s) {
            switch (s) {
                case A: return new int[][]{{1,0,7,3},{2,2,4,1},{3,4,1,4},{4,5,4,2}};
                case B: return new int[][]{{1,0,10,3},{2,1,2,1},{3,2,1,5}};
                case C: return new int[][]{{1,0,20,5},{2,1,2,1},{3,2,2,1},{4,3,1,1}};
                case D: return new int[][]{{1,0,5,2},{2,0,3,1},{3,0,8,3}};
                default: return new int[0][];
            }
        }

        /** Generate n random processes with bounded values. */
        static int[][] generateRandom(int n) {
            Random rng = new Random();
            int[][] rows = new int[n][4];
            for (int i = 0; i < n; i++) {
                rows[i][0] = i + 1;
                rows[i][1] = rng.nextInt(10);         // arrival 0-9
                rows[i][2] = 1 + rng.nextInt(10);     // burst 1-10
                rows[i][3] = 1 + rng.nextInt(5);      // priority 1-5
            }
            return rows;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  LAYER 4 — CONTROLLER
    // ════════════════════════════════════════════════════════════════════

    /**
     * SimulationController decouples UI from scheduling logic.
     * All three schedulers are run here; results are dispatched to the UI
     * via a callback interface so the controller never touches Swing directly.
     */
    static class SimulationController {
        private final Scheduler sjfPre = new SJFPreemptiveScheduler();
        private final Scheduler sjfNon = new SJFNonPreemptiveScheduler();
        private final Scheduler priPre = new PriorityPreemptiveScheduler();

        interface SimulationListener {
            void onResultsReady(SimulationResult pre,
                                SimulationResult non,
                                SimulationResult pri);
            void onError(String message);
        }

        /**
         * Run all three algorithms off the EDT (SwingWorker), then publish
         * results back on the EDT via the listener.
         */
        void runAll(List<Process> inputProcesses, SimulationListener listener) {
            if (inputProcesses == null || inputProcesses.isEmpty()) {
                listener.onError("No processes added. Please add at least one process first.");
                return;
            }
            new SwingWorker<SimulationResult[], Void>() {
                @Override
                protected SimulationResult[] doInBackground() {
                    return new SimulationResult[]{
                        sjfPre.run(inputProcesses),
                        sjfNon.run(inputProcesses),
                        priPre.run(inputProcesses)
                    };
                }
                @Override
                protected void done() {
                    try {
                        SimulationResult[] r = get();
                        listener.onResultsReady(r[0], r[1], r[2]);
                    } catch (Exception ex) {
                        listener.onError("Simulation error: " + ex.getMessage());
                    }
                }
            }.execute();
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  LAYER 5 — UI / THEME
    // ════════════════════════════════════════════════════════════════════

    /** Centralised colour and font constants. */
    static final class Theme {
        // Background palette
        static final Color BG_DARK    = new Color(30,  42,  56);
        static final Color BG_CARD    = new Color(37,  53,  69);
        static final Color BG_INPUT   = new Color(44,  62,  80);

        // Accent colours
        static final Color ACCENT     = new Color(74,  144, 217);
        static final Color GREEN      = new Color(46,  204, 113);
        static final Color ORANGE     = new Color(230, 126,  34);
        static final Color RED        = new Color(231,  76,  60);
        static final Color PURPLE     = new Color(155,  89, 182);
        static final Color TEAL       = new Color(26,  188, 156);
        static final Color YELLOW     = new Color(241, 196,  15);

        // Text palette
        static final Color TEXT_MAIN  = new Color(232, 235, 240);
        static final Color TEXT_SUB   = new Color(127, 179, 224);
        static final Color TEXT_MUTED = new Color(127, 140, 141);
        static final Color IDLE_COLOR = new Color(93,  109, 126);

        // Gantt palette — stable: sorted by PID label so P1 always gets index 0
        static final Color[] GANTT_COLORS = {
            new Color(74,  144, 217), new Color(230, 126,  34), new Color(46,  204, 113),
            new Color(155,  89, 182), new Color(231,  76,  60), new Color(26,  188, 156),
            new Color(243, 156,  18), new Color(52,  152, 219), new Color(211,  84,   0),
            new Color(39,  174,  96)
        };

        // Fonts
        static final Font FONT_TITLE   = new Font("SansSerif", Font.BOLD,  20);
        static final Font FONT_HEADING = new Font("SansSerif", Font.BOLD,  16);
        static final Font FONT_BODY    = new Font("SansSerif", Font.PLAIN, 13);
        static final Font FONT_SMALL   = new Font("SansSerif", Font.PLAIN, 11);
        static final Font FONT_LABEL   = new Font("SansSerif", Font.BOLD,  12);
        static final Font FONT_MONO    = new Font("Monospaced", Font.PLAIN, 12);
        static final Font FONT_BTN     = new Font("SansSerif", Font.BOLD,  12);

        private Theme() {} // non-instantiable
    }

    /**
     * UIFactory — static factory methods to create consistently-styled Swing
     * components.  Centralising this eliminates duplicated styling code.
     */
    static final class UIFactory {

        private UIFactory() {}

        static <T extends JComponent> T dark(T c) {
            c.setBackground(Theme.BG_DARK);
            return c;
        }

        static JLabel label(String text, Font font, Color color) {
            JLabel l = new JLabel(text);
            l.setFont(font);
            l.setForeground(color);
            return l;
        }

        static JButton button(String text, Color bg) {
            JButton b = new JButton(text);
            b.setBackground(bg);
            b.setForeground(Color.WHITE);
            b.setFont(Theme.FONT_BTN);
            b.setFocusPainted(false);
            b.setBorderPainted(false);
            b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            b.setBorder(new EmptyBorder(8, 16, 8, 16));
            b.setOpaque(true);
            // Hover effect
            b.addMouseListener(new MouseAdapter() {
                final Color base = bg;
                @Override public void mouseEntered(MouseEvent e) {
                    b.setBackground(base.brighter());
                }
                @Override public void mouseExited(MouseEvent e) {
                    b.setBackground(base);
                }
            });
            return b;
        }

        static JTextField inputField() {
            JTextField tf = new JTextField(8);
            tf.setBackground(Theme.BG_INPUT);
            tf.setForeground(Theme.TEXT_MAIN);
            tf.setCaretColor(Theme.TEXT_MAIN);
            tf.setFont(Theme.FONT_BODY);
            normalBorder(tf);
            return tf;
        }

        static void normalBorder(JTextField tf) {
            tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.ACCENT, 1),
                new EmptyBorder(5, 8, 5, 8)));
        }

        static void errorBorder(JTextField tf) {
            tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.RED, 2),
                new EmptyBorder(5, 8, 5, 8)));
        }

        static void validBorder(JTextField tf) {
            tf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.GREEN, 1),
                new EmptyBorder(5, 8, 5, 8)));
        }

        static JPanel labeled(String labelText, JTextField tf) {
            JPanel p = dark(new JPanel(new BorderLayout(0, 3)));
            JLabel l = label(labelText, Theme.FONT_SMALL, Theme.TEXT_SUB);
            p.add(l,  BorderLayout.NORTH);
            p.add(tf, BorderLayout.CENTER);
            return p;
        }

        static JTable styledTable(TableModel model) {
            JTable t = new JTable(model);
            t.setBackground(Theme.BG_CARD);
            t.setForeground(Theme.TEXT_MAIN);
            t.setFont(Theme.FONT_BODY);
            t.setRowHeight(28);
            t.setGridColor(Theme.BG_INPUT);
            t.setSelectionBackground(Theme.ACCENT);
            t.setSelectionForeground(Color.WHITE);
            t.setShowGrid(true);
            t.setIntercellSpacing(new Dimension(1, 1));

            JTableHeader header = t.getTableHeader();
            header.setBackground(Theme.BG_DARK);
            header.setForeground(Theme.TEXT_SUB);
            header.setFont(Theme.FONT_LABEL);
            header.setReorderingAllowed(false);

            t.setDefaultRenderer(Object.class, new AlternatingRowRenderer());
            return t;
        }

        static JScrollPane styledScroll(Component c) {
            JScrollPane sp = new JScrollPane(c);
            sp.getViewport().setBackground(Theme.BG_CARD);
            sp.setBackground(Theme.BG_DARK);
            sp.setBorder(BorderFactory.createLineBorder(Theme.BG_INPUT));
            return sp;
        }

        static TitledBorder titledBorder(String text) {
            TitledBorder b = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(Theme.BG_INPUT, 1), text);
            b.setTitleColor(Theme.TEXT_SUB);
            b.setTitleFont(Theme.FONT_LABEL);
            return b;
        }

        static JSeparator separator() {
            JSeparator sep = new JSeparator();
            sep.setForeground(Theme.BG_INPUT);
            return sep;
        }

        static void styleTabPane(JTabbedPane tabs) {
            tabs.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                @Override protected void installDefaults() {
                    super.installDefaults();
                    highlight      = Theme.BG_DARK;
                    lightHighlight = Theme.BG_DARK;
                    shadow         = Theme.BG_DARK;
                    darkShadow     = Theme.BG_DARK;
                    focus          = Theme.BG_DARK;
                }
            });
        }
    }

    // ── Alternating-row table cell renderer ─────────────────────────────
    static class AlternatingRowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            setHorizontalAlignment(SwingConstants.CENTER);
            setBorder(new EmptyBorder(0, 8, 0, 8));
            if (isSelected) {
                setBackground(Theme.ACCENT);
                setForeground(Color.WHITE);
            } else {
                setBackground(row % 2 == 0 ? Theme.BG_CARD : Theme.BG_INPUT);
                setForeground(Theme.TEXT_MAIN);
            }
            return this;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  TABLE MODELS
    // ════════════════════════════════════════════════════════════════════

    /** Editable model for the process input table. */
    static class ProcessTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"PID", "Arrival", "Burst", "Priority"};
        private final List<int[]> rows = new ArrayList<>();

        @Override public int    getRowCount()              { return rows.size(); }
        @Override public int    getColumnCount()           { return COLUMNS.length; }
        @Override public String getColumnName(int c)       { return COLUMNS[c]; }
        @Override public Object getValueAt(int r, int c)   { return c == 0 ? "P" + rows.get(r)[0] : rows.get(r)[c]; }
        @Override public boolean isCellEditable(int r, int c) { return false; }

        void addRow(int id, int arrival, int burst, int priority) {
            rows.add(new int[]{id, arrival, burst, priority});
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
        }

        void removeRow(int rowIndex) {
            rows.remove(rowIndex);
            fireTableRowsDeleted(rowIndex, rowIndex);
        }

        void clear() {
            rows.clear();
            fireTableDataChanged();
        }

        boolean hasDuplicateId(int id) {
            for (int[] r : rows) if (r[0] == id) return true;
            return false;
        }

        List<Process> toProcessList() {
            List<Process> list = new ArrayList<>(rows.size());
            for (int[] r : rows) list.add(new Process(r[0], r[1], r[2], r[3]));
            return list;
        }
    }

    /** Read-only model for an algorithm result table. */
    static class ResultTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"PID", "AT", "BT", "PR", "CT", "WT", "TAT", "RT"};
        private final List<Object[]> rows = new ArrayList<>();

        @Override public int    getRowCount()              { return rows.size(); }
        @Override public int    getColumnCount()           { return COLUMNS.length; }
        @Override public String getColumnName(int c)       { return COLUMNS[c]; }
        @Override public Object getValueAt(int r, int c)   { return rows.get(r)[c]; }
        @Override public boolean isCellEditable(int r, int c) { return false; }

        void setProcesses(List<Process> ps) {
            rows.clear();
            for (Process p : ps)
                rows.add(new Object[]{
                    "P" + p.id, p.arrival, p.burst, p.priority,
                    p.completion, p.waiting, p.turnaround, p.response
                });
            fireTableDataChanged();
        }

        void clear() { rows.clear(); fireTableDataChanged(); }
    }

    /** Three-row summary model for the Compare tab. */
    static class CompareTableModel extends AbstractTableModel {
        private static final String[] COLUMNS = {"Algorithm", "Avg WT", "Avg TAT", "Avg RT"};
        private final Object[][] data = {
            {AlgorithmType.SJF_PREEMPTIVE.displayName,     "—", "—", "—"},
            {AlgorithmType.SJF_NON_PREEMPTIVE.displayName, "—", "—", "—"},
            {AlgorithmType.PRIORITY_PREEMPTIVE.displayName, "—", "—", "—"}
        };

        @Override public int    getRowCount()            { return 3; }
        @Override public int    getColumnCount()         { return COLUMNS.length; }
        @Override public String getColumnName(int c)     { return COLUMNS[c]; }
        @Override public Object getValueAt(int r, int c) { return data[r][c]; }
        @Override public boolean isCellEditable(int r, int c) { return false; }

        void update(SimulationResult pre, SimulationResult non, SimulationResult pri) {
            data[0][1] = fmt(pre.averageWaiting());
            data[0][2] = fmt(pre.averageTurnaround());
            data[0][3] = fmt(pre.averageResponse());
            data[1][1] = fmt(non.averageWaiting());
            data[1][2] = fmt(non.averageTurnaround());
            data[1][3] = fmt(non.averageResponse());
            data[2][1] = fmt(pri.averageWaiting());
            data[2][2] = fmt(pri.averageTurnaround());
            data[2][3] = fmt(pri.averageResponse());
            fireTableDataChanged();
        }

        private static String fmt(double v) { return String.format("%.2f", v); }
    }

    // ── CompareTableRenderer — highlights minimum value per column in green ─
    static class CompareTableRenderer extends DefaultTableCellRenderer {
        private final CompareTableModel model;

        CompareTableRenderer(CompareTableModel m) { this.model = m; }

        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected,
                boolean hasFocus, int row, int col) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
            setHorizontalAlignment(col == 0 ? SwingConstants.LEFT : SwingConstants.CENTER);
            setBorder(new EmptyBorder(0, 8, 0, 8));

            if (isSelected) { setBackground(Theme.ACCENT); setForeground(Color.WHITE); return this; }
            setBackground(row % 2 == 0 ? Theme.BG_CARD : Theme.BG_INPUT);
            setForeground(Theme.TEXT_MAIN);
            setFont(Theme.FONT_BODY);

            if (col >= 1 && col <= 3) {
                try {
                    double thisVal = Double.parseDouble(String.valueOf(model.getValueAt(row, col)));
                    double minVal  = Double.MAX_VALUE;
                    for (int r = 0; r < model.getRowCount(); r++) {
                        Object v = model.getValueAt(r, col);
                        if (!"—".equals(v))
                            minVal = Math.min(minVal, Double.parseDouble(String.valueOf(v)));
                    }
                    if (thisVal == minVal && minVal < Double.MAX_VALUE) {
                        setForeground(Theme.GREEN);
                        setFont(Theme.FONT_BODY.deriveFont(Font.BOLD));
                    }
                } catch (NumberFormatException ignored) {}
            }
            return this;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  GANTT CHART PANEL
    //  Improvements over original:
    //    • Cached colour map — computed once in setSegments(), not in paint
    //    • Tooltip showing PID / start / end / duration
    //    • Timeline grid lines
    //    • Adaptive cell width for large timelines
    //    • Clipped labels on narrow cells
    //    • Anti-aliased round-rect bars with drop shadow
    // ════════════════════════════════════════════════════════════════════
    static class GanttChartPanel extends JPanel implements MouseMotionListener {
        private static final int PANEL_HEIGHT = 110;
        private static final int BAR_Y        = 12;
        private static final int BAR_H        = 52;
        private static final int TICK_Y       = BAR_Y + BAR_H + 16;

        private List<Segment>       segments  = new ArrayList<>();
        private Map<String, Color>  colorMap  = new LinkedHashMap<>();
        private int                 cellWidth = 48;

        // Tooltip support
        private Segment hoveredSegment = null;

        GanttChartPanel() {
            setBackground(Theme.BG_CARD);
            setPreferredSize(new Dimension(800, PANEL_HEIGHT));
            setToolTipText(""); // enables tooltip system
            addMouseMotionListener(this);
        }

        void setSegments(List<Segment> segs) {
            this.segments = segs;
            rebuildColorMap();
            recalculateCellWidth();
            revalidate();
            repaint();
        }

        /** Rebuild colour map sorted by PID label for stable assignment. */
        private void rebuildColorMap() {
            colorMap.clear();
            List<String> labels = new ArrayList<>();
            for (Segment s : segments)
                if (!"IDLE".equals(s.label) && !labels.contains(s.label))
                    labels.add(s.label);
            Collections.sort(labels);
            for (int i = 0; i < labels.size(); i++)
                colorMap.put(labels.get(i), Theme.GANTT_COLORS[i % Theme.GANTT_COLORS.length]);
        }

        private void recalculateCellWidth() {
            int totalTime = segments.isEmpty() ? 1 : segments.get(segments.size() - 1).end;
            cellWidth = Math.max(28, Math.min(52, 1400 / Math.max(1, totalTime)));
            setPreferredSize(new Dimension(totalTime * cellWidth + 60, PANEL_HEIGHT));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (segments.isEmpty()) {
                paintEmptyState(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            paintGridLines(g2);
            paintBars(g2);
            paintTimeTicks(g2);
        }

        private void paintEmptyState(Graphics g) {
            Graphics2D g2 = (Graphics2D) g;
            g2.setColor(Theme.TEXT_MUTED);
            g2.setFont(Theme.FONT_SMALL);
            g2.drawString("No data — run simulation first.", 12, BAR_Y + BAR_H / 2 + 4);
        }

        private void paintGridLines(Graphics2D g2) {
            g2.setColor(new Color(Theme.BG_INPUT.getRed(),
                                  Theme.BG_INPUT.getGreen(),
                                  Theme.BG_INPUT.getBlue(), 80));
            g2.setStroke(new BasicStroke(0.5f));
            int totalTime = segments.get(segments.size() - 1).end;
            for (int t = 0; t <= totalTime; t++) {
                int x = t * cellWidth + 10;
                g2.drawLine(x, BAR_Y, x, BAR_Y + BAR_H);
            }
        }

        private void paintBars(Graphics2D g2) {
            for (Segment s : segments) {
                int x = s.start * cellWidth + 10;
                int w = s.duration() * cellWidth;
                if (w <= 0) continue;

                Color c = "IDLE".equals(s.label)
                        ? Theme.IDLE_COLOR
                        : colorMap.getOrDefault(s.label, Theme.ACCENT);

                // Drop shadow
                g2.setColor(new Color(0, 0, 0, 50));
                g2.fillRoundRect(x + 2, BAR_Y + 2, w - 2, BAR_H, 10, 10);

                // Bar fill — highlighted if hovered
                boolean hovered = (s == hoveredSegment);
                g2.setColor(hovered ? c.brighter() : c);
                g2.fillRoundRect(x, BAR_Y, w - 2, BAR_H, 10, 10);

                // Border
                g2.setColor(c.darker());
                g2.setStroke(new BasicStroke(hovered ? 2f : 1.5f));
                g2.drawRoundRect(x, BAR_Y, w - 2, BAR_H, 10, 10);

                // Label (skip if too narrow)
                int fontSize = Math.max(8, Math.min(14, w / 3));
                g2.setFont(new Font("SansSerif", Font.BOLD, fontSize));
                FontMetrics fm = g2.getFontMetrics();
                if (fm.stringWidth(s.label) < w - 4) {
                    g2.setColor(Color.WHITE);
                    int lx = x + (w - fm.stringWidth(s.label)) / 2;
                    int ly = BAR_Y + BAR_H / 2 + fm.getAscent() / 2 - 2;
                    g2.drawString(s.label, lx, ly);
                }
            }
        }

        private void paintTimeTicks(Graphics2D g2) {
            g2.setColor(Theme.TEXT_MUTED);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 10));
            for (Segment s : segments)
                g2.drawString(String.valueOf(s.start), s.start * cellWidth + 10, TICK_Y);
            if (!segments.isEmpty()) {
                Segment last = segments.get(segments.size() - 1);
                g2.drawString(String.valueOf(last.end), last.end * cellWidth + 10, TICK_Y);
            }
        }

        // ── Tooltip (MouseMotionListener) ────────────────────────────────
        @Override
        public void mouseMoved(MouseEvent e) {
            Segment found = null;
            for (Segment s : segments) {
                int x = s.start * cellWidth + 10;
                int w = s.duration() * cellWidth;
                if (e.getX() >= x && e.getX() <= x + w
                        && e.getY() >= BAR_Y && e.getY() <= BAR_Y + BAR_H) {
                    found = s;
                    break;
                }
            }
            if (found != hoveredSegment) {
                hoveredSegment = found;
                repaint();
            }
        }

        @Override
        public void mouseDragged(MouseEvent e) { mouseMoved(e); }

        @Override
        public String getToolTipText(MouseEvent e) {
            for (Segment s : segments) {
                int x = s.start * cellWidth + 10;
                int w = s.duration() * cellWidth;
                if (e.getX() >= x && e.getX() <= x + w
                        && e.getY() >= BAR_Y && e.getY() <= BAR_Y + BAR_H) {
                    return String.format(
                        "<html><b>%s</b><br>Start: %d &nbsp; End: %d &nbsp; Duration: %d</html>",
                        s.label, s.start, s.end, s.duration());
                }
            }
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  REAL-TIME FIELD VALIDATOR
    // ════════════════════════════════════════════════════════════════════

    /**
     * Validates a text field as the user types.
     * Sets border color and calls statusCallback with the error (or "" for ok).
     */
    class FieldValidator implements DocumentListener {
        private final JTextField          field;
        private final String              fieldName;
        private final boolean             allowZero;
        private final java.util.function.Consumer<String> statusCallback;

        FieldValidator(JTextField field, String fieldName,
                       boolean allowZero,
                       java.util.function.Consumer<String> statusCallback) {
            this.field          = field;
            this.fieldName      = fieldName;
            this.allowZero      = allowZero;
            this.statusCallback = statusCallback;
        }

        @Override public void insertUpdate(DocumentEvent e)  { validate(); }
        @Override public void removeUpdate(DocumentEvent e)  { validate(); }
        @Override public void changedUpdate(DocumentEvent e) { validate(); }

        private void validate() {
            String raw = field.getText().trim();
            if (raw.isEmpty()) {
                UIFactory.normalBorder(field);
                return;
            }
            if (!raw.matches("-?\\d+")) {
                UIFactory.errorBorder(field);
                statusCallback.accept(fieldName + ": Only integer values are allowed.");
                return;
            }
            int val;
            try { val = Integer.parseInt(raw); }
            catch (NumberFormatException ex) {
                UIFactory.errorBorder(field);
                statusCallback.accept(fieldName + ": Value out of range.");
                return;
            }
            if (val < 0) {
                UIFactory.errorBorder(field);
                statusCallback.accept(fieldName + ": Negative values are not allowed.");
                return;
            }
            if (!allowZero && val == 0) {
                UIFactory.errorBorder(field);
                statusCallback.accept(fieldName + " cannot be 0.");
                return;
            }
            UIFactory.validBorder(field);
            statusCallback.accept("");
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  UI PANELS
    // ════════════════════════════════════════════════════════════════════

    // ── Status Bar ───────────────────────────────────────────────────────
    static class StatusBar extends JLabel {
        StatusBar() {
            setFont(Theme.FONT_SMALL);
            setForeground(Theme.TEXT_SUB);
            setBorder(new EmptyBorder(4, 0, 0, 0));
            setText("Add processes, then click Run Simulation.");
        }

        void show(String msg, Color color) {
            setText(msg);
            setForeground(color);
        }
    }

    // ── InputPanel ───────────────────────────────────────────────────────
    /**
     * The "Input" tab containing the process-entry form, process queue
     * table, scenario shortcuts, and the Run / Export buttons.
     */
    class InputPanel extends JPanel {
        private final ProcessTableModel tableModel;
        private final StatusBar         statusBar;
        private       JTextField        tfId, tfAt, tfBt, tfPr;
        private       JButton           btnRun;

        InputPanel(ProcessTableModel tableModel, StatusBar statusBar) {
            super(new BorderLayout(0, 10));
            this.tableModel = tableModel;
            this.statusBar  = statusBar;
            setBackground(Theme.BG_DARK);
            setBorder(new EmptyBorder(18, 18, 18, 18));
            buildLayout();
        }

        private void buildLayout() {
            // Header
            JPanel header = UIFactory.dark(new JPanel(new BorderLayout(0, 4)));
            header.add(UIFactory.label("Process Input & Configuration", Theme.FONT_TITLE,    Theme.TEXT_MAIN), BorderLayout.NORTH);
            header.add(UIFactory.label("⚑  Priority Rule: Smaller number = Higher priority   |   All three algorithms run on simulation",
                                       Theme.FONT_SMALL, Theme.ORANGE), BorderLayout.SOUTH);
            add(header, BorderLayout.NORTH);

            // Centre: form + table + scenarios
            JPanel center = UIFactory.dark(new JPanel(new BorderLayout(0, 10)));
            center.add(buildFormPanel(),     BorderLayout.NORTH);
            center.add(buildTablePanel(),    BorderLayout.CENTER);
            center.add(buildScenarioPanel(), BorderLayout.SOUTH);
            add(center, BorderLayout.CENTER);

            // Bottom: run bar + status
            add(buildRunBar(), BorderLayout.SOUTH);
        }

        private JPanel buildFormPanel() {
            JPanel form = UIFactory.dark(new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 6)));
            form.setBorder(UIFactory.titledBorder("Add Process  —  All fields are required"));

            tfId = UIFactory.inputField();
            tfAt = UIFactory.inputField();
            tfBt = UIFactory.inputField();
            tfPr = UIFactory.inputField();

            // Real-time validators
            tfId.getDocument().addDocumentListener(new FieldValidator(tfId, "Process ID",   false, this::onValidationMessage));
            tfAt.getDocument().addDocumentListener(new FieldValidator(tfAt, "Arrival Time",  true, this::onValidationMessage));
            tfBt.getDocument().addDocumentListener(new FieldValidator(tfBt, "Burst Time",   false, this::onValidationMessage));
            tfPr.getDocument().addDocumentListener(new FieldValidator(tfPr, "Priority",     false, this::onValidationMessage));

            form.add(UIFactory.labeled("Process ID",   tfId));
            form.add(UIFactory.labeled("Arrival Time", tfAt));
            form.add(UIFactory.labeled("Burst Time",   tfBt));
            form.add(UIFactory.labeled("Priority",     tfPr));

            JButton btnAdd = UIFactory.button("⊕  Add Process", Theme.GREEN);
            btnAdd.addActionListener(e -> addProcess());
            tfPr.addActionListener(e -> addProcess());

            form.add(btnAdd);
            return form;
        }

        private JPanel buildTablePanel() {
            JTable tbl = UIFactory.styledTable(tableModel);
            tbl.getColumnModel().getColumn(0).setPreferredWidth(60);

            // Context menu on rows
            JPopupMenu popup = new JPopupMenu();
            popup.setBackground(Theme.BG_CARD);
            JMenuItem miRemove = new JMenuItem("Remove Selected Row");
            miRemove.setBackground(Theme.BG_CARD);
            miRemove.setForeground(Theme.TEXT_MAIN);
            miRemove.addActionListener(e -> removeSelectedRow(tbl));
            popup.add(miRemove);
            tbl.setComponentPopupMenu(popup);

            JButton btnRemove = UIFactory.button("⊖  Remove Selected", Theme.RED);
            btnRemove.addActionListener(e -> removeSelectedRow(tbl));

            JScrollPane scroll = UIFactory.styledScroll(tbl);
            scroll.setPreferredSize(new Dimension(0, 175));
            scroll.setBorder(UIFactory.titledBorder("Process Queue  (right-click for options)"));

            JPanel p = UIFactory.dark(new JPanel(new BorderLayout(6, 0)));
            p.add(scroll,    BorderLayout.CENTER);
            p.add(btnRemove, BorderLayout.EAST);
            return p;
        }

        private JPanel buildScenarioPanel() {
            JPanel p = UIFactory.dark(new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4)));
            p.setBorder(UIFactory.titledBorder("Quick Scenarios"));
            p.add(UIFactory.label("Load: ", Theme.FONT_LABEL, Theme.TEXT_SUB));

            for (ScenarioService.Scenario s : ScenarioService.Scenario.values()) {
                JButton btn = UIFactory.button(s.label, colorForScenario(s));
                btn.addActionListener(e -> loadScenario(s));
                p.add(btn);
            }

            JButton btnRandom = UIFactory.button("⚄  Random (5)", Theme.YELLOW);
            btnRandom.addActionListener(e -> loadRandom(5));
            p.add(btnRandom);
            return p;
        }

        private Color colorForScenario(ScenarioService.Scenario s) {
            switch (s) {
                case A: return Theme.ACCENT;
                case B: return Theme.ORANGE;
                case C: return Theme.PURPLE;
                case D: return Theme.TEAL;
                default: return Theme.ACCENT;
            }
        }

        private JPanel buildRunBar() {
            btnRun = UIFactory.button("  ▶  Run All Algorithms  ", Theme.ACCENT);
            btnRun.setFont(new Font("SansSerif", Font.BOLD, 15));
            btnRun.addActionListener(e -> onRunClicked());

            JButton btnClear  = UIFactory.button("Clear All",   Theme.RED);
            JButton btnExport = UIFactory.button("⬇ Export CSV", Theme.TEAL);
            btnClear.addActionListener(e -> clearAll());
            btnExport.addActionListener(e -> exportCsv());

            JPanel actions = UIFactory.dark(new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0)));
            actions.add(btnRun);
            actions.add(btnClear);
            actions.add(btnExport);

            JPanel bar = UIFactory.dark(new JPanel(new BorderLayout(0, 4)));
            bar.add(actions,   BorderLayout.NORTH);
            bar.add(statusBar, BorderLayout.SOUTH);
            return bar;
        }

        // ── actions ──────────────────────────────────────────────────────

        private void addProcess() {
            String rawId = tfId.getText().trim();
            String rawAt = tfAt.getText().trim();
            String rawBt = tfBt.getText().trim();
            String rawPr = tfPr.getText().trim();

            if (rawId.isEmpty() || rawAt.isEmpty() || rawBt.isEmpty() || rawPr.isEmpty()) {
                statusBar.show("All fields are required — no field may be empty.", Theme.RED);
                return;
            }
            String errId = ValidationService.validateId(rawId);       if (errId != null) { statusBar.show(errId, Theme.RED); return; }
            String errAt = ValidationService.validateArrival(rawAt);  if (errAt != null) { statusBar.show(errAt, Theme.RED); return; }
            String errBt = ValidationService.validateBurst(rawBt);    if (errBt != null) { statusBar.show(errBt, Theme.RED); return; }
            String errPr = ValidationService.validatePriority(rawPr); if (errPr != null) { statusBar.show(errPr, Theme.RED); return; }

            int id = Integer.parseInt(rawId);
            if (tableModel.hasDuplicateId(id)) {
                statusBar.show("Process ID already exists — P" + id + " is already in the queue.", Theme.RED);
                return;
            }
            if (tableModel.getRowCount() >= ValidationService.MAX_PROCESSES) {
                statusBar.show("Maximum of " + ValidationService.MAX_PROCESSES + " processes allowed.", Theme.ORANGE);
                return;
            }

            tableModel.addRow(id, Integer.parseInt(rawAt),
                                  Integer.parseInt(rawBt),
                                  Integer.parseInt(rawPr));
            resetFields();
            tfId.requestFocus();
            statusBar.show("Process P" + id + " added.  Queue size: " + tableModel.getRowCount(), Theme.GREEN);
        }

        private void removeSelectedRow(JTable tbl) {
            int row = tbl.getSelectedRow();
            if (row >= 0) { tableModel.removeRow(row); statusBar.show("Process removed.", Theme.ORANGE); }
            else          { statusBar.show("Select a row to remove.", Theme.TEXT_MUTED); }
        }

        private void resetFields() {
            for (JTextField tf : new JTextField[]{tfId, tfAt, tfBt, tfPr}) {
                tf.setText("");
                UIFactory.normalBorder(tf);
            }
        }

        void loadScenario(ScenarioService.Scenario s) {
            tableModel.clear();
            for (int[] r : ScenarioService.getRows(s))
                tableModel.addRow(r[0], r[1], r[2], r[3]);
            statusBar.show(s.statusMessage, colorForScenario(s));
        }

        private void loadRandom(int count) {
            tableModel.clear();
            for (int[] r : ScenarioService.generateRandom(count))
                tableModel.addRow(r[0], r[1], r[2], r[3]);
            statusBar.show(count + " random processes generated.", Theme.YELLOW);
        }

        private void clearAll() {
            tableModel.clear();
            statusBar.show("Process list cleared.", Theme.RED);
        }

        private void onRunClicked() {
            onRunSimulation();   // delegates to outer class
        }

        private void onValidationMessage(String msg) {
            if (!msg.isEmpty()) statusBar.show(msg, Theme.RED);
            else                statusBar.show("", Theme.TEXT_MUTED);
        }

        // Wired by outer class after last result is available
        private SimulationResult lastPre, lastNon, lastPri;

        void cacheResults(SimulationResult pre, SimulationResult non, SimulationResult pri) {
            lastPre = pre; lastNon = non; lastPri = pri;
        }

        private void exportCsv() {
            if (lastPre == null) {
                statusBar.show("Run simulation first before exporting.", Theme.ORANGE);
                return;
            }
            JFileChooser fc = new JFileChooser();
            fc.setSelectedFile(new File("scheduling_results.csv"));
            if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                try (PrintWriter pw = new PrintWriter(fc.getSelectedFile())) {
                    pw.print(AnalysisService.buildCsv(lastPre, lastNon, lastPri));
                    statusBar.show("Exported to " + fc.getSelectedFile().getName(), Theme.GREEN);
                } catch (IOException ex) {
                    statusBar.show("Export failed: " + ex.getMessage(), Theme.RED);
                }
            }
        }
    }

    // ── ResultPanel ──────────────────────────────────────────────────────
    /** Reusable result tab for a single algorithm. */
    static class ResultPanel extends JPanel {
        private final GanttChartPanel gantt      = new GanttChartPanel();
        private final ResultTableModel tableModel = new ResultTableModel();
        private final JLabel          lblAvg     = UIFactory.label(
            "Run simulation to see averages.", Theme.FONT_LABEL, Theme.TEXT_MUTED);

        ResultPanel(String title, String ruleText) {
            super(new BorderLayout(0, 10));
            setBackground(Theme.BG_DARK);
            setBorder(new EmptyBorder(18, 18, 18, 18));
            buildLayout(title, ruleText);
        }

        private void buildLayout(String title, String ruleText) {
            // Header
            JPanel header = UIFactory.dark(new JPanel(new BorderLayout(0, 4)));
            header.add(UIFactory.label(title,    Theme.FONT_HEADING, Theme.TEXT_MAIN), BorderLayout.NORTH);
            header.add(UIFactory.label(ruleText, Theme.FONT_SMALL,   Theme.ORANGE),   BorderLayout.SOUTH);
            add(header, BorderLayout.NORTH);

            // Gantt chart
            JScrollPane ganttScroll = new JScrollPane(gantt,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            ganttScroll.setPreferredSize(new Dimension(0, 115));
            ganttScroll.setBorder(UIFactory.titledBorder("Gantt Chart  (hover for details)"));
            ganttScroll.getViewport().setBackground(Theme.BG_CARD);

            // Result table
            JTable table = UIFactory.styledTable(tableModel);
            JScrollPane tableScroll = UIFactory.styledScroll(table);
            tableScroll.setPreferredSize(new Dimension(0, 240));
            tableScroll.setBorder(UIFactory.titledBorder(
                "Results  —  AT=Arrival  BT=Burst  PR=Priority  CT=Completion  WT=Waiting  TAT=Turnaround  RT=Response"));

            lblAvg.setFont(new Font("SansSerif", Font.BOLD, 12));
            lblAvg.setForeground(Theme.GREEN);

            JPanel center = UIFactory.dark(new JPanel(new BorderLayout(0, 8)));
            center.add(ganttScroll,  BorderLayout.NORTH);
            center.add(lblAvg,       BorderLayout.CENTER);
            center.add(tableScroll,  BorderLayout.SOUTH);
            add(center, BorderLayout.CENTER);
        }

        void applyResult(SimulationResult result) {
            gantt.setSegments(result.gantt);
            tableModel.setProcesses(result.processes);
            lblAvg.setText(AnalysisService.averageSummaryLine(result));
            lblAvg.setForeground(Theme.GREEN);
        }

        GanttChartPanel getGanttPanel() { return gantt; }
    }

    // ── ComparePanel ─────────────────────────────────────────────────────
    /**
     * The "Compare All" tab.
     * Layout: summary table → best-metric row → three Gantt charts (stacked)
     *         → analysis text area.
     * All three Gantt panels are separate instances so they update independently.
     */
    static class ComparePanel extends JPanel {
        private final CompareTableModel cmpModel       = new CompareTableModel();
        private final JTextArea         taAnalysis     = new JTextArea();
        private final JLabel            lblBestWT      = UIFactory.label("", Theme.FONT_LABEL, Theme.GREEN);
        private final JLabel            lblBestTAT     = UIFactory.label("", Theme.FONT_LABEL, Theme.GREEN);
        private final JLabel            lblBestRT      = UIFactory.label("", Theme.FONT_LABEL, Theme.GREEN);
        private final GanttChartPanel   ganttPre       = new GanttChartPanel();
        private final GanttChartPanel   ganttNon       = new GanttChartPanel();
        private final GanttChartPanel   ganttPri       = new GanttChartPanel();

        private Runnable onCompareClicked;

        ComparePanel() {
            super(new BorderLayout(0, 10));
            setBackground(Theme.BG_DARK);
            setBorder(new EmptyBorder(18, 18, 18, 18));
            buildLayout();
        }

        void setOnCompareClicked(Runnable r) { this.onCompareClicked = r; }

        private void buildLayout() {
            // Title row
            JPanel topRow = UIFactory.dark(new JPanel(new BorderLayout(10, 0)));
            JButton btnCompare = UIFactory.button("  ⚖  Compare All Algorithms  ", Theme.ACCENT);
            btnCompare.setFont(new Font("SansSerif", Font.BOLD, 14));
            btnCompare.addActionListener(e -> { if (onCompareClicked != null) onCompareClicked.run(); });
            topRow.add(UIFactory.label("Algorithm Comparison & Analysis", Theme.FONT_TITLE, Theme.TEXT_MAIN), BorderLayout.WEST);
            topRow.add(btnCompare, BorderLayout.EAST);
            add(topRow, BorderLayout.NORTH);

            // Scrollable body
            JPanel body = UIFactory.dark(new JPanel());
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(new EmptyBorder(8, 0, 8, 0));

            body.add(buildSummarySection());
            body.add(Box.createVerticalStrut(10));
            body.add(buildGanttSection());
            body.add(Box.createVerticalStrut(10));
            body.add(buildAnalysisSection());

            JScrollPane outer = new JScrollPane(body,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
            outer.setBorder(BorderFactory.createEmptyBorder());
            outer.getViewport().setBackground(Theme.BG_DARK);
            add(outer, BorderLayout.CENTER);
        }

        private JPanel buildSummarySection() {
            JPanel p = UIFactory.dark(new JPanel(new BorderLayout(0, 6)));
            p.setBorder(UIFactory.titledBorder("Summary Table"));

            JTable table = UIFactory.styledTable(cmpModel);
            table.setDefaultRenderer(Object.class, new CompareTableRenderer(cmpModel));
            table.getColumnModel().getColumn(0).setPreferredWidth(240);
            JScrollPane scroll = UIFactory.styledScroll(table);
            scroll.setPreferredSize(new Dimension(0, 100));

            // Best-per-metric row
            JPanel bestRow = UIFactory.dark(new JPanel(new FlowLayout(FlowLayout.LEFT, 16, 4)));
            bestRow.add(UIFactory.label("Best:", Theme.FONT_LABEL, Theme.TEXT_SUB));
            bestRow.add(UIFactory.label("WT →",   Theme.FONT_SMALL, Theme.TEXT_MUTED));
            bestRow.add(lblBestWT);
            bestRow.add(UIFactory.label("  |  TAT →", Theme.FONT_SMALL, Theme.TEXT_MUTED));
            bestRow.add(lblBestTAT);
            bestRow.add(UIFactory.label("  |  RT →",  Theme.FONT_SMALL, Theme.TEXT_MUTED));
            bestRow.add(lblBestRT);

            p.add(scroll,  BorderLayout.CENTER);
            p.add(bestRow, BorderLayout.SOUTH);
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 165));
            return p;
        }

        private JPanel buildGanttSection() {
            JPanel p = UIFactory.dark(new JPanel());
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setBorder(UIFactory.titledBorder("Gantt Charts  (hover for details)"));

            p.add(ganttRow("Preemptive SJF (SRTF)",   ganttPre));
            p.add(Box.createVerticalStrut(4));
            p.add(ganttRow("Non-Preemptive SJF",       ganttNon));
            p.add(Box.createVerticalStrut(4));
            p.add(ganttRow("Priority Scheduling",      ganttPri));
            p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 360));
            return p;
        }

        private JPanel ganttRow(String label, GanttChartPanel gantt) {
            JPanel row = UIFactory.dark(new JPanel(new BorderLayout(8, 0)));
            JLabel lbl = UIFactory.label(label, Theme.FONT_SMALL, Theme.TEXT_SUB);
            lbl.setPreferredSize(new Dimension(180, 20));
            JScrollPane sc = new JScrollPane(gantt,
                JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
            sc.setPreferredSize(new Dimension(0, 100));
            sc.getViewport().setBackground(Theme.BG_CARD);
            sc.setBorder(BorderFactory.createLineBorder(Theme.BG_INPUT, 1));
            row.add(lbl, BorderLayout.WEST);
            row.add(sc,  BorderLayout.CENTER);
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 108));
            return row;
        }

        private JPanel buildAnalysisSection() {
            taAnalysis.setEditable(false);
            taAnalysis.setFont(Theme.FONT_MONO);
            taAnalysis.setBackground(Theme.BG_CARD);
            taAnalysis.setForeground(Theme.TEXT_MAIN);
            taAnalysis.setCaretColor(Theme.TEXT_MAIN);
            taAnalysis.setBorder(new EmptyBorder(10, 12, 10, 12));
            taAnalysis.setText("Run the simulation or click \"Compare All Algorithms\" to see the analysis.");

            JScrollPane scroll = UIFactory.styledScroll(taAnalysis);
            scroll.setPreferredSize(new Dimension(0, 270));
            scroll.setBorder(UIFactory.titledBorder("Analysis & Conclusion"));
            scroll.setMaximumSize(new Dimension(Integer.MAX_VALUE, 290));

            JPanel p = UIFactory.dark(new JPanel(new BorderLayout()));
            p.add(scroll, BorderLayout.CENTER);
            return p;
        }

        void applyResults(SimulationResult pre, SimulationResult non, SimulationResult pri) {
            cmpModel.update(pre, non, pri);

            lblBestWT .setText("✔ " + AnalysisService.bestName(pre.averageWaiting(),    non.averageWaiting(),    pri.averageWaiting()));
            lblBestTAT.setText("✔ " + AnalysisService.bestName(pre.averageTurnaround(), non.averageTurnaround(), pri.averageTurnaround()));
            lblBestRT .setText("✔ " + AnalysisService.bestName(pre.averageResponse(),   non.averageResponse(),   pri.averageResponse()));

            ganttPre.setSegments(pre.gantt);
            ganttNon.setSegments(non.gantt);
            ganttPri.setSegments(pri.gantt);

            taAnalysis.setText(AnalysisService.buildReport(pre, non, pri));
            taAnalysis.setCaretPosition(0);
        }
    }

    // ════════════════════════════════════════════════════════════════════
    //  MAIN FRAME  (wires everything together)
    // ════════════════════════════════════════════════════════════════════

    // Tab indices
    private static final int TAB_INPUT    = 0;
    private static final int TAB_SJF_PRE  = 1;
    private static final int TAB_SJF_NON  = 2;
    private static final int TAB_PRIORITY = 3;
    private static final int TAB_COMPARE  = 4;

    private final ProcessTableModel     inputModel    = new ProcessTableModel();
    private final StatusBar             statusBar     = new StatusBar();
    private final SimulationController  controller    = new SimulationController();

    private final InputPanel  inputPanel;
    private final ResultPanel sjfPrePanel;
    private final ResultPanel sjfNonPanel;
    private final ResultPanel priPanel;
    private final ComparePanel comparePanel;
    private       JTabbedPane  tabs;

    Main_SJF() {
        super("CPU Scheduling Simulator  —  SJF & Priority Scheduling");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1200, 800);
        setMinimumSize(new Dimension(960, 640));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG_DARK);

        // Construct all panels (constructor injection — no hidden dependencies)
        inputPanel   = new InputPanel(inputModel, statusBar);
        sjfPrePanel  = new ResultPanel(
            "SJF — Preemptive  (Shortest Remaining Time First / SRTF)",
            "Selection: shortest remaining burst time.  Tie-break: Arrival Time → PID");
        sjfNonPanel  = new ResultPanel(
            "SJF — Non-Preemptive  (Shortest Job First)",
            "Selection: shortest burst time at arrival.  Once started, runs to completion.  Tie-break: Arrival Time → PID");
        priPanel     = new ResultPanel(
            "Priority Scheduling  (Preemptive)",
            "Selection: lowest priority number = highest priority.  Tie-break: Arrival Time → PID");
        comparePanel = new ComparePanel();

        comparePanel.setOnCompareClicked(this::onRunSimulation);

        buildTabbedPane();
        add(tabs);

        // Keyboard shortcut: F5 = Run
        KeyStroke f5 = KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0);
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(f5, "runSim");
        getRootPane().getActionMap().put("runSim", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { onRunSimulation(); }
        });

        setVisible(true);
        inputPanel.loadScenario(ScenarioService.Scenario.B);
    }

    private void buildTabbedPane() {
        tabs = new JTabbedPane();
        tabs.setBackground(Theme.BG_DARK);
        tabs.setForeground(Theme.TEXT_MAIN);
        tabs.setFont(new Font("SansSerif", Font.BOLD, 13));
        UIFactory.styleTabPane(tabs);

        tabs.addTab("  ⌨ Input  ",                inputPanel);
        tabs.addTab("  ⚡ SJF Preemptive  ",      sjfPrePanel);
        tabs.addTab("  📋 SJF Non-Preemptive  ",  sjfNonPanel);
        tabs.addTab("  ★ Priority  ",             priPanel);
        tabs.addTab("  ⚖ Compare All  ",          comparePanel);
    }

    /** Central simulation entry point — called by InputPanel, ComparePanel, and F5 shortcut. */
    void onRunSimulation() {
        if (inputModel.getRowCount() == 0) {
            statusBar.show("No processes added. Please add at least one process first.", Theme.RED);
            return;
        }
        statusBar.show("Running simulations…", Theme.TEXT_SUB);
        List<Process> input = inputModel.toProcessList();

        controller.runAll(input, new SimulationController.SimulationListener() {
            @Override
            public void onResultsReady(SimulationResult pre, SimulationResult non, SimulationResult pri) {
                // All callbacks arrive on the EDT (SwingWorker.done())
                sjfPrePanel.applyResult(pre);
                sjfNonPanel.applyResult(non);
                priPanel   .applyResult(pri);
                comparePanel.applyResults(pre, non, pri);
                inputPanel.cacheResults(pre, non, pri);

                statusBar.show("All 3 algorithms complete.  Press F5 to re-run.  Check individual tabs or Compare All.", Theme.GREEN);
                tabs.setSelectedIndex(TAB_COMPARE);
            }

            @Override
            public void onError(String message) {
                statusBar.show(message, Theme.RED);
            }
        });
    }

    // ════════════════════════════════════════════════════════════════════
    //  ENTRY POINT
    // ════════════════════════════════════════════════════════════════════
    public static void main(String[] args) {
        // Apply UIManager defaults before any Swing components are created
        applyLookAndFeel();
        SwingUtilities.invokeLater(Main_SJF::new);
    }

    private static void applyLookAndFeel() {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
        catch (Exception ignored) {}

        UIManager.put("TabbedPane.selected",          Theme.BG_CARD);
        UIManager.put("TabbedPane.background",        Theme.BG_DARK);
        UIManager.put("TabbedPane.foreground",        Theme.TEXT_MAIN);
        UIManager.put("TabbedPane.contentAreaColor",  Theme.BG_DARK);
        UIManager.put("TabbedPane.tabAreaBackground", Theme.BG_DARK);
        UIManager.put("Panel.background",             Theme.BG_DARK);
        UIManager.put("ScrollPane.background",        Theme.BG_DARK);
        UIManager.put("Viewport.background",          Theme.BG_CARD);
        UIManager.put("ToolTip.background",           Theme.BG_CARD);
        UIManager.put("ToolTip.foreground",           Theme.TEXT_MAIN);
        UIManager.put("ToolTip.border",
            BorderFactory.createLineBorder(Theme.ACCENT, 1));
        UIManager.put("PopupMenu.background",         Theme.BG_CARD);
        UIManager.put("MenuItem.background",          Theme.BG_CARD);
        UIManager.put("MenuItem.foreground",          Theme.TEXT_MAIN);
    }
}
