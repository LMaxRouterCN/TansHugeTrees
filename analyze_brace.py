import re

# 读取文件
with open(r"src\main\java\tannyjung\tanshugetrees_handcode\systems\world_gen\TreeLocation.java", "r", encoding="utf-8") as f:
    lines = f.readlines()

# 分析第392-462行的大括号结构
print("=== 第392-462行大括号分析 ===")
for i in range(391, 462):  # 0-indexed
    line = lines[i]
    stripped = line.strip()
    
    # 计算实际缩进（空格数）
    leading_spaces = len(line) - len(line.lstrip())
    
    # 统计大括号
    opens = line.count('{')
    closes = line.count('}')
    
    if opens > 0 or closes > 0 or stripped.startswith('if ') or stripped.startswith('for ') or stripped.startswith('while ') or stripped.startswith('scan_pos'):
        print(f"Line {i+1}: indent={leading_spaces}, opens={opens}, closes={closes}, {stripped[:50]}")

print("\n=== 问题定位 ===")
print("第395行缩进为0，应该在while循环内")
print("第407行缩进为0，应该在while循环内")
print("需要修复这些缩进")