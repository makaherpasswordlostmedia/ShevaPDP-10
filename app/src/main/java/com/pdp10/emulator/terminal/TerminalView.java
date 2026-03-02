package com.pdp10.emulator.terminal;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Terminal emulator view for PDP-10
 * Renders monospace text in classic green-on-black terminal style.
 * Handles keyboard input and output buffering.
 */
public class TerminalView extends View {

    // Terminal dimensions
    private int cols = 80;
    private int rows = 24;

    // Character buffer: rows x cols
    private char[][] screen;
    private int[][] fg;      // Foreground color per cell
    private int[][] bg;      // Background color per cell
    private boolean[][] bold;
    private int cursorRow = 0;
    private int cursorCol = 0;
    private boolean cursorVisible = true;
    private boolean cursorBlink = true;

    // Scroll back buffer
    private static final int MAX_SCROLLBACK = 500;
    private List<char[]> scrollback = new ArrayList<>();
    private int scrollOffset = 0;

    // Input buffer
    private LinkedBlockingQueue<Integer> inputQueue = new LinkedBlockingQueue<>(1024);
    private StringBuilder currentLine = new StringBuilder();

    // Painting
    private Paint textPaint;
    private Paint bgPaint;
    private Paint cursorPaint;
    private float charWidth;
    private float charHeight;
    private float charAscent;
    private float paddingLeft = 4f;
    private float paddingTop  = 4f;

    // Colors
    private int colorFg       = Color.parseColor("#00FF41");  // Matrix green
    private int colorBg       = Color.parseColor("#0D0D0D");  // Near black
    private int colorCursor   = Color.parseColor("#00FF41");
    private int colorBright   = Color.parseColor("#AAFFAA");
    private int colorDim      = Color.parseColor("#007020");
    private int colorRed      = Color.parseColor("#FF4444");
    private int colorYellow   = Color.parseColor("#FFFF44");
    private int colorCyan     = Color.parseColor("#44FFFF");

    // Cursor blink
    private long lastBlink = 0;
    private static final long BLINK_INTERVAL_MS = 500;

    // Input listener
    public interface InputListener {
        void onChar(char c);
        void onLine(String line);
    }
    private InputListener inputListener;

    public TerminalView(Context context) {
        super(context);
        init();
    }

    public TerminalView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        screen = new char[rows][cols];
        fg     = new int[rows][cols];
        bg     = new int[rows][cols];
        bold   = new boolean[rows][cols];
        clearScreen();

        // Text paint
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextSize(28f);
        textPaint.setColor(colorFg);

        // Background paint
        bgPaint = new Paint();
        bgPaint.setColor(colorBg);

        // Cursor paint
        cursorPaint = new Paint();
        cursorPaint.setColor(colorCursor);
        cursorPaint.setAlpha(200);

        // Calculate char dimensions
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        charHeight = fm.descent - fm.ascent;
        charAscent = -fm.ascent;
        charWidth  = textPaint.measureText("M");

        setFocusable(true);
        setFocusableInTouchMode(true);

