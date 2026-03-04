package com.pdp10.emulator.cpu;

import com.pdp10.emulator.memory.PDP10Memory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * PDP-10 CPU Emulator -- KA10/KI10 instruction set
 *
 * 36-bit word, 18-bit addressing, 256K words, 16 accumulators.
 * Ones-complement arithmetic.
 */
public class PDP10CPU {

    // -- Constants -------------------------------------------------------------
    // NOTE: Java forbids underscore immediately after 0x prefix.
    // All hex literals are written without leading underscore.
    public static final long WORD_MASK       = 0xFFFFFFFFFL;   // 36 bits
    public static final long HALF_MASK       = 0x3FFFFL;        // 18 bits
    public static final long SIGN_BIT        = 0x800000000L;    // Bit 35 (MSB)
    public static final long MAX_POS         = 0x3FFFFFFFFL;
    public static final long ONES_NEG_ZERO   = 0xFFFFFFFFFL;   // -0 in ones-complement
    public static final long LEFT_HALF_MASK  = 0xFFFFC0000L;   // bits 35-18
    public static final long RIGHT_HALF_MASK = 0x000003FFFFL;  // bits 17-0

    // -- Flags -----------------------------------------------------------------
    public static final long FLAG_OV   = (1L << 35);
    public static final long FLAG_CY0  = (1L << 34);
    public static final long FLAG_CY1  = (1L << 33);
    public static final long FLAG_FPU  = (1L << 32);
    public static final long FLAG_TR1  = (1L << 31);
    public static final long FLAG_TR2  = (1L << 30);
    public static final long FLAG_USER = (1L << 27);

    // -- Registers -------------------------------------------------------------
    private final long[] AC = new long[16];
    private int  PC    = 0200;   // conventional start
    private long FLAGS = 0;

    // -- Subsystems ------------------------------------------------------------
    private final PDP10Memory memory;
    private final IOHandler   io;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean halted  = new AtomicBoolean(false);
    private volatile long instructionCount = 0;
    private volatile int  lastOpcode = 0;
    private String haltReason = "";

    private final long[] opStats = new long[512];

    // -- I/O callback ----------------------------------------------------------
    public interface IOHandler {
        void writeChar(char c);
        int  readChar();          // -1 if nothing available
        void halt(String reason);
        void interrupt(int level);
    }

    public PDP10CPU(PDP10Memory memory, IOHandler io) {
        this.memory = memory;
        this.io     = io;
        reset();
    }

    // =========================================================================
    // Public control API
    // =========================================================================

    public void reset() {
        for (int i = 0; i < 16; i++) AC[i] = 0;
        PC    = 0200;
        FLAGS = 0;
        halted.set(false);
        running.set(false);
        instructionCount = 0;
    }

    public void setPC(int address) { PC = address & 0x3FFFF; }
    public int  getPC()            { return PC; }
    public long getFlags()         { return FLAGS; }
    public boolean isHalted()      { return halted.get(); }
    public boolean isRunning()     { return running.get(); }
    public long getInstructionCount() { return instructionCount; }
    public String getHaltReason()  { return haltReason; }

    public long[] getACs() { return AC.clone(); }

    /**
     * PDP-10 ARCHITECTURE: AC[0-15] ARE memory locations 0-15.
     * All memory reads/writes must go through these helpers.
     */
    private long readMem(int addr) {
        addr &= 0x3FFFF;
        if (addr < 16) return AC[addr];
        return memory.read(addr);
    }

    private void writeMem(int addr, long val) {
        addr &= 0x3FFFF;
        val &= WORD_MASK;
        if (addr < 16) AC[addr] = val;
        else memory.write(addr, val);
    }

    private static final long MAX_INSTRUCTIONS = 10_000_000L;

    public void run() {
        running.set(true);
        halted.set(false);
        long startCount = instructionCount;
        while (running.get() && !halted.get()) {
            step();
            if ((instructionCount - startCount) >= MAX_INSTRUCTIONS) {
                halt("Instruction limit reached");
                break;
            }
            if ((instructionCount & 0x3FFFL) == 0) Thread.yield();
        }
        running.set(false);
    }

    public void stop() {
        running.set(false);
    }

