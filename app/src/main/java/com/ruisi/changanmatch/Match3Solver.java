package com.ruisi.changanmatch;

public final class Match3Solver {
    public static final class Move {
        public final int row1;
        public final int column1;
        public final int row2;
        public final int column2;
        public final int matchedCells;
        public final int score;

        Move(int row1, int column1, int row2, int column2, int matchedCells, int score) {
            this.row1 = row1;
            this.column1 = column1;
            this.row2 = row2;
            this.column2 = column2;
            this.matchedCells = matchedCells;
            this.score = score;
        }

        public String shortLabel() {
            return "R" + (row1 + 1) + "C" + (column1 + 1) + " → R" +
                    (row2 + 1) + "C" + (column2 + 1);
        }
    }

    private static final class MatchInfo {
        final boolean[][] marked;
        int count;
        int longest;

        MatchInfo(int rows, int columns) {
            marked = new boolean[rows][columns];
        }
    }

    public Move findBest(int[][] board) {
        if (!isRectangular(board)) return null;
        int rows = board.length;
        int columns = board[0].length;
        Move best = null;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                if (column + 1 < columns) {
                    Move move = testSwap(board, row, column, row, column + 1);
                    if (better(move, best)) best = move;
                }
                if (row + 1 < rows) {
                    Move move = testSwap(board, row, column, row + 1, column);
                    if (better(move, best)) best = move;
                }
            }
        }
        return best;
    }

    private Move testSwap(int[][] board, int r1, int c1, int r2, int c2) {
        if (board[r1][c1] < 0 || board[r2][c2] < 0 || board[r1][c1] == board[r2][c2]) {
            return null;
        }
        int temp = board[r1][c1];
        board[r1][c1] = board[r2][c2];
        board[r2][c2] = temp;
        MatchInfo info = collectMatches(board);
        temp = board[r1][c1];
        board[r1][c1] = board[r2][c2];
        board[r2][c2] = temp;

        if (!info.marked[r1][c1] && !info.marked[r2][c2]) return null;
        int centerBonus = centerBonus(board.length, board[0].length, r1, c1, r2, c2);
        int score = info.count * 100 + info.longest * 25 + centerBonus;
        return new Move(r1, c1, r2, c2, info.count, score);
    }

    private MatchInfo collectMatches(int[][] board) {
        int rows = board.length;
        int columns = board[0].length;
        MatchInfo info = new MatchInfo(rows, columns);
        for (int row = 0; row < rows; row++) {
            int start = 0;
            while (start < columns) {
                int end = start + 1;
                while (end < columns && board[row][end] == board[row][start]) end++;
                int length = end - start;
                if (board[row][start] >= 0 && length >= 3) {
                    info.longest = Math.max(info.longest, length);
                    for (int column = start; column < end; column++) mark(info, row, column);
                }
                start = end;
            }
        }
        for (int column = 0; column < columns; column++) {
            int start = 0;
            while (start < rows) {
                int end = start + 1;
                while (end < rows && board[end][column] == board[start][column]) end++;
                int length = end - start;
                if (board[start][column] >= 0 && length >= 3) {
                    info.longest = Math.max(info.longest, length);
                    for (int row = start; row < end; row++) mark(info, row, column);
                }
                start = end;
            }
        }
        return info;
    }

    private void mark(MatchInfo info, int row, int column) {
        if (!info.marked[row][column]) {
            info.marked[row][column] = true;
            info.count++;
        }
    }

    private boolean better(Move candidate, Move current) {
        return candidate != null && (current == null || candidate.score > current.score);
    }

    private int centerBonus(int rows, int columns, int r1, int c1, int r2, int c2) {
        double centerRow = (rows - 1) / 2.0;
        double centerColumn = (columns - 1) / 2.0;
        double distance = Math.abs(r1 - centerRow) + Math.abs(c1 - centerColumn) +
                Math.abs(r2 - centerRow) + Math.abs(c2 - centerColumn);
        return Math.max(0, 20 - (int) Math.round(distance * 2));
    }

    private boolean isRectangular(int[][] board) {
        if (board == null || board.length < 3 || board[0] == null || board[0].length < 3) return false;
        int columns = board[0].length;
        for (int[] row : board) if (row == null || row.length != columns) return false;
        return true;
    }
}
