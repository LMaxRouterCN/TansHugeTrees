# 修复TreeLocation.java第395-407行的缩进和缺失的大括号

filepath = r"src\main\java\tannyjung\tanshugetrees_handcode\systems\world_gen\TreeLocation.java"
with open(filepath, "r", encoding="utf-8") as f:
    lines = f.readlines()

# 第395行（0-indexed: 394）：缩进从0改为20
lines[394] = "                    scan_pos = new ChunkPos(center_chunk.x + scanX, center_chunk.z + scanZ);\n"
print("Line 395: 修复缩进为20空格")

# 第406行（0-indexed: 405）：添加缺失的关闭Get Data块的 }
# 原第406行是空行，需要在第405行后插入新行
insert_line = "                }\n"  # 缩进16，关闭Get Data块
lines.insert(406, insert_line)
print("Line 406: 插入关闭Get Data块的 }")

# 第407行（0-indexed: 406，插入后变成407）：缩进从0改为16
lines[407] = "                if (data != null && data.isEmpty() == false) {\n"
print("Line 407: 修复缩进为16空格")

# 写回文件
with open(filepath, "w", encoding="utf-8") as f:
    f.writelines(lines)

print("\n修复完成！")
print("- 第395行：scan_pos缩进改为20")
print("- 第406行：插入关闭Get Data块的 }")
print("- 第407行：if语句缩进改为16")