    public void step() {
        if (halted.get()) return;
        try {
            long instr = memory.read(PC);
            int savedPC = PC;
            PC = (PC + 1) & 0x3FFFF;
            execute(instr);
            instructionCount++;
        } catch (Exception e) {
            halt("CPU exception: " + e.getMessage());
        }
    }

    private void halt(String reason) {
        halted.set(true);
        running.set(false);
        haltReason = reason;
        if (io != null) io.halt(reason);
    }

    // =========================================================================
    // Instruction field extraction
    // =========================================================================

    private static int opcode(long w) { return (int)((w >> 27) & 0x1FF); }
    private static int acF(long w)    { return (int)((w >> 23) & 0xF);   }
    private static int iF(long w)     { return (int)((w >> 22) & 0x1);   }
    private static int xF(long w)     { return (int)((w >> 18) & 0xF);   }
    private static int yF(long w)     { return (int)(w & 0x3FFFF);       }

    /** Compute effective address, following indirection chain. */
    private int ea(long instr) {
        int y = yF(instr);
        int x = xF(instr);
        int i = iF(instr);
        int addr = y;
        if (x != 0) addr = (addr + (int)(AC[x] & HALF_MASK)) & 0x3FFFF;
        int depth = 0;
        while (i != 0 && depth++ < 64) {
            long ind = readMem(addr);
            i    = iF(ind);
            x    = xF(ind);
            addr = yF(ind);
            if (x != 0) addr = (addr + (int)(AC[x] & HALF_MASK)) & 0x3FFFF;
        }
        return addr;
    }

    // =========================================================================
    // Arithmetic helpers
    // =========================================================================

    private long add(long a, long b) {
        // Ones-complement addition with end-around carry
        long sum = (a & WORD_MASK) + (b & WORD_MASK);
        if ((sum & (WORD_MASK + 1)) != 0) {  // carry out of bit 35
            sum = (sum & WORD_MASK) + 1;      // end-around carry
        }
        long result = sum & WORD_MASK;
        boolean aNeg = (a & SIGN_BIT) != 0;
        boolean bNeg = (b & SIGN_BIT) != 0;
        boolean rNeg = (result & SIGN_BIT) != 0;
        if ((!aNeg && !bNeg && rNeg) || (aNeg && bNeg && !rNeg)) FLAGS |= FLAG_OV;
        return result;
    }

    private long neg(long a)         { return (~a) & WORD_MASK; }
    private long sub(long a, long b) { return add(a, neg(b)); }

    private boolean isNeg(long w)  { return (w & SIGN_BIT) != 0; }
    private boolean isZero(long w) { return w == 0 || w == ONES_NEG_ZERO; }

    private long signed(long w) {
        w &= WORD_MASK;
        return isNeg(w) ? w - (SIGN_BIT << 1) : w;
    }

    private long swap(long w) {
        long l = (w >> 18) & HALF_MASK;
        long r = w & HALF_MASK;
        return ((r << 18) | l) & WORD_MASK;
    }

    // --- Shifts ---------------------------------------------------------------

    private long ash(long v, int cnt) {
        long sign = v & SIGN_BIT;
        long mag  = v & ~SIGN_BIT & WORD_MASK;
        if (cnt > 0)  mag = (mag << cnt) & ~SIGN_BIT & WORD_MASK;
        else if (cnt < 0) mag >>= -cnt;
        return sign | mag;
    }

    private long lsh(long v, int cnt) {
        if (cnt > 0) return (v << cnt)   & WORD_MASK;
        if (cnt < 0) return (v >>> -cnt) & WORD_MASK;
        return v;
    }

    private long rot(long v, int cnt) {
        cnt = ((cnt % 36) + 36) % 36;
        return ((v << cnt) | (v >>> (36 - cnt))) & WORD_MASK;
    }

    private int parseCnt(int ea) {
        int c = ea & 0x1FF;
        return c >= 0x100 ? c - 0x200 : c;
    }

    // --- Integer multiply / divide --------------------------------------------

    private long imul(long a, long b) {
        long r = (signed(a) * signed(b));
        if (r > 0x3FFFFFFFFL || r < -0x400000000L) FLAGS |= FLAG_OV;
        return r & WORD_MASK;
    }

