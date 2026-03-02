package com.pdp10.emulator.cpu;

import com.pdp10.emulator.memory.PDP10Memory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PDP-10 CPU Emulator
 * Implements KA10/KI10 instruction set architecture
 * 
 * Architecture:
 * - 36-bit word size
 * - 18-bit addressing (256K words)
 * - 16 accumulators (AC0-AC15), part of memory page 0
 * - Program Counter (PC) - 18 bits
 * - Flags register
 */
public class PDP10CPU {

    // =========================================================================
    // PDP-10 Word size and masks
    // =========================================================================
    public static final long WORD_MASK      = 0x_FFFFF_FFFFF L; // 36 bits
    public static final long HALF_MASK      = 0x3FFFFL;          // 18 bits
    public static final long SIGN_BIT       = 0x_80000_00000L;   // Bit 0 (MSB)
    public static final long MAX_POS        = 0x_3FFFF_FFFFL;
    public static final long MAX_NEG        = 0x_80000_00000L;
    public static final long ONES_COMP_NEG1 = 0x_FFFFF_FFFFL;   // -0 in ones complement
    public static final long LEFT_HALF_MASK = 0x_FFFFE_00000L;   // Left 18 bits
    public static final long RIGHT_HALF_MASK= 0x_0000_3FFFFL;    // Right 18 bits

    // =========================================================================
    // Registers
    // =========================================================================
    private long[] AC = new long[16];       // Accumulators 0-15
    private int PC = 0;                      // Program Counter (18-bit)
    private long FLAGS = 0;                  // Processor flags

    // =========================================================================
    // Flags bits (in left half of PC word)
    // =========================================================================
    public static final long FLAG_OV   = (1L << 35); // Overflow
    public static final long FLAG_CY0  = (1L << 34); // Carry 0
    public static final long FLAG_CY1  = (1L << 33); // Carry 1
    public static final long FLAG_FPU  = (1L << 32); // Floating overflow
    public static final long FLAG_TR1  = (1L << 31); // Trap 1
    public static final long FLAG_TR2  = (1L << 30); // Trap 2
    public static final long FLAG_AFI  = (1L << 29); // Arithmetic flag
    public static final long FLAG_PUB  = (1L << 28); // Public mode
    public static final long FLAG_USER = (1L << 27); // User mode
    public static final long FLAG_UIO  = (1L << 26); // User I/O
    public static final long FLAG_IOT  = (1L << 25); // I/O trap
    public static final long FLAG_PCHI = (1L << 24); // PC high
    public static final long FLAG_INT  = (1L << 23); // Interrupt inhibit

    // =========================================================================
    // Memory subsystem
    // =========================================================================
    private PDP10Memory memory;

    // =========================================================================
    // I/O subsystem callbacks
    // =========================================================================
    public interface IOHandler {
        void writeChar(char c);
        int readChar();   // Returns -1 if no char available
        void halt(String reason);
        void interrupt(int level);
    }

    private IOHandler ioHandler;

    // =========================================================================
    // CPU state
    // =========================================================================
    private AtomicBoolean running = new AtomicBoolean(false);
    private AtomicBoolean halted  = new AtomicBoolean(false);
    private volatile long instructionCount = 0;
    private volatile int lastOpcode = 0;
    private String haltReason = "";

    // Instruction stats
    private long[] opStats = new long[512]; // per-opcode execution count

    public PDP10CPU(PDP10Memory memory, IOHandler ioHandler) {
        this.memory    = memory;
        this.ioHandler = ioHandler;
        reset();
    }

    // =========================================================================
    // CPU Control
    // =========================================================================

    public void reset() {
        for (int i = 0; i < 16; i++) AC[i] = 0;
        PC     = 0200; // Conventional start address
        FLAGS  = 0;
        halted.set(false);
        running.set(false);
        instructionCount = 0;
    }

    public void setPC(int address) {
        PC = address & 0x3FFFF;
    }

    public int getPC() { return PC; }

    public void setAC(int n, long value) {
        AC[n & 0xF] = value & WORD_MASK;
        memory.write(n & 0xF, AC[n & 0xF]); // ACs are also memory 0-17
    }

    public long getAC(int n) {
        return AC[n & 0xF];
    }

    public long getFlags() { return FLAGS; }

    public boolean isHalted() { return halted.get(); }
    public boolean isRunning() { return running.get(); }
    public long getInstructionCount() { return instructionCount; }
    public String getHaltReason() { return haltReason; }

    // =========================================================================
    // Main execution loop
    // =========================================================================

    public void run() {
        running.set(true);
        halted.set(false);
        while (running.get() && !halted.get()) {
            step();
        }
        running.set(false);
    }

    public void stop() {
        running.set(false);
    }

    public void step() {
        if (halted.get()) return;

        try {
            long instruction = memory.read(PC);
            int savedPC = PC;
            PC = (PC + 1) & 0x3FFFF;
            executeInstruction(instruction, savedPC);
            instructionCount++;
        } catch (Exception e) {
            halt("CPU exception: " + e.getMessage());
        }
    }

    private void halt(String reason) {
        halted.set(true);
        running.set(false);
        haltReason = reason;
        if (ioHandler != null) ioHandler.halt(reason);
    }

    // =========================================================================
    // Instruction Decoding
    // =========================================================================

    // Instruction format: [op:9][AC:4][I:1][X:4][Y:18]
    private static int opcode(long w)  { return (int)((w >> 27) & 0777); }
    private static int acField(long w) { return (int)((w >> 23) & 017);  }
    private static int iField(long w)  { return (int)((w >> 22) & 01);   }
    private static int xField(long w)  { return (int)((w >> 18) & 017);  }
    private static int yField(long w)  { return (int)(w & 0777777);      }

