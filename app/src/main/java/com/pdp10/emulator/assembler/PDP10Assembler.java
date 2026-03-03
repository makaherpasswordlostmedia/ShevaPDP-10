package com.pdp10.emulator.assembler;

import com.pdp10.emulator.memory.PDP10Memory;
import java.util.*;

/**
 * Simple PDP-10 Assembler
 * Supports basic MACRO-10 / MIDAS style assembly language.
 * 
 * Syntax:
 *   LABEL: MNEMONIC AC, @OFFSET(INDEX)
 *   LABEL: MNEMONIC AC, IMMEDIATE
 *   ; comment
 */
public class PDP10Assembler {

    private PDP10Memory memory;

    public static class AssemblyError {
        public final int lineNumber;
        public final String line;
        public final String message;
        public AssemblyError(int lineNumber, String line, String message) {
            this.lineNumber = lineNumber;
            this.line = line;
            this.message = message;
        }
        @Override public String toString() {
            return String.format("Line %d: %s\n  -> %s", lineNumber, line, message);
        }
    }

    public static class AssemblyResult {
        public final List<AssemblyError> errors = new ArrayList<>();
        public int startAddress = 0200;
        public int wordCount = 0;
        public Map<String, Integer> symbols = new HashMap<>();

        public boolean hasErrors() { return !errors.isEmpty(); }
        public String errorReport() {
            StringBuilder sb = new StringBuilder();
            for (AssemblyError e : errors) sb.append(e.toString()).append('\n');
            return sb.toString();
        }
    }