    private void idiv(int ac, long a, long b) {
        if (isZero(b)) { FLAGS |= FLAG_OV; return; }
        long q = signed(a) / signed(b);
        long r = signed(a) % signed(b);
        AC[ac] = q & WORD_MASK;
        if (ac + 1 < 16) AC[ac + 1] = r & WORD_MASK;
    }

    // --- Jump condition test --------------------------------------------------

    private boolean testCond(int cond, long val) {
        switch (cond & 7) {
            case 0: return false;
            case 1: return isNeg(val);
            case 2: return isNeg(val) || isZero(val);
            case 3: return isZero(val);
            case 4: return !isZero(val);
            case 5: return !isNeg(val) || isZero(val);
            case 6: return !isNeg(val) && !isZero(val);
            case 7: return true;
            default: return false;
        }
    }

    // =========================================================================
    // Main dispatcher
    // =========================================================================

    private void execute(long w) {
        int op = opcode(w);
        int ac = acF(w);
        lastOpcode = op;
        opStats[op]++;

        // Decode address only once
        final int addr;

        switch (op) {

        // -- Monitor calls & LUUO 000-077 -------------------------------------
        case 0001:                                                          // HALT
            halt("HALT at " + String.format("%06o", PC - 1));
            break;
        case 0002:                                                          // OUTCHR
            if (io != null && AC[ac] != 0) io.writeChar((char)(AC[ac] & 0x7F));
            break;
        case 0003:                                                          // INCHR
            AC[ac] = (io != null) ? Math.max(0, io.readChar()) : 0;
            break;
        default:
            // LUUO 000-077: store instruction and dispatch
            if (op == 0) {
                // Opcode 0 = uninitialized memory; treat as HALT to avoid infinite loop
                halt("Uninitialized memory at " + String.format("%06o", PC - 1));
            } else {
                memory.write(040, w);
                memory.write(041, (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK));
                PC = 042;
            }
            break;

        // -- MOVE group 200-217 ------------------------------------------------
        case 0200: AC[ac] = readMem(ea(w));                    break; // MOVE
        case 0201: AC[ac] = ea(w) & HALF_MASK;                     break; // MOVEI
        case 0202: writeMem(ea(w), AC[ac]);                    break; // MOVEM
        case 0203: { long v=readMem(ea(w)); if(ac!=0)AC[ac]=v; writeMem(ea(w),v); break; } // MOVES
        case 0204: AC[ac] = swap(readMem(ea(w)));              break; // MOVS
        case 0205: AC[ac] = ((long)(ea(w)&HALF_MASK))<<18;        break; // MOVSI
        case 0206: writeMem(ea(w), swap(AC[ac]));              break; // MOVSM
        case 0207: { long v=swap(readMem(ea(w))); if(ac!=0)AC[ac]=v; writeMem(ea(w),v); break; } // MOVSS
        case 0210: AC[ac] = neg(readMem(ea(w)));               break; // MOVN
        case 0211: AC[ac] = neg(ea(w) & HALF_MASK);               break; // MOVNI
        case 0212: writeMem(ea(w), neg(AC[ac]));               break; // MOVNM
        case 0213: { long v=neg(readMem(ea(w))); if(ac!=0)AC[ac]=v; writeMem(ea(w),v); break; } // MOVNS
        case 0214: { long v=readMem(ea(w)); AC[ac]=isNeg(v)?neg(v):v; break; } // MOVM
        case 0215: { long v=ea(w)&HALF_MASK;   AC[ac]=isNeg(v)?neg(v):v; break; } // MOVMI
        case 0216: { long v=AC[ac]; writeMem(ea(w), isNeg(v)?neg(v):v); break; } // MOVMM
        case 0217: { long v=readMem(ea(w)); v=isNeg(v)?neg(v):v; if(ac!=0)AC[ac]=v; writeMem(ea(w),v); break; } // MOVMS

        // -- Integer arithmetic 220-237 ----------------------------------------
        case 0220: AC[ac] = imul(AC[ac], readMem(ea(w)));      break; // IMUL
        case 0221: AC[ac] = imul(AC[ac], ea(w)&HALF_MASK);         break; // IMULI
        case 0222: writeMem(ea(w), imul(AC[ac], readMem(ea(w)))); break; // IMULM
        case 0223: { long r=imul(AC[ac],readMem(ea(w))); AC[ac]=r; writeMem(ea(w),r); break; } // IMULB
        case 0230: idiv(ac, AC[ac], readMem(ea(w)));            break; // IDIV
        case 0231: idiv(ac, AC[ac], ea(w)&HALF_MASK);               break; // IDIVI

        // -- Shift group 240-247 -----------------------------------------------
        case 0240: AC[ac] = ash(AC[ac], parseCnt(ea(w)));           break; // ASH
        case 0241: AC[ac] = rot(AC[ac], parseCnt(ea(w)));           break; // ROT
        case 0242: AC[ac] = lsh(AC[ac], parseCnt(ea(w)));           break; // LSH
        case 0243: {                                                        // JFFO
            int e = ea(w);
            if (AC[ac] != 0) {
                int pos = 0; long tmp = AC[ac];
                while ((tmp & SIGN_BIT) == 0) { pos++; tmp <<= 1; }
                if (ac+1 < 16) AC[ac+1] = pos;
                PC = e;
            }
            break;
        }
        case 0244: {                                                        // ASHC
            int cnt = parseCnt(ea(w));
            long hi = AC[ac]; long lo = (ac+1<16)?AC[ac+1]:0;
            long sign = hi & SIGN_BIT;
            long combined = ((hi & ~SIGN_BIT) << 35) | lo;
            if (cnt > 0) combined <<= cnt; else combined >>= -cnt;
            AC[ac] = sign | ((combined >> 35) & ~SIGN_BIT & WORD_MASK);
            if (ac+1 < 16) AC[ac+1] = combined & WORD_MASK;
            break;
        }
        case 0245: {                                                        // ROTC
            int cnt = parseCnt(ea(w));
            long hi = AC[ac]; long lo = (ac+1<16)?AC[ac+1]:0;
            long c72 = (hi << 36) | lo;
            cnt = ((cnt % 72) + 72) % 72;
            c72 = (c72 << cnt) | (c72 >>> (72-cnt));
            AC[ac] = (c72 >>> 36) & WORD_MASK;
            if (ac+1 < 16) AC[ac+1] = c72 & WORD_MASK;
            break;
        }
        case 0246: {                                                        // LSHC
            int cnt = parseCnt(ea(w));
            long hi = AC[ac]; long lo = (ac+1<16)?AC[ac+1]:0;
            long c72 = (hi << 36) | lo;
            if (cnt > 0) c72 <<= cnt; else c72 >>>= -cnt;
            AC[ac] = (c72 >>> 36) & WORD_MASK;
            if (ac+1 < 16) AC[ac+1] = c72 & WORD_MASK;
            break;
        }

        // -- Jump/call group 254-267 -------------------------------------------
        case 0254: {                                                        // JRST
            int e = ea(w);
            if ((ac & 010) != 0) FLAGS = memory.read(PC-1) & LEFT_HALF_MASK;
            PC = e;
            break;
        }
        case 0255: {                                                        // JFCL
            int e = ea(w);
            long bits = (long)ac << 32;
            if ((FLAGS & bits) != 0) { FLAGS &= ~bits; PC = e; }
            break;
        }
        case 0256: execute(readMem(ea(w)));                    break;  // XCT
        case 0260: {                                                        // PUSHJ
            int e = ea(w);
            AC[ac] = add(AC[ac], 0x000001000001L);
            writeMem((int)(AC[ac] & HALF_MASK), (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK));
            PC = e;
            break;
        }
        case 0261: {                                                        // PUSH
            int e = ea(w);
            AC[ac] = add(AC[ac], 0x000001000001L);
            writeMem((int)(AC[ac] & HALF_MASK), readMem(e));
            break;
        }
        case 0262: {                                                        // POP
            int e = ea(w);
            writeMem(e, readMem((int)(AC[ac] & HALF_MASK)));
            AC[ac] = sub(AC[ac], 0x000001000001L);
            break;
        }
        case 0263: {                                                        // POPJ
            int src = (int)(AC[ac] & HALF_MASK);
            PC = (int)(readMem(src) & HALF_MASK);
            AC[ac] = sub(AC[ac], 0x000001000001L);
            break;
        }
        case 0264: {                                                        // JSR
            int e = ea(w);
            writeMem(e, (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK));
            PC = (e + 1) & 0x3FFFF;
            break;
        }
        case 0265: {                                                        // JSP
            int e = ea(w);
            AC[ac] = (FLAGS & LEFT_HALF_MASK) | (PC & HALF_MASK);
            PC = e;
            break;
        }
        case 0266: {                                                        // JSA
            int e = ea(w);
            writeMem(e, AC[ac]);
            AC[ac] = ((long)e << 18) | (PC & HALF_MASK);
            PC = (e + 1) & 0x3FFFF;
            break;
        }
        case 0267: {                                                        // JRA
            int e = ea(w);
            AC[ac] = readMem((int)((AC[ac] >> 18) & HALF_MASK));
            PC = e;
            break;
        }

        // -- ADD / SUB 270-277 -------------------------------------------------
        case 0270: AC[ac] = add(AC[ac], readMem(ea(w)));       break;  // ADD
        case 0271: AC[ac] = add(AC[ac], ea(w) & HALF_MASK);        break;  // ADDI
        case 0272: { int e=ea(w); writeMem(e, add(AC[ac], readMem(e))); break; } // ADDM
        case 0273: { int e=ea(w); long r=add(AC[ac],memory.read(e)); AC[ac]=r; memory.write(e,r); break; } // ADDB
        case 0274: AC[ac] = sub(AC[ac], readMem(ea(w)));       break;  // SUB
        case 0275: AC[ac] = sub(AC[ac], ea(w) & HALF_MASK);        break;  // SUBI
        case 0276: { int e=ea(w); writeMem(e, sub(readMem(e), AC[ac])); break; } // SUBM
        case 0277: { int e=ea(w); long r=sub(AC[ac],memory.read(e)); AC[ac]=r; memory.write(e,r); break; } // SUBB

        // -- Compare/Skip 300-337 ----------------------------------------------
        // CAI family (immediate compare, 300-307)
        case 0300: case 0301: case 0302: case 0303:
        case 0304: case 0305: case 0306: case 0307: {
            long diff = sub(AC[ac], ea(w) & HALF_MASK);
            if (testCond(op & 7, diff)) PC = (PC + 1) & 0x3FFFF;
            break;
        }
        // CAM family (memory compare, 310-317)
        case 0310: case 0311: case 0312: case 0313:
        case 0314: case 0315: case 0316: case 0317: {
            long diff = sub(AC[ac], readMem(ea(w)));
            if (testCond(op & 7, diff)) PC = (PC + 1) & 0x3FFFF;
            break;
        }
        // JUMP family (320-327) -- jump if AC matches condition
        case 0320: case 0321: case 0322: case 0323:
        case 0324: case 0325: case 0326: case 0327: {
            int e = ea(w);
            if (testCond(op & 7, AC[ac])) PC = e;
            break;
        }
        // SKIP family (330-337)
        case 0330: case 0331: case 0332: case 0333:
        case 0334: case 0335: case 0336: case 0337: {
            int e = ea(w); long val = memory.read(e);
            if (ac != 0) AC[ac] = val;
            if (testCond(op & 7, val)) PC = (PC + 1) & 0x3FFFF;
            break;
        }

        // -- AOJ/AOS/SOJ/SOS 340-377 ------------------------------------------
        case 0340: case 0341: case 0342: case 0343:
        case 0344: case 0345: case 0346: case 0347: {                // AOJ
            int e = ea(w); AC[ac] = add(AC[ac], 1);
            if (testCond(op & 7, AC[ac])) PC = e;
            break;
        }
        case 0350: case 0351: case 0352: case 0353:
        case 0354: case 0355: case 0356: case 0357: {                // AOS
            int e = ea(w); long v = add(memory.read(e), 1);
            writeMem(e, v); if (ac != 0) AC[ac] = v;
            if (testCond(op & 7, v)) PC = (PC + 1) & 0x3FFFF;
            break;
        }
        case 0360: case 0361: case 0362: case 0363:
        case 0364: case 0365: case 0366: case 0367: {                // SOJ
            int e = ea(w); AC[ac] = sub(AC[ac], 1);
            if (testCond(op & 7, AC[ac])) PC = e;
            break;
        }
        case 0370: case 0371: case 0372: case 0373:
        case 0374: case 0375: case 0376: case 0377: {                // SOS
            int e = ea(w); long v = sub(memory.read(e), 1);
            writeMem(e, v); if (ac != 0) AC[ac] = v;
            if (testCond(op & 7, v)) PC = (PC + 1) & 0x3FFFF;
            break;
        }

        // -- Boolean 400-477 ---------------------------------------------------
        case 0400: case 0401: case 0402: case 0403:  // SETZ
        case 0404: case 0405: case 0406: case 0407:  // AND
        case 0410: case 0411: case 0412: case 0413:  // ANDCA
        case 0414: case 0415: case 0416: case 0417:  // SETM
        case 0420: case 0421: case 0422: case 0423:  // ANDCM
        case 0424: case 0425: case 0426: case 0427:  // SETA
        case 0430: case 0431: case 0432: case 0433:  // XOR
        case 0434: case 0435: case 0436: case 0437:  // OR
        case 0440: case 0441: case 0442: case 0443:  // ANDCB
        case 0444: case 0445: case 0446: case 0447:  // EQV
        case 0450: case 0451: case 0452: case 0453:  // SETCA
        case 0454: case 0455: case 0456: case 0457:  // ORCA
        case 0460: case 0461: case 0462: case 0463:  // SETCM
        case 0464: case 0465: case 0466: case 0467:  // ORCM
        case 0470: case 0471: case 0472: case 0473:  // ORCB
        case 0474: case 0475: case 0476: case 0477:  // SETO
            boolOp(op, ac, w); break;

        // -- Half-word 500-577 -------------------------------------------------
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
            halfOp(op, ac, w); break;

        // -- Test 600-677 ------------------------------------------------------
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
            testOp(op, ac, w); break;

        // -- I/O 700-777 -------------------------------------------------------
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
            ioOp(op, ac, w); break;

        } // end switch
    }

