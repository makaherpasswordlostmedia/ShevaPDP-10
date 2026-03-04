package com.pdp10.emulator.assembler;

import com.pdp10.emulator.memory.PDP10Memory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Two-pass PDP-10 assembler (MACRO-10 compatible subset).
 * Syntax: LABEL: OPCODE AC, @OFFSET(INDEX) ; comment
 * Numbers: octal default, decimal with trailing '.', hex with 0x
 * Directives: LOC, EXP, .WORD, XWD, ASCII, ASCIZ, END
 */
public class PDP10Assembler {

    private final PDP10Memory memory;

    public PDP10Assembler(PDP10Memory memory) {
        this.memory = memory;
    }

    // =========================================================================
    // AssemblyResult
    // =========================================================================

    public static class AssemblyResult {
        public final List<String>         errors  = new ArrayList<>();
        public final Map<String, Integer> symbols = new HashMap<>();
        public int wordCount    = 0;
        public int startAddress = 0200;

        public boolean hasErrors() { return !errors.isEmpty(); }

        public String errorReport() {
            StringBuilder sb = new StringBuilder();
            for (String e : errors) sb.append(e).append('\n');
            return sb.toString();
        }
    }

    // =========================================================================
    // Opcode table
    // =========================================================================

    private static final Map<String, Integer> OPS = new HashMap<>();
    static {
        OPS.put("MOVE",  0200); OPS.put("MOVEI", 0201); OPS.put("MOVEM", 0202); OPS.put("MOVES", 0203);
        OPS.put("MOVS",  0204); OPS.put("MOVSI", 0205); OPS.put("MOVSM", 0206); OPS.put("MOVSS", 0207);
        OPS.put("MOVN",  0210); OPS.put("MOVNI", 0211); OPS.put("MOVNM", 0212); OPS.put("MOVNS", 0213);
        OPS.put("MOVM",  0214); OPS.put("MOVMI", 0215); OPS.put("MOVMM", 0216); OPS.put("MOVMS", 0217);
        OPS.put("IMUL",  0220); OPS.put("IMULI", 0221); OPS.put("IMULM", 0222); OPS.put("IMULB", 0223);
        OPS.put("MUL",   0224); OPS.put("MULI",  0225); OPS.put("MULM",  0226); OPS.put("MULB",  0227);
        OPS.put("IDIV",  0230); OPS.put("IDIVI", 0231); OPS.put("IDIVM", 0232); OPS.put("IDIVB", 0233);
        OPS.put("DIV",   0234); OPS.put("DIVI",  0235); OPS.put("DIVM",  0236); OPS.put("DIVB",  0237);
        OPS.put("ASH",   0240); OPS.put("ROT",   0241); OPS.put("LSH",   0242); OPS.put("JFFO",  0243);
        OPS.put("ASHC",  0244); OPS.put("ROTC",  0245); OPS.put("LSHC",  0246);
        OPS.put("JRST",  0254); OPS.put("JFCL",  0255); OPS.put("XCT",   0256);
        OPS.put("PUSHJ", 0260); OPS.put("PUSH",  0261); OPS.put("POP",   0262); OPS.put("POPJ",  0263);
        OPS.put("JSR",   0264); OPS.put("JSP",   0265); OPS.put("JSA",   0266); OPS.put("JRA",   0267);
        OPS.put("ADD",   0270); OPS.put("ADDI",  0271); OPS.put("ADDM",  0272); OPS.put("ADDB",  0273);
        OPS.put("SUB",   0274); OPS.put("SUBI",  0275); OPS.put("SUBM",  0276); OPS.put("SUBB",  0277);
        OPS.put("CAIL",  0300); OPS.put("CAIE",  0301); OPS.put("CAILE", 0302); OPS.put("CAIA",  0303);
        OPS.put("CAIGE", 0304); OPS.put("CAIG",  0305); OPS.put("CAIN",  0306); OPS.put("CAM",   0307);
        OPS.put("CAML",  0310); OPS.put("CAME",  0311); OPS.put("CAMLE", 0312); OPS.put("CAMA",  0313);
        OPS.put("CAMGE", 0314); OPS.put("CAMG",  0315); OPS.put("CAMN",  0316); OPS.put("CAMM",  0317);
        OPS.put("JUMP",  0320); OPS.put("JUMPL", 0321); OPS.put("JUMPLE",0322); OPS.put("JUMPE", 0323);
        OPS.put("JUMPN", 0324); OPS.put("JUMPGE",0325); OPS.put("JUMPG", 0326); OPS.put("JUMPA", 0327);
        OPS.put("SKIP",  0330); OPS.put("SKIPL", 0331); OPS.put("SKIPLE",0332); OPS.put("SKIPE", 0333);
        OPS.put("SKIPN", 0334); OPS.put("SKIPGE",0335); OPS.put("SKIPG", 0336); OPS.put("SKIPA", 0337);
        OPS.put("AOJ",   0340); OPS.put("AOJL",  0341); OPS.put("AOJLE", 0342); OPS.put("AOJE",  0343);
        OPS.put("AOJN",  0344); OPS.put("AOJGE", 0345); OPS.put("AOJG",  0346); OPS.put("AOJA",  0347);
        OPS.put("AOS",   0350); OPS.put("AOSL",  0351); OPS.put("AOSLE", 0352); OPS.put("AOSE",  0353);
        OPS.put("AOSN",  0354); OPS.put("AOSGE", 0355); OPS.put("AOSG",  0356); OPS.put("AOSA",  0357);
        OPS.put("SOJ",   0360); OPS.put("SOJL",  0361); OPS.put("SOJLE", 0362); OPS.put("SOJE",  0363);
        OPS.put("SOJN",  0364); OPS.put("SOJGE", 0365); OPS.put("SOJG",  0366); OPS.put("SOJA",  0367);
        OPS.put("SOS",   0370); OPS.put("SOSL",  0371); OPS.put("SOSLE", 0372); OPS.put("SOSE",  0373);
        OPS.put("SOSN",  0374); OPS.put("SOSGE", 0375); OPS.put("SOSG",  0376); OPS.put("SOSA",  0377);
        OPS.put("SETZ",  0400); OPS.put("SETZI", 0401); OPS.put("SETZM", 0402); OPS.put("SETZB", 0403);
        OPS.put("AND",   0404); OPS.put("ANDI",  0405); OPS.put("ANDM",  0406); OPS.put("ANDB",  0407);
        OPS.put("ANDCA", 0410); OPS.put("SETM",  0414); OPS.put("ANDCM", 0420);
        OPS.put("SETA",  0424); OPS.put("SETAI", 0425);
        OPS.put("XOR",   0430); OPS.put("XORI",  0431); OPS.put("XORM",  0432); OPS.put("XORB",  0433);
        OPS.put("OR",    0434); OPS.put("ORI",   0435); OPS.put("ORM",   0436); OPS.put("ORB",   0437);
        OPS.put("ANDCB", 0440); OPS.put("EQV",   0444); OPS.put("SETCA", 0450);
        OPS.put("ORCA",  0454); OPS.put("SETCM", 0460); OPS.put("ORCM",  0464);
        OPS.put("ORCB",  0470); OPS.put("SETO",  0474); OPS.put("SETOI", 0475);
        OPS.put("HLL",   0500); OPS.put("HLLI",  0501); OPS.put("HLLM",  0502); OPS.put("HLLS",  0503);
        OPS.put("HRL",   0504); OPS.put("HRLI",  0505); OPS.put("HRLM",  0506); OPS.put("HRLS",  0507);
        OPS.put("HRR",   0540); OPS.put("HRRI",  0541); OPS.put("HRRM",  0542); OPS.put("HRRS",  0543);
        OPS.put("HLR",   0544); OPS.put("HLRI",  0545); OPS.put("HLRM",  0546); OPS.put("HLRS",  0547);
        OPS.put("HRRZ",  0550); OPS.put("HLRZ",  0554); OPS.put("HRRO",  0560); OPS.put("HLRO",  0564);
        OPS.put("TRN",   0600); OPS.put("TLN",   0604); OPS.put("TRZ",   0620); OPS.put("TLZ",   0624);
        OPS.put("TRC",   0640); OPS.put("TLC",   0644); OPS.put("TRO",   0660); OPS.put("TLO",   0664);
        OPS.put("TDN",   0610); OPS.put("TSN",   0614); OPS.put("TDZ",   0630); OPS.put("TSZ",   0634);
        OPS.put("TDC",   0650); OPS.put("TSC",   0654); OPS.put("TDO",   0670); OPS.put("TSO",   0674);
        OPS.put("BLKI",  0700); OPS.put("DATAI", 0704); OPS.put("BLKO",  0710); OPS.put("DATAO", 0714);
        OPS.put("CONO",  0720); OPS.put("CONI",  0724); OPS.put("CONSZ", 0730); OPS.put("CONSO", 0734);
        // Pseudos
        OPS.put("HALT",  0001); // dedicated halt sentinel
        OPS.put("NOP",   0254);
    }

