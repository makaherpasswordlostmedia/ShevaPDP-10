package com.pdp10.emulator.memory;

/**
 * PDP-10 Memory subsystem
 * 
 * The PDP-10 uses 36-bit words with 18-bit addressing.
 * Physical memory: up to 256K words (262144 locations)
 * Java uses long (64-bit) to store 36-bit words.
 */
public class PDP10Memory {

    public static final int MEM_SIZE = 256 * 1024; // 256K words
    public static final long WORD_MASK = 0x_FFFFF_FFFFL; // 36 bits

    private long[] core;
    private boolean[] dirty; // for memory change tracking

    // Memory access statistics
    private long readCount = 0;
    private long writeCount = 0;

    // Memory breakpoint support
    public interface MemoryBreakpoint {
        void onRead(int address, long value);
        void onWrite(int address, long oldValue, long newValue);
    }

    private MemoryBreakpoint breakpoint;
    private boolean[] readBreakpoints;
    private boolean[] writeBreakpoints;

    public PDP10Memory() {
        core  = new long[MEM_SIZE];
        dirty = new boolean[MEM_SIZE];
        readBreakpoints  = new boolean[MEM_SIZE];
        writeBreakpoints = new boolean[MEM_SIZE];
        clear();
    }

    /**
     * Read a word from memory
     */
    public long read(int address) {
        address &= 0x3FFFF; // 18-bit address
        readCount++;
        long value = core[address] & WORD_MASK;
        if (readBreakpoints[address] && breakpoint != null) {
            breakpoint.onRead(address, value);
        }
        return value;
    }

    /**
     * Write a word to memory
     */
    public void write(int address, long value) {
        address &= 0x3FFFF;
        value &= WORD_MASK;
        writeCount++;
        if (writeBreakpoints[address] && breakpoint != null) {
            breakpoint.onWrite(address, core[address], value);
        }
        core[address] = value;
        dirty[address] = true;
    }

    /**
     * Clear all memory to zero
     */
    public void clear() {
        for (int i = 0; i < MEM_SIZE; i++) {
            core[i] = 0;
            dirty[i] = false;
        }
        readCount  = 0;
        writeCount = 0;
    }

    /**
     * Load a block of words into memory
     */
    public void loadWords(int startAddress, long[] words) {
        for (int i = 0; i < words.length; i++) {
            int addr = (startAddress + i) & 0x3FFFF;
            core[addr] = words[i] & WORD_MASK;
        }
    }

    /**
     * Load a SIMH/PDP-10 format binary image
     * Format: 5 bytes per word (36 bits + padding)
     */
    public void loadBinary(byte[] data, int startAddress) {
        int addr = startAddress;
        for (int i = 0; i + 4 < data.length; i += 5) {
            // PDP-10 word is packed as: bytes [4][3][2][1][0] = bits 35..0
            long word = ((long)(data[i]   & 0xFF) << 28) |
                        ((long)(data[i+1] & 0xFF) << 21) |
                        ((long)(data[i+2] & 0xFF) << 14) |
                        ((long)(data[i+3] & 0xFF) <<  7) |
                        ((long)(data[i+4] & 0x7F));
            core[addr & 0x3FFFF] = word & WORD_MASK;
            addr++;
        }
    }

    /**
     * Export memory region as bytes (5 bytes per word)
     */
    public byte[] exportBinary(int startAddress, int count) {
        byte[] data = new byte[count * 5];
        for (int i = 0; i < count; i++) {
            long word = core[(startAddress + i) & 0x3FFFF];
            data[i*5 + 0] = (byte)((word >> 28) & 0xFF);
            data[i*5 + 1] = (byte)((word >> 21) & 0xFF);
            data[i*5 + 2] = (byte)((word >> 14) & 0xFF);
            data[i*5 + 3] = (byte)((word >>  7) & 0xFF);
            data[i*5 + 4] = (byte)(word         & 0x7F);
        }
        return data;
    }

    /**
     * Dump memory as octal string for debugging
     */
    public String dumpOctal(int startAddress, int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) {
            int addr = (startAddress + i) & 0x3FFFF;
            if (i % 4 == 0) {
                if (i > 0) sb.append('\n');
                sb.append(String.format("%06o: ", addr));
            }
            sb.append(String.format("%012o ", core[addr]));
        }
        return sb.toString();
    }

    /**
     * Search memory for a pattern
     */
    public int findPattern(int startAddress, long pattern, long mask) {
        for (int i = startAddress; i < MEM_SIZE; i++) {
            if ((core[i] & mask) == (pattern & mask)) return i;
        }
        return -1;
    }

    public void setBreakpoint(MemoryBreakpoint bp) { this.breakpoint = bp; }
    public void setReadBreakpoint(int addr, boolean enable)  { readBreakpoints[addr & 0x3FFFF]  = enable; }
    public void setWriteBreakpoint(int addr, boolean enable) { writeBreakpoints[addr & 0x3FFFF] = enable; }

    public long getReadCount()  { return readCount;  }
    public long getWriteCount() { return writeCount; }
    public long[] getCore()     { return core; }
    public int getSize()        { return MEM_SIZE; }

    /**
     * Get a snapshot of memory for display (returns copy)
     */
    public long[] getSnapshot(int start, int length) {
        long[] snap = new long[length];
        for (int i = 0; i < length; i++) {
            snap[i] = core[(start + i) & 0x3FFFF] & WORD_MASK;
        }
        return snap;
    }

    /**
     * Check if memory region is non-zero (useful for display)
     */
    public boolean hasContent(int start, int length) {
        for (int i = 0; i < length; i++) {
            if (core[(start + i) & 0x3FFFF] != 0) return true;
        }
        return false;
    }
                  }
