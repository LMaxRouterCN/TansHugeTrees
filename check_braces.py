import re

with open(r"src\main\java\tannyjung\tanshugetrees_handcode\systems\world_gen\TreeLocation.java", "r", encoding="utf-8") as f:
    lines = f.readlines()

brace_stack = []
for i, line in enumerate(lines, 1):
    # Skip comments
    stripped = line.strip()
    if stripped.startswith("//") or stripped.startswith("/*") or stripped.startswith("*"):
        continue
    
    # Count braces
    opens = line.count("{")
    closes = line.count("}")
    
    for _ in range(opens):
        brace_stack.append(i)
    
    for _ in range(closes):
        if brace_stack:
            brace_stack.pop()
        else:
            print(f"Line {i}: Extra closing brace")
    
    if abs(len(brace_stack)) < 5 and (opens > 0 or closes > 0):
        print(f"Line {i}: brace_depth={len(brace_stack)}, {line.strip()[:60]}")

print(f"\nFinal brace depth: {len(brace_stack)}")
if brace_stack:
    print(f"Unclosed braces at lines: {brace_stack}")