    // =========================================================================
    // Boolean operations (400-477)
    // =========================================================================

    private void boolOp(int op, int ac, long w) {
        int  e   = ea(w);
        long mem = readMem(e);
        long a   = AC[ac];
        int  fn  = (op >> 2) & 0xF;
        long result;
        switch (fn) {
            case 0:  result = 0;               break; // SETZ
            case 1:  result = a & mem;         break; // AND
            case 2:  result = ~a & mem;        break; // ANDCA
            case 3:  result = mem;             break; // SETM
            case 4:  result = a & ~mem;        break; // ANDCM
            case 5:  result = a;               break; // SETA
            case 6:  result = a ^ mem;         break; // XOR
            case 7:  result = a | mem;         break; // OR
            case 8:  result = ~(a | mem);      break; // ANDCB
            case 9:  result = ~(a ^ mem);      break; // EQV
            case 10: result = ~a;              break; // SETCA
            case 11: result = ~a | mem;        break; // ORCA
            case 12: result = ~mem;            break; // SETCM
            case 13: result = a | ~mem;        break; // ORCM
            case 14: result = ~(a & mem);      break; // ORCB
            default: result = WORD_MASK;       break; // SETO
        }
        result &= WORD_MASK;
        int dest = op & 3;
        switch (dest) {
            case 0: case 1: AC[ac] = result;   break;
            case 2: writeMem(e, result);   break;
            case 3: AC[ac] = result; writeMem(e, result); break;
        }
    }

