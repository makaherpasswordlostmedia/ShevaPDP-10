package com.pdp10.emulator;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.tabs.TabLayout;
import com.pdp10.emulator.assembler.PDP10Assembler;
import com.pdp10.emulator.cpu.PDP10CPU;
import com.pdp10.emulator.memory.PDP10Memory;
import com.pdp10.emulator.terminal.TerminalView;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Activity for PDP-10 Emulator
 *
 * Three tabs:
 *  0 - TERMINAL : green VT100-style console for program I/O
 *  1 - EDITOR   : assembly source editor + assembler
 *  2 - DEBUG    : registers, disassembly, memory dump
 */
public class MainActivity extends AppCompatActivity {

    // -- Emulator subsystems (shared state) -----------------------------------
    public static PDP10Memory memory;
    public static PDP10CPU    cpu;
    public static PDP10Assembler assembler;
    public static volatile boolean cpuRunning = false;

    // -- UI -------------------------------------------------------------------
    private TerminalView terminalView;
    private TextView     statusBar;
    private Handler      mainHandler;
    private ExecutorService cpuExecutor;

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Global crash handler — shows error in dialog instead of silent crash
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            Log.e("PDP10", "CRASH in " + thread.getName(), throwable);
            String msg = throwable.getClass().getSimpleName() + ": " + throwable.getMessage()
                       + "\n\nIn: " + (throwable.getStackTrace().length > 0
                           ? throwable.getStackTrace()[0].toString() : "unknown");
            mainHandler.post(() -> new AlertDialog.Builder(MainActivity.this)
                .setTitle("Crash")
                .setMessage(msg)
                .setPositiveButton("OK", null)
                .show());
        });

        mainHandler = new Handler(Looper.getMainLooper());
        cpuExecutor = Executors.newSingleThreadExecutor();

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) setSupportActionBar(toolbar);

        initEmulator();
        setupTabs();
        statusBar    = findViewById(R.id.status_bar);
        terminalView = findViewById(R.id.terminal_view);
        if (terminalView != null) {
            terminalView.setOnClickListener(v -> terminalView.showKeyboard());
        }

        updateStatus();
        printWelcome();
    }

    // =========================================================================
    // Emulator initialisation
    // =========================================================================

    private void initEmulator() {
        memory    = new PDP10Memory();
        assembler = new PDP10Assembler(memory);
        cpu = new PDP10CPU(memory, new PDP10CPU.IOHandler() {

            @Override public void writeChar(char c) {
                // printChar is now thread-safe (synchronized + postInvalidate)
                // so we can call it directly from the CPU thread
                if (terminalView != null) terminalView.printChar(c);
            }

            @Override public int readChar() {
                return terminalView != null ? terminalView.readInputChar() : -1;
            }

            @Override public void halt(String reason) {
                cpuRunning = false;
                mainHandler.post(() -> {
                    if (terminalView != null) {
                        terminalView.printInfo("\n[HALTED: " + reason + "]");
                        terminalView.printInfo("[PC=" +
                            String.format("%06o", cpu.getPC()) +
                            "  #" + cpu.getInstructionCount() + "]");
                    }
                    updateStatus();
                });
            }

            @Override public void interrupt(int level) { /* TODO */ }
        });
    }

    // =========================================================================
    // Tab switching
    // =========================================================================

    private void setupTabs() {
        TabLayout tabs = findViewById(R.id.tab_layout);
        if (tabs == null) return;
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                showPanel(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });
    }

    private void showPanel(int index) {
        View terminal = findViewById(R.id.panel_terminal);
        View editor   = findViewById(R.id.panel_editor);
        View debug    = findViewById(R.id.panel_debug);
        if (terminal != null) terminal.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        if (editor   != null) editor  .setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        if (debug    != null) debug   .setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (index == 2) updateDebugView();
    }

    // =========================================================================
    // Menu
    // =========================================================================

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if      (id == R.id.menu_run)          { runCPU();   return true; }
        else if (id == R.id.menu_stop)         { stopCPU();  return true; }
        else if (id == R.id.menu_step)         { stepCPU();  return true; }
        else if (id == R.id.menu_reset)        { confirmReset(); return true; }
        else if (id == R.id.menu_assemble)     { assembleAndLoad(); return true; }
        else if (id == R.id.menu_load_hello)   { loadSample(PDP10Assembler.getHelloWorldProgram(),  "Hello World"); return true; }
        else if (id == R.id.menu_load_fib)     { loadSample(PDP10Assembler.getFibonacciProgram(),   "Fibonacci");   return true; }
        else if (id == R.id.menu_load_counter) { loadSample(PDP10Assembler.getCounterProgram(),     "Counter");     return true; }
        return super.onOptionsItemSelected(item);
    }

    // =========================================================================
    // onClick handlers (referenced by android:onClick in layout XML)
    // All must have signature:  public void methodName(View v)
    // =========================================================================

    /** Editor tab -- Assemble button */
    public void onAssembleClick(View v) { assembleAndLoad(); }

    /** Editor tab -- Run button (assemble then run) */
    public void onRunFromEditorClick(View v) {
        assembleAndLoad();
        if (!cpu.isHalted()) runCPU();
    }

    /** Editor tab -- Clear button */
    public void onClearEditorClick(View v) {
        EditText ed = findViewById(R.id.editor_input);
        if (ed != null) ed.setText("");
    }

    /** Debug tab -- Run button */
    public void onDebugRun(View v)   { runCPU();  }
    /** Debug tab -- Step button */
    public void onDebugStep(View v)  { stepCPU(); }
    /** Debug tab -- Stop button */
    public void onDebugStop(View v)  { stopCPU(); }
    /** Debug tab -- Reset button */
    public void onDebugReset(View v) { confirmReset(); }

    // =========================================================================
    // CPU control
    // =========================================================================

    public void runCPU() {
        if (cpuRunning) return;
        cpuRunning = true;
        updateStatus();
        if (terminalView != null)
            terminalView.printInfo("[RUN  PC=" + String.format("%06o", cpu.getPC()) + "]");
        cpuExecutor.execute(() -> {
            cpu.run();
            cpuRunning = false;
            mainHandler.post(this::updateStatus);
        });
    }

    public void stopCPU() {
        cpu.stop();
        cpuRunning = false;
        updateStatus();
        if (terminalView != null) terminalView.printInfo("[STOP]");
    }

    public void stepCPU() {
        if (cpuRunning) return;
        cpu.step();
        updateStatus();
        updateDebugView();
        if (terminalView != null) {
            int prevPC = (cpu.getPC() - 1) & 0x3FFFF;
            terminalView.printInfo("[STEP] " + PDP10CPU.disassembleWord(prevPC, memory.read(prevPC)));
        }
    }

    private void confirmReset() {
        new AlertDialog.Builder(this)
            .setTitle("Reset")
            .setMessage("Reset CPU and clear memory?")
            .setPositiveButton("Reset", (d, w) -> {
                stopCPU();
                cpu.reset();
                memory.clear();
                if (terminalView != null) { terminalView.clearScreen(); printWelcome(); }
                updateStatus();
                updateDebugView();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // =========================================================================
    // Assembler
    // =========================================================================

    private void assembleAndLoad() {
        EditText ed = findViewById(R.id.editor_input);
        if (ed == null) {
            Toast.makeText(this, "Open the Editor tab first", Toast.LENGTH_SHORT).show();
            return;
        }
        String source = ed.getText().toString().trim();
        if (source.isEmpty()) {
            Toast.makeText(this, "Editor is empty -- load a sample from the menu", Toast.LENGTH_SHORT).show();
            return;
        }

        memory.clear();
        PDP10Assembler.AssemblyResult result = assembler.assemble(source);

        TextView asmOut = findViewById(R.id.assembler_output);

        if (result.hasErrors()) {
            String report = "ERRORS:\n" + result.errorReport();
            if (asmOut   != null) asmOut.setText(report);
            if (terminalView != null) terminalView.printError(report);
            Toast.makeText(this, "Assembly failed", Toast.LENGTH_SHORT).show();
        } else {
            StringBuilder msg = new StringBuilder();
            msg.append(String.format("OK  %d words  start=%06o\n", result.wordCount, result.startAddress));
            for (Map.Entry<String, Integer> e : result.symbols.entrySet()) {
                msg.append(String.format("  %-12s %06o\n", e.getKey(), e.getValue()));
            }
            if (asmOut != null) asmOut.setText(msg.toString());
            if (terminalView != null) terminalView.printInfo(msg.toString());

            cpu.reset();
            cpu.setPC(result.startAddress);
            updateStatus();
            updateDebugView();
            Toast.makeText(this, "Assembled " + result.wordCount + " words", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSample(int rawResId, String name) {
        try {
            java.io.InputStream is = getResources().openRawResource(rawResId);
            byte[] buf = new byte[is.available()];
            is.read(buf);
            is.close();
            String source = new String(buf, "UTF-8");
            EditText ed = findViewById(R.id.editor_input);
            if (ed != null) ed.setText(source);
            TabLayout tabs = findViewById(R.id.tab_layout);
            if (tabs != null) tabs.selectTab(tabs.getTabAt(1));
            Toast.makeText(this, "Loaded: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Failed to load: " + name, Toast.LENGTH_SHORT).show();
        }
    }

    // =========================================================================
    // Debug view
    // =========================================================================

    public void updateDebugView() {
        if (cpu == null) return;

        // Registers
        TextView regView = findViewById(R.id.debug_registers);
        if (regView != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("PC: %06o   FLAGS: %012o\n", cpu.getPC(), cpu.getFlags()));
            sb.append(String.format("State: %s   #insn: %,d\n\n",
                cpuRunning ? "RUNNING" : (cpu.isHalted() ? "HALTED" : "STOPPED"),
                cpu.getInstructionCount()));
            long[] acs = cpu.getACs();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("AC%02d: %012o  (%d)\n", i, acs[i], twosComp(acs[i])));
            }
            regView.setText(sb.toString());
        }

        // Disassembly
        TextView disasmView = findViewById(R.id.debug_disasm);
        if (disasmView != null) {
            StringBuilder sb = new StringBuilder();
            int pc = cpu.getPC();
            for (int i = -2; i < 12; i++) {
                int addr = (pc + i) & 0x3FFFF;
                String line = PDP10CPU.disassembleWord(addr, memory.read(addr));
                sb.append(i == 0 ? ">> " : "   ").append(line).append('\n');
            }
            disasmView.setText(sb.toString());
        }

        // Memory dump
        TextView memView = findViewById(R.id.debug_memory);
        if (memView != null) {
            // FIX: declare sb BEFORE using it
            StringBuilder sb = new StringBuilder();
            int base = cpu.getPC() & ~7;
            sb.append(String.format("Memory at %06o:\n", base));
            for (int r = 0; r < 8; r++) {
                sb.append(String.format("%06o: ", (base + r * 4) & 0x3FFFF));
                for (int c = 0; c < 4; c++) {
                    sb.append(String.format("%012o ", memory.read((base + r * 4 + c) & 0x3FFFF)));
                }
                sb.append('\n');
            }
            memView.setText(sb.toString());
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void updateStatus() {
        if (statusBar == null) return;
        String state = cpuRunning ? " RUNNING" : (cpu != null && cpu.isHalted() ? " HALTED" : " STOPPED");
        statusBar.setText(String.format("%s   PC:%06o   #%d",
            state,
            cpu != null ? cpu.getPC() : 0,
            cpu != null ? cpu.getInstructionCount() : 0));
    }

    private long twosComp(long v) {
        if ((v & 0x800000000L) != 0) return v - 0x1000000000L;
        return v;
    }

    private void printWelcome() {
        if (terminalView == null) return;
        terminalView.clearScreen();
        terminalView.printInfo("=========================================");
        terminalView.printInfo("  PDP-10 Emulator  --  KA10/KI10");
        terminalView.printInfo("  256K Words  |  Android Edition");
        terminalView.printInfo("=========================================");
        terminalView.print("\n");
        terminalView.printInfo("Menu -> Load Program -> pick a sample");
        terminalView.printInfo("Switch to EDITOR tab, tap ASSEMBLE");
        terminalView.printInfo("Then tap RUN or use Debug tab to step");
        terminalView.print("\nPDP-10> ");
    }

    // =========================================================================
    // Lifecycle
    // =========================================================================

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCPU();
        cpuExecutor.shutdown();
    }
}
