package com.ruisi.changanmatch;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.Arrays;

public final class BoardAnalyzer {
    private static final int SAMPLE_GRID = 5;
    private static final int CHANNELS = 4;

    public static final class Frame {
        public final double[][] features;
        public final int rows;
        public final int columns;

        Frame(double[][] features, int rows, int columns) {
            this.features = features;
            this.rows = rows;
            this.columns = columns;
        }
    }

    public Frame extract(Bitmap bitmap, Rect board, int rows, int columns) {
        if (bitmap == null || board == null || rows < 3 || columns < 3) return null;
        Rect safe = new Rect(
                clamp(board.left, 0, bitmap.getWidth() - 1),
                clamp(board.top, 0, bitmap.getHeight() - 1),
                clamp(board.right, 1, bitmap.getWidth()),
                clamp(board.bottom, 1, bitmap.getHeight()));
        if (safe.width() < columns * 8 || safe.height() < rows * 8) return null;

        double[][] features = new double[rows * columns][SAMPLE_GRID * SAMPLE_GRID * CHANNELS];
        double cellWidth = safe.width() / (double) columns;
        double cellHeight = safe.height() / (double) rows;
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                int index = row * columns + column;
                fillCellFeature(bitmap, safe, row, column, cellWidth, cellHeight, features[index]);
            }
        }
        return new Frame(features, rows, columns);
    }

    private void fillCellFeature(Bitmap bitmap, Rect board, int row, int column,
                                 double cellWidth, double cellHeight, double[] output) {
        int cursor = 0;
        int radius = Math.max(1, (int) Math.round(Math.min(cellWidth, cellHeight) * 0.025));
        for (int sy = 0; sy < SAMPLE_GRID; sy++) {
            for (int sx = 0; sx < SAMPLE_GRID; sx++) {
                double fx = 0.18 + sx * (0.64 / (SAMPLE_GRID - 1));
                double fy = 0.18 + sy * (0.64 / (SAMPLE_GRID - 1));
                int x = (int) Math.round(board.left + (column + fx) * cellWidth);
                int y = (int) Math.round(board.top + (row + fy) * cellHeight);
                int color = averageColor(bitmap, x, y, radius);
                float[] hsv = new float[3];
                Color.colorToHSV(color, hsv);
                output[cursor++] = Color.red(color) / 255.0;
                output[cursor++] = Color.green(color) / 255.0;
                output[cursor++] = Color.blue(color) / 255.0;
                output[cursor++] = (hsv[1] * 0.65) + (hsv[2] * 0.35);
            }
        }
    }

    private int averageColor(Bitmap bitmap, int centerX, int centerY, int radius) {
        long red = 0, green = 0, blue = 0;
        int count = 0;
        int left = clamp(centerX - radius, 0, bitmap.getWidth() - 1);
        int right = clamp(centerX + radius, 0, bitmap.getWidth() - 1);
        int top = clamp(centerY - radius, 0, bitmap.getHeight() - 1);
        int bottom = clamp(centerY + radius, 0, bitmap.getHeight() - 1);
        for (int y = top; y <= bottom; y++) {
            for (int x = left; x <= right; x++) {
                int color = bitmap.getPixel(x, y);
                red += Color.red(color);
                green += Color.green(color);
                blue += Color.blue(color);
                count++;
            }
        }
        if (count == 0) return Color.BLACK;
        return Color.rgb((int) (red / count), (int) (green / count), (int) (blue / count));
    }

    public double difference(Frame first, Frame second) {
        if (first == null || second == null || first.rows != second.rows ||
                first.columns != second.columns || first.features.length != second.features.length) {
            return Double.POSITIVE_INFINITY;
        }
        double sum = 0;
        for (int i = 0; i < first.features.length; i++) {
            sum += distance(first.features[i], second.features[i]);
        }
        return sum / Math.max(1, first.features.length);
    }

    public int[][] classify(Frame frame, int requestedKinds) {
        if (frame == null || frame.features.length == 0) return null;
        int kinds = clamp(requestedKinds, 2, frame.features.length);
        int dimensions = frame.features[0].length;
        double[][] centroids = initializeCentroids(frame.features, kinds, dimensions);
        int[] assignments = new int[frame.features.length];
        Arrays.fill(assignments, -1);

        for (int iteration = 0; iteration < 16; iteration++) {
            boolean changed = false;
            for (int i = 0; i < frame.features.length; i++) {
                int nearest = nearest(frame.features[i], centroids);
                if (assignments[i] != nearest) {
                    assignments[i] = nearest;
                    changed = true;
                }
            }

            double[][] sums = new double[kinds][dimensions];
            int[] counts = new int[kinds];
            for (int i = 0; i < frame.features.length; i++) {
                int group = assignments[i];
                counts[group]++;
                for (int d = 0; d < dimensions; d++) sums[group][d] += frame.features[i][d];
            }
            for (int group = 0; group < kinds; group++) {
                if (counts[group] == 0) {
                    int farthest = farthestSample(frame.features, assignments, centroids);
                    centroids[group] = frame.features[farthest].clone();
                } else {
                    for (int d = 0; d < dimensions; d++) {
                        centroids[group][d] = sums[group][d] / counts[group];
                    }
                }
            }
            if (!changed && iteration > 1) break;
        }

        int[][] board = new int[frame.rows][frame.columns];
        for (int row = 0; row < frame.rows; row++) {
            for (int column = 0; column < frame.columns; column++) {
                board[row][column] = assignments[row * frame.columns + column];
            }
        }
        return board;
    }

    private double[][] initializeCentroids(double[][] features, int kinds, int dimensions) {
        double[] mean = new double[dimensions];
        for (double[] feature : features) {
            for (int d = 0; d < dimensions; d++) mean[d] += feature[d];
        }
        for (int d = 0; d < dimensions; d++) mean[d] /= features.length;

        double[][] centroids = new double[kinds][dimensions];
        int first = 0;
        double max = -1;
        for (int i = 0; i < features.length; i++) {
            double value = distance(features[i], mean);
            if (value > max) {
                max = value;
                first = i;
            }
        }
        centroids[0] = features[first].clone();
        for (int group = 1; group < kinds; group++) {
            int farthest = 0;
            double bestDistance = -1;
            for (int i = 0; i < features.length; i++) {
                double nearestDistance = Double.POSITIVE_INFINITY;
                for (int previous = 0; previous < group; previous++) {
                    nearestDistance = Math.min(nearestDistance,
                            distance(features[i], centroids[previous]));
                }
                if (nearestDistance > bestDistance) {
                    bestDistance = nearestDistance;
                    farthest = i;
                }
            }
            centroids[group] = features[farthest].clone();
        }
        return centroids;
    }

    private int nearest(double[] feature, double[][] centroids) {
        int nearest = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int group = 0; group < centroids.length; group++) {
            double value = distanceSquared(feature, centroids[group]);
            if (value < best) {
                best = value;
                nearest = group;
            }
        }
        return nearest;
    }

    private int farthestSample(double[][] features, int[] assignments, double[][] centroids) {
        int farthest = 0;
        double best = -1;
        for (int i = 0; i < features.length; i++) {
            int group = assignments[i] < 0 ? 0 : assignments[i];
            double value = distanceSquared(features[i], centroids[group]);
            if (value > best) {
                best = value;
                farthest = i;
            }
        }
        return farthest;
    }

    private double distance(double[] first, double[] second) {
        return Math.sqrt(distanceSquared(first, second) / Math.max(1, first.length));
    }

    private double distanceSquared(double[] first, double[] second) {
        double sum = 0;
        int length = Math.min(first.length, second.length);
        for (int i = 0; i < length; i++) {
            double delta = first[i] - second[i];
            sum += delta * delta;
        }
        return sum;
    }

    public String signature(int[][] board) {
        if (board == null) return "";
        StringBuilder builder = new StringBuilder(board.length * board[0].length * 2);
        for (int[] row : board) {
            for (int value : row) builder.append((char) ('A' + Math.max(0, value)));
            builder.append('|');
        }
        return builder.toString();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