    private static final Map<String, Integer> DEVICES = new HashMap<>();
    static {
        DEVICES.put("APR", 0000); DEVICES.put("PI",  0004); DEVICES.put("PAG", 0010);
        DEVICES.put("CTY", 0100); DEVICES.put("DTE", 0200);
    }

    // =========================================================================
    // Assemble
    // =========================================================================

    public AssemblyResult assemble(String source) {
        AssemblyResult result = new AssemblyResult();
        String[] lines = source.split("\n");

        // Pass 1: collect labels
        int lc = 0200;
        for (String line : lines) {
            String raw = stripComment(line).trim();
            if (raw.isEmpty()) continue;
            if (raw.contains(":")) {
                int col = raw.indexOf(':');
                String lbl = raw.substring(0, col).trim().toUpperCase();
                if (!lbl.isEmpty()) result.symbols.put(lbl, lc);
                raw = raw.substring(col + 1).trim();
                if (raw.isEmpty()) continue;
            }
            String up = raw.toUpperCase();
            String[] p = raw.split("\\s+", 2);
            String mn = p[0].toUpperCase();
            if (mn.equals("LOC") || mn.equals("RELOC")) {
                lc = parseNum(p.length > 1 ? p[1].trim() : "0", result.symbols, null);
            } else if (mn.equals("ASCII") || mn.equals("ASCIZ")) {
                String s = extractString(raw);
                lc += (s.length() + (mn.equals("ASCIZ") ? 1 : 0) + 4) / 5;
            } else if (mn.equals("EXP") || mn.equals(".WORD")) {
                lc += p.length > 1 ? p[1].split(",").length : 1;
            } else if (mn.equals("XWD")) {
                lc++;
            } else if (mn.equals("END")) {
                break;
            } else {
                lc++;
            }
        }

        // Pass 2: emit
        lc = 0200;
        result.startAddress = 0200;
        int wc = 0;
        for (String line : lines) {
            String raw = stripComment(line).trim();
            if (raw.isEmpty()) continue;
            if (raw.contains(":")) {
                raw = raw.substring(raw.indexOf(':') + 1).trim();
                if (raw.isEmpty()) continue;
            }
            String[] p = raw.split("\\s+", 2);
            String mn = p[0].toUpperCase();
            String rest = p.length > 1 ? p[1].trim() : "";

            if (mn.equals("LOC") || mn.equals("RELOC")) {
                lc = parseNum(rest, result.symbols, result);
            } else if (mn.equals("END")) {
                if (!rest.isEmpty()) result.startAddress = parseNum(rest, result.symbols, result);
                break;
            } else if (mn.equals("ASCII") || mn.equals("ASCIZ")) {
                String s = extractString(raw);
                if (mn.equals("ASCIZ")) s = s + "\0";
                for (int w : packAscii(s)) { memory.write(lc++, w); wc++; }
            } else if (mn.equals("EXP") || mn.equals(".WORD")) {
                for (String tok : (rest.isEmpty() ? "0" : rest).split(",")) {
                    memory.write(lc++, parseNum(tok.trim(), result.symbols, result));
                    wc++;
                }
            } else if (mn.equals("XWD")) {
                String[] h = rest.split(",", 2);
                long l2 = parseNum(h[0].trim(), result.symbols, result);
                long r  = h.length > 1 ? parseNum(h[1].trim(), result.symbols, result) : 0;
                memory.write(lc++, ((l2 & 0x3FFFFL) << 18) | (r & 0x3FFFFL));
                wc++;
            } else {
                memory.write(lc++, assemInstr(mn, rest, lc - 1, result));
                wc++;
            }
        }
        result.wordCount = wc;
        return result;
    }