        // Start cursor blink
        postDelayed(blinkRunnable, BLINK_INTERVAL_MS);
    }

    private final Runnable blinkRunnable = new Runnable() {
        @Override public void run() {
            cursorBlink = !cursorBlink;
            invalidate();
            postDelayed(blinkRunnable, BLINK_INTERVAL_MS);
        }
    };

    // =========================================================================
    // Drawing
    // =========================================================================

    @Override
    protected void onDraw(Canvas canvas) {
        // Background
        canvas.drawColor(colorBg);

        int displayRows = rows;
        int startRow    = 0;

        // Draw each character
        for (int row = 0; row < displayRows; row++) {
            for (int col = 0; col < cols; col++) {
                float x = paddingLeft + col * charWidth;
                float y = paddingTop  + row * charHeight;

                // Background
                int cellBg = bg[row][col];
                if (cellBg != colorBg) {
                    bgPaint.setColor(cellBg);
                    canvas.drawRect(x, y, x + charWidth, y + charHeight, bgPaint);
                }

                // Character
                char c = screen[row][col];
                if (c != ' ' && c != 0) {
                    textPaint.setColor(fg[row][col] != 0 ? fg[row][col] : colorFg);
                    textPaint.setFakeBoldText(bold[row][col]);
                    canvas.drawText(String.valueOf(c), x, y + charAscent, textPaint);
                }
            }
        }

        // Cursor
        if (cursorVisible && cursorBlink) {
            float cx = paddingLeft + cursorCol * charWidth;
            float cy = paddingTop  + cursorRow * charHeight;
            cursorPaint.setColor(colorCursor);
            canvas.drawRect(cx, cy + charHeight - 3, cx + charWidth, cy + charHeight, cursorPaint);
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = (int)(paddingLeft * 2 + cols * charWidth);
        int h = (int)(paddingTop  * 2 + rows * charHeight);
        setMeasuredDimension(w, h);
    }

    // =========================================================================
    // Screen operations
    // =========================================================================

    public synchronized void print(String text) {
        for (char c : text.toCharArray()) {
            printChar(c);
        }
        post(() -> invalidate());
    }

    public synchronized void printChar(char c) {
        switch (c) {
            case '\n':
                newLine();
                break;
            case '\r':
                cursorCol = 0;
                break;
            case '\b':  // Backspace
                if (cursorCol > 0) {
                    cursorCol--;
                    screen[cursorRow][cursorCol] = ' ';
                }
                break;
            case '\t':  // Tab
                cursorCol = ((cursorCol / 8) + 1) * 8;
                if (cursorCol >= cols) { cursorCol = 0; newLine(); }
                break;
            case 7:     // Bell - ignore
                break;
            default:
                if (c >= 32 && c < 127) {
                    screen[cursorRow][cursorCol] = c;
                    fg[cursorRow][cursorCol] = colorFg;
                    bg[cursorRow][cursorCol] = colorBg;
                    cursorCol++;
                    if (cursorCol >= cols) {
                        cursorCol = 0;
                        newLine();
                    }
                }
        }
    }

    private void newLine() {
        cursorRow++;
        cursorCol = 0;
        if (cursorRow >= rows) {
            scrollUp();
            cursorRow = rows - 1;
        }
    }

    private void scrollUp() {
        // Save top row to scrollback
        scrollback.add(screen[0].clone());
        if (scrollback.size() > MAX_SCROLLBACK) scrollback.remove(0);

        // Shift rows up
        for (int r = 0; r < rows - 1; r++) {
            screen[r] = screen[r + 1];
            fg[r] = fg[r + 1];
            bg[r] = bg[r + 1];
            bold[r] = bold[r + 1];
        }
        // Clear last row
        screen[rows - 1] = new char[cols];
        fg[rows - 1]     = new int[cols];
        bg[rows - 1]     = new int[cols];
        bold[rows - 1]   = new boolean[cols];
    }

    public synchronized void clearScreen() {
        for (int r = 0; r < rows; r++) {
            screen[r] = new char[cols];
            fg[r]     = new int[cols];
            bg[r]     = new int[cols];
            bold[r]   = new boolean[cols];
        }
        cursorRow = 0;
        cursorCol = 0;
    }

    public void printLine(String s) { print(s + "\n"); }

    public void printError(String s) {
        // Print in red
        for (int i = 0; i < s.length(); i++) {
            screen[cursorRow][cursorCol] = s.charAt(i);
            fg[cursorRow][cursorCol] = colorRed;
            cursorCol++;
            if (cursorCol >= cols) { cursorCol = 0; newLine(); }
        }
        print("\n");
    }

    public void printInfo(String s) {
        for (int i = 0; i < s.length(); i++) {
            screen[cursorRow][cursorCol] = s.charAt(i);
            fg[cursorRow][cursorCol] = colorCyan;
            cursorCol++;
            if (cursorCol >= cols) { cursorCol = 0; newLine(); }
        }
        print("\n");
    }

    // =========================================================================
    // Input handling
    // =========================================================================

    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT;
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE;
        return new TerminalInputConnection(this, true);
    }

    @Override
    public boolean onCheckIsTextEditor() { return true; }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        char c = (char)event.getUnicodeChar();
        if (c != 0) {
            handleInput(c);
            return true;
        }
        switch (keyCode) {
            case KeyEvent.KEYCODE_DEL:
                handleInput('\b');
                return true;
            case KeyEvent.KEYCODE_ENTER:
                handleInput('\n');
                return true;
            case KeyEvent.KEYCODE_TAB:
                handleInput('\t');
                return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void handleInput(char c) {
        // Echo character
        if (c == '\n') {
            print("\n");
            String line = currentLine.toString();
            currentLine.setLength(0);
            // Enqueue line characters + newline
            for (char lc : (line + "\n").toCharArray()) {
                inputQueue.offer((int)lc);
            }
            if (inputListener != null) inputListener.onLine(line);
        } else if (c == '\b') {
            if (currentLine.length() > 0) {
                currentLine.deleteCharAt(currentLine.length() - 1);
                print("\b \b");
            }
        } else if (c >= 32 && c < 127) {
            currentLine.append(c);
            print(String.valueOf(c));
            inputQueue.offer((int)c);
        }
        if (inputListener != null) inputListener.onChar(c);
    }

    /** Called by CPU to get the next input character. Returns -1 if none. */
    public int readInputChar() {
        Integer c = inputQueue.poll();
        return (c != null) ? c : -1;
    }

    public void setInputListener(InputListener l) { this.inputListener = l; }

    // =========================================================================
    // Keyboard popup
    // =========================================================================

    public void showKeyboard() {
        requestFocus();
        InputMethodManager imm = (InputMethodManager)getContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(this, InputMethodManager.SHOW_FORCED);
    }

    public void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager)getContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(getWindowToken(), 0);
    }

    // =========================================================================
    // Custom InputConnection
    // =========================================================================

    private class TerminalInputConnection extends BaseInputConnection {
        public TerminalInputConnection(View targetView, boolean fullEditor) {
            super(targetView, fullEditor);
        }

        @Override
        public boolean commitText(CharSequence text, int newCursorPosition) {
            for (int i = 0; i < text.length(); i++) {
                handleInput(text.charAt(i));
            }
            return true;
        }

        @Override
        public boolean deleteSurroundingText(int beforeLength, int afterLength) {
            if (beforeLength > 0) handleInput('\b');
            return true;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                TerminalView.this.onKeyDown(event.getKeyCode(), event);
            }
            return super.sendKeyEvent(event);
        }
    }
            }