    // =========================================================================
    // Half-word operations (500-577)
    // =========================================================================

    private void halfOp(int op, int ac, long w) {
        int  e   = ea(w);
        long src = readMem(e);
        long dst = AC[ac];
        // Bit 8 of opcode: 0=R-to-L, 1=L-to-R (relative to source half)
        // sub-family determined by bits 3-5
        int sub = (op - 0500) >> 2;          // 0-15
        boolean srcLeft = (sub & 4) == 0;    // 0=R src, 4=L src
        boolean dstLeft = (sub & 8) == 0;    // 0=R dst, 8=L dst

        long half = srcLeft ? (src >> 18) & HALF_MASK : src & HALF_MASK;

        // Fill mode from bits 1-2
        int fm = (op & 6) >> 1;
        long fill;
        switch (fm) {
            case 0: fill = dstLeft ? (dst >> 18) & HALF_MASK : dst & HALF_MASK; break;
            case 1: fill = 0;       break;
            case 2: fill = HALF_MASK; break;
            default: fill = (half & 0x20000L) != 0 ? HALF_MASK : 0; break;
        }

        long result = dstLeft
            ? ((half << 18) | fill) & WORD_MASK
            : ((fill << 18) | half) & WORD_MASK;

        if ((op & 1) == 0) {
            AC[ac] = result;
        } else {
            if (ac != 0) AC[ac] = result;
            writeMem(e, result);
        }
    }

