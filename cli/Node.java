import module java.base;

/// A node of the syntax tree: an expression, and the part of the input it came from
/// (code-point offsets [start, end)), so an error can point at the part that failed.
///
/// Every value is a list; a single number is a list of one, and arithmetic applies
/// element by element.
record Node(Node.Expr expr, int start, int end) {

    enum Op { PLUS, MINUS, TIMES, DIVIDE, MOD, POWER }

    /// Sealed, so `eval`, `any` and `isElementwise` handle every kind of node or don't compile.
    sealed interface Expr {
        record Num(Value value) implements Expr { }
        /// `@`: the list read from stdin.
        record Stdin() implements Expr { }
        record Neg(Node operand) implements Expr { }
        record Sqrt(Node operand) implements Expr { }
        record Binary(Op op, Node left, Node right) implements Expr { }
        record Call(Func func, Node args) implements Expr { }
        record ListOf(List<Node> items) implements Expr { }
        record Range(Node from, Node to) implements Expr { }
    }

    static Node binary(Op op, Node left, Node right) {
        return new Node(new Expr.Binary(op, left, right), left.start(), right.end());
    }

    static Node range(Node from, Node to) {
        return new Node(new Expr.Range(from, to), from.start(), to.end());
    }

    /// True when `pred` holds anywhere in the tree.
    boolean any(Predicate<Expr> pred) {
        return pred.test(expr) || switch (expr) {
            case Expr.Num _, Expr.Stdin _ -> false;
            case Expr.Neg(var operand) -> operand.any(pred);
            case Expr.Sqrt(var operand) -> operand.any(pred);
            case Expr.Call(var _, var args) -> args.any(pred);
            case Expr.Binary(var _, var left, var right) -> left.any(pred) || right.any(pred);
            case Expr.Range(var from, var to) -> from.any(pred) || to.any(pred);
            case Expr.ListOf(var items) -> items.stream().anyMatch(n -> n.any(pred));
        };
    }

    /// True when nothing combines or reorders elements, so a list (such as stdin) can be
    /// evaluated a piece at a time with the same result.
    boolean isElementwise() {
        return !any(e -> switch (e) {
            case Expr.ListOf _, Expr.Range _ -> true;
            case Expr.Call(var func, var _) -> func.kind() == Func.Kind.AGGREGATE;
            default -> false;
        });
    }

    /// Evaluates this node, with `stdin` (null when there is none) for any `@`.
    List<Value> eval(List<Value> stdin) {
        try {
            return switch (expr) {
                case Expr.Num(var value) -> List.of(value);
                case Expr.Stdin _ -> {
                    if (stdin == null) {
                        throw new CalcError.Input("'@' needs input on stdin");
                    }
                    yield stdin;
                }
                case Expr.Neg(var operand) -> operand.eval(stdin).stream().map(Value::negate).toList();
                case Expr.Sqrt(var operand) -> operand.eval(stdin).stream().map(Value::sqrt).toList();
                case Expr.Binary(var op, var left, var right) -> zip(op, left.eval(stdin), right.eval(stdin));
                case Expr.Call(var func, var args) -> func.apply(args.eval(stdin));
                case Expr.ListOf(var items) -> items.stream().flatMap(n -> n.eval(stdin).stream()).toList();
                case Expr.Range(var from, var to) -> range(from.eval(stdin), to.eval(stdin));
            };
        } catch (CalcError e) {
            throw e.at(start, end); // only takes effect if no inner node has claimed the error
        }
    }

    static Value apply(Op op, Value a, Value b) {
        return switch (op) {
            case PLUS -> a.add(b);
            case MINUS -> a.subtract(b);
            case TIMES -> a.multiply(b);
            case DIVIDE -> a.divide(b);
            case MOD -> a.mod(b);
            case POWER -> a.power(b);
        };
    }

    /// Applies `op` element by element. A list of one pairs with every element of the other.
    static List<Value> zip(Op op, List<Value> a, List<Value> b) {
        if (a.size() == b.size()) {
            return IntStream.range(0, a.size()).mapToObj(i -> apply(op, a.get(i), b.get(i))).toList();
        }
        if (a.size() == 1) {
            return b.stream().map(y -> apply(op, a.getFirst(), y)).toList();
        }
        if (b.size() == 1) {
            return a.stream().map(x -> apply(op, x, b.getFirst())).toList();
        }
        throw new CalcError.Input("can't combine lists of %d and %d numbers".formatted(a.size(), b.size()));
    }

    /// Ranges stop well short of exhausting memory.
    static final int MAX_RANGE = 1_000_000;

    static List<Value> range(List<Value> from, List<Value> to) {
        if (from.size() != 1 || to.size() != 1) {
            throw new CalcError.Input("a range needs a single number at each end");
        }
        var first = from.getFirst();
        var last = to.getFirst();
        int direction = Value.compare(first, last) <= 0 ? 1 : -1;
        var step = Value.of(direction);
        var out = new ArrayList<Value>();
        for (var v = first; Value.compare(v, last) * direction <= 0; v = v.add(step)) {
            if (out.size() == MAX_RANGE) {
                throw new CalcError.Input("range is longer than %,d numbers".formatted(MAX_RANGE));
            }
            out.add(v);
        }
        return out;
    }
}