    // =========================================================================
    // Instruction encoder
    // =========================================================================

    private long assemInstr(String mn, String ops, int lc, AssemblyResult r) {
        if (mn.equals("HALT")) return (long) 0001 << 27; // halt sentinel
        if (mn.equals("NOP"))  return ((long) 0254 << 27) | (lc & 0x3FFFF);

        Integer op = OPS.get(mn);
        if (op == null) { r.errors.add(lc + ": unknown op: " + mn); return 0; }

        ops = ops.trim();
        if (ops.isEmpty()) return (long) op << 27;

        // I/O instructions
        if (op >= 0700) return encodeIO(op, ops, lc, r);

        int ac = 0, idx = 0, ind = 0;
        long addr = 0;
        int comma = findComma(ops);
        String ea;
        if (comma >= 0) {
            ac = (int)(parseNum(ops.substring(0, comma).trim(), r.symbols, r) & 0xF);
            ea = ops.substring(comma + 1).trim();
        } else {
            ea = ops;
        }
        if (ea.startsWith("@")) { ind = 1; ea = ea.substring(1).trim(); }
        int po = ea.lastIndexOf('('), pc = ea.lastIndexOf(')');
        if (po >= 0 && pc > po) {
            idx = (int)(parseNum(ea.substring(po + 1, pc).trim(), r.symbols, r) & 0xF);
            ea  = ea.substring(0, po).trim();
        }
        if (!ea.isEmpty()) addr = parseNum(ea, r.symbols, r) & 0x3FFFFL;

        return ((long) op << 27) | ((long) ac << 23) | ((long) ind << 22)
             | ((long) idx << 18) | addr;
    }