    // Opcode table
    private static final Map<String, Integer> OPCODES = new HashMap<>();
    static {
        OPCODES.put("MOVE", 0200);  OPCODES.put("MOVEI",0201); OPCODES.put("MOVEM",0202); OPCODES.put("MOVES",0203);
        OPCODES.put("MOVS", 0204);  OPCODES.put("MOVSI",0205); OPCODES.put("MOVSM",0206); OPCODES.put("MOVSS",0207);
        OPCODES.put("MOVN", 0210);  OPCODES.put("MOVNI",0211); OPCODES.put("MOVNM",0212); OPCODES.put("MOVNS",0213);
        OPCODES.put("MOVM", 0214);  OPCODES.put("MOVMI",0215); OPCODES.put("MOVMM",0216); OPCODES.put("MOVMS",0217);
        OPCODES.put("IMUL", 0220);  OPCODES.put("IMULI",0221); OPCODES.put("IMULM",0222); OPCODES.put("IMULB",0223);
        OPCODES.put("MUL",  0224);  OPCODES.put("MULI", 0225); OPCODES.put("MULM", 0226); OPCODES.put("MULB", 0227);
        OPCODES.put("IDIV", 0230);  OPCODES.put("IDIVI",0231); OPCODES.put("IDIVM",0232); OPCODES.put("IDIVB",0233);
        OPCODES.put("DIV",  0234);  OPCODES.put("DIVI", 0235); OPCODES.put("DIVM", 0236); OPCODES.put("DIVB", 0237);
        OPCODES.put("ASH",  0240);  OPCODES.put("ROT",  0241); OPCODES.put("LSH",  0242); OPCODES.put("JFFO", 0243);
        OPCODES.put("ASHC", 0244);  OPCODES.put("ROTC", 0245); OPCODES.put("LSHC", 0246);
        OPCODES.put("JRST", 0254);  OPCODES.put("JFCL", 0255); OPCODES.put("XCT",  0256);
        OPCODES.put("PUSHJ",0260);  OPCODES.put("PUSH", 0261); OPCODES.put("POP",  0262); OPCODES.put("POPJ", 0263);
        OPCODES.put("JSR",  0264);  OPCODES.put("JSP",  0265); OPCODES.put("JSA",  0266); OPCODES.put("JRA",  0267);
        OPCODES.put("ADD",  0270);  OPCODES.put("ADDI", 0271); OPCODES.put("ADDM", 0272); OPCODES.put("ADDB", 0273);
        OPCODES.put("SUB",  0274);  OPCODES.put("SUBI", 0275); OPCODES.put("SUBM", 0276); OPCODES.put("SUBB", 0277);
        OPCODES.put("CAIL", 0300);  OPCODES.put("CAIE", 0301); OPCODES.put("CAILE",0302); OPCODES.put("CAIA", 0303);
        OPCODES.put("CAIGE",0304);  OPCODES.put("CAIG", 0305); OPCODES.put("CAIN", 0306);
        OPCODES.put("CAMN", 0316);  OPCODES.put("CAML", 0310); OPCODES.put("CAME", 0311); OPCODES.put("CAMLE",0312);
        OPCODES.put("CAMA", 0313);  OPCODES.put("CAMGE",0314); OPCODES.put("CAMG", 0315);
        OPCODES.put("JUMP", 0320);  OPCODES.put("JUMPL",0321); OPCODES.put("JUMPLE",0322); OPCODES.put("JUMPE",0323);
        OPCODES.put("JUMPN",0324);  OPCODES.put("JUMPGE",0325);OPCODES.put("JUMPG",0326); OPCODES.put("JUMPA",0327);
        OPCODES.put("SKIP", 0330);  OPCODES.put("SKIPL",0331); OPCODES.put("SKIPLE",0332);OPCODES.put("SKIPE",0333);
        OPCODES.put("SKIPN",0334);  OPCODES.put("SKIPGE",0335);OPCODES.put("SKIPG",0336); OPCODES.put("SKIPA",0337);
        OPCODES.put("AOJ",  0340);  OPCODES.put("AOJL", 0341); OPCODES.put("AOJLE",0342); OPCODES.put("AOJE", 0343);
        OPCODES.put("AOJN", 0344);  OPCODES.put("AOJGE",0345); OPCODES.put("AOJG", 0346); OPCODES.put("AOJA", 0347);
        OPCODES.put("AOS",  0350);  OPCODES.put("AOSL", 0351); OPCODES.put("AOSLE",0352); OPCODES.put("AOSE", 0353);
        OPCODES.put("AOSN", 0354);  OPCODES.put("AOSGE",0355); OPCODES.put("AOSG", 0356); OPCODES.put("AOSA", 0357);
        OPCODES.put("SOJ",  0360);  OPCODES.put("SOJL", 0361); OPCODES.put("SOJLE",0362); OPCODES.put("SOJE", 0363);
        OPCODES.put("SOJN", 0364);  OPCODES.put("SOJGE",0365); OPCODES.put("SOJG", 0366); OPCODES.put("SOJA", 0367);
        OPCODES.put("SOS",  0370);  OPCODES.put("SOSL", 0371); OPCODES.put("SOSLE",0372); OPCODES.put("SOSE", 0373);
        OPCODES.put("SOSN", 0374);  OPCODES.put("SOSGE",0375); OPCODES.put("SOSG", 0376); OPCODES.put("SOSA", 0377);
        OPCODES.put("SETZ", 0400);  OPCODES.put("AND",  0404); OPCODES.put("ANDCA",0410); OPCODES.put("SETM", 0414);
        OPCODES.put("ANDCM",0420);  OPCODES.put("SETA", 0424); OPCODES.put("XOR",  0430); OPCODES.put("OR",   0434);
        OPCODES.put("ANDCB",0440);  OPCODES.put("EQV",  0444); OPCODES.put("SETCA",0450); OPCODES.put("ORCA", 0454);
        OPCODES.put("SETCM",0460);  OPCODES.put("ORCM", 0464); OPCODES.put("ORCB", 0470); OPCODES.put("SETO", 0474);
        OPCODES.put("HRL",  0504);  OPCODES.put("HRLI", 0505); OPCODES.put("HRLM", 0506); OPCODES.put("HRLS", 0507);
        OPCODES.put("HRR",  0540);  OPCODES.put("HRRI", 0541); OPCODES.put("HRRM", 0542); OPCODES.put("HRRS", 0543);
        OPCODES.put("HLL",  0500);  OPCODES.put("HLLI", 0501); OPCODES.put("HLLM", 0502); OPCODES.put("HLLS", 0503);
        OPCODES.put("TDN",  0600);  OPCODES.put("TDZ",  0620); OPCODES.put("TDO",  0660); OPCODES.put("TDC",  0640);
        OPCODES.put("TSN",  0610);  OPCODES.put("TSZ",  0630); OPCODES.put("TSO",  0670); OPCODES.put("TSC",  0650);
        // Pseudo-ops
        OPCODES.put("HALT", 0254);  // JRST 0
        OPCODES.put("NOP",  0254);  // JRST .+1
    }

