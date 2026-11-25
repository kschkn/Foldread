package ru.foldread;

/** Recognises a brief open → bent → open cycle, including three-state hinge sensors. */
final class FoldGesture {
    private boolean startedOpen;
    private long bentAt = -1;
    private long lastTurn = -2000;

    boolean sample(float angle, long millis) {
        if (!Float.isFinite(angle) || angle < 55) {
            reset();
            return false;
        }
        if (angle >= 168) {
            boolean turn = startedOpen && bentAt >= 0 && millis - bentAt <= 3500
                    && millis - lastTurn >= 750;
            startedOpen = true;
            bentAt = -1;
            if (turn) lastTurn = millis;
            return turn;
        }
        if (startedOpen && angle <= 148 && bentAt < 0) bentAt = millis;
        return false;
    }

    void reset() {
        startedOpen = false;
        bentAt = -1;
    }
}