    private long encodeIO(int base, String ops, int lc, AssemblyResult r) {
        String[] p   = ops.split(",", 2);
        String devStr = p[0].trim().toUpperCase();
        String rest  = p.length > 1 ? p[1].trim() : "0";
        Integer dv   = DEVICES.get(devStr);
        int dev      = dv != null ? dv : (int)(parseNum(devStr, r.symbols, r) & 0177);
        int func     = base & 07;
        int ind = 0, idx = 0;
        long addr = 0;
        if (rest.startsWith("@")) { ind = 1; rest = rest.substring(1).trim(); }
        int po = rest.lastIndexOf('('), pc = rest.lastIndexOf(')');
        if (po >= 0 && pc > po) {
            idx  = (int)(parseNum(rest.substring(po + 1, pc).trim(), r.symbols, r) & 0xF);
            rest = rest.substring(0, po).trim();
        }
        if (!rest.isEmpty()) addr = parseNum(rest, r.symbols, r) & 0x3FFFFL;
        int acField = (dev >> 1) & 0xF;
        long opCode = ((dev << 3) | func | 0700) & 0x1FF;
        return (opCode << 27) | ((long) acField << 23) | ((long) ind << 22)
             | ((long) idx << 18) | addr;
    }

    // =========================================================================
    // Number parser
    // =========================================================================