    /**
     * Calculate effective address with indexing and indirection
     */
    private int effectiveAddress(long instruction) {
        int y = yField(instruction);
        int x = xField(instruction);
        int i = iField(instruction);

        int ea = y;
        if (x != 0) ea = (ea + (int)(AC[x] & HALF_MASK)) & 0x3FFFF;

        // Handle indirection (follow chain)
        int depth = 0;
        while (i != 0 && depth < 64) {
            long ind = memory.read(ea);
            i  = iField(ind);
            x  = xField(ind);
            ea = yField(ind);
            if (x != 0) ea = (ea + (int)(AC[x] & HALF_MASK)) & 0x3FFFF;
            depth++;
        }
        return ea;
    }

    // =========================================================================
    // Arithmetic helpers
    // =========================================================================

    /** Add two 36-bit ones-complement numbers, set flags */
    private long add(long a, long b) {
        long result = (a + b) & WORD_MASK;

        // Carry detection
        boolean aCy = (a & SIGN_BIT) != 0;
        boolean bCy = (b & SIGN_BIT) != 0;
        boolean rCy = (result & SIGN_BIT) != 0;

        // Overflow: both inputs same sign, result different sign
        if ((!aCy && !bCy && rCy) || (aCy && bCy && !rCy)) {
            FLAGS |= FLAG_OV | FLAG_CY0;
        }

        // End-around carry for ones-complement
        long sum = (a & ~SIGN_BIT) + (b & ~SIGN_BIT);
        if ((sum & SIGN_BIT) != 0) {
            // Carry into sign bit
            FLAGS |= FLAG_CY0 | FLAG_CY1;
        }

        return result;
    }

    private long negate(long a) {
        return (~a) & WORD_MASK; // ones complement negation
    }

    private long sub(long a, long b) {
        return add(a, negate(b));
    }

    private boolean isNegative(long w) {
        return (w & SIGN_BIT) != 0;
    }

    private boolean isZero(long w) {
        return w == 0 || w == ONES_COMP_NEG1; // both 0 and -0 count
    }

    /** Sign extend 18-bit half-word to 36-bit */
    private long signExtend18(int v) {
        if ((v & 0x20000) != 0) return (long)v | 0xFFFFFC0000L; // sign bit set
        return (long)v;
    }

    // =========================================================================
    // Main instruction dispatcher
    // =========================================================================