    public PDP10Assembler(PDP10Memory memory) {
        this.memory = memory;
    }

    public AssemblyResult assemble(String source) {
        AssemblyResult result = new AssemblyResult();
        String[] lines = source.split("\n");

        // Two-pass assembly
        Map<String, Integer> symbolTable = new HashMap<>();
        List<long[]> instructions = new ArrayList<>(); // [address, word, lineNum]
        int currentAddress = 0200;
        int originAddress  = 0200;

        // Pass 1: collect labels and calculate addresses
        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
            String line = lines[lineNum].trim();
            if (line.isEmpty() || line.startsWith(";")) continue;

            // Handle labels
            if (line.contains(":")) {
                int colonPos = line.indexOf(':');
                String label = line.substring(0, colonPos).trim().toUpperCase();
                symbolTable.put(label, currentAddress);
                line = line.substring(colonPos + 1).trim();
            }
            if (line.isEmpty() || line.startsWith(";")) continue;

            // Handle directives
            String[] parts = splitLine(line);
            String op = parts[0].toUpperCase();

            if (op.equals(".LOC") || op.equals("LOC") || op.equals("RELOC")) {
                try {
                    currentAddress = parseNumber(parts.length > 1 ? parts[1] : "0200", symbolTable);
                    originAddress = currentAddress;
                } catch (Exception e) {
                    result.errors.add(new AssemblyError(lineNum+1, line, "Bad address: " + e.getMessage()));
                }
                continue;
            }
            if (op.equals(".WORD") || op.equals("EXP") || op.equals("XWD")) {
                currentAddress++;
                continue;
            }
            if (op.equals(".ASCII") || op.equals("ASCIZ") || op.equals("ASCII")) {
                String str = getStringArg(line);
                currentAddress += (str.length() + 4) / 5; // 5 chars per word
                continue;
            }
            if (op.equals("END") || op.equals(".END")) break;

            if (OPCODES.containsKey(op)) {
                currentAddress++;
            }
        }

        // Pass 2: assemble instructions
        result.symbols = symbolTable;
        currentAddress = originAddress;

        for (int lineNum = 0; lineNum < lines.length; lineNum++) {
            String rawLine = lines[lineNum];
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith(";")) continue;

            // Remove comment
            int semicolon = line.indexOf(';');
            if (semicolon >= 0) line = line.substring(0, semicolon).trim();
            if (line.isEmpty()) continue;

            // Skip labels
            if (line.contains(":")) {
                line = line.substring(line.indexOf(':') + 1).trim();
            }
            if (line.isEmpty()) continue;

            String[] parts = splitLine(line);
            String op = parts[0].toUpperCase();