    // =========================================================================
    // Test operations (600-677)
    // =========================================================================

    private void testOp(int op, int ac, long w) {
        int  e    = ea(w);
        long mask = readMem(e);
        long val  = AC[ac];

        boolean swapped = (op & 020) != 0;
        long testMask   = swapped ? swap(mask) : mask;

        // Modify field (bits 3-4)
        int modify = ((op - 0600) >> 3) & 3;
        switch (modify) {
            case 1: AC[ac] = (val & ~testMask) & WORD_MASK; break;
            case 2: AC[ac] = (val ^ testMask)  & WORD_MASK; break;
            case 3: AC[ac] = (val | testMask)  & WORD_MASK; break;
            default: break;
        }

        // Skip condition (bits 1-2)
        int cond = (op >> 1) & 3;
        boolean skip = false;
        switch (cond) {
            case 0: break;
            case 1: skip = (val & testMask) == 0;  break;
            case 2: skip = (val & testMask) != 0;  break;
            case 3: skip = true;                    break;
        }
        if (skip) PC = (PC + 1) & 0x3FFFF;
    }

    // =========================================================================
    // I/O operations (700-777) -- minimal CTY console emulation
    // =========================================================================

    private void ioOp(int op, int ac, long w) {
        // PDP-10 I/O format: opcode bits 5-3 = device[2:0], bits 2-0 = function
        // device[6:3] comes from AC field; function codes:
        // 0=BLKI, 1=DATAI, 2=BLKO, 3=DATAO, 4=CONO, 5=CONI, 6=CONSZ, 7=CONSO
        int devLow  = (op >> 3) & 07;
        int devHigh = ac & 0xF;
        int device  = (devHigh << 3) | devLow;
        int func    = op & 07;

        // Device 0 = APR/console (CTY) in our simple emulator
        if (device == 0 || device == 0120) {
            switch (func) {
                case 5: AC[ac] = 0300L; break;          // CONI -- status: ready+done
                case 3:                                  // DATAO -- output char
                    if (io != null) {
                        char c = (char)(AC[ac] & 0x7F);
                        if (c != 0) io.writeChar(c);
                    }
                    break;
                case 1:                                  // DATAI -- input char
                    if (io != null) {
                        int ch = io.readChar();
                        AC[ac] = ch >= 0 ? ch & 0x7FL : 0;
                    }
                    break;
                default: break;
            }
        }
    }

