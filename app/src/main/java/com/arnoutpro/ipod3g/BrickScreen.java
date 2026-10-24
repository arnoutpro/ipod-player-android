package com.arnoutpro.ipod3g;

import android.graphics.Canvas;

/** Extras > Games > Brick: turn the wheel to move the paddle, select to serve. */
public class BrickScreen extends Screen {
    private static final int COLS = 8, ROWS = 4;
    private static final float BRICK_W = 36, BRICK_H = 10, GAP = 3, TOP = 26;
    private static final float PADDLE_W = 52, PADDLE_H = 6, BALL = 6;

    private final boolean[][] bricks = new boolean[ROWS][COLS];
    private float w = Lcd.W, h = Lcd.H - Lcd.TITLE_H;
    private float paddleX = (Lcd.W - PADDLE_W) / 2f;
    private float bx, by, vx, vy;
    private boolean served;
    private int lives = 3, score, level = 1, left;
    private boolean over;
    private long lastFrame;

    public BrickScreen(Lcd lcd) {
        super(lcd, "Brick");
        newLevel();
    }

    private void newLevel() {
        left = 0;
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++) { bricks[r][c] = true; left++; }
        served = false;
    }

    @Override
    public boolean scroll(int d, long now) {
        float old = paddleX;
        paddleX = Math.max(0, Math.min(w - PADDLE_W, paddleX + d * 14));
        return old != paddleX;
    }

    @Override
    public void select() {
        if (over) {
            over = false;
            lives = 3;
            score = 0;
            level = 1;
            newLevel();
            return;
        }
        if (!served) {
            served = true;
            float speed = 150 + level * 20;
            vx = speed * 0.6f * (Math.random() < 0.5 ? -1 : 1);
            vy = -speed;
        }
    }

    @Override
    public long frameDelay(long now) { return 16; }

    @Override
    public void draw(Canvas c, int cw, int ch, long now) {
        w = cw;
        h = ch;
        float dt = lastFrame == 0 ? 0 : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        float py = h - 16;
        if (!served) {
            bx = paddleX + PADDLE_W / 2f - BALL / 2f;
            by = py - BALL - 1;
        } else if (!over) {
            step(dt, py);
        }

        c.drawText("Score " + score, 8, 18, lcd.small);
        String lv = "Lives " + lives;
        c.drawText(lv, w - 8 - lcd.small.measureText(lv), 18, lcd.small);

        float x0 = (w - (COLS * BRICK_W + (COLS - 1) * GAP)) / 2f;
        for (int r = 0; r < ROWS; r++)
            for (int col = 0; col < COLS; col++)
                if (bricks[r][col]) {
                    float x = x0 + col * (BRICK_W + GAP), y = TOP + r * (BRICK_H + GAP);
                    c.drawRect(x, y, x + BRICK_W, y + BRICK_H, r % 2 == 0 ? lcd.ink : lcd.dither);
                    if (r % 2 == 1) c.drawRect(x, y, x + BRICK_W, y + BRICK_H, lcd.stroke);
                }

        c.drawRect(paddleX, py, paddleX + PADDLE_W, py + PADDLE_H, lcd.ink);
        if (!over) c.drawRect(bx, by, bx + BALL, by + BALL, lcd.ink);

        String msg = over ? "Game Over" : !served ? "Press Select" : null;
        if (msg != null) {
            float tw = lcd.text.measureText(msg);
            c.drawText(msg, (w - tw) / 2f, h * 0.68f, lcd.text);
        }
    }

    private void step(float dt, float py) {
        bx += vx * dt;
        by += vy * dt;
        if (bx < 0) { bx = 0; vx = Math.abs(vx); }
        if (bx + BALL > w) { bx = w - BALL; vx = -Math.abs(vx); }
        if (by < 22) { by = 22; vy = Math.abs(vy); }

        // Paddle: the bounce angle depends on where the ball lands.
        if (vy > 0 && by + BALL >= py && by + BALL <= py + PADDLE_H + 6
                && bx + BALL >= paddleX && bx <= paddleX + PADDLE_W) {
            float speed = (float) Math.hypot(vx, vy);
            float hit = ((bx + BALL / 2f) - (paddleX + PADDLE_W / 2f)) / (PADDLE_W / 2f);
            hit = Math.max(-0.85f, Math.min(0.85f, hit));
            vx = speed * hit;
            vy = -(float) Math.sqrt(Math.max(1, speed * speed - vx * vx));
            by = py - BALL;
        }

        float x0 = (w - (COLS * BRICK_W + (COLS - 1) * GAP)) / 2f;
        outer:
        for (int r = 0; r < ROWS; r++)
            for (int col = 0; col < COLS; col++) {
                if (!bricks[r][col]) continue;
                float x = x0 + col * (BRICK_W + GAP), y = TOP + r * (BRICK_H + GAP);
                if (bx + BALL > x && bx < x + BRICK_W && by + BALL > y && by < y + BRICK_H) {
                    bricks[r][col] = false;
                    left--;
                    score += (ROWS - r) * 10;
                    float overlapX = Math.min(bx + BALL - x, x + BRICK_W - bx);
                    float overlapY = Math.min(by + BALL - y, y + BRICK_H - by);
                    if (overlapX < overlapY) vx = -vx; else vy = -vy;
                    break outer;
                }
            }
        if (left == 0) {
            level++;
            newLevel();
        }
        if (by > h) {
            lives--;
            served = false;
            if (lives <= 0) over = true;
        }
    }
}
