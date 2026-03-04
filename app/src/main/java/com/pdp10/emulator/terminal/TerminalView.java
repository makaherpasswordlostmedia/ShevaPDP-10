package com.pdp10.emulator.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * PDP-10 Terminal — green-on-black, 80x24.
 * Thread-safe: all screen mutations are synchronized.
 */
public class TerminalView extends View {

    private static final int COLS = 80;
    private static final int ROWS = 24;
    private static final int MAX_SCROLLBACK = 500;
    private static final long BLINK_MS = 500;

    // Screen buffer
    private final char[][]    screen = new char[ROWS][COLS];
    private final int[][]     fgBuf  = new int[ROWS][COLS];
    private int cursorRow = 0;
    private int cursorCol = 0;
    private boolean cursorBlink = true;

    // Input
    private final LinkedBlockingQueue<Integer> inputQueue = new LinkedBlockingQueue<>(4096);
    private final StringBuilder currentLine = new StringBuilder();

    // Paint
    private Paint textPaint;
    private Paint cursorPaint;
    private float charW;
    private float charH;
    private float charAscent;
    private static final float PAD = 4f;

    // Colors
    private static final int COLOR_BG     = 0xFF0D0D0D;
    private static final int COLOR_FG     = 0xFF00FF41;
    private static final int COLOR_CURSOR = 0xFF00FF41;
    private static final int COLOR_INFO   = 0xFF44FFFF;
    private static final int COLOR_ERR    = 0xFFFF4444;
    private static final int COLOR_DIM    = 0xFF007020;

    public TerminalView(Context ctx)                        { super(ctx);       init(); }
    public TerminalView(Context ctx, AttributeSet attrs)    { super(ctx, attrs); init(); }
    public TerminalView(Context ctx, AttributeSet a, int d) { super(ctx, a, d); init(); }

    private void init() {
        clearScreenLocked();

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(28f);
        textPaint.setColor(COLOR_FG);

        cursorPaint = new Paint();
        cursorPaint.setColor(COLOR_CURSOR);
        cursorPaint.setAlpha(200);

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        charH      = fm.descent - fm.ascent;
        charAscent = -fm.ascent;
        charW      = textPaint.measureText("M");

        setFocusable(true);
        setFocusableInTouchMode(true);
        postDelayed(blinkRunnable, BLINK_MS);
    }

    private final Runnable blinkRunnable = new Runnable() {
        @Override public void run() {
            cursorBlink = !cursorBlink;
            invalidate();
            postDelayed(blinkRunnable, BLINK_MS);
        }
    };