    private void executeInstruction(long w, int pc) {
        int op = opcode(w);
        int ac = acField(w);
        lastOpcode = op;
        opStats[op]++;

        switch (op) {

        // =====================================================================
        // 0xx - Logical and bit operations
        // =====================================================================

        case 0000: // LUUO - Unimplemented user operation (trap)
        case 0001: case 0002: case 0003: case 0004:
        case 0005: case 0006: case 0007:
            luuoTrap(op, ac, w);
            break;

        // =====================================================================
        // 1xx - Move instructions
        // =====================================================================

        case 0200: { // MOVE  - Move to AC
            int ea = effectiveAddress(w);
            AC[ac] = memory.read(ea);
            break;
        }
        case 0201: { // MOVEI - Move Immediate
            int ea = effectiveAddress(w); // EA is the value (no memory access)
            AC[ac] = ea & HALF_MASK;
            break;
        }
        case 0202: { // MOVEM - Move to Memory
            int ea = effectiveAddress(w);
            memory.write(ea, AC[ac]);
            break;
        }
        case 0203: { // MOVES - Move to Both (self)
            int ea = effectiveAddress(w);
            long val = memory.read(ea);
            if (ac != 0) AC[ac] = val;
            memory.write(ea, val);
            break;
        }
        case 0204: { // MOVS  - Move Swapped
            int ea = effectiveAddress(w);
            long val = memory.read(ea);
            AC[ac] = swap(val);
            break;
        }
        case 0205: { // MOVSI - Move Swapped Immediate
            int ea = effectiveAddress(w);
            AC[ac] = ((long)ea << 18) & WORD_MASK;
            break;
        }
        case 0206: { // MOVSM - Move Swapped to Memory
            int ea = effectiveAddress(w);
            memory.write(ea, swap(AC[ac]));
            break;
        }
        case 0207: { // MOVSS - Move Swapped to Self
            int ea = effectiveAddress(w);
            long val = swap(memory.read(ea));
            if (ac != 0) AC[ac] = val;
            memory.write(ea, val);
            break;
        }
        case 0210: { // MOVN  - Move Negated
            int ea = effectiveAddress(w);
            AC[ac] = negate(memory.read(ea));
            break;
        }
        case 0211: { // MOVNI - Move Negated Immediate
            int ea = effectiveAddress(w);
            AC[ac] = negate(ea & HALF_MASK);
            break;
        }
        case 0212: { // MOVNM - Move Negated to Memory
            int ea = effectiveAddress(w);
            memory.write(ea, negate(AC[ac]));
            break;
        }
        case 0213: { // MOVNS - Move Negated to Self
            int ea = effectiveAddress(w);
            long val = negate(memory.read(ea));
            if (ac != 0) AC[ac] = val;
            memory.write(ea, val);
            break;
        }
        case 0214: { // MOVM  - Move Magnitude
            int ea = effectiveAddress(w);
            long val = memory.read(ea);
            AC[ac] = isNegative(val) ? negate(val) : val;
            break;
        }
        case 0215: { // MOVMI - Move Magnitude Immediate
            int ea = effectiveAddress(w);
            AC[ac] = ea & HALF_MASK; // immediate is always positive
            break;
        }
        case 0216: { // MOVMM - Move Magnitude to Memory
            int ea = effectiveAddress(w);
            long val = AC[ac];
            memory.write(ea, isNegative(val) ? negate(val) : val);
            break;
        }
        case 0217: { // MOVMS - Move Magnitude to Self
            int ea = effectiveAddress(w);
            long val = memory.read(ea);
            val = isNegative(val) ? negate(val) : val;
            if (ac != 0) AC[ac] = val;
            memory.write(ea, val);
            break;
        }

        // =====================================================================
        // Integer Arithmetic
        // =====================================================================

        case 0220: { // IMUL - Integer Multiply
            int ea = effectiveAddress(w);
            long m = memory.read(ea);
            AC[ac] = imul(AC[ac], m);
            break;
        }
        case 0221: { // IMULI - Integer Multiply Immediate
            int ea = effectiveAddress(w);
            AC[ac] = imul(AC[ac], ea & HALF_MASK);
            break;
        }
        case 0222: { // IMULM - Integer Multiply to Memory
            int ea = effectiveAddress(w);
            long result = imul(AC[ac], memory.read(ea));
            memory.write(ea, result);
            break;
        }
        case 0223: { // IMULB - Integer Multiply Both
            int ea = effectiveAddress(w);
            long result = imul(AC[ac], memory.read(ea));
            AC[ac] = result;
            memory.write(ea, result);
            break;
        }
        case 0224: { // MUL  - Multiply (double word result)
            int ea = effectiveAddress(w);
            long[] res = mul(AC[ac], memory.read(ea));
            AC[ac] = res[0];
            if (ac + 1 < 16) AC[ac + 1] = res[1];
            break;
        }
        case 0225: { // MULI - Multiply Immediate
            int ea = effectiveAddress(w);
            long[] res = mul(AC[ac], ea & HALF_MASK);
            AC[ac] = res[0];
            if (ac + 1 < 16) AC[ac + 1] = res[1];
            break;
        }
        case 0230: { // IDIV - Integer Divide
            int ea = effectiveAddress(w);
            idiv(ac, AC[ac], memory.read(ea));
            break;
        }
        case 0231: { // IDIVI - Integer Divide Immediate
            int ea = effectiveAddress(w);
            idiv(ac, AC[ac], ea & HALF_MASK);
            break;
        }
        case 0234: { // DIV  - Divide (double word dividend)
            int ea = effectiveAddress(w);
            long hi = AC[ac];
            long lo = (ac + 1 < 16) ? AC[ac + 1] : 0;
            divDouble(ac, hi, lo, memory.read(ea));
            break;
        }
        case 0235: { // DIVI
            int ea = effectiveAddress(w);
            long hi = AC[ac];
            long lo = (ac + 1 < 16) ? AC[ac + 1] : 0;
            divDouble(ac, hi, lo, ea & HALF_MASK);
            break;
        }

        // =====================================================================
        // Shift and rotate
        // =====================================================================

        case 0240: { // ASH  - Arithmetic Shift
            int ea = effectiveAddress(w);
            int cnt = (ea & 0777);
            if (cnt >= 0400) cnt = cnt - 0777 - 1; // signed
            AC[ac] = ash(AC[ac], cnt);
            break;
        }
        case 0241: { // ROT  - Rotate
            int ea = effectiveAddress(w);
            int cnt = (ea & 0777);
            if (cnt >= 0400) cnt = cnt - 0777 - 1;
            AC[ac] = rot(AC[ac], cnt);
            break;
        }
        case 0242: { // LSH  - Logical Shift
            int ea = effectiveAddress(w);
            int cnt = (ea & 0777);
            if (cnt >= 0400) cnt = cnt - 0777 - 1;
            AC[ac] = lsh(AC[ac], cnt);
            break;
        }
        case 0243: { // JFFO - Jump if Find First One
            int ea = effectiveAddress(w);
            if (AC[ac] != 0) {
                int pos = 0;
                long tmp = AC[ac];
                while ((tmp & SIGN_BIT) == 0) { pos++; tmp <<= 1; }
                if (ac + 1 < 16) AC[ac + 1] = pos;
                PC = ea;
            }
            break;
        }
        case 0244: { // ASHC - Arithmetic Shift Combined
            int ea = effectiveAddress(w);
            int cnt = (ea & 0777);
            if (cnt >= 0400) cnt = cnt - 0777 - 1;
            ashc(ac, cnt);
            break;
        }
        case 0245: { // ROTC - Rotate Combined
            int ea = effectiveAddress(w);
            int cnt = (ea & 0777);
            if (cnt >= 0400) cnt = cnt - 0777 - 1;
            rotc(ac, cnt);
            break;
        }
        case 0246: { // LSHC - Logical Shift Combined
            int ea = effectiveAddress(w);
            int cnt = (ea & 0777);
            if (cnt >= 0400) cnt = cnt - 0777 - 1;
            lshc(ac, cnt);
            break;
        }

        // =====================================================================
        // Add / Subtract
        // =====================================================================

        case 0270: { // ADD
            int ea = effectiveAddress(w);
            AC[ac] = add(AC[ac], memory.read(ea));
            break;
        }
        case 0271: { // ADDI - Add Immediate
            int ea = effectiveAddress(w);
            AC[ac] = add(AC[ac], ea & HALF_MASK);
            break;
        }
        case 0272: { // ADDM - Add to Memory
            int ea = effectiveAddress(w);
            long result = add(AC[ac], memory.read(ea));
            memory.write(ea, result);
            break;
        }
        case 0273: { // ADDB - Add Both
            int ea = effectiveAddress(w);
            long result = add(AC[ac], memory.read(ea));
            AC[ac] = result;
            memory.write(ea, result);
            break;
        }
        case 0274: { // SUB
            int ea = effectiveAddress(w);
            AC[ac] = sub(AC[ac], memory.read(ea));
            break;
        }
        case 0275: { // SUBI
            int ea = effectiveAddress(w);
            AC[ac] = sub(AC[ac], ea & HALF_MASK);
            break;
        }
        case 0276: { // SUBM
            int ea = effectiveAddress(w);
            long result = sub(memory.read(ea), AC[ac]);
            memory.write(ea, result);
            break;
        }
        case 0277: { // SUBB
            int ea = effectiveAddress(w);
            long result = sub(AC[ac], memory.read(ea));
            AC[ac] = result;
            memory.write(ea, result);
            break;
        }

        // =====================================================================
        // Comparison / Skip instructions (CAI, CAM variants)
        // =====================================================================

        case 0300: skipIfCondition(ac, 0, effectiveAddress(w), false, false, false); break; // CAIL
        case 0301: AC[ac] = (effectiveAddress(w) & HALF_MASK); break; // CAIE ?
        // Full CAI/CAM family (0300-0317)
        case 0302: case 0303: case 0304: case 0305:
        case 0306: case 0307: case 0310: case 0311:
        case 0312: case 0313: case 0314: case 0315:
        case 0316: case 0317:
            caiCam(op, ac, w);
            break;

        // =====================================================================
        // Jump instructions
        // =====================================================================

        case 0320: { // JUMP  - Jump if AC condition
        case 0321: case 0322: case 0323:
        case 0324: case 0325: case 0326: case 0327:
            jumpCondition(op - 0320, ac, effectiveAddress(w));
            break;
        }

        case 0330: case 0331: case 0332: case 0333:
        case 0334: case 0335: case 0336: case 0337: { // SKIP
            skipCondition(op - 0330, ac, w);
            break;
        }

        case 0340: case 0341: case 0342: case 0343:
        case 0344: case 0345: case 0346: case 0347: { // AOJ - Add 1, Jump
            int ea = effectiveAddress(w);
            AC[ac] = add(AC[ac], 1);
            if (testJumpCondition(op - 0340, AC[ac])) PC = ea;
            break;
        }

        case 0350: case 0351: case 0352: case 0353:
        case 0354: case 0355: case 0356: case 0357: { // AOS - Add 1, Skip
            int ea = effectiveAddress(w);
            long val = add(memory.read(ea), 1);
            memory.write(ea, val);
            if (ac != 0) AC[ac] = val;
            if (testJumpCondition(op - 0350, val)) PC = (PC + 1) & 0x3FFFF;
            break;
        }

        case 0360: case 0361: case 0362: case 0363:
        case 0364: case 0365: case 0366: case 0367: { // SOJ - Subtract 1, Jump
            int ea = effectiveAddress(w);
            AC[ac] = sub(AC[ac], 1);
            if (testJumpCondition(op - 0360, AC[ac])) PC = ea;
            break;
        }

        case 0370: case 0371: case 0372: case 0373:
        case 0374: case 0375: case 0376: case 0377: { // SOS - Subtract 1, Skip
            int ea = effectiveAddress(w);
            long val = sub(memory.read(ea), 1);
            memory.write(ea, val);
            if (ac != 0) AC[ac] = val;
            if (testJumpCondition(op - 0370, val)) PC = (PC + 1) & 0x3FFFF;
            break;
        }

        // =====================================================================
        // Boolean operations
        // =====================================================================

        case 0400: case 0401: case 0402: case 0403: // SETZ
        case 0404: case 0405: case 0406: case 0407: // AND
        case 0410: case 0411: case 0412: case 0413: // ANDCA
        case 0414: case 0415: case 0416: case 0417: // SETM
        case 0420: case 0421: case 0422: case 0423: // ANDCM
        case 0424: case 0425: case 0426: case 0427: // SETA
        case 0430: case 0431: case 0432: case 0433: // XOR
        case 0434: case 0435: case 0436: case 0437: // OR
        case 0440: case 0441: case 0442: case 0443: // ANDCB
        case 0444: case 0445: case 0446: case 0447: // EQV
        case 0450: case 0451: case 0452: case 0453: // SETCA
        case 0454: case 0455: case 0456: case 0457: // ORCA
        case 0460: case 0461: case 0462: case 0463: // SETCM
        case 0464: case 0465: case 0466: case 0467: // ORCM
        case 0470: case 0471: case 0472: case 0473: // ORCB
        case 0474: case 0475: case 0476: case 0477: // SETO
            boolOp(op, ac, w);
            break;

        // =====================================================================
        // Half-word instructions
        // =====================================================================

        case 0500: case 0501: case 0502: case 0503:
        case 0504: case 0505: case 0506: case 0507:
        case 0510: case 0511: case 0512: case 0513:
        case 0514: case 0515: case 0516: case 0517:
        case 0520: case 0521: case 0522: case 0523:
        case 0524: case 0525: case 0526: case 0527:
        case 0530: case 0531: case 0532: case 0533:
        case 0534: case 0535: case 0536: case 0537:
        case 0540: case 0541: case 0542: case 0543:
        case 0544: case 0545: case 0546: case 0547:
        case 0550: case 0551: case 0552: case 0553:
        case 0554: case 0555: case 0556: case 0557:
        case 0560: case 0561: case 0562: case 0563:
        case 0564: case 0565: case 0566: case 0567:
        case 0570: case 0571: case 0572: case 0573:
        case 0574: case 0575: case 0576: case 0577:
            halfWordOp(op, ac, w);
            break;

        // =====================================================================
        // Test and modify instructions (TDN, TSN, TDZ, TSZ, etc.)
        // =====================================================================

        case 0600: case 0601: case 0602: case 0603:
        case 0604: case 0605: case 0606: case 0607:
        case 0610: case 0611: case 0612: case 0613:
        case 0614: case 0615: case 0616: case 0617:
        case 0620: case 0621: case 0622: case 0623:
        case 0624: case 0625: case 0626: case 0627:
        case 0630: case 0631: case 0632: case 0633:
        case 0634: case 0635: case 0636: case 0637:
        case 0640: case 0641: case 0642: case 0643:
        case 0644: case 0645: case 0646: case 0647:
        case 0650: case 0651: case 0652: case 0653:
        case 0654: case 0655: case 0656: case 0657:
        case 0660: case 0661: case 0662: case 0663:
        case 0664: case 0665: case 0666: case 0667:
        case 0670: case 0671: case 0672: case 0673:
        case 0674: case 0675: case 0676: case 0677:
            testOp(op, ac, w);
            break;

        // =====================================================================
        // I/O and control instructions
        // =====================================================================

        case 0700: case 0701: case 0702: case 0703:
        case 0704: case 0705: case 0706: case 0707:
        case 0710: case 0711: case 0712: case 0713:
        case 0714: case 0715: case 0716: case 0717:
        case 0720: case 0721: case 0722: case 0723:
        case 0724: case 0725: case 0726: case 0727:
        case 0730: case 0731: case 0732: case 0733:
        case 0734: case 0735: case 0736: case 0737:
        case 0740: case 0741: case 0742: case 0743:
        case 0744: case 0745: case 0746: case 0747:
        case 0750: case 0751: case 0752: case 0753:
        case 0754: case 0755: case 0756: case 0757:
        case 0760: case 0761: case 0762: case 0763:
        case 0764: case 0765: case 0766: case 0767:
        case 0770: case 0771: case 0772: case 0773:
        case 0774: case 0775: case 0776: case 0777:
            ioInstruction(op, ac, w);
            break;

        // =====================================================================
        // Jump family
        // =====================================================================

        case 0254: { // JRST - Jump and Restore
            int ea = effectiveAddress(w);
            if ((ac & 010) != 0) { // Restore flags
                FLAGS = memory.read(PC - 1); // simplified
            }
            PC = ea;
            break;
        }
        case 0255: { // JFCL - Jump on Flag and Clear
            int ea = effectiveAddress(w);
            long flagBits = (long)ac << 32;
            if ((FLAGS & flagBits) != 0) {
                FLAGS &= ~flagBits;
                PC = ea;
            }
            break;
        }
        case 0256: { // XCT - Execute
            int ea = effectiveAddress(w);
            long xctInstr = memory.read(ea);
            executeInstruction(xctInstr, ea);
            break;
        }
        case 0260: case 0261: case 0262: case 0263: { // PUSHJ / PUSH
            if (op == 0261) { // PUSH
                int ea = effectiveAddress(w);
                AC[ac] = add(AC[ac], 0x000001000001L); // increment both halves
                int dest = (int)(AC[ac] & HALF_MASK);
                memory.write(dest, memory.read(ea));
            } else if (op == 0260) { // PUSHJ
                int ea = effectiveAddress(w);
                AC[ac] = add(AC[ac], 0x000001000001L);
                int dest = (int)(AC[ac] & HALF_MASK);
                memory.write(dest, (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK));
                PC = ea;
            }
            break;
        }
        case 0262: case 0263: { // POP / POPJ
            if (op == 0262) { // POP
                int ea = effectiveAddress(w);
                int src = (int)(AC[ac] & HALF_MASK);
                memory.write(ea, memory.read(src));
                AC[ac] = sub(AC[ac], 0x000001000001L);
            } else { // POPJ
                int src = (int)(AC[ac] & HALF_MASK);
                long retWord = memory.read(src);
                AC[ac] = sub(AC[ac], 0x000001000001L);
                PC = (int)(retWord & HALF_MASK);
            }
            break;
        }
        case 0264: { // JSR - Jump to Subroutine
            int ea = effectiveAddress(w);
            memory.write(ea, (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK));
            PC = (ea + 1) & 0x3FFFF;
            break;
        }
        case 0265: { // JSP - Jump and Save PC
            int ea = effectiveAddress(w);
            AC[ac] = (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK);
            PC = ea;
            break;
        }
        case 0266: { // JSA - Jump and Save AC
            int ea = effectiveAddress(w);
            memory.write(ea, AC[ac]);
            AC[ac] = ((long)ea << 18) | (PC & HALF_MASK);
            PC = (ea + 1) & 0x3FFFF;
            break;
        }
        case 0267: { // JRA - Jump Restore AC
            int ea = effectiveAddress(w);
            AC[ac] = memory.read((int)((AC[ac] >> 18) & HALF_MASK));
            PC = ea;
            break;
        }

        // Unimplemented -> unimplemented user operation
        default:
            luuoTrap(op, ac, w);
        }
    }