    private int parseNum(String s, Map<String, Integer> syms, AssemblyResult r) {
        s = s.trim();
        if (s.isEmpty()) return 0;
        if (Character.isLetter(s.charAt(0))) {
            String key = s.toUpperCase();
            if (syms != null && syms.containsKey(key)) return syms.get(key);
            if (key.equals("CTY")) return 0100;
            return 0;
        }
        try {
            if (s.startsWith("0x") || s.startsWith("0X"))
                return (int)(Long.parseLong(s.substring(2), 16) & 0xFFFFFFFFL);
            if (s.endsWith("."))
                return Integer.parseInt(s.substring(0, s.length() - 1), 10);
            return (int)(Long.parseLong(s, 8) & 0xFFFFFFFFL);
        } catch (NumberFormatException e) {
            if (r != null) r.errors.add("Bad number: " + s);
            return 0;
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static String stripComment(String line) {
        boolean inStr = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' || c == '\'') inStr = !inStr;
            if (!inStr && c == ';') return line.substring(0, i);
        }
        return line;
    }

    private static int findComma(String s) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (c == ',' && depth == 0) return i;
        }
        return -1;
    }

    private static String extractString(String line) {
        int q1 = line.indexOf('"');
        if (q1 >= 0) {
            int q2 = line.indexOf('"', q1 + 1);
            if (q2 > q1) return line.substring(q1 + 1, q2);
        }
        int a1 = line.indexOf('\'');
        if (a1 >= 0) {
            int a2 = line.indexOf('\'', a1 + 1);
            if (a2 > a1) return line.substring(a1 + 1, a2);
        }
        return "";
    }

    private static int[] packAscii(String s) {
        int nw = (s.length() + 4) / 5;
        int[] w = new int[nw];
        for (int i = 0; i < nw; i++) {
            int word = 0;
            for (int j = 0; j < 5; j++) {
                int idx = i * 5 + j;
                word = (word << 7) | (idx < s.length() ? s.charAt(idx) & 0x7F : 0);
            }
            w[i] = word;
        }
        return w;
    }

    // =========================================================================
    // Sample programs  (called by MainActivity)
    // =========================================================================

    public static String getHelloWorldProgram() {
        return "; PDP-10 Hello World\n"
             + "; Uses OUTCHR to print each character\n"
             + "        LOC 200\n"
             + "START:  MOVEI 1,MSG     ; AC1 = address of string\n"
             + "LOOP:   MOVE  0,@1      ; AC0 = next char (indirect)\n"
             + "        JUMPE 0,DONE    ; Zero byte = end of string\n"
             + "        OUTCHR 0,       ; Output char in AC0\n"
             + "        AOJA  1,LOOP    ; Next char\n"
             + "DONE:   HALT\n"
             + "MSG:    ASCII \"Hello, PDP-10!\\n\"\n"
             + "        EXP 0\n"
             + "        END START\n";
    }

    public static String getFibonacciProgram() {
        return "; PDP-10 Fibonacci\n"
             + "        LOC 200\n"
             + "START:  MOVEI 0,0\n"
             + "        MOVEI 1,1\n"
             + "        MOVEI 2,14\n"
             + "LOOP:   MOVEM 0,RESULT\n"
             + "        MOVE  3,1\n"
             + "        ADD   1,0\n"
             + "        MOVE  0,3\n"
             + "        SOJE  2,DONE\n"
             + "        JRST  LOOP\n"
             + "DONE:   HALT\n"
             + "RESULT: EXP 0\n"
             + "        END START\n";
    }

    public static String getCounterProgram() {
        return "; PDP-10 Counter 0..17\n"
             + "        LOC 200\n"
             + "START:  SETZ  0,\n"
             + "        MOVEI 1,TABLE\n"
             + "        MOVEI 2,20\n"
             + "LOOP:   MOVEM 0,@1\n"
             + "        AOJ   0,\n"
             + "        CAME  0,2\n"
             + "        AOJA  1,LOOP\n"
             + "DONE:   HALT\n"
             + "TABLE:  EXP 0,0,0,0,0,0,0,0\n"
             + "        EXP 0,0,0,0,0,0,0,0\n"
             + "        END START\n";
    }
}