            try {
                if (op.equals("END") || op.equals(".END")) break;

                if (op.equals(".LOC") || op.equals("LOC") || op.equals("RELOC")) {
                    currentAddress = parseNumber(parts.length > 1 ? parts[1] : "0200", symbolTable);
                    originAddress  = currentAddress;
                    continue;
                }

                if (op.equals("EXP") || op.equals(".WORD")) {
                    long val = 0;
                    if (parts.length > 1) val = parseExpression(parts[1], symbolTable, currentAddress);
                    memory.write(currentAddress, val);
                    instructions.add(new long[]{currentAddress, val, lineNum+1});
                    currentAddress++;
                    result.wordCount++;
                    continue;
                }

                if (op.equals("XWD")) {
                    long left = 0, right = 0;
                    if (parts.length > 1) {
                        String[] halves = parts[1].split(",");
                        left  = parseExpression(halves[0].trim(), symbolTable, currentAddress);
                        right = (halves.length > 1) ? parseExpression(halves[1].trim(), symbolTable, currentAddress) : 0;
                    }
                    long val = ((left & 0x3FFFFL) << 18) | (right & 0x3FFFFL);
                    memory.write(currentAddress, val);
                    currentAddress++;
                    result.wordCount++;
                    continue;
                }

                if (op.equals("ASCII") || op.equals("ASCIZ")) {
                    String str = getStringArg(line);
                    if (op.equals("ASCIZ")) str = str + '\0';
                    int[] ascii = packAscii(str);
                    for (int w : ascii) {
                        memory.write(currentAddress++, w & 0xFFFFFFFFL);
                        result.wordCount++;
                    }
                    continue;
                }

                if (op.equals("HALT")) {
                    // JRST 0
                    memory.write(currentAddress, (0254L << 27));
                    currentAddress++;
                    result.wordCount++;
                    continue;
                }

                if (op.equals("NOP")) {
                    memory.write(currentAddress, 0);
                    currentAddress++;
                    result.wordCount++;
                    continue;
                }

                if (!OPCODES.containsKey(op)) {
                    result.errors.add(new AssemblyError(lineNum+1, rawLine.trim(), "Unknown opcode: " + op));
                    currentAddress++;
                    continue;
                }

                int opcode = OPCODES.get(op);
                int ac = 0, x = 0, y = 0, indirect = 0;

                // Parse operands: AC, @Y(X)  or  AC, Y  or  Y
                if (parts.length > 1) {
                    String operands = parts[1];
                    // Check if first operand is AC
                    if (operands.contains(",")) {
                        int commaIdx = operands.indexOf(',');
                        String acStr = operands.substring(0, commaIdx).trim();
                        String eaStr = operands.substring(commaIdx + 1).trim();
                        ac = parseNumber(acStr, symbolTable) & 017;

                        // Parse EA: @Y(X)
                        ParsedEA ea = parseEA(eaStr, symbolTable, currentAddress);
                        y = ea.y;
                        x = ea.x;
                        indirect = ea.indirect;
                    } else {
                        // No AC, just EA
                        ParsedEA ea = parseEA(operands.trim(), symbolTable, currentAddress);
                        y = ea.y;
                        x = ea.x;
                        indirect = ea.indirect;
                    }
                }

                // Encode instruction: [op:9][ac:4][i:1][x:4][y:18]
                long word = ((long)opcode << 27) | ((long)ac << 23) |
                            ((long)indirect << 22) | ((long)x << 18) | (y & 0x3FFFF);
                memory.write(currentAddress, word);
                instructions.add(new long[]{currentAddress, word, lineNum+1});
                currentAddress++;
                result.wordCount++;

            } catch (Exception e) {
                result.errors.add(new AssemblyError(lineNum+1, rawLine.trim(), e.getMessage()));
                currentAddress++; // skip
            }
        }

        result.startAddress = originAddress;
        return result;
    }

    private static class ParsedEA {
        int y, x, indirect;
        ParsedEA(int y, int x, int indirect) {
            this.y = y; this.x = x; this.indirect = indirect;
        }
    }

    private ParsedEA parseEA(String s, Map<String, Integer> syms, int currentPC) throws Exception {
        s = s.trim();
        int indirect = 0;
        int x = 0;

        if (s.startsWith("@")) {
            indirect = 1;
            s = s.substring(1).trim();
        }

        // Check for index: Y(X)
        int parenOpen = s.indexOf('(');
        if (parenOpen >= 0) {
            int parenClose = s.indexOf(')', parenOpen);
            String xStr = s.substring(parenOpen + 1, parenClose >= 0 ? parenClose : s.length()).trim();
            x = parseNumber(xStr, syms) & 017;
            s = s.substring(0, parenOpen).trim();
        }

        // Handle . (current location)
        s = s.replace(".", String.valueOf(currentPC));

        int y = (int)(parseExpression(s, syms, currentPC) & 0x3FFFF);
        return new ParsedEA(y, x, indirect);
    }

    private String[] splitLine(String line) {
        // Split on whitespace but keep operands together
        int space = -1;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ' || c == '\t') { space = i; break; }
        }
        if (space < 0) return new String[]{line};
        String op = line.substring(0, space).trim();
        String rest = line.substring(space).trim();
        return new String[]{op, rest};
    }

    private long parseExpression(String s, Map<String, Integer> syms, int currentPC) throws Exception {
        s = s.trim();
        if (s.isEmpty()) return 0;

        // Simple arithmetic: +, -, *
        // Handle + and -
        for (int i = s.length() - 1; i >= 0; i--) {
            char c = s.charAt(i);
            if ((c == '+' || c == '-') && i > 0) {
                long left  = parseExpression(s.substring(0, i), syms, currentPC);
                long right = parseExpression(s.substring(i+1), syms, currentPC);
                return (c == '+') ? left + right : left - right;
            }
        }
        return parseNumber(s, syms);
    }

    private int parseNumber(String s, Map<String, Integer> syms) throws Exception {
        s = s.trim().toUpperCase();
        if (s.isEmpty()) return 0;

        // Symbol
        if (Character.isLetter(s.charAt(0)) || s.charAt(0) == '_') {
            if (syms.containsKey(s)) return syms.get(s);
            // Try register names
            if (s.startsWith("AC") && s.length() <= 4) {
                try { return Integer.parseInt(s.substring(2)); } catch (Exception ignore) {}
            }
            throw new Exception("Undefined symbol: " + s);
        }

        // Octal (default if starts with 0 or has octal digits only)
        if (s.startsWith("0") && !s.startsWith("0X") && !s.startsWith("0D")) {
            return (int)(Long.parseLong(s, 8) & 0xFFFFFFFFL);
        }
        if (s.startsWith("0X")) return (int)(Long.parseLong(s.substring(2), 16) & 0xFFFFFFFFL);
        if (s.startsWith("0D")) return Integer.parseInt(s.substring(2));

        // Decimal or octal heuristic (no 8s or 9s = octal)
        boolean hasOctalOnly = true;
        for (char c : s.toCharArray()) {
            if (c == '8' || c == '9') { hasOctalOnly = false; break; }
        }
        if (hasOctalOnly && s.length() > 1) {
            return (int)(Long.parseLong(s, 8) & 0xFFFFFFFFL);
        }
        return Integer.parseInt(s);
    }

    private String getStringArg(String line) {
        int q1 = line.indexOf('"');
        if (q1 >= 0) {
            int q2 = line.indexOf('"', q1 + 1);
            if (q2 >= 0) return line.substring(q1 + 1, q2);
        }
        int a1 = line.indexOf('\'');
        if (a1 >= 0) {
            int a2 = line.indexOf('\'', a1 + 1);
            if (a2 >= 0) return line.substring(a1 + 1, a2);
        }
        return "";
    }

    /** Pack ASCII string into PDP-10 words (7-bit, 5 chars per word) */
    private int[] packAscii(String s) {
        int numWords = (s.length() + 4) / 5;
        int[] words = new int[numWords];
        for (int i = 0; i < numWords; i++) {
            int w = 0;
            for (int j = 0; j < 5; j++) {
                int idx = i * 5 + j;
                int ch = (idx < s.length()) ? (s.charAt(idx) & 0x7F) : 0;
                w = (w << 7) | ch;
            }
            // Note: this gives 35 bits; bit 0 (MSB of PDP-10 word) is 0
            words[i] = w;
        }
        return words;
    }

    /**
     * Returns a sample "Hello World" program in PDP-10 assembly
     */
    public static String getHelloWorldProgram() {
        return
            "; PDP-10 Hello World\n" +
            "; Outputs 'Hello, World!' via console I/O\n" +
            "; Uses DATAO instruction to write characters\n" +
            "\n" +
            "        LOC 200\n" +
            "\n" +
            "START:  MOVEI 1,MSG     ; Load address of message into AC1\n" +
            "LOOP:   LDB   2,[POINT 7,(1),6]  ; Load byte via byte pointer\n" +
            "        JUMPE 2,DONE    ; Jump if null terminator\n" +
            "        MOVEI 0,@1      ; Get char to AC0\n" +
            "        MOVEM 0,OUTBUF  ; Store in output buffer\n" +
            "        DATAO CTY,OUTBUF ; Output character\n" +
            "        AOJA  1,LOOP    ; Increment and loop\n" +
            "DONE:   HALT            ; Stop\n" +
            "\n" +
            "MSG:    ASCII \"Hello, World!\" \n" +
            "OUTBUF: EXP 0\n" +
            "        END START\n";
    }

    /**
     * Returns a Fibonacci sequence program
     */
    public static String getFibonacciProgram() {
        return
            "; PDP-10 Fibonacci Sequence\n" +
            "; Computes first N Fibonacci numbers\n" +
            "; AC0 = current, AC1 = next, AC2 = counter\n" +
            "\n" +
            "        LOC 200\n" +
            "\n" +
            "START:  MOVEI 0,0       ; F(0) = 0\n" +
            "        MOVEI 1,1       ; F(1) = 1\n" +
            "        MOVEI 2,14      ; Count = 14 (octal), 12 iterations\n" +
            "\n" +
            "LOOP:   MOVEM 0,RESULT  ; Store current Fibonacci number\n" +
            "        MOVE  3,1       ; Save F(n+1)\n" +
            "        ADD   1,0       ; F(n+2) = F(n) + F(n+1)\n" +
            "        MOVE  0,3       ; F(n) = old F(n+1)\n" +
            "        SOJE  2,DONE    ; Decrement counter, jump if zero\n" +
            "        JRST  LOOP      ; Continue\n" +
            "\n" +
            "DONE:   HALT            ; Done - result in AC0\n" +
            "\n" +
            "RESULT: EXP 0           ; Storage for current value\n" +
            "        END START\n";
    }

    /**
     * Returns a counter/loop program
     */
    public static String getCounterProgram() {
        return
            "; PDP-10 Counter Demo\n" +
            "; Counts from 0 to 7 and stores results\n" +
            "\n" +
            "        LOC 200\n" +
            "\n" +
            "START:  MOVEI 0,0       ; Counter = 0\n" +
            "        MOVEI 1,TABLE   ; Pointer to table\n" +
            "\n" +
            "LOOP:   MOVEM 0,(1)     ; Store counter at (1)\n" +
            "        ADDI  1,1       ; Increment pointer\n" +
            "        AOJA  0,LOOP    ; Increment AC0 and loop always\n" +
            "        CAIGE 0,10      ; Skip if AC0 >= 10 (octal)\n" +
            "        JRST  LOOP\n" +
            "\n" +
            "HALT:   HALT\n" +
            "\n" +
            "TABLE:  EXP 0\n" +
            "        EXP 0\n" +
            "        EXP 0\n" +
            "        EXP 0\n" +
            "        EXP 0\n" +
            "        EXP 0\n" +
            "        EXP 0\n" +
            "        EXP 0\n" +
            "        END START\n";
    }
}
