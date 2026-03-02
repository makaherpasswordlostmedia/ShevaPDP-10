package com.pdp10.emulator;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import com.pdp10.emulator.assembler.PDP10Assembler;
import com.pdp10.emulator.cpu.PDP10CPU;
import com.pdp10.emulator.memory.PDP10Memory;
import com.pdp10.emulator.terminal.TerminalView;
import com.pdp10.emulator.ui.DebugFragment;
import com.pdp10.emulator.ui.EditorFragment;
import com.pdp10.emulator.ui.MemoryFragment;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Main Activity for PDP-10 Emulator
 * 
 * Provides three tabs:
 * 1. Terminal - classic VT100-style terminal for I/O
 * 2. Editor   - source code editor with assembler
 * 3. Debug    - register inspector, memory viewer, disassembler
 */
public class MainActivity extends AppCompatActivity {

    // Emulator components (shared across fragments)
    public static PDP10Memory memory;
    public static PDP10CPU cpu;
    public static PDP10Assembler assembler;
    public static volatile boolean cpuRunning = false;

    // UI components
    private TerminalView terminalView;
    private TextView statusBar;
    private Handler mainHandler;
    private ExecutorService cpuExecutor;

    // Assembler output buffer
    private StringBuilder assemblerOutput = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mainHandler  = new Handler(Looper.getMainLooper());
        cpuExecutor  = Executors.newSingleThreadExecutor();

        // Initialize emulator subsystems
        initEmulator();

        // Setup UI
        setupTabs();
        setupStatusBar();
        setupTerminal();