    // =========================================================================
    // Helper instruction implementations
    // =========================================================================

    private long swap(long w) {
        long left  = (w >> 18) & HALF_MASK;
        long right = w & HALF_MASK;
        return (right << 18) | left;
    }

    private long imul(long a, long b) {
        // 36-bit integer multiply, result truncated to 36 bits
        long sa = toSigned36(a);
        long sb = toSigned36(b);
        long result = sa * sb;
        if (result > MAX_POS || result < -MAX_POS - 1) {
            FLAGS |= FLAG_OV;
        }
        return fromSigned36(result);
    }

    private long[] mul(long a, long b) {
        long sa = toSigned36(a);
        long sb = toSigned36(b);
        long result = sa * sb;
        // Split into two 36-bit words
        long hi = (result >> 35) & WORD_MASK;
        long lo = result & WORD_MASK;
        return new long[]{hi, lo};
    }

    private void idiv(int ac, long a, long b) {
        if (isZero(b)) {
            FLAGS |= FLAG_OV;
            return;
        }
        long sa = toSigned36(a);
        long sb = toSigned36(b);
        long q = sa / sb;
        long r = sa % sb;
        AC[ac] = fromSigned36(q);
        if (ac + 1 < 16) AC[ac + 1] = fromSigned36(r);
    }

