import re

# 读取文件
filepath = r"src\main\java\tannyjung\tanshugetrees_handcode\systems\world_gen\TreeLocation.java"
with open(filepath, "r", encoding="utf-8") as f:
    lines = f.readlines()

# 需要修复的行（0-indexed）
fixes = {
    # 第395行：scan_pos应该在Get Data块内，缩进20
    394: (0, 20),  # 从0缩进改为20
    # 第396行：if语句，缩进20（保持不变）
    # 第398行：} else {，缩进20（保持不变）
    # 第407行：if语句应该在while循环内，缩进16
    406: (0, 16),  # 从0缩进改为16
}

# 应用修复
for line_num, (old_indent, new_indent) in fixes.items():
    line = lines[line_num]
    stripped = line.lstrip()
    lines[line_num] = " " * new_indent + stripped
    print(f"Line {line_num+1}: 缩进从 {old_indent} 改为 {new_indent}")

# 写回文件
with open(filepath, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("\n修复完成！")