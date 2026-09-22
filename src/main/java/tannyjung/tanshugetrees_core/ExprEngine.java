package tannyjung.tanshugetrees_core;

// [LMax Fix V54 刀S] [长期记忆: 149] 表达式求值引擎（budget_ms 与 P0-R2 预生成半径共用核心）。
//
// max 设计哲学（2026-09-23 拍板）："做一个给强大的人用的强大的工具，而不是一个给傻瓜用的魔法道具"——
// 调度策略（预算值/预生成范围）从代码推到用户数据层：本引擎只提供机制（编译+求值），
// 策略（表达式字符串）全权交给配置。budget_ms 绑 t/d/q 变量表；P0-R2 预生成半径绑另一张表——
// 一个引擎，多张绑定。
//
// 语法（递归下降，零外部依赖）：
//   expr   := term (('+'|'-') term)*
//   term   := unary (('*'|'/'|'%') unary)*
//   unary  := ('+'|'-') unary | primary
//   primary:= NUMBER | VAR | FUNC '(' expr (',' expr)* ')' | '(' expr ')'
//   FUNC   := min | max | clamp     —— min/max 至少 2 参（可多）；clamp 恰 3 参(value, low, high)
// 字符集前向锁 [长期记忆: 151]（config 格式革命兼容）：仅小写字母/数字/'.'/'+'/'-'/'*'/'/'/'%'/'('/')'/','/空格，
//   无反斜杠无引号无冒号无大写无科学计数法——在任何未来配置格式中免转义原样搬家。
// 求值语义：IEEE 754 双精度。除零得 ±Infinity、0/0 得 NaN、溢出得 ±Infinity——引擎不吞不修，
//   由调用方做 isFinite/范围校验并执行回退策略（机制与策略分离，本类不掺策略）。
// 线程模型：compile() 于配置加载/热重载线程；eval() 于主线程 tick 边界。Program 不可变，
//   跨线程经 volatile 引用换装（原子，无锁）。
// 分配模型：eval 零分配——节点树编译期固化，变量数组由调用方持有复用。
public final class ExprEngine {

    private ExprEngine () {} // 纯静态工具类，禁止实例化

    /**
     * 编译表达式为不可变 Program。语法/字符集/变量名/函数元数错误抛 SyntaxException（含 1 起始字符位置）。
     *
     * @param expression     表达式字符串（上限 512 字符，防御性上限）
     * @param variable_names 变量名表（下标顺序 = eval 时 vars 数组下标；null 视为空表）
     */
    public static Program compile (String expression, String[] variable_names) {
        if (expression == null) throw new SyntaxException("expression is null", 0);
        if (expression.length() > 512) throw new SyntaxException("expression too long (limit 512 chars)", 0);
        Parser parser = new Parser(expression, variable_names == null ? new String[0] : variable_names);
        return new Program(parser.parse());
    }

    /** 编译产物：不可变、线程安全（volatile 换装）、eval 零分配。 */
    public static final class Program {
        private final Node root;
        private Program (Node root) { this.root = root; }

        /**
         * 求值。vars 下标对应 compile 传入 variable_names 顺序（长度契约归调用方，本方法不检查）。
         * 返回 IEEE 双精度结果；可能为 NaN/±Infinity（除零/溢出），由调用方校验回退。
         */
        public double eval (double[] vars) {
            return root.eval(vars);
        }
    }

    /** 表达式语法错误。position 为 0 起始字符下标（消息中展示为 1 起始）。 */
    public static final class SyntaxException extends RuntimeException {
        public final int position;
        public SyntaxException (String message, int position) {
            super(message + " (at char " + (position + 1) + ")");
            this.position = position;
        }
    }

    // ---------------- 内部 AST ----------------

    private abstract static class Node {
        abstract double eval (double[] v);
    }

    private static final class NumNode extends Node {
        private final double value;
        NumNode (double value) { this.value = value; }
        double eval (double[] v) { return value; }
    }

    private static final class VarNode extends Node {
        private final int slot;
        VarNode (int slot) { this.slot = slot; }
        double eval (double[] v) { return v[slot]; }
    }

    private static final class NegNode extends Node {
        private final Node child;
        NegNode (Node child) { this.child = child; }
        double eval (double[] v) { return -child.eval(v); }
    }

    private static final class BinNode extends Node {
        private final char op;
        private final Node left, right;
        BinNode (char op, Node left, Node right) { this.op = op; this.left = left; this.right = right; }
        double eval (double[] v) {
            double l = left.eval(v), r = right.eval(v);
            switch (op) {
                case '+': return l + r;
                case '-': return l - r;
                case '*': return l * r;
                case '/': return l / r;   // 除零 → ±Infinity / 0/0 → NaN，调用方校验
                case '%': return l % r;
                default:   throw new IllegalStateException("unreachable op '" + op + "'");
            }
        }
    }

    private static final class FuncNode extends Node {
        private static final int MIN = 0, MAX = 1, CLAMP = 2;
        private final int func;
        private final Node[] args;
        FuncNode (int func, Node[] args) { this.func = func; this.args = args; }
        double eval (double[] v) {
            switch (func) {
                case MIN: {
                    double best = args[0].eval(v);
                    for (int k = 1; k < args.length; k++) { double a = args[k].eval(v); if (a < best) best = a; }
                    return best;
                }
                case MAX: {
                    double best = args[0].eval(v);
                    for (int k = 1; k < args.length; k++) { double a = args[k].eval(v); if (a > best) best = a; }
                    return best;
                }
                default: { // CLAMP(value, low, high)
                    double value = args[0].eval(v), lo = args[1].eval(v), hi = args[2].eval(v);
                    return value < lo ? lo : (value > hi ? hi : value);
                }
            }
        }
    }

