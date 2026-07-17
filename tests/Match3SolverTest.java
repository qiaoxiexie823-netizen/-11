import com.ruisi.changanmatch.Match3Solver;

public class Match3SolverTest {
    public static void main(String[] args) {
        Match3Solver solver = new Match3Solver();

        int[][] horizontal = {
                {0, 1, 0},
                {2, 0, 3},
                {4, 0, 5}
        };
        Match3Solver.Move move = solver.findBest(horizontal);
        require(move != null, "expected a horizontal match move");
        require(move.matchedCells >= 3, "expected at least three matched cells");
        require(adjacent(move), "move must swap adjacent cells");

        int[][] vertical = {
                {0, 1, 2},
                {3, 0, 1},
                {4, 0, 2},
                {5, 3, 0}
        };
        move = solver.findBest(vertical);
        require(move != null, "expected a vertical match move");
        require(adjacent(move), "move must swap adjacent cells");

        int[][] invalid = {{0, 1}, {1, 0}};
        require(solver.findBest(invalid) == null, "boards smaller than 3x3 are invalid");

        System.out.println("Match3Solver tests passed");
    }

    private static boolean adjacent(Match3Solver.Move move) {
        return Math.abs(move.row1 - move.row2) + Math.abs(move.column1 - move.column2) == 1;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