    // =========================================================================
    // Disassembler
    // =========================================================================

    public String disassemble(int address) {
        return disassembleWord(address, memory.read(address));
    }

    public static String disassembleWord(int addr, long w) {
        int op = (int)((w >> 27) & 0x1FF);
        int ac = (int)((w >> 23) & 0xF);
        int i  = (int)((w >> 22) & 0x1);
        int x  = (int)((w >> 18) & 0xF);
        int y  = (int)(w & 0x3FFFF);
        String mn = getMnemonic(op);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%06o: %012o  %-8s", addr, w, mn));
        if (ac != 0) sb.append(String.format("%d,", ac));
        if (i  != 0) sb.append("@");
        sb.append(String.format("%06o", y));
        if (x  != 0) sb.append(String.format("(%d)", x));
        return sb.toString();
    }

    public static String getMnemonic(int op) {
        switch (op) {
            case 0001: return "HALT";
            case 0002: return "OUTCHR";
            case 0003: return "INCHR";
            case 0200: return "MOVE";  case 0201: return "MOVEI"; case 0202: return "MOVEM"; case 0203: return "MOVES";
            case 0204: return "MOVS";  case 0205: return "MOVSI"; case 0206: return "MOVSM"; case 0207: return "MOVSS";
            case 0210: return "MOVN";  case 0211: return "MOVNI"; case 0212: return "MOVNM"; case 0213: return "MOVNS";
            case 0214: return "MOVM";  case 0215: return "MOVMI"; case 0216: return "MOVMM"; case 0217: return "MOVMS";
            case 0220: return "IMUL";  case 0221: return "IMULI"; case 0230: return "IDIV";  case 0231: return "IDIVI";
            case 0240: return "ASH";   case 0241: return "ROT";   case 0242: return "LSH";   case 0243: return "JFFO";
            case 0244: return "ASHC";  case 0245: return "ROTC";  case 0246: return "LSHC";
            case 0254: return "JRST";  case 0255: return "JFCL";  case 0256: return "XCT";
            case 0260: return "PUSHJ"; case 0261: return "PUSH";  case 0262: return "POP";   case 0263: return "POPJ";
            case 0264: return "JSR";   case 0265: return "JSP";   case 0266: return "JSA";   case 0267: return "JRA";
            case 0270: return "ADD";   case 0271: return "ADDI";  case 0272: return "ADDM";  case 0273: return "ADDB";
            case 0274: return "SUB";   case 0275: return "SUBI";  case 0276: return "SUBM";  case 0277: return "SUBB";
            case 0300: return "CAIL";  case 0301: return "CAIE";  case 0302: return "CAILE"; case 0303: return "CAIA";
            case 0304: return "CAIGE"; case 0305: return "CAIG";  case 0306: return "CAIN";  case 0307: return "CAIM";
            case 0310: return "CAML";  case 0311: return "CAME";  case 0312: return "CAMLE"; case 0313: return "CAMA";
            case 0314: return "CAMGE"; case 0315: return "CAMG";  case 0316: return "CAMN";  case 0317: return "CAM";
            case 0320: return "JUMP";  case 0321: return "JUMPL"; case 0322: return "JUMPLE";case 0323: return "JUMPE";
            case 0324: return "JUMPN"; case 0325: return "JUMPGE";case 0326: return "JUMPG"; case 0327: return "JUMPA";
            case 0330: return "SKIP";  case 0331: return "SKIPL"; case 0332: return "SKIPLE";case 0333: return "SKIPE";
            case 0334: return "SKIPN"; case 0335: return "SKIPGE";case 0336: return "SKIPG"; case 0337: return "SKIPA";
            case 0340: return "AOJ";   case 0341: return "AOJL";  case 0346: return "AOJG";  case 0347: return "AOJA";
            case 0350: return "AOS";   case 0357: return "AOSA";
            case 0360: return "SOJ";   case 0361: return "SOJL";  case 0366: return "SOJG";  case 0367: return "SOJA";
            case 0370: return "SOS";   case 0377: return "SOSA";
            case 0400: return "SETZ";  case 0404: return "AND";   case 0410: return "ANDCA"; case 0414: return "SETM";
            case 0420: return "ANDCM"; case 0424: return "SETA";  case 0430: return "XOR";   case 0434: return "OR";
            case 0440: return "ANDCB"; case 0444: return "EQV";   case 0450: return "SETCA"; case 0454: return "ORCA";
            case 0460: return "SETCM"; case 0464: return "ORCM";  case 0470: return "ORCB";  case 0474: return "SETO";
            case 0500: return "HLL";   case 0504: return "HRL";   case 0540: return "HRR";   case 0544: return "HLR";
            default:   return String.format("%03o", op);
        }
    }

    public long[] getOpStats() { return opStats.clone(); }

    public String dumpRegisters() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("PC:%06o  FLAGS:%012o  #%d\n", PC, FLAGS, instructionCount));
        for (int i = 0; i < 16; i++) {
            sb.append(String.format("AC%02d: %012o  (%d)\n", i, AC[i], signed(AC[i])));
        }
        return sb.toString();
    }
}