    private void divDouble(int ac, long hi, long lo, long divisor) {
        if (isZero(divisor)) {
            FLAGS |= FLAG_OV;
            return;
        }
        // 72-bit / 36-bit division
        long dividend = (toSigned36(hi) << 35) | (lo & WORD_MASK);
        long div = toSigned36(divisor);
        long q = dividend / div;
        long r = dividend % div;
        AC[ac] = fromSigned36(q);
        if (ac + 1 < 16) AC[ac + 1] = fromSigned36(r);
    }

    private long toSigned36(long w) {
        w &= WORD_MASK;
        if ((w & SIGN_BIT) != 0) return w - (SIGN_BIT << 1);
        return w;
    }

    private long fromSigned36(long v) {
        return v & WORD_MASK;
    }

    private long ash(long v, int cnt) {
        long sign = v & SIGN_BIT;
        v &= ~SIGN_BIT;
        if (cnt > 0) {
            v = (v << cnt);
            // Check for overflow: any bits shifted past bit 1 differ from sign
        } else if (cnt < 0) {
            v = (v >> (-cnt));
        }
        return sign | (v & ~SIGN_BIT & WORD_MASK);
    }

    private long lsh(long v, int cnt) {
        if (cnt > 0) return (v << cnt) & WORD_MASK;
        if (cnt < 0) return (v >>> (-cnt)) & WORD_MASK;
        return v;
    }