    // =========================================================================
    // Draw
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(COLOR_BG);
        char[][]  sc;
        int[][]   fg;
        int       cr, cc;
        synchronized (this) {
            sc = new char[ROWS][COLS];
            fg = new int[ROWS][COLS];
            for (int r = 0; r < ROWS; r++) {
                sc[r] = screen[r].clone();
                fg[r] = fgBuf[r].clone();
            }
            cr = cursorRow;
            cc = cursorCol;
        }
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                char c = sc[row][col];
                if (c != 0 && c != ' ') {
                    float x = PAD + col * charW;
                    float y = PAD + row * charH + charAscent;
                    textPaint.setColor(fg[row][col] != 0 ? fg[row][col] : COLOR_FG);
                    canvas.drawText(String.valueOf(c), x, y, textPaint);
                }
            }
        }
        if (cursorBlink) {
            float cx = PAD + cc * charW;
            float cy = PAD + cr * charH;
            canvas.drawRect(cx, cy + charH - 3f, cx + charW, cy + charH, cursorPaint);
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = Math.max(getSuggestedMinimumWidth(),
                         (int)(PAD * 2 + COLS * charW));
        int h = Math.max(getSuggestedMinimumHeight(),
                         (int)(PAD * 2 + ROWS * charH));
        setMeasuredDimension(
            resolveSize(w, wSpec),
            resolveSize(h, hSpec));
    }

    // =========================================================================
    // Output — all synchronized, all bounds-checked
    // =========================================================================

    public void print(String text) {
        synchronized (this) {
            for (int i = 0; i < text.length(); i++) putChar(text.charAt(i), COLOR_FG);
        }
        postInvalidate();
    }

    public void printChar(char c) {
        synchronized (this) { putChar(c, COLOR_FG); }
        postInvalidate();
    }

    public void printInfo(String s) {
        synchronized (this) {
            for (int i = 0; i < s.length(); i++) putChar(s.charAt(i), COLOR_INFO);
            putChar('\n', COLOR_INFO);
        }
        postInvalidate();
    }

    public void printError(String s) {
        synchronized (this) {
            for (int i = 0; i < s.length(); i++) putChar(s.charAt(i), COLOR_ERR);
            putChar('\n', COLOR_ERR);
        }
        postInvalidate();
    }

    public void printLine(String s) { print(s + "\n"); }

    public synchronized void clearScreen() {
        clearScreenLocked();
        postInvalidate();
    }

    // Call only while holding lock
    private void clearScreenLocked() {
        for (int r = 0; r < ROWS; r++) {
            screen[r] = new char[COLS];
            fgBuf[r]  = new int[COLS];
        }
        cursorRow = 0;
        cursorCol = 0;
    }

    /** Core character writer — caller must hold lock. */
    private void putChar(char c, int color) {
        // Clamp cursor just in case
        cursorRow = Math.max(0, Math.min(cursorRow, ROWS - 1));
        cursorCol = Math.max(0, Math.min(cursorCol, COLS - 1));

        switch (c) {
            case '\n':
                cursorCol = 0;
                cursorRow++;
                if (cursorRow >= ROWS) { scrollUpLocked(); cursorRow = ROWS - 1; }
                break;
            case '\r':
                cursorCol = 0;
                break;
            case '\b':
                if (cursorCol > 0) { cursorCol--; screen[cursorRow][cursorCol] = ' '; }
                break;
            case '\t':
                cursorCol = ((cursorCol / 8) + 1) * 8;
                if (cursorCol >= COLS) { cursorCol = 0; cursorRow++;
                    if (cursorRow >= ROWS) { scrollUpLocked(); cursorRow = ROWS - 1; } }
                break;
            default:
                if (c >= 32 && c < 127) {
                    screen[cursorRow][cursorCol] = c;
                    fgBuf[cursorRow][cursorCol]  = color;
                    cursorCol++;
                    if (cursorCol >= COLS) {
                        cursorCol = 0;
                        cursorRow++;
                        if (cursorRow >= ROWS) { scrollUpLocked(); cursorRow = ROWS - 1; }
                    }
                }
        }
    }

    private void scrollUpLocked() {
        char[] top = screen[0].clone();
        for (int r = 0; r < ROWS - 1; r++) {
            screen[r] = screen[r + 1];
            fgBuf[r]  = fgBuf[r + 1];
        }
        screen[ROWS - 1] = new char[COLS];
        fgBuf[ROWS - 1]  = new int[COLS];
    }

    // =========================================================================
    // Input
    // =========================================================================

    public int readInputChar() {
        Integer c = inputQueue.poll();
        return c != null ? c : -1;
    }

    private void handleInput(char c) {
        if (c == '\n' || c == '\r') {
            print("\n");
            String line = currentLine.toString();
            currentLine.setLength(0);
            for (char lc : (line + "\n").toCharArray()) inputQueue.offer((int) lc);
        } else if (c == '\b') {
            if (currentLine.length() > 0) {
                currentLine.deleteCharAt(currentLine.length() - 1);
                print("\b \b");
            }
        } else if (c >= 32 && c < 127) {
            currentLine.append(c);
            print(String.valueOf(c));
            inputQueue.offer((int) c);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        char c = (char) event.getUnicodeChar();
        if (c != 0) { handleInput(c); return true; }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:   handleInput('\b'); return true;
            case KeyEvent.KEYCODE_ENTER: handleInput('\n'); return true;
            case KeyEvent.KEYCODE_TAB:   handleInput('\t'); return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType  = InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE;
        return new BaseInputConnection(this, true) {
            @Override public boolean commitText(CharSequence text, int pos) {
                for (int i = 0; i < text.length(); i++) handleInput(text.charAt(i));
                return true;
            }
            @Override public boolean deleteSurroundingText(int before, int after) {
                if (before > 0) handleInput('\b');
                return true;
            }
            @Override public boolean sendKeyEvent(KeyEvent event) {
                if (event.getAction() == KeyEvent.ACTION_DOWN)
                    TerminalView.this.onKeyDown(event.getKeyCode(), event);
                return super.sendKeyEvent(event);
            }
        };
    }

    @Override public boolean onCheckIsTextEditor() { return true; }

    public void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager)
            getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
    }
}
