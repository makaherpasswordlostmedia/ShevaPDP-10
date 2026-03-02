# PDP-10 Emulator for Android

A fully functional KA10/KI10 PDP-10 emulator for Android with:
- **Complete CPU core** — 36-bit word, 18-bit addressing, 256K core memory
- **Full instruction set** — MOVE, arithmetic, shifts, boolean, half-word, test, I/O
- **Terminal emulator** — Green-on-black VT-style with keyboard input
- **Assembler** — MACRO-10 compatible, two-pass with symbol table
- **Debugger** — Register inspector, disassembler, memory dump

---

## Architecture

```
app/src/main/java/com/pdp10/emulator/
├── MainActivity.java           ← Main UI with 3 tabs
├── cpu/
│   └── PDP10CPU.java          ← CPU: 36-bit ALU, all instruction groups
├── memory/
│   └── PDP10Memory.java       ← 256K word core memory
├── assembler/
│   └── PDP10Assembler.java    ← Two-pass assembler with sample programs
└── terminal/
    └── TerminalView.java      ← Custom Android terminal view
```

---

## Building

1. Open in **Android Studio** (Arctic Fox or newer)
2. Wait for Gradle sync
3. Click **Run ▶** (or `./gradlew assembleDebug`)
4. Minimum API: 26 (Android 8.0)

---

## PDP-10 ISA Implemented

### Instruction Groups
| Range (octal) | Group |
|---|---|
| 000-077 | LUUO traps |
| 200-277 | Move, MOVS, MOVN, MOVM variants |
| 220-237 | Integer multiply, divide |
| 240-247 | Shift: ASH, ROT, LSH, JFFO, ASHC, ROTC, LSHC |
| 254-267 | Jump: JRST, JFCL, XCT, PUSHJ, PUSH, POP, POPJ, JSR, JSP |
| 270-277 | ADD, ADDI, ADDM, ADDB, SUB, SUBI, SUBM, SUBB |
| 300-337 | Compare: CAIL/CAM, JUMP, SKIP families |
| 340-377 | AOJ, AOS, SOJ, SOS (add/sub 1 and jump/skip) |
| 400-477 | Boolean: SETZ, AND, ANDCA, SETM, XOR, OR, EQV, SETO, etc. |
| 500-577 | Half-word: HRL, HRR, HLL, HRLS, HRRI, etc. |
| 600-677 | Test: TDN, TDZ, TDO, TDC, TSN, TSZ, TSO, TSC |
| 700-777 | I/O: DATAI/DATAO/CONI/CONO (CTY console) |

### Registers
- **AC0–AC15** — 16 accumulators (also memory locations 0–17 octal)
- **PC** — 18-bit program counter
- **FLAGS** — Overflow, Carry0/1, Trap1/2, User mode, etc.

### Addressing Modes
- **Direct**: `MOVE 1, 1234` — load from address 1234 octal
- **Immediate**: `MOVEI 1, 42` — load constant 42
- **Indexed**: `MOVE 1, 100(2)` — address = 100 + AC2
- **Indirect**: `MOVE 1, @PTR` — load from address pointed to by PTR
- **Combined**: `MOVE 1, @100(2)` — indirect + indexed

---

## Assembly Language

### Syntax
```
LABEL:  MNEMONIC  AC, @OFFSET(INDEX)   ; comment
```

### Example Programs

**Hello World**
```asm
        LOC 200
START:  MOVEI 1, MSG    ; AC1 = address of message
LOOP:   MOVEI 0, @1     ; AC0 = char at AC1
        JUMPE 0, DONE   ; if null, done
        DATAO CTY, AC0  ; output char
        AOJA  1, LOOP   ; AC1++ and loop
DONE:   HALT
MSG:    ASCII "Hello, World!"
        END START
```

**Fibonacci**
```asm
        LOC 200
START:  MOVEI 0, 0      ; F(0)
        MOVEI 1, 1      ; F(1)
        MOVEI 2, 14     ; counter
LOOP:   MOVE  3, 1
        ADD   1, 0
        MOVE  0, 3
        SOJE  2, DONE
        JRST  LOOP
DONE:   HALT
        END START
```

---

## UI Features

| Tab | Features |
|---|---|
| **TERMINAL** | Console I/O, keyboard input, scrollback |
| **EDITOR** | Code editor, assemble button, syntax coloring |
| **DEBUG** | PC/Flags/ACs display, disassembly view, memory dump |

---

## Known Limitations

- Floating point (FP) instructions not implemented (KI10 FP needs separate unit)
- Virtual memory / TOPS-20 paging not implemented
- KL10/KS10 extended instruction sets not included
- No hardware interrupts or PI system (planned)
- I/O limited to console TTY (CTY device)

---

## References

- *PDP-10 Reference Manual*, DEC 1968
- *DECSYSTEM-10 Assembly Language Handbook*
- *TOPS-10 Monitor Calls Reference Manual*
- SimH PDP-10 simulator (reference implementation)