    // ---------------- 递归下降解析器 ----------------

    private static final class Parser {
        private final String src;
        private final String[] var_names;
        private int pos = 0;
        private int depth = 0; // 嵌套深度守卫（防病态括号/函数嵌套撑爆解析栈）

        Parser (String src, String[] var_names) {
            this.src = src;
            this.var_names = var_names;
        }

        Node parse () {
            Node root = parseExpr();
            skipWhitespace();
            if (pos < src.length()) throw new SyntaxException("unexpected trailing character '" + src.charAt(pos) + "'", pos);
            return root;
        }

        private Node parseExpr () {
            if (++depth > 64) { depth--; throw new SyntaxException("expression nesting exceeds limit of 64", pos); }
            try {
                Node node = parseTerm();
                while (true) {
                    skipWhitespace();
                    if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                        char op = src.charAt(pos);
                        pos++;
                        node = new BinNode(op, node, parseTerm());
                    } else {
                        return node;
                    }
                }
            } finally {
                depth--;
            }
        }

        private Node parseTerm () {
            Node node = parseUnary();
            while (true) {
                skipWhitespace();
                if (pos < src.length() && (src.charAt(pos) == '*' || src.charAt(pos) == '/' || src.charAt(pos) == '%')) {
                    char op = src.charAt(pos);
                    pos++;
                    node = new BinNode(op, node, parseUnary());
                } else {
                    return node;
                }
            }
        }

        private Node parseUnary () {
            skipWhitespace();
            if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) {
                char op = src.charAt(pos);
                pos++;
                Node child = parseUnary();
                return op == '-' ? new NegNode(child) : child; // 一元 '+' 无操作，直接透传
            }
            return parsePrimary();
        }

        private Node parsePrimary () {
            skipWhitespace();
            if (pos >= src.length()) throw new SyntaxException("unexpected end of expression", pos);
            char c = src.charAt(pos);
            if (c == '(') {
                pos++;
                Node inner = parseExpr();
                skipWhitespace();
                expect(')');
                return inner;
            }
            if (c >= '0' && c <= '9') return parseNumber();
            if (c >= 'a' && c <= 'z') return parseIdentOrFunc();
            throw new SyntaxException("unexpected character '" + c + "' (allowed: a-z 0-9 . + - * / % ( ) , space)", pos);
        }

        // 数字：纯十进制（数字+可选单个小数点）。无科学计数法（'e' 会被当成标识符拒绝——字符集前向锁的一部分）。
        private Node parseNumber () {
            int start = pos;
            boolean dot = false;
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c >= '0' && c <= '9') { pos++; }
                else if (c == '.' && !dot) { dot = true; pos++; }
                else break;
            }
            String text = src.substring(start, pos);
            // 循环保证至少 1 个数字；点后无数字时 pos 停在末尾 → text 以 '.' 结尾，与尾部点同一分支捕获
            if (text.endsWith(".")) throw new SyntaxException("malformed number '" + text + "' (trailing dot)", start);
            if (text.startsWith(".")) throw new SyntaxException("malformed number '" + text + "' (leading dot)", start);
            return new NumNode(Double.parseDouble(text));
        }

        private Node parseIdentOrFunc () {
            int start = pos;
            while (pos < src.length() && src.charAt(pos) >= 'a' && src.charAt(pos) <= 'z') pos++;
            String name = src.substring(start, pos);
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == '(') {
                pos++;
                if (name.equals("min")) return parseFuncArgs(FuncNode.MIN, 2, "min");
                if (name.equals("max")) return parseFuncArgs(FuncNode.MAX, 2, "max");
                if (name.equals("clamp")) return parseFuncArgs(FuncNode.CLAMP, 3, "clamp");
                throw new SyntaxException("unknown function '" + name + "' (allowed: min, max, clamp)", start);
            }
            for (int k = 0; k < var_names.length; k++) {
                if (name.equals(var_names[k])) return new VarNode(k);
            }
            throw new SyntaxException("unknown variable '" + name + "' (allowed: " + String.join(", ", var_names) + ")", start);
        }

        // 解析已消费 '(' 之后的参数表直至 ')'。min/max 至少 2 参；clamp 恰 3 参。
        private Node parseFuncArgs (int func, int min_args, String func_name) {
            java.util.ArrayList<Node> args = new java.util.ArrayList<>();
            skipWhitespace();
            if (pos < src.length() && src.charAt(pos) == ')') {
                pos++;
                throw new SyntaxException("function '" + func_name + "' needs at least " + min_args + " arguments (empty list)", pos - 1);
            }
            while (true) {
                args.add(parseExpr());
                skipWhitespace();
                if (pos < src.length() && src.charAt(pos) == ',') { pos++; continue; }
                break;
            }
            expect(')');
            if (func == FuncNode.CLAMP && args.size() != 3)
                throw new SyntaxException("function 'clamp' takes exactly 3 arguments (value, low, high), got " + args.size(), pos);
            if (func != FuncNode.CLAMP && args.size() < 2)
                throw new SyntaxException("function '" + func_name + "' needs at least 2 arguments, got " + args.size(), pos);
            return new FuncNode(func, args.toArray(new Node[0]));
        }

        private void expect (char c) {
            if (pos < src.length() && src.charAt(pos) == c) { pos++; return; }
            String found = pos < src.length() ? (" but found '" + src.charAt(pos) + "'") : " but expression ended";
            throw new SyntaxException("expected '" + c + "'" + found, pos);
        }

        private void skipWhitespace () {
            while (pos < src.length() && (src.charAt(pos) == ' ' || src.charAt(pos) == '\t')) pos++;
        }
    }
}