    private long rot(long v, int cnt) {
        cnt = ((cnt % 36) + 36) % 36;
        return ((v << cnt) | (v >>> (36 - cnt))) & WORD_MASK;
    }

    private void ashc(int ac, int cnt) {
        long lo = (ac + 1 < 16) ? AC[ac + 1] : 0;
        long hi = AC[ac];
        long combined = (hi << 35) | lo;
        long sign = hi & SIGN_BIT;
        combined &= ~(SIGN_BIT << 35);
        if (cnt > 0) combined <<= cnt;
        else combined >>= (-cnt);
        AC[ac] = sign | ((combined >> 35) & ~SIGN_BIT & WORD_MASK);
        if (ac + 1 < 16) AC[ac + 1] = combined & WORD_MASK;
    }

    private void rotc(int ac, int cnt) {
        long lo = (ac + 1 < 16) ? AC[ac + 1] : 0;
        long hi = AC[ac];
        long combined = (hi << 36) | lo;
        cnt = ((cnt % 72) + 72) % 72;
        combined = ((combined << cnt) | (combined >>> (72 - cnt)));
        AC[ac] = (combined >>> 36) & WORD_MASK;
        if (ac + 1 < 16) AC[ac + 1] = combined & WORD_MASK;
    }

    private void lshc(int ac, int cnt) {
        long lo = (ac + 1 < 16) ? AC[ac + 1] : 0;
        long hi = AC[ac];
        long combined = (hi << 36) | lo;
        if (cnt > 0) combined <<= cnt;
        else combined >>>= (-cnt);
        AC[ac] = (combined >>> 36) & WORD_MASK;
        if (ac + 1 < 16) AC[ac + 1] = combined & WORD_MASK;
    }

    private void boolOp(int op, int ac, long w) {
        int ea = effectiveAddress(w);
        long mem = memory.read(ea);
        long a   = AC[ac];

        // Decode function (bits 5-6 of opcode)
        int func = (op >> 2) & 017;
        long result;
        switch (func) {
            case 0000: result = 0; break;         // SETZ
            case 0001: result = a & mem; break;   // AND
            case 0002: result = ~a & mem; break;  // ANDCA
            case 0003: result = mem; break;        // SETM
            case 0004: result = a & ~mem; break;  // ANDCM
            case 0005: result = a; break;          // SETA
            case 0006: result = a ^ mem; break;   // XOR
            case 0007: result = a | mem; break;   // OR
            case 0010: result = ~(a | mem); break;// ANDCB
            case 0011: result = ~(a ^ mem); break;// EQV
            case 0012: result = ~a; break;         // SETCA
            case 0013: result = ~a | mem; break;  // ORCA
            case 0014: result = ~mem; break;       // SETCM
            case 0015: result = a | ~mem; break;  // ORCM
            case 0016: result = ~(a & mem); break;// ORCB
            default:   result = WORD_MASK; break; // SETO
        }
        result &= WORD_MASK;

        // Destination (bits 0-1 of opcode)
        int dest = op & 3;
        switch (dest) {
            case 0: // AC
                AC[ac] = result; break;
            case 1: // Immediate (same as AC for most)
                AC[ac] = result; break;
            case 2: // Memory
                memory.write(ea, result); break;
            case 3: // Both
                AC[ac] = result;
                memory.write(ea, result); break;
        }
    }