        // Welcome message
        printWelcome();
    }

    private void initEmulator() {
        memory    = new PDP10Memory();
        assembler = new PDP10Assembler(memory);
        cpu = new PDP10CPU(memory, new PDP10CPU.IOHandler() {
            @Override
            public void writeChar(char c) {
                if (terminalView != null) {
                    terminalView.post(() -> {
                        terminalView.printChar(c);
                    });
                }
            }

            @Override
            public int readChar() {
                if (terminalView != null) {
                    return terminalView.readInputChar();
                }
                return -1;
            }

            @Override
            public void halt(String reason) {
                cpuRunning = false;
                mainHandler.post(() -> {
                    if (terminalView != null) {
                        terminalView.printInfo("\n[CPU HALTED: " + reason + "]");
                        terminalView.printInfo("[PC=" + String.format("%06o", cpu.getPC()) +
                            "  Insn=" + cpu.getInstructionCount() + "]");
                    }
                    updateStatus();
                });
            }

            @Override
            public void interrupt(int level) {
                // TODO: implement interrupt handling
            }
        });
    }

    private void setupTerminal() {
        terminalView = findViewById(R.id.terminal_view);
        if (terminalView != null) {
            terminalView.setOnClickListener(v -> terminalView.showKeyboard());
        }
    }

    private void setupTabs() {
        // The tabs are configured in the layout; if using ViewPager2 with fragments
        // this would be more complex. For simplicity we use a tab layout
        // with manual fragment switching here.
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        if (tabLayout != null) {
            tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
                @Override
                public void onTabSelected(TabLayout.Tab tab) {
                    showTab(tab.getPosition());
                }
                @Override public void onTabUnselected(TabLayout.Tab tab) {}
                @Override public void onTabReselected(TabLayout.Tab tab) {}
            });
        }
    }

    private void showTab(int position) {
        View terminalPanel  = findViewById(R.id.panel_terminal);
        View editorPanel    = findViewById(R.id.panel_editor);
        View debugPanel     = findViewById(R.id.panel_debug);

        if (terminalPanel != null) terminalPanel.setVisibility(position == 0 ? View.VISIBLE : View.GONE);
        if (editorPanel   != null) editorPanel.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        if (debugPanel    != null) debugPanel.setVisibility(position == 2 ? View.VISIBLE : View.GONE);

        if (position == 2) updateDebugView();
    }

    private void setupStatusBar() {
        statusBar = findViewById(R.id.status_bar);
        updateStatus();
    }

    private void updateStatus() {
        if (statusBar == null) return;
        String status = cpuRunning ? "▶ RUNNING" : "■ HALTED";
        String info = String.format("  PC:%06o  Insn:%d",
            cpu != null ? cpu.getPC() : 0,
            cpu != null ? cpu.getInstructionCount() : 0);
        statusBar.setText(status + info);
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
        if (id == R.id.menu_run) {
            runCPU();
            return true;
        } else if (id == R.id.menu_stop) {
            stopCPU();
            return true;
        } else if (id == R.id.menu_step) {
            stepCPU();
            return true;
        } else if (id == R.id.menu_reset) {
            resetEmulator();
            return true;
        } else if (id == R.id.menu_assemble) {
            assembleCode();
            return true;
        } else if (id == R.id.menu_load_hello) {
            loadSampleProgram(PDP10Assembler.getHelloWorldProgram(), "Hello World");
            return true;
        } else if (id == R.id.menu_load_fib) {
            loadSampleProgram(PDP10Assembler.getFibonacciProgram(), "Fibonacci");
            return true;
        } else if (id == R.id.menu_load_counter) {
            loadSampleProgram(PDP10Assembler.getCounterProgram(), "Counter");
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // =========================================================================
    // CPU control
    // =========================================================================

    public void runCPU() {
        if (cpuRunning) return;
        cpuRunning = true;
        updateStatus();

        if (terminalView != null) {
            terminalView.printInfo("[CPU RUNNING from PC=" + String.format("%06o", cpu.getPC()) + "]");
        }

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
        if (terminalView != null) {
            terminalView.printInfo("[CPU STOPPED]");
        }
    }

    public void stepCPU() {
        if (cpuRunning) return;
        cpu.step();
        updateStatus();
        updateDebugView();

        if (terminalView != null) {
            String disasm = cpu.disassemble(cpu.getPC() - 1 < 0 ? 0 : cpu.getPC() - 1);
            terminalView.printInfo("[STEP] " + disasm);
        }
    }

    public void resetEmulator() {
        new AlertDialog.Builder(this)
            .setTitle("Reset Emulator")
            .setMessage("Reset CPU and clear memory?")
            .setPositiveButton("Reset", (d, w) -> {
                stopCPU();
                cpu.reset();
                memory.clear();
                if (terminalView != null) {
                    terminalView.clearScreen();
                    printWelcome();
                }
                updateStatus();
                updateDebugView();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    // =========================================================================
    // Assembler
    // =========================================================================

    public void assembleCode() {
        EditText editorView = findViewById(R.id.editor_input);
        if (editorView == null) {
            Toast.makeText(this, "Open the Editor tab first", Toast.LENGTH_SHORT).show();
            return;
        }

        String source = editorView.getText().toString();
        if (source.trim().isEmpty()) {
            Toast.makeText(this, "Editor is empty", Toast.LENGTH_SHORT).show();
            return;
        }

        memory.clear();
        PDP10Assembler.AssemblyResult result = assembler.assemble(source);

        StringBuilder msg = new StringBuilder();
        if (result.hasErrors()) {
            msg.append("ASSEMBLY ERRORS:\n").append(result.errorReport());
            if (terminalView != null) terminalView.printError(msg.toString());
        } else {
            msg.append(String.format("Assembled %d words, start=0%o\n",
                result.wordCount, result.startAddress));
            if (!result.symbols.isEmpty()) {
                msg.append("Symbols:\n");
                for (Map.Entry<String, Integer> e : result.symbols.entrySet()) {
                    msg.append(String.format("  %-12s = 0%06o\n", e.getKey(), e.getValue()));
                }
            }
            cpu.reset();
            cpu.setPC(result.startAddress);

            if (terminalView != null) terminalView.printInfo(msg.toString());
            updateStatus();
            updateDebugView();
            Toast.makeText(this, "Assembly OK! " + result.wordCount + " words", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSampleProgram(String source, String name) {
        EditText editorView = findViewById(R.id.editor_input);
        if (editorView != null) {
            editorView.setText(source);
        }

        // Switch to editor tab
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        if (tabLayout != null) tabLayout.selectTab(tabLayout.getTabAt(1));

        Toast.makeText(this, "Loaded: " + name, Toast.LENGTH_SHORT).show();
    }

    // =========================================================================
    // Debug view
    // =========================================================================

    public void updateDebugView() {
        if (cpu == null) return;

        // Update register display
        TextView regView = findViewById(R.id.debug_registers);
        if (regView != null) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("PC: %06o   FLAGS: %012o\n", cpu.getPC(), cpu.getFlags()));
            sb.append(String.format("Running: %s   Halted: %s\n",
                cpuRunning ? "YES" : "NO", cpu.isHalted() ? "YES" : "NO"));
            sb.append(String.format("Instructions: %,d\n\n", cpu.getInstructionCount()));
            long[] acs = cpu.getACs();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("AC%02d: %012o  (%,d)\n", i, acs[i],
                    twosComp(acs[i])));
            }
            regView.setText(sb.toString());
        }

        // Update disassembly
        TextView disasmView = findViewById(R.id.debug_disasm);
        if (disasmView != null) {
            StringBuilder sb = new StringBuilder();
            int pc = cpu.getPC();
            for (int i = -2; i < 12; i++) {
                int addr = (pc + i) & 0x3FFFF;
                String line = PDP10CPU.disassembleWord(addr, memory.read(addr));
                if (i == 0) sb.append(">> ");
                else sb.append("   ");
                sb.append(line).append('\n');
            }
            disasmView.setText(sb.toString());
        }

        // Update memory view
        TextView memView = findViewById(R.id.debug_memory);
        if (memView != null) {
            int base = cpu.getPC() & ~7;
            sb.append(String.format("Memory at 0%06o:\n", base));
            StringBuilder sb = new StringBuilder();
            for (int r = 0; r < 8; r++) {
                sb.append(String.format("0%06o: ", (base + r * 4) & 0x3FFFF));
                for (int c = 0; c < 4; c++) {
                    sb.append(String.format("0%012o ", memory.read((base + r * 4 + c) & 0x3FFFF)));
                }
                sb.append('\n');
            }
            memView.setText(sb.toString());
        }
    }

    private long twosComp(long v) {
        if ((v & 0x80000_00000L) != 0) return v - 0x100000_00000L;
        return v;
    }

    // =========================================================================
    // Welcome message
    // =========================================================================

    private void printWelcome() {
        if (terminalView == null) return;
        terminalView.clearScreen();
        terminalView.printInfo("========================================");
        terminalView.printInfo("  PDP-10 Emulator v1.0 for Android");
        terminalView.printInfo("  KA10/KI10 Instruction Set");
        terminalView.printInfo("  256K Words Core Memory");
        terminalView.printInfo("========================================");
        terminalView.print("\n");
        terminalView.printInfo("Commands from Editor tab:");
        terminalView.printInfo("  1. Write/load PDP-10 assembly code");
        terminalView.printInfo("  2. Tap [Assemble] to compile");
        terminalView.printInfo("  3. Tap [Run] to execute");
        terminalView.printInfo("  4. Use Debug tab to inspect state");
        terminalView.print("\n");
        terminalView.printInfo("Load a sample via Menu > Load Program");
        terminalView.print("\n");
        terminalView.print("PDP-10> ");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCPU();
        cpuExecutor.shutdown();
    }
                  }
