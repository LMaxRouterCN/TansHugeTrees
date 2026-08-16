import sys
with open(r"D:\Documents\mcmod\TansHugeTrees\src\main\java\tannyjung\tanshugetrees_handcode\systems\world_gen\TreeLocation.java", "r", encoding="utf-8") as f:
    lines = f.readlines()
d = 0
for i, line in enumerate(lines):
    d += line.count('{') - line.count('