    private void halfWordOp(int op, int ac, long w) {
        int ea = effectiveAddress(w);
        long src = memory.read(ea);
        long dst = AC[ac];

        boolean sourceLeft  = (op & 040) == 0;
        boolean destLeft    = (op & 020) == 0;
        boolean fillMode    = (op & 06) >> 1; // 0=preserve, 1=zero, 2=ones, 3=extend

        long half = sourceLeft ? (src >> 18) & HALF_MASK : src & HALF_MASK;

        // Fill
        long fill;
        int fm = (op & 06) >> 1;
        switch (fm) {
            case 0: fill = destLeft ? (dst >> 18) : (dst & HALF_MASK); break;
            case 1: fill = 0; break;
            case 2: fill = HALF_MASK; break;
            default: fill = (half & 0x20000) != 0 ? HALF_MASK : 0; break;
        }

        long result;
        if (destLeft) {
            result = (half << 18) | (fill);
        } else {
            result = (fill << 18) | half;
        }
        result &= WORD_MASK;

        int destType = op & 1;
        if (destType == 0) {
            AC[ac] = result;
        } else {
            if (ac != 0) AC[ac] = result;
            memory.write(ea, result);
        }
    }

    private void testOp(int op, int ac, long w) {
        int ea = effectiveAddress(w);
        long mask = memory.read(ea);
        long val  = AC[ac];

        // Source: 0=direct, 1=swapped
        boolean swapped = (op & 020) != 0;
        long testVal = swapped ? swap(mask) : mask;

        // Condition: 0=never, 1=equal, 2=notequal, 3=always
        int cond = (op >> 1) & 3;
        boolean skip = false;
        switch (cond) {
            case 0: break;                           // TDN/TSN - never skip
            case 1: skip = (val & testVal) == 0; break; // TDZN/TSZN
            case 2: skip = (val & testVal) != 0; break; // TDN/TSN (skip never)
            case 3: skip = true; break;              // TDA/TSA
        }

        // Modify: 0=none, 1=AND(complement), 2=XOR(ones), 3=OR(ones)
        int modify = ((op - 0600) >> 3) & 3;
        switch (modify) {
            case 0: break;
            case 1: AC[ac] = val & ~testVal & WORD_MASK; break;
            case 2: AC[ac] = val ^ testVal; break;
            case 3: AC[ac] = val | testVal; break;
        }

        if (skip) PC = (PC + 1) & 0x3FFFF;
    }

    private void caiCam(int op, int ac, long w) {
        int ea = effectiveAddress(w);
        long a = AC[ac];
        long b;
        boolean isImm = (op & 010) == 0;
        if (isImm) {
            b = ea & HALF_MASK;
        } else {
            b = memory.read(ea);
        }

        // Compare condition (bits 0-2 of opcode within family)
        int cond = op & 7;
        long diff = sub(a, b);
        boolean skip = testCondition(cond, diff);
        if (skip) PC = (PC + 1) & 0x3FFFF;
    }

    private void jumpCondition(int cond, int ac, int ea) {
        if (testJumpCondition(cond, AC[ac])) PC = ea;
    }

    private void skipCondition(int cond, int ac, long w) {
        int ea = effectiveAddress(w);
        long val = memory.read(ea);
        if (ac != 0) AC[ac] = val;
        if (testJumpCondition(cond, val)) PC = (PC + 1) & 0x3FFFF;
    }

    private boolean testJumpCondition(int cond, long val) {
        switch (cond) {
            case 0: return false;                    // never
            case 1: return isNegative(val);          // L (less than 0)
            case 2: return isNegative(val)||isZero(val); // LE
            case 3: return isZero(val);              // E (equal zero)
            case 4: return !isZero(val);             // NE
            case 5: return !isNegative(val)&&!isZero(val); // GE
            case 6: return !isNegative(val);         // G (greater or equal 0)
            case 7: return true;                     // always
            default: return false;
        }
    }

    private boolean testCondition(int cond, long diff) {
        // Skip conditions for CAI/CAM: L, LE, E, GE, G, NE
        switch (cond & 7) {
            case 0: return false;
            case 1: return isNegative(diff);
            case 2: return isNegative(diff) || isZero(diff);
            case 3: return isZero(diff);
            case 4: return !isNegative(diff) && !isZero(diff);
            case 5: return !isNegative(diff);
            case 6: return !isZero(diff);
            case 7: return true;
            default: return false;
        }
    }

    private void luuoTrap(int op, int ac, long w) {
        // Store trap info and vector to interrupt handler (simplified)
        memory.write(040, (long)op << 27 | (long)ac << 23 | (w & 0x3FFFF));
        memory.write(041, (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK));
        PC = 042;
    }

    // =========================================================================
    // I/O instruction dispatch
    // =========================================================================

    private void ioInstruction(int op, int ac, long w) {
        // I/O instructions: bits 0-8 = device + function
        // Common TOPS-10/20 monitor calls via UUO
        int ea = effectiveAddress(w);
        int device = (op >> 3) & 077; // bits 3-8
        int func   = op & 07;          // bits 0-2

        // Handle common console I/O devices
        switch (device) {
            case 0: // CTY (console TTY)
                handleConsoleIO(func, ac, ea);
                break;
            case 01: // PTR (paper tape reader)
            case 02: // PTP (paper tape punch)
            default:
                // Silently ignore unknown I/O for now
                break;
        }
    }

    private void handleConsoleIO(int func, int ac, int ea) {
        switch (func) {
            case 0: // CONO - Condition Out (set status)
                break;
            case 1: // CONI - Condition In (read status)
                AC[ac] = 0xC0L; // ready bits set
                break;
            case 2: // DATAO - Output data
                if (ioHandler != null) {
                    long data = AC[ac];
                    // Output low 7 bits as ASCII
                    char c = (char)(data & 0x7F);
                    if (c != 0) ioHandler.writeChar(c);
                }
                break;
            case 3: // DATAI - Input data
                if (ioHandler != null) {
                    int ch = ioHandler.readChar();
                    if (ch >= 0) {
                        AC[ac] = ch & 0x7F;
                    } else {
                        AC[ac] = 0; // no input available
                    }
                }
                break;
        }
    }

    // =========================================================================
    // Debug / Inspection API
    // =========================================================================

    public long[] getACs() { return AC.clone(); }

    public String dumpRegisters() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("PC: %06o  FLAGS: %012o\n", PC, FLAGS));
        sb.append(String.format("Insn: #%d  LastOp: %03o\n\n", instructionCount, lastOpcode));
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("AC%02d: %012o  (%011d)\n", i, AC[i], toSigned36(AC[i])));
        }
        return sb.toString();
    }

    public String disassemble(int address) {
        long w = memory.read(address);
        return disassembleWord(address, w);
    }

    public static String disassembleWord(int addr, long w) {
        int op = opcode(w);
        int ac = acField(w);
        int i  = iField(w);
        int x  = xField(w);
        int y  = yField(w);

        String mnemonic = getMnemonic(op);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%06o: %012o  %-8s", addr, w, mnemonic));
        if (ac != 0) sb.append(String.format("%d,", ac));
        if (i != 0) sb.append("@");
        sb.append(String.format("%06o", y));
        if (x != 0) sb.append(String.format("(%d)", x));
        return sb.toString();
    }

    public static String getMnemonic(int op) {
        String[] mnemonics = new String[512];
        // Fill known mnemonics
        mnemonics[0200]="MOVE";  mnemonics[0201]="MOVEI"; mnemonics[0202]="MOVEM"; mnemonics[0203]="MOVES";
        mnemonics[0204]="MOVS";  mnemonics[0205]="MOVSI"; mnemonics[0206]="MOVSM"; mnemonics[0207]="MOVSS";
        mnemonics[0210]="MOVN";  mnemonics[0211]="MOVNI"; mnemonics[0212]="MOVNM"; mnemonics[0213]="MOVNS";
        mnemonics[0214]="MOVM";  mnemonics[0215]="MOVMI"; mnemonics[0216]="MOVMM"; mnemonics[0217]="MOVMS";
        mnemonics[0220]="IMUL";  mnemonics[0221]="IMULI"; mnemonics[0222]="IMULM"; mnemonics[0223]="IMULB";
        mnemonics[0224]="MUL";   mnemonics[0225]="MULI";  mnemonics[0226]="MULM";  mnemonics[0227]="MULB";
        mnemonics[0230]="IDIV";  mnemonics[0231]="IDIVI"; mnemonics[0232]="IDIVM"; mnemonics[0233]="IDIVB";
        mnemonics[0234]="DIV";   mnemonics[0235]="DIVI";  mnemonics[0236]="DIVM";  mnemonics[0237]="DIVB";
        mnemonics[0240]="ASH";   mnemonics[0241]="ROT";   mnemonics[0242]="LSH";   mnemonics[0243]="JFFO";
        mnemonics[0244]="ASHC";  mnemonics[0245]="ROTC";  mnemonics[0246]="LSHC";
        mnemonics[0254]="JRST";  mnemonics[0255]="JFCL";  mnemonics[0256]="XCT";
        mnemonics[0260]="PUSHJ"; mnemonics[0261]="PUSH";  mnemonics[0262]="POP";   mnemonics[0263]="POPJ";
        mnemonics[0264]="JSR";   mnemonics[0265]="JSP";   mnemonics[0266]="JSA";   mnemonics[0267]="JRA";
        mnemonics[0270]="ADD";   mnemonics[0271]="ADDI";  mnemonics[0272]="ADDM";  mnemonics[0273]="ADDB";
        mnemonics[0274]="SUB";   mnemonics[0275]="SUBI";  mnemonics[0276]="SUBM";  mnemonics[0277]="SUBB";
        mnemonics[0300]="CAIL";  mnemonics[0301]="CAIE";  mnemonics[0302]="CAIL";  mnemonics[0303]="CAILE";
        mnemonics[0304]="CAIA";  mnemonics[0305]="CAIGE"; mnemonics[0306]="CAIG";  mnemonics[0307]="CAIN";
        mnemonics[0320]="JUMP";  mnemonics[0321]="JUMPL"; mnemonics[0322]="JUMPLE";mnemonics[0323]="JUMPE";
        mnemonics[0324]="JUMPN"; mnemonics[0325]="JUMPGE";mnemonics[0326]="JUMPG"; mnemonics[0327]="JUMPA";
        mnemonics[0330]="SKIP";  mnemonics[0331]="SKIPL"; mnemonics[0332]="SKIPLE";mnemonics[0333]="SKIPE";
        mnemonics[0334]="SKIPN"; mnemonics[0335]="SKIPGE";mnemonics[0336]="SKIPG"; mnemonics[0337]="SKIPA";
        mnemonics[0400]="SETZ";  mnemonics[0404]="AND";   mnemonics[0410]="ANDCA"; mnemonics[0414]="SETM";
        mnemonics[0420]="ANDCM"; mnemonics[0424]="SETA";  mnemonics[0430]="XOR";   mnemonics[0434]="OR";
        mnemonics[0440]="ANDCB"; mnemonics[0444]="EQV";   mnemonics[0450]="SETCA"; mnemonics[0454]="ORCA";
        mnemonics[0460]="SETCM"; mnemonics[0464]="ORCM";  mnemonics[0470]="ORCB";  mnemonics[0474]="SETO";
        String m = (op < 512 && mnemonics[op] != null) ? mnemonics[op] : String.format("???(%03o)", op);
        return m;
    }

    public long[] getOpStats() { return opStats.clone(); }
}
